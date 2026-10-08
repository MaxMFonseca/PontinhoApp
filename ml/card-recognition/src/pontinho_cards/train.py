"""Reproducible EfficientDet-Lite0 training with a frozen-holdout firewall."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
import random
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from pathlib import Path, PurePosixPath
from types import MappingProxyType
from typing import Any

import yaml
from PIL import Image

from pontinho_cards.labels import CARD_LABELS, parse_card_label
from pontinho_cards.secure_io import (
    SecureReadError,
    safe_name,
    safe_relative_posix,
    secure_read_bytes,
    secure_write_bytes,
)

_RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_PARTITION_TO_CSV = {"train": "TRAIN", "validation": "VAL"}


class TrainingConfigError(ValueError):
    """Raised before training when a reproducibility or data contract is unsafe."""


@dataclass(frozen=True)
class TrainingConfig:
    detector_family: str
    labels: tuple[str, ...]
    seed: int
    image_size: int
    batch_size: int
    confidence_threshold: float
    iou_threshold: float
    epochs: int
    early_stopping_enabled: bool
    early_stopping_patience: int
    train_partition: str
    validation_partition: str
    train_whole_model: bool
    augmentation: dict[str, bool]
    document: dict[str, Any]


@dataclass(frozen=True)
class PreparedRun:
    config: TrainingConfig
    staging_dir: Path
    output_dir: Path
    splits_path: Path
    requirements_lock: Path


@dataclass(frozen=True)
class TrainingOutcome:
    model: Any
    detector: Any
    validation_data: Any
    history: dict[str, list[Any]]
    record_counts: dict[str, int]


@dataclass(frozen=True)
class SyntheticEvidenceSnapshot:
    """One immutable byte snapshot of every artifact used to authorize synthesis."""

    split_payload: bytes
    manifest_payload: bytes
    review_payload: bytes
    distribution_payload: bytes
    contact_sheet_payload: bytes
    generated_payloads: Mapping[str, bytes]


def _mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise TrainingConfigError(f"{name} must be an object")
    return value


def _positive_int(value: Any, name: str) -> int:
    if type(value) is not int or value <= 0:
        raise TrainingConfigError(f"{name} must be a positive integer")
    return value


def _unit_interval(value: Any, name: str) -> float:
    if type(value) not in {int, float} or not math.isfinite(value):
        raise TrainingConfigError(f"{name} must be a finite number in [0, 1]")
    normalized = float(value)
    if not 0.0 <= normalized <= 1.0:
        raise TrainingConfigError(f"{name} must be a finite number in [0, 1]")
    return normalized


def load_training_config_bytes(
    payload: bytes, *, source_name: str = "training config"
) -> TrainingConfig:
    """Parse one already-snapshotted training-selection contract."""

    try:
        document = yaml.safe_load(payload)
    except (UnicodeError, yaml.YAMLError) as error:
        raise TrainingConfigError(f"cannot parse {source_name}: {error}") from error
    root = _mapping(document, "training config")
    if root.get("detector_family") != "efficientdet_lite0":
        raise TrainingConfigError("detector_family must be efficientdet_lite0")
    labels = root.get("labels")
    if not isinstance(labels, list) or tuple(labels) != CARD_LABELS:
        raise TrainingConfigError("labels must be exactly CARD_LABELS in canonical order")
    seed = root.get("seed")
    if type(seed) is not int:
        raise TrainingConfigError("seed must be an integer")
    image_size = root.get("image_size")
    if type(image_size) is not int or image_size != 320:
        raise TrainingConfigError("image_size must be exactly 320 for EfficientDet-Lite0")
    batch_size = _positive_int(root.get("batch_size"), "batch_size")
    if batch_size > 64:
        raise TrainingConfigError("batch_size must be between 1 and 64")
    confidence_threshold = _unit_interval(root.get("confidence_threshold"), "confidence_threshold")
    iou_threshold = _unit_interval(root.get("iou_threshold"), "iou_threshold")

    partitions = _mapping(root.get("partitions"), "partitions")
    train_partition = partitions.get("train")
    validation_partition = partitions.get("validation")
    if train_partition != "train" or validation_partition != "validation":
        raise TrainingConfigError("partitions must never reference the frozen test partition")
    if any("test" in str(value).casefold() for value in partitions.values()):
        raise TrainingConfigError("partitions must never reference the frozen test partition")

    training = _mapping(root.get("training"), "training")
    epochs = _positive_int(training.get("epochs"), "epochs")
    if epochs > 200:
        raise TrainingConfigError("epochs must be bounded at 200")
    early = _mapping(training.get("early_stopping"), "training.early_stopping")
    if early.get("enabled") is not True:
        raise TrainingConfigError("early stopping must be enabled")
    patience = _positive_int(early.get("patience"), "early stopping patience")
    if patience >= epochs:
        raise TrainingConfigError("early stopping patience must be smaller than epochs")
    train_whole_model = training.get("train_whole_model")
    if type(train_whole_model) is not bool:
        raise TrainingConfigError("train_whole_model must be a boolean")
    augmentation = _mapping(training.get("augmentation"), "training.augmentation")
    expected_switches = {"autoaugment", "horizontal_flip", "scale_jitter"}
    if set(augmentation) != expected_switches or not all(
        type(value) is bool for value in augmentation.values()
    ):
        raise TrainingConfigError(
            "augmentation must define boolean autoaugment, horizontal_flip, and scale_jitter"
        )
    return TrainingConfig(
        detector_family="efficientdet_lite0",
        labels=tuple(labels),
        seed=seed,
        image_size=image_size,
        batch_size=batch_size,
        confidence_threshold=confidence_threshold,
        iou_threshold=iou_threshold,
        epochs=epochs,
        early_stopping_enabled=True,
        early_stopping_patience=patience,
        train_partition=str(train_partition),
        validation_partition=str(validation_partition),
        train_whole_model=train_whole_model,
        augmentation=dict(augmentation),
        document=dict(root),
    )


def load_training_config(path: str | Path) -> TrainingConfig:
    """Securely snapshot and validate the complete training-selection contract."""

    config_path = Path(path)
    try:
        payload = secure_read_bytes(config_path, root=config_path.parent)
    except SecureReadError as error:
        raise TrainingConfigError(f"cannot read training config {config_path}: {error}") from error
    return load_training_config_bytes(payload, source_name=str(config_path))


def _load_training_partitions_payload(
    payload: bytes,
    *,
    train_partition: str,
    validation_partition: str,
) -> tuple[list[Mapping[str, Any]], list[Mapping[str, Any]]]:
    try:
        document = json.loads(payload)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise TrainingConfigError(f"cannot parse split manifest: {error}") from error
    root = _mapping(document, "split manifest")
    partitions = _mapping(root.get("partitions"), "split manifest partitions")
    # Intentionally address only these two keys. The frozen final holdout lives in
    # final-holdout.json and the optional `test` member is never validated or used.
    train_records = partitions.get(train_partition)
    validation_records = partitions.get(validation_partition)
    if not isinstance(train_records, list) or not isinstance(validation_records, list):
        raise TrainingConfigError("split manifest requires train and validation lists")
    if not all(isinstance(record, Mapping) for record in train_records + validation_records):
        raise TrainingConfigError("train and validation records must be objects")
    return train_records, validation_records


def _load_training_partitions(
    path: Path, *, train_partition: str, validation_partition: str
) -> tuple[list[Mapping[str, Any]], list[Mapping[str, Any]]]:
    try:
        payload = secure_read_bytes(path, root=path.parent)
    except SecureReadError as error:
        raise TrainingConfigError(f"cannot read split manifest {path}: {error}") from error
    train, validation = _load_training_partitions_payload(
        payload,
        train_partition=train_partition,
        validation_partition=validation_partition,
    )

    return (
        _resolve_training_paths(train, path.parent),
        _resolve_training_paths(validation, path.parent),
    )


def _resolve_training_paths(
    records: list[Mapping[str, Any]], base: Path
) -> list[Mapping[str, Any]]:
    resolved: list[Mapping[str, Any]] = []
    for raw in records:
        record = dict(raw)
        value = record.get("path")
        if isinstance(value, str) and not Path(value).is_absolute():
            try:
                relative = safe_relative_posix(value, field="training image path")
            except ValueError as error:
                raise TrainingConfigError(str(error)) from error
            record["path"] = str(base.joinpath(*relative.parts))
        resolved.append(record)
    return resolved


def _json_payload(payload: bytes, name: str) -> dict[str, Any]:
    try:
        document = json.loads(payload)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise TrainingConfigError(f"cannot parse {name}: {error}") from error
    if not isinstance(document, dict):
        raise TrainingConfigError(f"{name} must be an object")
    return document


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _synthetic_config_hash(generation: Mapping[str, Any]) -> str:
    fields = {
        key: generation.get(key)
        for key in (
            "generator",
            "seed",
            "requestedSceneCount",
            "sceneSize",
            "baseSplitManifestSha256",
        )
    }
    return hashlib.sha256(
        (json.dumps(fields, sort_keys=True, separators=(",", ":")) + "\n").encode()
    ).hexdigest()


def _safe_training_name(value: Any, field: str) -> str:
    try:
        return safe_name(value, field=field)
    except ValueError as error:
        raise TrainingConfigError(str(error)) from error


def capture_synthetic_evidence(
    *,
    split_payload: bytes,
    synthetic_manifest: str | Path,
    human_review: str | Path,
) -> SyntheticEvidenceSnapshot:
    """Securely capture each synthetic authorization artifact exactly once."""

    manifest_path = Path(synthetic_manifest)
    review_path = Path(human_review)

    def snapshot(path: Path, root: Path, name: str) -> bytes:
        try:
            return secure_read_bytes(path, root=root)
        except SecureReadError as error:
            raise TrainingConfigError(f"cannot snapshot {name}: {error}") from error

    manifest_payload = snapshot(
        manifest_path, manifest_path.parent, "synthetic training manifest"
    )
    manifest = _json_payload(manifest_payload, "synthetic training manifest")
    review_payload = snapshot(review_path, review_path.parent, "synthetic human review")
    root = manifest_path.parent
    distribution_payload = snapshot(root / "distribution.json", root, "synthetic distribution")
    contact_sheet_payload = snapshot(root / "contact-sheet.jpg", root, "synthetic contact sheet")
    records = manifest.get("records")
    if not isinstance(records, list):
        raise TrainingConfigError("synthetic training records must be a list")
    members: set[str] = set()
    for record in records:
        if not isinstance(record, Mapping):
            raise TrainingConfigError("synthetic training records must be objects")
        try:
            members.add(
                safe_relative_posix(record.get("path"), field="synthetic image path").as_posix()
            )
            provenance = record.get("syntheticProvenance")
            if not isinstance(provenance, Mapping):
                raise TrainingConfigError("synthetic provenance is missing")
            members.add(
                safe_relative_posix(
                    provenance.get("metadata"), field="synthetic metadata path"
                ).as_posix()
            )
        except ValueError as error:
            raise TrainingConfigError(str(error)) from error
    generated = {
        member: snapshot(root.joinpath(*PurePosixPath(member).parts), root, member)
        for member in sorted(members)
    }
    return SyntheticEvidenceSnapshot(
        split_payload=bytes(split_payload),
        manifest_payload=manifest_payload,
        review_payload=review_payload,
        distribution_payload=distribution_payload,
        contact_sheet_payload=contact_sheet_payload,
        generated_payloads=MappingProxyType(generated),
    )


def _validate_synthetic_record(
    raw: Any,
    *,
    index: int,
    generated_payloads: Mapping[str, bytes],
    train_parents: Mapping[str, Mapping[str, Any]],
    held_out_ids: set[str],
) -> dict[str, Any]:
    if not isinstance(raw, Mapping) or raw.get("isRealPhoto") is not False:
        raise TrainingConfigError("synthetic training records must be non-real objects")
    record = dict(raw)
    image_id = _safe_training_name(record.get("imageId"), "synthetic imageId")
    if record.get("sourceId") != "synthetic-tabletop-v1":
        raise TrainingConfigError("synthetic sourceId must use the canonical safe ID")
    _safe_training_name(record.get("sourceId"), "synthetic sourceId")
    _safe_training_name(record.get("deckGroupId"), "synthetic deckGroupId")
    _safe_training_name(record.get("splitGroupId"), "synthetic splitGroupId")
    try:
        relative = safe_relative_posix(record.get("path"), field="synthetic image path")
    except ValueError as error:
        raise TrainingConfigError(f"invalid synthetic image path: {error}") from error
    payload = generated_payloads.get(relative.as_posix())
    if payload is None:
        raise TrainingConfigError("synthetic image is absent from immutable evidence")
    digest = hashlib.sha256(payload).hexdigest()
    if record.get("imageSha256") != digest or record.get("sourceDerivativeId") != digest:
        raise TrainingConfigError("synthetic image/derivative hash mismatch")
    try:
        with Image.open(io.BytesIO(payload)) as image:
            image.load()
            dimensions = image.size
    except (OSError, SyntaxError) as error:
        raise TrainingConfigError("synthetic image is not decodable") from error
    if dimensions != (record.get("width"), record.get("height")):
        raise TrainingConfigError("synthetic image dimensions mismatch")
    provenance = record.get("syntheticProvenance")
    if not isinstance(provenance, Mapping):
        raise TrainingConfigError("synthetic provenance is missing")
    if provenance.get("generator") != "pontinho_cards.synthetic.tabletop-v1" or type(
        provenance.get("seed")
    ) is not int:
        raise TrainingConfigError("synthetic generator provenance is invalid")
    try:
        metadata_relative = safe_relative_posix(
            provenance.get("metadata"), field="synthetic metadata path"
        )
    except ValueError as error:
        raise TrainingConfigError(f"invalid synthetic metadata path: {error}") from error
    metadata_payload = generated_payloads.get(metadata_relative.as_posix())
    if metadata_payload is None:
        raise TrainingConfigError("synthetic metadata is absent from immutable evidence")
    if provenance.get("metadataSha256") != hashlib.sha256(metadata_payload).hexdigest():
        raise TrainingConfigError("synthetic metadata hash mismatch")
    try:
        metadata = json.loads(metadata_payload)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise TrainingConfigError("synthetic metadata is invalid") from error
    if (
        not isinstance(metadata, Mapping)
        or metadata.get("seed") != provenance.get("seed")
        or metadata.get("width") != record.get("width")
        or metadata.get("height") != record.get("height")
    ):
        raise TrainingConfigError("synthetic metadata does not bind the record")
    background = provenance.get("background")
    if (
        not isinstance(background, Mapping)
        or dict(background) != metadata.get("background")
        or background.get("kind") != "procedural"
        or background.get("source") != "procedural-tabletop-v1"
        or background.get("license") != "CC0-1.0"
        or background.get("generator") != "pontinho_cards.synthetic.tabletop-v1"
        or type(background.get("seed")) is not int
    ):
        raise TrainingConfigError("synthetic background provenance is invalid")
    boxes = record.get("boxes")
    cards = metadata.get("cards")
    parents = provenance.get("parents")
    parent_ids = provenance.get("parentImageIds")
    if not isinstance(boxes, list) or not isinstance(cards, list) or not isinstance(parents, list):
        raise TrainingConfigError("synthetic card provenance is invalid")
    if len(boxes) != len(cards):
        raise TrainingConfigError("synthetic boxes differ from metadata cards")
    canonical_boxes = [
        {"label": card.get("label"), **dict(card.get("finalBox", {}))}
        for card in cards
        if isinstance(card, Mapping) and isinstance(card.get("finalBox"), Mapping)
    ]
    if canonical_boxes != boxes:
        raise TrainingConfigError("synthetic boxes differ from metadata cards")
    normalized_parents: list[str] = []
    for parent in parents:
        if not isinstance(parent, Mapping):
            raise TrainingConfigError("synthetic parent provenance is invalid")
        parent_id = parent.get("imageId")
        if not isinstance(parent_id, str) or parent_id in held_out_ids:
            raise TrainingConfigError("synthetic parent crosses a held-out partition")
        source = train_parents.get(parent_id)
        if (
            source is None
            or source.get("isRealPhoto") is not True
            or parent.get("imageSha256") != source.get("imageSha256")
            or parent.get("sourceId") != source.get("sourceId")
        ):
            raise TrainingConfigError("synthetic parent is not an exact real train source")
        normalized_parents.append(parent_id)
    expected_parents = sorted(set(normalized_parents))
    if len(parents) != len(expected_parents):
        raise TrainingConfigError("synthetic parent provenance is duplicated")
    if parent_ids != expected_parents or record.get("augmentationParentIds") != expected_parents:
        raise TrainingConfigError("synthetic augmentation ancestry is invalid")
    expected_single = expected_parents[0] if len(expected_parents) == 1 else None
    if record.get("augmentationParentId") != expected_single:
        raise TrainingConfigError("synthetic augmentation parent is invalid")
    if boxes and not expected_parents:
        raise TrainingConfigError("synthetic positive scene lacks real train ancestry")
    if not boxes and (cards or expected_parents):
        raise TrainingConfigError("synthetic empty negative has card ancestry")
    parent_evidence = {
        (parent["imageId"], parent["imageSha256"], parent["sourceId"])
        for parent in parents
    }
    for card in cards:
        if not isinstance(card, Mapping) or card.get("partition") != "train":
            raise TrainingConfigError("synthetic metadata card ancestry is invalid")
        pixel_source = card.get("pixelSource")
        if not isinstance(pixel_source, Mapping):
            raise TrainingConfigError("synthetic metadata card ancestry is invalid")
        identity = (
            pixel_source.get("parentImageId"),
            pixel_source.get("parentImageSha256"),
            pixel_source.get("sourceId"),
        )
        if identity not in parent_evidence or card.get("parentImageSha256") != identity[1]:
            raise TrainingConfigError("synthetic metadata card ancestry is invalid")
    record["path"] = f"synthetic-evidence/{relative.as_posix()}"
    record["imageId"] = image_id
    return record


def merge_approved_synthetic_training(
    evidence: SyntheticEvidenceSnapshot,
) -> dict[str, Any]:
    """Merge only already-snapshotted evidence; never reopen authorization paths."""

    split_payload = evidence.split_payload
    manifest_payload = evidence.manifest_payload
    review_payload = evidence.review_payload
    split = _json_payload(split_payload, "split manifest")
    manifest = _json_payload(manifest_payload, "synthetic manifest")
    review = _json_payload(review_payload, "synthetic human review")
    audit = manifest.get("audit")
    records = manifest.get("records")
    if (
        manifest.get("schemaVersion") != 1
        or manifest.get("kind") != "pontinho-synthetic-training"
        or manifest.get("partition") != "train"
        or not isinstance(records, list)
        or not isinstance(audit, Mapping)
        or audit.get("status") != "PENDING_HUMAN_REVIEW"
    ):
        raise TrainingConfigError("synthetic training manifest schema/audit status is invalid")
    generation = manifest.get("generation")
    if not isinstance(generation, Mapping):
        raise TrainingConfigError("synthetic generation evidence is missing")
    generation_hash = _synthetic_config_hash(generation)
    if (
        generation.get("configSha256") != generation_hash
        or generation.get("generator") != "pontinho_cards.synthetic.tabletop-v1"
        or generation.get("baseSplitManifestSha256")
        != hashlib.sha256(split_payload).hexdigest()
        or generation.get("seed") != manifest.get("seed")
    ):
        raise TrainingConfigError("synthetic generation evidence is invalid")
    expected_review = {
        "schemaVersion": 1,
        "status": "HUMAN_APPROVED",
        "syntheticManifestSha256": hashlib.sha256(manifest_payload).hexdigest(),
        "generationConfigSha256": generation_hash,
        "distributionSha256": audit.get("distributionSha256"),
        "contactSheetSha256": audit.get("contactSheetSha256"),
    }
    if review != expected_review:
        raise TrainingConfigError("synthetic training human-review receipt is invalid")
    for filename, field, payload in (
        ("distribution.json", "distributionSha256", evidence.distribution_payload),
        ("contact-sheet.jpg", "contactSheetSha256", evidence.contact_sheet_payload),
    ):
        if hashlib.sha256(payload).hexdigest() != audit.get(field):
            raise TrainingConfigError(f"synthetic audit artifact hash mismatch: {filename}")
    distribution = _json_payload(evidence.distribution_payload, "distribution")
    if (
        distribution.get("schemaVersion") != 1
        or distribution.get("seed") != manifest.get("seed")
        or distribution.get("sceneCount") != audit.get("sceneCount")
        or distribution.get("requestedSceneCount") != generation.get("requestedSceneCount")
        or distribution.get("audit", {}).get("minimumScenes") != audit.get("minimumScenes")
        or distribution.get("audit", {}).get("status") != audit.get("status")
    ):
        raise TrainingConfigError("synthetic distribution audit is invalid")
    partitions = _mapping(split.get("partitions"), "split manifest partitions")
    train = partitions.get("train")
    validation = partitions.get("validation")
    test = partitions.get("test")
    if not all(isinstance(value, list) for value in (train, validation, test)):
        raise TrainingConfigError("split manifest partitions are invalid")
    train_parents = {
        str(record.get("imageId")): record for record in train if isinstance(record, Mapping)
    }
    held_out_ids = {
        str(record.get("imageId"))
        for partition in (validation, test)
        for record in partition
        if isinstance(record, Mapping)
    }
    merged_records = [
        _validate_synthetic_record(
            raw,
            index=index,
            generated_payloads=evidence.generated_payloads,
            train_parents=train_parents,
            held_out_ids=held_out_ids,
        )
        for index, raw in enumerate(records)
    ]
    if any(record["boxes"] for record in merged_records) and (
        len(merged_records) < 100
        or audit.get("minimumScenes") != 100
        or audit.get("sceneCount") != len(merged_records)
    ):
        raise TrainingConfigError(
            "synthetic positive training requires at least 100 audited scenes"
        )
    for field in ("imageId", "imageSha256", "sourceDerivativeId"):
        existing = {
            record.get(field)
            for partition in (train, validation, test)
            for record in partition
            if isinstance(record, Mapping)
        }
        synthetic = [record.get(field) for record in merged_records]
        if None in synthetic or len(synthetic) != len(set(synthetic)) or existing & set(synthetic):
            raise TrainingConfigError(f"synthetic training {field} leaks or is duplicated")
    for field in ("deckGroupId", "splitGroupId"):
        forbidden = {
            record.get(field)
            for partition in (validation, test)
            for record in partition
            if isinstance(record, Mapping)
        }
        synthetic = {record.get(field) for record in merged_records}
        if None in synthetic or forbidden & synthetic:
            raise TrainingConfigError(f"synthetic training {field} crosses a held-out partition")
    return {
        **split,
        "partitions": {
            **partitions,
            "train": [*train, *merged_records],
            "validation": validation,
            "test": test,
        },
        "syntheticManifestSha256": hashlib.sha256(manifest_payload).hexdigest(),
        "syntheticReviewSha256": hashlib.sha256(review_payload).hexdigest(),
        "syntheticGenerationConfigSha256": generation_hash,
    }


def _checked_image(record: Mapping[str, Any]) -> tuple[Path, int, int, str]:
    path_value = record.get("path")
    if not isinstance(path_value, str) or not path_value:
        raise TrainingConfigError("training record path must be non-empty text")
    path = Path(path_value)
    width = _positive_int(record.get("width"), "record width")
    height = _positive_int(record.get("height"), "record height")
    digest = record.get("imageSha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise TrainingConfigError("training record imageSha256 must be lowercase SHA-256")
    try:
        payload = path.read_bytes()
        with Image.open(path) as image:
            actual_size = image.size
            image_format = image.format
            image.verify()
    except (OSError, SyntaxError) as error:
        raise TrainingConfigError(f"cannot read training image {path}") from error
    if hashlib.sha256(payload).hexdigest() != digest:
        raise TrainingConfigError(f"training image hash mismatch: {path}")
    if actual_size != (width, height):
        raise TrainingConfigError(f"training image dimensions mismatch: {path}")
    return path.resolve(), width, height, str(image_format)


def _model_maker_image(
    source: Path, *, source_format: str, digest: str, staging_dir: Path | None
) -> Path:
    if source_format == "JPEG" or staging_dir is None:
        return source
    staging_dir.mkdir(parents=True, exist_ok=True)
    target = staging_dir / f"{digest}.jpg"
    if target.exists():
        return target
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.stem}.", suffix=".jpg", dir=staging_dir
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with Image.open(source) as image:
            image.convert("RGB").save(
                temporary,
                format="JPEG",
                quality=95,
                optimize=False,
                progressive=False,
            )
        temporary.replace(target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return target


def _csv_rows(
    record: Mapping[str, Any], set_name: str, *, staging_dir: Path | None = None
) -> list[list[Any]]:
    source_path, width, height, source_format = _checked_image(record)
    digest = str(record["imageSha256"])
    image_path = _model_maker_image(
        source_path,
        source_format=source_format,
        digest=digest,
        staging_dir=staging_dir,
    )
    boxes = record.get("boxes")
    if not isinstance(boxes, list) or not boxes:
        raise TrainingConfigError("training record boxes must be a non-empty list")
    rows: list[list[Any]] = []
    for box in boxes:
        if not isinstance(box, Mapping):
            raise TrainingConfigError("training box must be an object")
        label = box.get("label")
        try:
            parse_card_label(label)  # type: ignore[arg-type]
        except ValueError as error:
            raise TrainingConfigError(f"invalid training label: {label!r}") from error
        coordinates: list[float] = []
        for key in ("xmin", "ymin", "xmax", "ymax"):
            value = box.get(key)
            if type(value) not in {int, float} or not math.isfinite(value):
                raise TrainingConfigError(f"box {key} must be finite numeric data")
            coordinates.append(float(value))
        xmin, ymin, xmax, ymax = coordinates
        if not (0 <= xmin < xmax <= width and 0 <= ymin < ymax <= height):
            if xmax <= xmin or ymax <= ymin:
                raise TrainingConfigError("training box must have positive area")
            raise TrainingConfigError("training box must remain inside the image")
        rows.append(
            [
                set_name,
                str(image_path),
                label,
                xmin / width,
                ymin / height,
                "",
                "",
                xmax / width,
                ymax / height,
                "",
                "",
            ]
        )
    return rows


def write_model_maker_csv(
    splits_path: str | Path,
    output_path: str | Path,
    *,
    train_partition: str,
    validation_partition: str,
) -> dict[str, int]:
    """Translate only train/validation records to Model Maker's CSV contract."""

    source = Path(splits_path)
    train, validation = _load_training_partitions(
        source,
        train_partition=train_partition,
        validation_partition=validation_partition,
    )
    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging_dir = target.parent / "model-maker-images"
    train_rows: list[list[Any]] = []
    for record in train:
        train_rows.extend(_csv_rows(record, _PARTITION_TO_CSV["train"], staging_dir=staging_dir))
    validation_rows: list[list[Any]] = []
    for record in validation:
        validation_rows.extend(
            _csv_rows(record, _PARTITION_TO_CSV["validation"], staging_dir=staging_dir)
        )
    label_index = {label: index for index, label in enumerate(CARD_LABELS)}

    def canonical_row_order(row: list[Any]) -> tuple[int, str, float, float]:
        return label_index[str(row[2])], str(row[1]), float(row[3]), float(row[4])

    train_rows.sort(key=canonical_row_order)
    validation_rows.sort(key=canonical_row_order)
    rows = [*train_rows, *validation_rows]
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerows(rows)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return {"train": len(train), "validation": len(validation)}


def _pascal_annotation(
    record: Mapping[str, Any], *, allow_empty: bool, staging_dir: Path | None = None
) -> ET.Element:
    source_path, width, height, source_format = _checked_image(record)
    image_path = _model_maker_image(
        source_path,
        source_format=source_format,
        digest=str(record["imageSha256"]),
        staging_dir=staging_dir,
    )
    boxes = record.get("boxes")
    if not isinstance(boxes, list) or (not boxes and not allow_empty):
        raise TrainingConfigError("validation boxes must be a non-empty list")
    root = ET.Element("annotation")
    ET.SubElement(root, "filename").text = str(image_path)
    size = ET.SubElement(root, "size")
    ET.SubElement(size, "width").text = str(width)
    ET.SubElement(size, "height").text = str(height)
    ET.SubElement(size, "depth").text = "3"
    for box in boxes:
        if not isinstance(box, Mapping):
            raise TrainingConfigError("training box must be an object")
        label = box.get("label")
        try:
            parse_card_label(label)  # type: ignore[arg-type]
        except ValueError as error:
            raise TrainingConfigError(f"invalid training label: {label!r}") from error
        coordinates = [box.get(key) for key in ("xmin", "ymin", "xmax", "ymax")]
        if any(
            type(value) not in {int, float} or not math.isfinite(value) for value in coordinates
        ):
            raise TrainingConfigError("training box coordinates must be finite")
        xmin, ymin, xmax, ymax = (float(value) for value in coordinates)
        if not (0 <= xmin < xmax <= width and 0 <= ymin < ymax <= height):
            raise TrainingConfigError("training box must have positive area inside the image")
        item = ET.SubElement(root, "object")
        ET.SubElement(item, "name").text = str(label)
        ET.SubElement(item, "pose").text = "Unspecified"
        ET.SubElement(item, "truncated").text = "0"
        ET.SubElement(item, "difficult").text = "0"
        bounds = ET.SubElement(item, "bndbox")
        for key, value in zip(("xmin", "ymin", "xmax", "ymax"), (xmin, ymin, xmax, ymax)):
            ET.SubElement(bounds, key).text = str(int(round(value)))
    return root


def write_model_maker_pascal(
    splits_path: str | Path,
    output_dir: str | Path,
    *,
    train_partition: str,
    validation_partition: str,
) -> dict[str, int]:
    """Write supported Pascal VOC annotations, retaining empty negative train images."""

    train, validation = _load_training_partitions(
        Path(splits_path),
        train_partition=train_partition,
        validation_partition=validation_partition,
    )
    root = Path(output_dir)
    image_staging = root / "model-maker-images"
    counts = {"train": len(train), "validation": len(validation), "negativeTrain": 0}
    for partition, records in (("train", train), ("validation", validation)):
        directory = root / partition
        directory.mkdir(parents=True, exist_ok=True)
        for record in records:
            image_id = record.get("imageId")
            if not isinstance(image_id, str) or not re.fullmatch(
                r"[A-Za-z0-9][A-Za-z0-9._:-]*", image_id
            ):
                raise TrainingConfigError("record imageId cannot name a Pascal annotation")
            boxes = record.get("boxes")
            if partition == "train" and boxes == []:
                counts["negativeTrain"] += 1
            document = _pascal_annotation(
                record,
                allow_empty=partition == "train",
                staging_dir=image_staging,
            )
            ET.indent(document)
            payload = ET.tostring(document, encoding="unicode").encode("utf-8")
            _atomic_write(directory / f"{image_id}.xml", payload)
    return counts


def _atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise TrainingConfigError(f"cannot hash required output {path}: {error}") from error


def _git_commit(project_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=project_root,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise TrainingConfigError("cannot resolve the Git commit for this run") from error
    return result.stdout.strip()


def prepare_training_run(
    *,
    config_path: str | Path,
    splits_path: str | Path,
    run_id: str,
    checkpoint_root: str | Path,
    requirements_lock: str | Path,
    git_commit: str | None = None,
    synthetic_manifest: str | Path | None = None,
    synthetic_review: str | Path | None = None,
) -> PreparedRun:
    """Validate all inputs before atomically creating reproducibility metadata."""

    if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id) or run_id in {".", ".."}:
        raise TrainingConfigError("run-id must be 1-64 safe filename characters")
    config_file = Path(os.path.abspath(config_path))
    splits_file = Path(os.path.abspath(splits_path))
    lock_file = Path(os.path.abspath(requirements_lock))
    def snapshot_input(path: Path, name: str) -> bytes:
        try:
            return secure_read_bytes(path, root=path.parent)
        except SecureReadError as error:
            raise TrainingConfigError(f"cannot read {name} {path}: {error}") from error

    config_payload = snapshot_input(config_file, "training config")
    split_payload = snapshot_input(splits_file, "split manifest")
    lock_payload = snapshot_input(lock_file, "requirements lock")
    config = load_training_config_bytes(config_payload, source_name=str(config_file))
    if (synthetic_manifest is None) != (synthetic_review is None):
        raise TrainingConfigError("synthetic manifest and human review must be provided together")
    source_document: dict[str, Any] | None = None
    synthetic_evidence: SyntheticEvidenceSnapshot | None = None
    if synthetic_manifest is not None and synthetic_review is not None:
        synthetic_evidence = capture_synthetic_evidence(
            split_payload=split_payload,
            synthetic_manifest=synthetic_manifest,
            human_review=synthetic_review,
        )
        source_document = merge_approved_synthetic_training(synthetic_evidence)
    train, validation = (
        _load_training_partitions_payload(
            split_payload,
            train_partition=config.train_partition,
            validation_partition=config.validation_partition,
        )
        if source_document is None
        else (
            list(source_document["partitions"][config.train_partition]),
            list(source_document["partitions"][config.validation_partition]),
        )
    )
    train = _resolve_training_paths(train, splits_file.parent)
    validation = _resolve_training_paths(validation, splits_file.parent)
    if not train:
        raise TrainingConfigError("no audited training records; refusing to create a run")
    if not validation:
        raise TrainingConfigError("no audited validation records; refusing to create a run")
    if any(record.get("isRealPhoto") is not True for record in validation):
        raise TrainingConfigError("validation must remain real-photo-only")
    # Validate every used byte before the first checkpoint-directory write.
    for record in train:
        if record.get("isRealPhoto") is True:
            _pascal_annotation(record, allow_empty=True)
    for record in validation:
        _pascal_annotation(record, allow_empty=False)
    training_labels = {
        str(box["label"])
        for record in train
        for box in record["boxes"]
        if isinstance(box, Mapping) and "label" in box
    }
    if training_labels != set(CARD_LABELS):
        raise TrainingConfigError("training data must contain all 52 canonical classes")
    validation_labels = {
        str(box["label"])
        for record in validation
        for box in record["boxes"]
        if isinstance(box, Mapping) and "label" in box
    }
    if validation_labels != set(CARD_LABELS):
        raise TrainingConfigError("validation data must contain all 52 canonical classes")
    lock_hash = hashlib.sha256(lock_payload).hexdigest()
    split_manifest_hash = hashlib.sha256(split_payload).hexdigest()
    training_input_hash = split_manifest_hash
    synthetic_manifest_hash: str | None = None
    synthetic_review_hash: str | None = None
    synthetic_generation_hash: str | None = None
    commit = git_commit or _git_commit(config_file.parents[3])
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit):
        raise TrainingConfigError("git commit must be a hexadecimal revision")

    root = Path(checkpoint_root).resolve()
    output_dir = (root / run_id).resolve()
    if output_dir.parent != root:
        raise TrainingConfigError("run-id resolves outside the checkpoint root")
    if output_dir.exists():
        raise TrainingConfigError(f"run output already exists: {output_dir}")
    root.mkdir(parents=True, exist_ok=True)
    staging_dir = Path(tempfile.mkdtemp(prefix=f".{run_id}.", dir=root))
    try:
        merged_path = staging_dir / "training-input.json"
        if source_document is not None:
            merged_payload = _canonical_json(source_document)
            training_input_hash = hashlib.sha256(merged_payload).hexdigest()
            synthetic_manifest_hash = str(source_document["syntheticManifestSha256"])
            synthetic_review_hash = str(source_document["syntheticReviewSha256"])
            synthetic_generation_hash = str(source_document["syntheticGenerationConfigSha256"])
            assert synthetic_evidence is not None
            manifest_document = _json_payload(
                synthetic_evidence.manifest_payload, "synthetic manifest"
            )
            image_members = {
                str(record.get("path"))
                for record in manifest_document.get("records", [])
                if isinstance(record, Mapping)
            }
            for member in sorted(image_members):
                payload = synthetic_evidence.generated_payloads[member]
                secure_write_bytes(
                    staging_dir,
                    PurePosixPath("synthetic-evidence") / PurePosixPath(member),
                    payload,
                )
        else:
            merged_payload = split_payload
        _atomic_write(merged_path, merged_payload)
        if source_document is not None:
            splits_file = merged_path
            snapshotted_train, snapshotted_validation = _load_training_partitions(
                splits_file,
                train_partition=config.train_partition,
                validation_partition=config.validation_partition,
            )
            for record in snapshotted_train:
                _pascal_annotation(record, allow_empty=True)
            for record in snapshotted_validation:
                _pascal_annotation(record, allow_empty=False)
        resolved_yaml = yaml.safe_dump(config.document, sort_keys=True).encode("utf-8")
        metadata = {
            "schemaVersion": 2,
            "requirementsLockSha256": lock_hash,
            "splitManifestSha256": split_manifest_hash,
            "trainingInputSha256": training_input_hash,
            "syntheticManifestSha256": synthetic_manifest_hash,
            "syntheticReviewSha256": synthetic_review_hash,
            "syntheticGenerationConfigSha256": synthetic_generation_hash,
            "gitCommit": commit,
        }
        _atomic_write(staging_dir / "resolved-config.yaml", resolved_yaml)
        _atomic_write(
            staging_dir / "run-metadata.json",
            (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )
    except BaseException:
        shutil.rmtree(staging_dir, ignore_errors=True)
        raise
    return PreparedRun(config, staging_dir, output_dir, splits_file, lock_file)


def _augmentation_hparams(config: TrainingConfig) -> dict[str, Any]:
    return {
        "image_size": f"{config.image_size}x{config.image_size}",
        "input_rand_hflip": config.augmentation["horizontal_flip"],
        "jitter_min": 0.1 if config.augmentation["scale_jitter"] else 1.0,
        "jitter_max": 2.0 if config.augmentation["scale_jitter"] else 1.0,
        "autoaugment_policy": "v0" if config.augmentation["autoaugment"] else None,
    }


def assert_model_maker_label_maps(train_data: Any, validation_data: Any) -> None:
    """Require output indices 1..52 to match the canonical Android contract."""

    expected = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    if train_data.label_map != expected or validation_data.label_map != expected:
        raise TrainingConfigError(
            "Model Maker train and validation loaders must use the canonical label map"
        )


def assert_negative_examples_loaded(train_data: Any, expected_negative_count: int) -> None:
    """Inspect Model Maker's actual TFRecords and prove negatives survived conversion."""

    if expected_negative_count <= 0:
        return
    import tensorflow as tf

    files = sorted(tf.io.gfile.glob(str(train_data.tfrecord_file_patten)))
    if not files:
        raise TrainingConfigError("Model Maker training TFRecords are missing")
    feature = {"image/object/class/label": tf.io.VarLenFeature(tf.int64)}

    def label_count(serialized: Any) -> Any:
        parsed = tf.io.parse_single_example(serialized, feature)
        return tf.size(parsed["image/object/class/label"].values)

    batches = (
        tf.data.TFRecordDataset(files)
        .map(label_count, num_parallel_calls=1)
        .batch(int(train_data.size), drop_remainder=False)
    )
    values = list(batches.as_numpy_iterator())
    if len(values) != 1:
        raise TrainingConfigError("Model Maker negative verification did not form one full batch")
    counts = [int(value) for value in values[0].tolist()]
    if counts.count(0) != expected_negative_count or not any(count > 0 for count in counts):
        raise TrainingConfigError(
            "Model Maker TFRecords did not retain negatives alongside positive examples"
        )


def validate_validation_metrics(metrics: Mapping[str, Any]) -> None:
    """Require aggregate AP and one finite per-class AP for every canonical card."""

    aggregate = metrics.get("AP")
    if (
        isinstance(aggregate, bool)
        or not isinstance(aggregate, Real)
        or not math.isfinite(aggregate)
    ):
        raise TrainingConfigError("validation metrics require a finite aggregate AP")
    expected = {f"AP_/{label}" for label in CARD_LABELS}
    actual = {key for key in metrics if key.startswith("AP_/")}
    if actual != expected:
        raise TrainingConfigError(
            "validation metrics require exactly 52 canonical per-class AP keys"
        )
    if any(
        isinstance(metrics[key], bool)
        or not isinstance(metrics[key], Real)
        or not math.isfinite(metrics[key])
        for key in expected
    ):
        raise TrainingConfigError("validation AP metrics must be finite")


def _run_model_maker_training(prepared: PreparedRun, one_batch_smoke: bool) -> TrainingOutcome:
    """Execute Model Maker through fit; publishing is handled by the caller."""

    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    import numpy as np
    import tensorflow as tf
    from tensorflow_examples.lite.model_maker.third_party.efficientdet.keras import (
        train,
        train_lib,
    )
    from tflite_model_maker import object_detector

    config = prepared.config
    random.seed(config.seed)
    np.random.seed(config.seed)
    tf.random.set_seed(config.seed)
    annotations = prepared.staging_dir / "pascal"
    counts = write_model_maker_pascal(
        prepared.splits_path,
        annotations,
        train_partition=config.train_partition,
        validation_partition=config.validation_partition,
    )
    cache_dir = prepared.staging_dir / "model-maker-cache"
    train_names = sorted(path.stem for path in (annotations / "train").glob("*.xml"))
    validation_names = sorted(path.stem for path in (annotations / "validation").glob("*.xml"))
    train_data = object_detector.DataLoader.from_pascal_voc(
        images_dir="/",
        annotations_dir=str(annotations / "train"),
        label_map=list(CARD_LABELS),
        annotation_filenames=train_names,
        num_shards=1,
        cache_dir=str(cache_dir / "train"),
    )
    validation_data = object_detector.DataLoader.from_pascal_voc(
        images_dir="/",
        annotations_dir=str(annotations / "validation"),
        label_map=list(CARD_LABELS),
        annotation_filenames=validation_names,
        num_shards=1,
        cache_dir=str(cache_dir / "validation"),
    )
    assert_model_maker_label_maps(train_data, validation_data)
    assert_negative_examples_loaded(train_data, counts["negativeTrain"])
    batch_size = 1 if one_batch_smoke else config.batch_size
    if len(train_data) < batch_size:
        raise TrainingConfigError("training record count is smaller than batch_size")
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(prepared.staging_dir / "model"),
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        steps_per_execution=1,
        hparams=_augmentation_hparams(config),
        tf_random_seed=config.seed,
    )
    detector = object_detector.create(
        train_data,
        model_spec=spec,
        validation_data=validation_data,
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        train_whole_model=False if one_batch_smoke else config.train_whole_model,
        do_train=False,
    )
    train_ds, train_steps, _ = detector._get_dataset_and_steps(  # noqa: SLF001
        train_data, batch_size, is_training=True
    )
    validation_ds, validation_steps, validation_json = detector._get_dataset_and_steps(  # noqa: SLF001
        validation_data, batch_size, is_training=False
    )
    if one_batch_smoke:
        train_steps = 1
        validation_ds = None
        validation_steps = 0
        validation_json = None
    model = detector.create_model()
    spec.config.update(
        {
            "steps_per_epoch": train_steps,
            "eval_samples": batch_size * validation_steps,
            "val_json_file": validation_json,
            "batch_size": batch_size,
        }
    )
    train.setup_model(model, spec.config)
    train.init_experimental(spec.config)
    callbacks = train_lib.get_callbacks(spec.config.as_dict(), validation_ds)
    callbacks.append(
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss" if validation_ds is not None else "loss",
            patience=0 if one_batch_smoke else config.early_stopping_patience,
            restore_best_weights=True,
        )
    )
    history = model.fit(
        train_ds,
        epochs=1 if one_batch_smoke else config.epochs,
        steps_per_epoch=train_steps,
        callbacks=callbacks,
        validation_data=validation_ds,
        validation_steps=validation_steps or None,
        verbose=2,
    )
    detector.model = model
    return TrainingOutcome(
        model=model,
        detector=detector,
        validation_data=validation_data,
        history={name: list(values) for name, values in history.history.items()},
        record_counts=counts,
    )


def _save_selected_weights(model: Any, path: Path) -> str:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.stem}.", suffix=".weights.h5", dir=path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        model.save_weights(str(temporary), save_format="h5")
        with temporary.open("rb") as handle:
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return _sha256(path)


def _finalize_training_result(
    prepared: PreparedRun, outcome: TrainingOutcome, *, one_batch_smoke: bool
) -> dict[str, Any]:
    selected = prepared.staging_dir / "selected.weights.h5"
    selected_hash = _save_selected_weights(outcome.model, selected)
    outcome.model.load_weights(str(selected))
    outcome.detector.model = outcome.model
    validation_metrics = outcome.detector.evaluate(outcome.validation_data)
    validate_validation_metrics(validation_metrics)

    # Intermediate callback checkpoints are not candidates for Stage 6. Only the
    # restored-best, reloaded, evaluated checkpoint survives publication.
    shutil.rmtree(prepared.staging_dir / "model", ignore_errors=True)
    metrics = {
        "schemaVersion": 1,
        "status": "SMOKE_ONLY" if one_batch_smoke else "TRAINED_NOT_RELEASE_APPROVED",
        "recordCounts": outcome.record_counts,
        "history": {
            name: [float(value) for value in values] for name, values in outcome.history.items()
        },
        "validation": {name: float(value) for name, value in validation_metrics.items()},
        "selectedCheckpoint": selected.name,
        "selectedCheckpointSha256": selected_hash,
    }
    _atomic_write(
        prepared.staging_dir / "metrics.json",
        (json.dumps(metrics, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    return metrics


def _publish_staged_run(prepared: PreparedRun) -> None:
    if prepared.output_dir.exists():
        raise TrainingConfigError(f"run output already exists: {prepared.output_dir}")
    os.replace(prepared.staging_dir, prepared.output_dir)


def run_training(prepared: PreparedRun, *, one_batch_smoke: bool = False) -> dict[str, Any]:
    """Train, validate, then atomically publish one complete run directory."""

    try:
        # Export needs representative real images for integer calibration. Keep the
        # exact train/validation CSV inside the atomically published run so a valid
        # checkpoint can always be exported without reconstructing hidden inputs.
        write_model_maker_csv(
            prepared.splits_path,
            prepared.staging_dir / "annotations.csv",
            train_partition=prepared.config.train_partition,
            validation_partition=prepared.config.validation_partition,
        )
        outcome = _run_model_maker_training(prepared, one_batch_smoke)
        metrics = _finalize_training_result(prepared, outcome, one_batch_smoke=one_batch_smoke)
        _publish_staged_run(prepared)
        return metrics
    except BaseException:
        shutil.rmtree(prepared.staging_dir, ignore_errors=True)
        raise


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--splits", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--checkpoint-root", type=Path, default=Path("checkpoints"))
    parser.add_argument("--requirements-lock", type=Path, default=Path("requirements.lock"))
    parser.add_argument("--one-batch-smoke", action="store_true")
    parser.add_argument("--synthetic-manifest", type=Path)
    parser.add_argument("--synthetic-review", type=Path)
    args = parser.parse_args(argv)
    try:
        prepared = prepare_training_run(
            config_path=args.config,
            splits_path=args.splits,
            run_id=args.run_id,
            checkpoint_root=args.checkpoint_root,
            requirements_lock=args.requirements_lock,
            synthetic_manifest=args.synthetic_manifest,
            synthetic_review=args.synthetic_review,
        )
        run_training(prepared, one_batch_smoke=args.one_batch_smoke)
    except TrainingConfigError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
