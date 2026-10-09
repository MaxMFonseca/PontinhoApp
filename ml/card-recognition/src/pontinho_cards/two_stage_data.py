"""Materialize leakage-safe detector scenes and original-resolution classifier crops."""

from __future__ import annotations

import hashlib
import io
import math
import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from types import MappingProxyType
from typing import Any

from PIL import Image

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.secure_io import (
    SecureReadError,
    safe_relative_posix,
    secure_read_bytes,
    secure_write_bytes_immutable,
)
from pontinho_cards.split import SplitError, validate_split_document

_TRAINING_PARTITIONS = ("train", "validation")
_CROP_PADDING_RATIO = 0.10


class TwoStageDataError(ValueError):
    """Raised when two-stage inputs cannot be materialized without leakage."""


@dataclass(frozen=True)
class DetectorBox:
    label: str
    bounds: tuple[float, float, float, float]


@dataclass(frozen=True)
class DetectorRecord:
    image_id: str
    path: Path
    width: int
    height: int
    deck_group_id: str
    split_group_id: str
    source_image_sha256: str
    boxes: tuple[DetectorBox, ...]


@dataclass(frozen=True)
class ClassifierCrop:
    label: str
    source_bounds: tuple[int, int, int, int]
    deck_group_id: str
    source_image_sha256: str
    path: Path


@dataclass(frozen=True)
class TwoStageInputManifest:
    detector_partitions: Mapping[str, tuple[DetectorRecord, ...]]
    classifier_partitions: Mapping[str, tuple[ClassifierCrop, ...]]
    output_root: Path
    evidence_mode: str = "DECK_DISJOINT"


@dataclass(frozen=True)
class _AuditedScene:
    partition: str
    record: Mapping[str, Any]
    payload: bytes


def _validate_training_contract(
    partitions: Mapping[str, list[dict[str, Any]]],
) -> None:
    expected = set(CARD_LABELS)
    for partition in _TRAINING_PARTITIONS:
        records = partitions[partition]
        if not records:
            raise TwoStageDataError(f"{partition} must contain real scene records")
        if any("taskRoles" not in record for record in records):
            raise TwoStageDataError(
                f"{partition} records must preserve audited taskRoles provenance"
            )
        if any(
            not {"DETECTOR_SCENE", "CLASSIFIER_CROP"}.intersection(record["taskRoles"])
            for record in records
        ):
            raise TwoStageDataError(f"{partition} contains a record without a training role")
        labels = {
            box["label"]
            for record in records
            if "CLASSIFIER_CROP" in record["taskRoles"]
            for box in record["boxes"]
        }
        if labels != expected:
            missing = ", ".join(sorted(expected - labels)) or "none"
            raise TwoStageDataError(
                f"{partition} must cover all 52 canonical classes; missing: {missing}"
            )
        if any(record["isRealPhoto"] is not True for record in records):
            raise TwoStageDataError(f"{partition} must contain only real scene records")


def _read_audited_scene(root: Path, partition: str, record: Mapping[str, Any]) -> _AuditedScene:
    try:
        relative = safe_relative_posix(record["path"], field="normalized image path")
        payload = secure_read_bytes(root.joinpath(*relative.parts), root=root)
    except (KeyError, ValueError, SecureReadError) as error:
        raise TwoStageDataError(
            f"cannot securely read {partition} source image {record.get('imageId')!r}: {error}"
        ) from error
    digest = hashlib.sha256(payload).hexdigest()
    if digest != record["imageSha256"]:
        raise TwoStageDataError(
            f"source image SHA-256 differs from metadata for {record['imageId']!r}"
        )
    try:
        with Image.open(io.BytesIO(payload)) as image:
            image.load()
            actual_size = image.size
    except (OSError, SyntaxError) as error:
        raise TwoStageDataError(f"source image cannot be decoded: {record['imageId']!r}") from error
    expected_size = (record["width"], record["height"])
    if actual_size != expected_size:
        raise TwoStageDataError(
            f"source image dimensions differ from metadata for {record['imageId']!r}"
        )
    return _AuditedScene(partition=partition, record=record, payload=payload)


def _immutable_write(root: Path, relative: PurePosixPath, payload: bytes) -> Path:
    target = root.joinpath(*relative.parts)
    try:
        secure_write_bytes_immutable(root, relative, payload)
    except SecureReadError as error:
        if "immutable destination" in str(error):
            raise TwoStageDataError(f"immutable derived file content differs: {target}") from error
        raise TwoStageDataError(f"cannot write secure output {relative}: {error}") from error
    return target


def _source_suffix(record: Mapping[str, Any]) -> str:
    suffix = PurePosixPath(str(record["path"])).suffix.casefold()
    return suffix if suffix in {".jpg", ".jpeg", ".png", ".webp"} else ".img"


def _padded_bounds(
    box: Mapping[str, Any], width: int, height: int
) -> tuple[int, int, int, int]:
    box_width = float(box["xmax"]) - float(box["xmin"])
    box_height = float(box["ymax"]) - float(box["ymin"])
    pad_x = box_width * _CROP_PADDING_RATIO
    pad_y = box_height * _CROP_PADDING_RATIO
    left = max(0, math.floor(float(box["xmin"]) - pad_x))
    top = max(0, math.floor(float(box["ymin"]) - pad_y))
    right = min(width, math.ceil(float(box["xmax"]) + pad_x))
    bottom = min(height, math.ceil(float(box["ymax"]) + pad_y))
    if right <= left or bottom <= top:
        raise TwoStageDataError("padded classifier crop has no usable pixels")
    return left, top, right, bottom


def _crop_png(payload: bytes, bounds: tuple[int, int, int, int]) -> bytes:
    try:
        with Image.open(io.BytesIO(payload)) as source:
            source.load()
            crop = source.convert("RGB").crop(bounds)
            output = io.BytesIO()
            crop.save(output, format="PNG")
    except (OSError, SyntaxError) as error:
        raise TwoStageDataError("source image cannot be cropped") from error
    return output.getvalue()


def _box_index(box: Mapping[str, Any], canonical_index: int) -> int:
    value = box.get("boxIndex", canonical_index)
    if type(value) is not int or value < 0:
        raise TwoStageDataError("boxIndex must be a non-negative integer")
    return value


def build_two_stage_inputs(
    split_document: Mapping[str, object], output_root: Path, *,
    evidence_mode: str = "DECK_DISJOINT", include_experimental_test: bool = False,
) -> TwoStageInputManifest:
    """Build immutable train/validation projections without opening final holdout bytes.

    Normalized image paths are resolved beneath ``output_root``. Derived files are
    content-addressed below its ``detector`` and ``classifier`` directories.
    """

    root = Path(os.path.abspath(output_root))
    try:
        partitions = validate_split_document(split_document, evidence_mode=evidence_mode)
    except SplitError as error:
        raise TwoStageDataError(str(error)) from error
    _validate_training_contract(partitions)
    if include_experimental_test and evidence_mode != "EXPERIMENTAL_ONE_DECK":
        raise TwoStageDataError("only experimental test may be materialized")
    selected_partitions = (
        (*_TRAINING_PARTITIONS, "test") if include_experimental_test else _TRAINING_PARTITIONS
    )

    audited = tuple(
        _read_audited_scene(root, partition, record)
        for partition in selected_partitions
        for record in partitions[partition]
    )
    detector_partitions: dict[str, list[DetectorRecord]] = {
        partition: [] for partition in selected_partitions
    }
    classifier_partitions: dict[str, list[ClassifierCrop]] = {
        partition: [] for partition in selected_partitions
    }

    for scene in audited:
        record = scene.record
        source_digest = str(record["imageSha256"])
        if "DETECTOR_SCENE" in record["taskRoles"]:
            detector_relative = PurePosixPath(
                "detector", scene.partition, f"{source_digest}{_source_suffix(record)}"
            )
            detector_path = _immutable_write(root, detector_relative, scene.payload)
            detector_boxes = tuple(
                DetectorBox(
                    label="card",
                    bounds=(box["xmin"], box["ymin"], box["xmax"], box["ymax"]),
                )
                for box in record["boxes"]
            )
            detector_partitions[scene.partition].append(
                DetectorRecord(
                    image_id=str(record["imageId"]),
                    path=detector_path,
                    width=int(record["width"]),
                    height=int(record["height"]),
                    deck_group_id=str(record["deckGroupId"]),
                    split_group_id=str(record["splitGroupId"]),
                    source_image_sha256=source_digest,
                    boxes=detector_boxes,
                )
            )

        if "CLASSIFIER_CROP" not in record["taskRoles"]:
            continue
        for canonical_index, box in enumerate(record["boxes"]):
            box_index = _box_index(box, canonical_index)
            label = str(box["label"])
            bounds = _padded_bounds(box, int(record["width"]), int(record["height"]))
            crop_payload = _crop_png(scene.payload, bounds)
            crop_digest = hashlib.sha256(crop_payload).hexdigest()
            crop_relative = PurePosixPath(
                "classifier",
                scene.partition,
                label,
                f"{source_digest}-b{box_index:04d}-{label}-{crop_digest}.png",
            )
            crop_path = _immutable_write(root, crop_relative, crop_payload)
            classifier_partitions[scene.partition].append(
                ClassifierCrop(
                    label=label,
                    source_bounds=bounds,
                    deck_group_id=str(record["deckGroupId"]),
                    source_image_sha256=source_digest,
                    path=crop_path,
                )
            )

    detector = MappingProxyType(
        {partition: tuple(detector_partitions[partition]) for partition in selected_partitions}
    )
    classifier = MappingProxyType(
        {
            partition: tuple(classifier_partitions[partition])
            for partition in selected_partitions
        }
    )
    return TwoStageInputManifest(
        detector_partitions=detector,
        classifier_partitions=classifier,
        output_root=root,
        evidence_mode=evidence_mode,
    )
