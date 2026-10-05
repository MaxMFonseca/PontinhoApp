"""Fail-closed release gate for the Android card-detector asset pair."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import shutil
import stat
import sys
import tarfile
import tempfile
import zipfile
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

import yaml
from PIL import Image

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.manifest import (
    ManifestError,
    SourceManifest,
    parse_source_manifest_bytes,
)
from pontinho_cards.release_identity import validate_release_identity
from pontinho_cards.secure_io import SecureReadError, secure_read_bytes

_SHA256 = "0123456789abcdef"
_RELEASE_MODEL = "card_detector.tflite"
_RELEASE_METADATA = "card_detector.release.json"
_TRANSACTION = ".card_detector.release.transaction"
_REPARSE_POINT = 0x400
_PINNED_GATE_PATH = Path(__file__).resolve().parents[2] / "config" / "release-gate.yaml"


class ReleaseGateError(ValueError):
    """Raised before evidence can authorize an unsafe release."""


def _validate_release_identity(model_version: object, exported_at: object) -> tuple[str, str]:
    try:
        return validate_release_identity(model_version, exported_at)
    except ValueError as error:
        raise ReleaseGateError(str(error)) from error


@dataclass(frozen=True)
class ReleaseInputs:
    model: Path
    report: Path
    contract: Path
    source_manifest: Path
    split_manifest: Path
    holdout_manifest: Path
    validation_receipt: Path
    candidate_contract: Path
    comparison: Path
    validation_manifest: Path
    assets_dir: Path
    threshold_selection: Path | None = None
    source_audit_receipt: Path | None = None
    source_archives: Path | None = None
    normalized_records: Path | None = None
    training_input: Path | None = None
    synthetic_manifest: Path | None = None
    synthetic_review: Path | None = None


def _digest_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _is_digest(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in _SHA256 for character in value)
    )


def _regular_bytes(path: Path, name: str) -> bytes:
    try:
        return secure_read_bytes(path)
    except SecureReadError as error:
        raise ReleaseGateError(f"cannot read required {name}: {path}: {error}") from error


def _lstat_or_none(path: Path) -> os.stat_result | None:
    try:
        return path.lstat()
    except FileNotFoundError:
        return None
    except OSError as error:
        raise ReleaseGateError(f"cannot inspect filesystem path {path}: {error}") from error


def _is_link_or_reparse(result: os.stat_result) -> bool:
    attributes = int(getattr(result, "st_file_attributes", 0))
    return stat.S_ISLNK(result.st_mode) or bool(attributes & _REPARSE_POINT)


def _assert_safe_asset_directory(path: Path) -> None:
    absolute = Path(os.path.abspath(path))
    for candidate in (absolute, *absolute.parents):
        result = _lstat_or_none(candidate)
        if result is not None and _is_link_or_reparse(result):
            raise ReleaseGateError(
                "Android assets directory cannot use a symbolic link/reparse point"
            )
    result = _lstat_or_none(absolute)
    if result is not None and not stat.S_ISDIR(result.st_mode):
        raise ReleaseGateError("Android assets path must be a directory")


def _same_file_identity(left: Path, right: Path) -> bool:
    left_stat = left.lstat()
    right_stat = right.lstat()
    return (left_stat.st_dev, left_stat.st_ino) == (right_stat.st_dev, right_stat.st_ino)


def _json_document(payload: bytes, name: str) -> Mapping[str, Any]:
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseGateError(f"invalid {name}: {error}") from error
    if not isinstance(document, Mapping):
        raise ReleaseGateError(f"{name} must be an object")
    return document


def _read_json(path: Path, name: str) -> tuple[Mapping[str, Any], bytes]:
    payload = _regular_bytes(path, name)
    return _json_document(payload, name), payload


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _unit(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ReleaseGateError(f"{name} must be a finite number in [0, 1]")
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise ReleaseGateError(f"{name} must be a finite number in [0, 1]")
    return number


def _positive_int(value: Any, name: str) -> int:
    if type(value) is not int or value <= 0:
        raise ReleaseGateError(f"{name} must be a positive integer")
    return value


def _required_digest(document: Mapping[str, Any], field: str, name: str) -> str:
    value = document.get(field)
    if not _is_digest(value):
        raise ReleaseGateError(f"{name} metadata {field} must be a lowercase SHA-256")
    return str(value)


def _load_gate(path: Path) -> tuple[int, int, float, int | None, str | None]:
    payload = _regular_bytes(path, "release gate configuration")
    try:
        document = yaml.safe_load(payload)
    except yaml.YAMLError as error:
        raise ReleaseGateError(f"invalid release gate configuration: {error}") from error
    required = {
        "schema_version",
        "minimum_real_photo_count",
        "minimum_unseen_deck_groups",
        "minimum_exact_total_accuracy",
    }
    optional = {"source_audit_schema_version", "source_audit_receipt_sha256"}
    if (
        not isinstance(document, Mapping)
        or not required.issubset(document)
        or not set(document).issubset(required | optional)
    ):
        raise ReleaseGateError("release gate configuration schema is invalid")
    if document.get("schema_version") != 1:
        raise ReleaseGateError("release gate configuration schema version is unsupported")
    photos = _positive_int(document.get("minimum_real_photo_count"), "photo minimum")
    decks = _positive_int(document.get("minimum_unseen_deck_groups"), "deck minimum")
    accuracy = _unit(document.get("minimum_exact_total_accuracy"), "accuracy minimum")
    if (photos, decks, accuracy) != (200, 10, 0.95):
        raise ReleaseGateError("release thresholds may not weaken the approved 200/10/0.95 gate")
    audit_schema = document.get("source_audit_schema_version")
    audit_hash = document.get("source_audit_receipt_sha256")
    if audit_schema is not None and audit_schema != 2:
        raise ReleaseGateError("source audit trust-root schema version is unsupported")
    if audit_hash is not None and not _is_digest(audit_hash):
        raise ReleaseGateError("source audit trust-root hash must be a lowercase SHA-256")
    if audit_schema is None and audit_hash is not None:
        raise ReleaseGateError("source audit trust-root hash requires its schema version")
    return photos, decks, accuracy, audit_schema, audit_hash


def _validate_threshold_selection(
    artifact: Mapping[str, Any],
    *,
    model_hash: str,
    checkpoint_hash: str,
    config_hash: str,
    split_hash: str,
    validation_hash: str,
    score_threshold: float,
    iou_threshold: float,
) -> Mapping[str, Any]:
    """Recompute the deterministic best validation row and bind its provenance."""

    if artifact.get("schemaVersion") != 1:
        raise ReleaseGateError("threshold selection schemaVersion must be 1")
    bindings = {
        "modelSha256": model_hash,
        "checkpointSha256": checkpoint_hash,
        "configSha256": config_hash,
        "splitManifestSha256": split_hash,
        "validationManifestSha256": validation_hash,
    }
    if any(artifact.get(field) != value for field, value in bindings.items()):
        raise ReleaseGateError("threshold selection provenance mismatch")
    rows = artifact.get("rows")
    selected = artifact.get("selected")
    if not isinstance(rows, list) or not rows or not isinstance(selected, Mapping):
        raise ReleaseGateError("threshold sweep and selection must be nonempty")
    normalized: list[dict[str, float]] = []
    seen: set[tuple[float, float]] = set()
    fields = {
        "scoreThreshold",
        "iouThreshold",
        "exactHandTotalAccuracy",
        "exactCardMultisetAccuracy",
        "abstentionRate",
    }
    for row in rows:
        if not isinstance(row, Mapping) or set(row) != fields:
            raise ReleaseGateError("threshold sweep row schema is invalid")
        item = {field: _unit(row.get(field), f"threshold sweep {field}") for field in fields}
        coordinate = (item["scoreThreshold"], item["iouThreshold"])
        if coordinate in seen:
            raise ReleaseGateError("threshold sweep contains duplicate coordinates")
        seen.add(coordinate)
        normalized.append(item)
    best = max(
        normalized,
        key=lambda row: (
            row["exactHandTotalAccuracy"],
            row["exactCardMultisetAccuracy"],
            -row["abstentionRate"],
            row["scoreThreshold"],
            row["iouThreshold"],
        ),
    )
    if dict(selected) != best:
        raise ReleaseGateError("threshold selection is not the deterministic best sweep row")
    if best["scoreThreshold"] != score_threshold or best["iouThreshold"] != iou_threshold:
        raise ReleaseGateError("selected thresholds differ from the model contract")
    return best


def _recompute_threshold_sweep(
    artifact: Mapping[str, Any],
    *,
    model_payload: bytes,
    contract: Mapping[str, Any],
    validation: Mapping[str, Any],
    validation_path: Path,
    seed: int,
) -> None:
    """Rerun validation inference and reproduce every declared sweep cell."""

    if artifact.get("labels") != list(CARD_LABELS) or artifact.get("preprocessing") != {
        "input": contract.get("input"),
        "normalization": contract.get("normalization"),
    }:
        raise ReleaseGateError("threshold sweep preprocessing/label contract mismatch")
    grid = artifact.get("grid")
    if not isinstance(grid, Mapping):
        raise ReleaseGateError("threshold sweep grid is missing")
    scores = grid.get("scoreThresholds")
    ious = grid.get("iouThresholds")
    if not isinstance(scores, list) or not scores or not isinstance(ious, list) or not ious:
        raise ReleaseGateError("threshold sweep grid must be nonempty")
    score_values = [_unit(value, "grid score threshold") for value in scores]
    iou_values = [_unit(value, "grid IoU threshold") for value in ious]
    if len(set(score_values)) != len(score_values) or len(set(iou_values)) != len(iou_values):
        raise ReleaseGateError("threshold sweep grid contains duplicates")
    try:
        from pontinho_cards.evaluate import EvaluationError, _infer_scenes, evaluate_scenes

        with tempfile.TemporaryDirectory(prefix="pontinho-threshold-replay-") as directory:
            model = Path(directory) / _RELEASE_MODEL
            model.write_bytes(model_payload)
            scenes = _infer_scenes(
                model,
                contract,
                {"test": validation["records"]},
                validation_path,
            )
        disclosed = [
            {
                "sceneId": scene["sceneId"],
                "predictions": [
                    {
                        "label": detection.label,
                        "box": list(detection.box),
                        "confidence": detection.confidence,
                    }
                    for detection in scene["predictions"]
                ],
            }
            for scene in scenes
        ]
        if artifact.get("scenes") != disclosed:
            raise ReleaseGateError("threshold sweep raw per-scene detections mismatch")
        rows = []
        for score in score_values:
            for iou in iou_values:
                metrics = evaluate_scenes(
                    scenes,
                    confidence_threshold=score,
                    iou_threshold=iou,
                    seed=seed,
                    bootstrap_samples=20,
                )
                rows.append(
                    {
                        "scoreThreshold": score,
                        "iouThreshold": iou,
                        "exactHandTotalAccuracy": metrics["exactHandTotalAccuracy"],
                        "exactCardMultisetAccuracy": metrics["exactCardMultisetAccuracy"],
                        "abstentionRate": metrics["abstentionRate"],
                    }
                )
    except EvaluationError as error:
        raise ReleaseGateError(f"threshold sweep replay failed: {error}") from error
    if artifact.get("rows") != rows:
        raise ReleaseGateError("threshold sweep metrics differ from replayed inference")


def _validate_contract(
    contract: Mapping[str, Any],
    *,
    model_hash: str,
    split_hash: str,
    training_input_hash: str,
    synthetic_manifest_hash: str | None,
    synthetic_review_hash: str | None,
    synthetic_generation_hash: str | None,
    validation_hash: str,
    candidate_hash: str,
    comparison_hash: str,
    candidate: Mapping[str, Any],
    comparison: Mapping[str, Any],
) -> tuple[str, str, float, float]:
    if contract.get("schemaVersion") != 1:
        raise ReleaseGateError("model contract schemaVersion must be 1")
    if contract.get("modelSha256") != model_hash:
        raise ReleaseGateError("model hash does not match the Stage 6 contract")
    if tuple(contract.get("labels", ())) != CARD_LABELS:
        raise ReleaseGateError("model contract lacks exact 52-class label order")
    input_contract = contract.get("input")
    if (
        not isinstance(input_contract, Mapping)
        or input_contract.get("shape") != [1, 320, 320, 3]
        or input_contract.get("shapeSignature") != [1, 320, 320, 3]
        or input_contract.get("dtype") not in {"float32", "int8", "uint8"}
    ):
        raise ReleaseGateError("model contract input metadata is missing or incompatible")
    outputs = contract.get("outputs")
    if not isinstance(outputs, list) or [
        item.get("semantic") for item in outputs if isinstance(item, Mapping)
    ] != ["boxes", "classes", "scores", "count"]:
        raise ReleaseGateError("model contract output metadata is missing or incompatible")
    normalization = contract.get("normalization")
    if not isinstance(normalization, Mapping) or set(normalization) != {"mean", "std"}:
        raise ReleaseGateError("model contract normalization metadata is missing")
    if contract.get("splitManifestSha256") != split_hash:
        raise ReleaseGateError("model contract is not bound to the frozen split")
    provenance_bindings = {
        "trainingInputSha256": training_input_hash,
        "syntheticManifestSha256": synthetic_manifest_hash,
        "syntheticReviewSha256": synthetic_review_hash,
        "syntheticGenerationConfigSha256": synthetic_generation_hash,
    }
    if any(contract.get(field) != value for field, value in provenance_bindings.items()):
        raise ReleaseGateError("model contract training/synthetic provenance mismatch")
    if contract.get("validationManifestSha256") != validation_hash:
        raise ReleaseGateError("model contract is not bound to the Stage 6 validation manifest")
    if contract.get("candidateContractSha256") != candidate_hash:
        raise ReleaseGateError("Stage 6 candidate-contract hash mismatch")
    if contract.get("comparisonSha256") != comparison_hash:
        raise ReleaseGateError("Stage 6 comparison hash mismatch")
    for field in ("checkpointSha256", "configSha256", "requirementsLockSha256"):
        _required_digest(contract, field, "model contract")
    commit = contract.get("runGitCommit")
    if (
        not isinstance(commit, str)
        or len(commit) not in {40, 64}
        or any(c not in _SHA256 for c in commit)
    ):
        raise ReleaseGateError("model contract runGitCommit metadata is invalid")
    if candidate.get("schemaVersion") != 1:
        raise ReleaseGateError("candidate contract schema is invalid")
    candidate_bindings = {
        "checkpointSha256": contract.get("checkpointSha256"),
        "configSha256": contract.get("configSha256"),
        "runGitCommit": contract.get("runGitCommit"),
        "splitManifestSha256": split_hash,
        **provenance_bindings,
        "requirementsLockSha256": contract.get("requirementsLockSha256"),
        "thresholdProvenance": "configured-not-validation-selected",
        "labels": list(CARD_LABELS),
    }
    if any(candidate.get(field) != value for field, value in candidate_bindings.items()):
        raise ReleaseGateError("candidate contract provenance differs from selected model")
    if comparison.get("schemaVersion") != 1:
        raise ReleaseGateError("Stage 6 comparison schema is invalid")
    if comparison.get("candidateContractSha256") != candidate_hash:
        raise ReleaseGateError("Stage 6 comparison is not bound to the candidate contract")
    quantization = contract.get("quantization")
    if not isinstance(quantization, Mapping) or quantization.get("variant") not in {
        "float16",
        "int8",
    }:
        raise ReleaseGateError("model quantization metadata is missing")
    selected = str(quantization["variant"])
    candidate_variants = candidate.get("variants")
    if (
        not isinstance(candidate_variants, Mapping)
        or not isinstance(candidate_variants.get(selected), Mapping)
        or candidate_variants[selected].get("modelSha256") != model_hash
    ):
        raise ReleaseGateError("candidate contract does not bind the selected model")
    if comparison.get("selected") != selected:
        raise ReleaseGateError("Stage 6 comparison did not select the promoted quantization")
    comparison_bindings = {
        "selectedCheckpointSha256": contract.get("checkpointSha256"),
        "runGitCommit": contract.get("runGitCommit"),
        "validationManifestSha256": validation_hash,
        "splitManifestSha256": split_hash,
        **provenance_bindings,
    }
    if any(comparison.get(field) != value for field, value in comparison_bindings.items()):
        raise ReleaseGateError("Stage 6 comparison provenance differs from selected model")
    variants = comparison.get("variants")
    evidence = contract.get("selectionEvidence")
    if not isinstance(variants, Mapping) or not isinstance(evidence, Mapping):
        raise ReleaseGateError("Stage 6 comparison/provenance metadata is missing")
    evidence_variants = evidence.get("variants")
    if not isinstance(evidence_variants, Mapping) or variants != evidence_variants:
        raise ReleaseGateError("Stage 6 comparison differs from embedded selection evidence")
    if comparison.get("selectionRationale") != evidence.get("selectionRationale") or comparison.get(
        "androidDevice"
    ) != evidence.get("androidDevice"):
        raise ReleaseGateError("Stage 6 comparison rationale/device provenance mismatch")
    selected_evidence = variants.get(selected)
    if (
        not isinstance(selected_evidence, Mapping)
        or selected_evidence.get("modelSha256") != model_hash
    ):
        raise ReleaseGateError("Stage 6 comparison selected-model hash mismatch")
    if contract.get("thresholdProvenance") != "validation-selected":
        raise ReleaseGateError("threshold provenance must be validation-selected")
    score = _unit(contract.get("scoreThreshold"), "contract score threshold")
    iou = _unit(contract.get("iouThreshold"), "contract IoU threshold")
    model_version = contract.get("modelVersion")
    exported_at = contract.get("exportedAt")
    model_version, exported_at = _validate_release_identity(model_version, exported_at)
    return model_version, exported_at, score, iou


def _validate_frozen_holdout(
    split: Mapping[str, Any],
    split_payload: bytes,
    holdout: Mapping[str, Any],
    *,
    minimum_photos: int,
    minimum_decks: int,
) -> tuple[list[Mapping[str, Any]], Counter[str], set[str]]:
    if split.get("schemaVersion") != 1 or holdout.get("schemaVersion") != 1:
        raise ReleaseGateError("frozen split/holdout schema is invalid")
    if holdout.get("kind") != "pontinho-frozen-holdout":
        raise ReleaseGateError("holdout is not frozen producer evidence")
    split_hash = _digest_bytes(split_payload)
    if holdout.get("splitManifestSha256") != split_hash:
        raise ReleaseGateError("frozen holdout split hash mismatch")
    if split.get("seed") != holdout.get("seed"):
        raise ReleaseGateError("frozen split and holdout seeds differ")
    partitions = split.get("partitions")
    if not isinstance(partitions, Mapping):
        raise ReleaseGateError("frozen split partitions are missing")
    for name in ("train", "validation", "test"):
        if not isinstance(partitions.get(name), list):
            raise ReleaseGateError(f"frozen split partition {name} is invalid")
    records = holdout.get("test")
    if not isinstance(records, list) or records != partitions["test"]:
        raise ReleaseGateError("frozen holdout must exactly equal split.test")
    if holdout.get("testPartitionSha256") != _digest_bytes(_canonical_json(records)):
        raise ReleaseGateError("frozen holdout test-partition hash mismatch")
    identities: dict[str, set[Any]] = {}
    for field in ("imageSha256", "sourceDerivativeId", "deckGroupId", "splitGroupId"):
        identities[field] = set()
        partition_sets: list[set[Any]] = []
        for name in ("train", "validation", "test"):
            values = {
                record.get(field) for record in partitions[name] if isinstance(record, Mapping)
            }
            if None in values:
                raise ReleaseGateError(f"frozen split contains missing {field}")
            partition_sets.append(values)
        if any(
            partition_sets[left] & partition_sets[right]
            for left in range(3)
            for right in range(left + 1, 3)
        ):
            raise ReleaseGateError(f"frozen split partitions overlap by {field}")
        identities[field] = partition_sets[2]
    if len(records) < minimum_photos or len(identities["imageSha256"]) < minimum_photos:
        raise ReleaseGateError(f"release requires at least {minimum_photos} distinct real photos")
    if any(
        not isinstance(record, Mapping) or record.get("isRealPhoto") is not True
        for record in records
    ):
        raise ReleaseGateError("frozen holdout must contain real photos only")
    decks = {str(record.get("deckGroupId")) for record in records}
    if len(decks) < minimum_decks:
        raise ReleaseGateError(f"release requires at least {minimum_decks} unseen deck groups")
    training_decks = {
        record.get("deckGroupId")
        for name in ("train", "validation")
        for record in partitions[name]
        if isinstance(record, Mapping)
    }
    if decks & training_decks:
        raise ReleaseGateError("holdout decks are not unseen")
    support: Counter[str] = Counter()
    for record in records:
        source_id = record.get("sourceId")
        if not isinstance(source_id, str) or not source_id:
            raise ReleaseGateError("holdout source provenance is missing")
        boxes = record.get("boxes")
        if not isinstance(boxes, list) or not boxes:
            raise ReleaseGateError("holdout ground truth is missing")
        for box in boxes:
            if not isinstance(box, Mapping) or box.get("label") not in CARD_LABELS:
                raise ReleaseGateError("holdout contains unsupported class evidence")
            support[str(box["label"])] += 1
    if set(support) != set(CARD_LABELS):
        raise ReleaseGateError("frozen holdout does not provide 52-class coverage")
    return records, support, decks


def _validate_sources(
    payload: bytes,
    expected_hash: str,
    records: Sequence[Mapping[str, Any]],
) -> SourceManifest:
    if _digest_bytes(payload) != expected_hash:
        raise ReleaseGateError("source manifest hash differs from validation report")
    try:
        manifest = parse_source_manifest_bytes(payload, source_name="snapshotted source manifest")
    except ManifestError as error:
        raise ReleaseGateError(
            f"source manifest failed strict license validation: {error}"
        ) from error
    if not manifest.sources:
        raise ReleaseGateError("source manifest failed strict license validation: no sources")
    by_id = {source.source_id: source for source in manifest.sources}
    if len(by_id) != len(manifest.sources):
        raise ReleaseGateError("source manifest contains duplicate source IDs")
    observed = Counter((str(record["sourceId"]), str(record["deckGroupId"])) for record in records)
    for (source_id, deck_group), count in observed.items():
        source = by_id.get(source_id)
        if source is None or source.deck_group_id != deck_group:
            raise ReleaseGateError("holdout source/deck provenance is not licensed")
        if count > source.holdout_eligible_photo_count:
            raise ReleaseGateError("holdout exceeds the source's audited eligible photo count")
    return manifest


def _safe_record_path(root: Path, raw_path: Any) -> Path:
    if not isinstance(raw_path, str) or not raw_path or "\\" in raw_path:
        raise ReleaseGateError("canonical image path is invalid")
    relative = PurePosixPath(raw_path)
    if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
        raise ReleaseGateError("canonical image path is unsafe")
    candidate = root.joinpath(*relative.parts)
    for component in (candidate, *candidate.parents):
        if component == root.parent:
            break
        state = _lstat_or_none(component)
        if state is not None and _is_link_or_reparse(state):
            raise ReleaseGateError("canonical image path cannot use a symbolic link/reparse point")
    return candidate


def _validate_canonical_image(root: Path, record: Mapping[str, Any]) -> bytes:
    path = _safe_record_path(root, record.get("path"))
    payload = _regular_bytes(path, "canonical image")
    if record.get("imageSha256") != _digest_bytes(payload):
        raise ReleaseGateError("canonical image SHA-256 mismatch")
    try:
        with Image.open(io.BytesIO(payload)) as image:
            image.load()
            dimensions = image.size
    except (OSError, SyntaxError) as error:
        raise ReleaseGateError("canonical image is not decodable") from error
    if dimensions != (record.get("width"), record.get("height")):
        raise ReleaseGateError("canonical image dimensions mismatch")
    return payload


def _validate_dataset_evidence(
    split: Mapping[str, Any],
    *,
    root: Path,
    manifest: SourceManifest,
) -> dict[str, bytes]:
    partitions = split["partitions"]
    train = partitions["train"]
    validation = partitions["validation"]
    if not train or not validation:
        raise ReleaseGateError("training and validation partitions must both be nonempty")
    training_labels = {
        box.get("label")
        for record in train
        if isinstance(record, Mapping)
        for box in record.get("boxes", [])
        if isinstance(box, Mapping)
    }
    if training_labels != set(CARD_LABELS):
        raise ReleaseGateError("training partition must provide exact 52-class coverage")
    sources = {source.source_id: source for source in manifest.sources}
    owner_by_id: dict[str, str] = {}
    records_by_id: dict[str, Mapping[str, Any]] = {}
    image_payloads: dict[str, bytes] = {}
    for partition in ("train", "validation", "test"):
        for record in partitions[partition]:
            if not isinstance(record, Mapping):
                raise ReleaseGateError("canonical split record must be an object")
            image_id = record.get("imageId")
            if not isinstance(image_id, str) or not image_id or image_id in owner_by_id:
                raise ReleaseGateError("canonical image IDs must be globally unique")
            owner_by_id[image_id] = partition
            records_by_id[image_id] = record
            boxes = record.get("boxes")
            if record.get("isRealPhoto") is False:
                if partition != "train" or record.get("sourceId") != "synthetic-tabletop-v1":
                    raise ReleaseGateError("synthetic records are allowed only in train")
                if not isinstance(boxes, list):
                    raise ReleaseGateError("synthetic record boxes must be a list")
                continue
            source = sources.get(record.get("sourceId"))
            if source is None or source.deck_group_id != record.get("deckGroupId"):
                raise ReleaseGateError("canonical record source/deck binding is not approved")
            if not isinstance(boxes, list) or not boxes:
                raise ReleaseGateError("real canonical record boxes must be nonempty")
            if not _is_digest(record.get("sourceArchiveMemberSha256")):
                raise ReleaseGateError("real canonical record lacks archive-member provenance")
            for box in boxes:
                if not isinstance(box, Mapping) or box.get("label") not in CARD_LABELS:
                    raise ReleaseGateError("canonical record contains an unsupported label")
            image_payloads[image_id] = _validate_canonical_image(root, record)
    for image_id, record in records_by_id.items():
        parent = record.get("augmentationParentId")
        if parent is None:
            continue
        if not isinstance(parent, str) or parent not in owner_by_id:
            raise ReleaseGateError("augmentation ancestry is incomplete")
        if owner_by_id[parent] != owner_by_id[image_id]:
            raise ReleaseGateError("augmentation ancestry crosses split partitions")
        seen = {image_id}
        cursor: Any = parent
        while cursor is not None:
            if cursor in seen:
                raise ReleaseGateError("augmentation ancestry contains a cycle")
            seen.add(cursor)
            cursor = records_by_id[cursor].get("augmentationParentId")
    return image_payloads


def _validate_training_input(
    inputs: ReleaseInputs,
    *,
    split_payload: bytes,
    contract: Mapping[str, Any],
) -> tuple[Mapping[str, Any], bytes, str | None, str | None, str | None]:
    """Rebuild reviewed synthetic training input and bind its exact immutable bytes."""

    split_hash = _digest_bytes(split_payload)
    synthetic_hash = contract.get("syntheticManifestSha256")
    review_hash = contract.get("syntheticReviewSha256")
    generation_hash = contract.get("syntheticGenerationConfigSha256")
    declared = (synthetic_hash, review_hash, generation_hash)
    if all(value is None for value in declared):
        if contract.get("trainingInputSha256") != split_hash:
            raise ReleaseGateError("real-only training input must equal the frozen split")
        split = _json_document(split_payload, "frozen split manifest")
        return split, split_payload, None, None, None
    if not all(_is_digest(value) for value in declared):
        raise ReleaseGateError("synthetic provenance hashes must be all present or all null")
    if (
        inputs.training_input is None
        or inputs.synthetic_manifest is None
        or inputs.synthetic_review is None
    ):
        raise ReleaseGateError("reviewed synthetic training evidence is missing")
    try:
        from pontinho_cards.train import (
            TrainingConfigError,
            capture_synthetic_evidence,
            merge_approved_synthetic_training,
        )

        evidence = capture_synthetic_evidence(
            split_payload=split_payload,
            synthetic_manifest=inputs.synthetic_manifest,
            human_review=inputs.synthetic_review,
        )
        merged = merge_approved_synthetic_training(evidence)
    except TrainingConfigError as error:
        raise ReleaseGateError(f"synthetic training provenance is invalid: {error}") from error
    expected_payload = _canonical_json(merged)
    actual_payload = _regular_bytes(inputs.training_input, "merged training input")
    if actual_payload != expected_payload:
        raise ReleaseGateError("merged training input differs from reviewed reconstruction")
    actual_hash = _digest_bytes(actual_payload)
    if contract.get("trainingInputSha256") != actual_hash:
        raise ReleaseGateError("model contract merged training-input hash mismatch")
    if merged.get("syntheticManifestSha256") != synthetic_hash:
        raise ReleaseGateError("synthetic manifest hash differs from reviewed training input")
    if merged.get("syntheticReviewSha256") != review_hash:
        raise ReleaseGateError("synthetic review hash differs from reviewed training input")
    if merged.get("syntheticGenerationConfigSha256") != generation_hash:
        raise ReleaseGateError("synthetic generation hash differs from reviewed training input")
    return merged, actual_payload, str(synthetic_hash), str(review_hash), str(generation_hash)


def _validate_source_archives(
    manifest: SourceManifest, archive_root: Path | None
) -> dict[str, bytes]:
    if archive_root is None:
        raise ReleaseGateError("verified source archive directory is missing")
    state = _lstat_or_none(archive_root)
    if state is None or _is_link_or_reparse(state) or not stat.S_ISDIR(state.st_mode):
        raise ReleaseGateError("verified source archive directory is unsafe or missing")
    archives: dict[str, bytes] = {}
    for source in manifest.sources:
        allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
        if not source.source_id or any(character not in allowed for character in source.source_id):
            raise ReleaseGateError("source ID cannot name a canonical archive")
        archive = archive_root / source.archive_local_path
        payload = _regular_bytes(archive, "verified source archive")
        if _digest_bytes(payload) != source.archive_sha256:
            raise ReleaseGateError("verified source archive SHA-256 mismatch")
        archives[source.source_id] = payload
    return archives


def _archive_member(payload: bytes, member_path: Any) -> bytes:
    if not isinstance(member_path, str) or "\\" in member_path:
        raise ReleaseGateError("audited archive member path is invalid")
    member = PurePosixPath(member_path)
    if member.is_absolute() or any(part in {"", ".", ".."} for part in member.parts):
        raise ReleaseGateError("audited archive member path is unsafe")
    try:
        stream = io.BytesIO(payload)
        if zipfile.is_zipfile(stream):
            with zipfile.ZipFile(stream) as archive:
                info = archive.getinfo(member.as_posix())
                if info.is_dir():
                    raise ReleaseGateError("audited archive member is not a file")
                return archive.read(info)
        stream.seek(0)
        with tarfile.open(fileobj=stream, mode="r:*") as archive:
            info = archive.getmember(member.as_posix())
            if not info.isfile() or info.issym() or info.islnk():
                raise ReleaseGateError("audited archive member is not a regular file")
            extracted = archive.extractfile(info)
            if extracted is None:
                raise ReleaseGateError("audited archive member cannot be read")
            return extracted.read()
    except (KeyError, tarfile.TarError, zipfile.BadZipFile, OSError) as error:
        raise ReleaseGateError(f"audited archive member cannot be verified: {error}") from error


def _validate_source_audit(
    manifest: SourceManifest,
    manifest_hash: str,
    receipt_path: Path | None,
    *,
    required_schema: int | None,
    required_hash: str | None,
    split_hash: str,
    normalized_hash: str,
    records: Sequence[Mapping[str, Any]],
    archives: Mapping[str, bytes],
    image_payloads: Mapping[str, bytes],
) -> str:
    if required_schema is None or required_hash is None or receipt_path is None:
        raise ReleaseGateError("canonical human source-audit trust root is not configured")
    receipt, payload = _read_json(receipt_path, "source-audit trust-root receipt")
    if _digest_bytes(payload) != required_hash:
        raise ReleaseGateError("source-audit receipt differs from the configured trust root")
    expected_sources = [
        {
            "sourceId": source.source_id,
            "archiveSha256": source.archive_sha256,
            "licenseEvidenceUrl": source.license.evidence_url,
            "approvalNoteSha256": _digest_bytes(source.approval_note.encode()),
        }
        for source in manifest.sources
    ]
    expected_records = []
    for record in records:
        image_id = record.get("imageId")
        member_path = record.get("sourceArchiveMember")
        member_hash = record.get("sourceArchiveMemberSha256")
        source_id = record.get("sourceId")
        real_photo = record.get("isRealPhoto") is True
        if not isinstance(image_id, str):
            raise ReleaseGateError("audited record image ID is invalid")
        archive_hash: str | None = None
        if real_photo and not _is_digest(member_hash):
            raise ReleaseGateError("real image lacks audited archive-member provenance")
        if real_photo:
            archive = archives.get(str(source_id))
            if archive is None:
                raise ReleaseGateError("real image source archive is missing")
            archive_hash = _digest_bytes(archive)
            member_payload = _archive_member(archive, member_path)
            if _digest_bytes(member_payload) != member_hash:
                raise ReleaseGateError("audited archive member SHA-256 mismatch")
            if member_payload != image_payloads.get(image_id):
                raise ReleaseGateError("canonical image bytes differ from audited archive member")
        elif member_path is not None or member_hash is not None:
            raise ReleaseGateError("non-real record cannot claim real archive-member provenance")
        expected_records.append(
            {
                "imageId": image_id,
                "sourceId": source_id,
                "archiveSha256": archive_hash,
                "sourceArchiveMember": member_path,
                "sourceArchiveMemberSha256": member_hash,
                "canonicalImageSha256": record.get("imageSha256"),
                "width": record.get("width"),
                "height": record.get("height"),
                "isRealPhoto": real_photo,
            }
        )
    expected = {
        "schemaVersion": required_schema,
        "status": "HUMAN_APPROVED",
        "sourceManifestSha256": manifest_hash,
        "normalizedDatasetSha256": normalized_hash,
        "splitManifestSha256": split_hash,
        "sources": expected_sources,
        "records": expected_records,
    }
    if dict(receipt) != expected:
        raise ReleaseGateError("source-audit trust-root receipt semantics are invalid")
    return required_hash


def _validate_report(
    report: Mapping[str, Any],
    *,
    model_hash: str,
    contract_hash: str,
    holdout_hash: str,
    split_hash: str,
    holdout: Mapping[str, Any],
    support: Counter[str],
    decks: set[str],
    minimum_photos: int,
    minimum_decks: int,
    minimum_accuracy: float,
    score: float,
    iou: float,
) -> Mapping[str, Any]:
    required = {
        "schemaVersion",
        "status",
        "modelSha256",
        "modelContractSha256",
        "testManifestSha256",
        "holdoutIdentity",
        "splitManifestSha256",
        "testPartitionSha256",
        "sourceManifestSha256",
        "holdoutRecordCount",
        "holdoutUniquePhotoCount",
        "holdoutDeckGroupCount",
        "thresholdProvenance",
        "scoreThreshold",
        "iouThreshold",
        "metrics",
    }
    if not required.issubset(report):
        raise ReleaseGateError("validation report metadata is incomplete")
    if report.get("schemaVersion") != 1 or report.get("status") != "RELEASE_CANDIDATE":
        raise ReleaseGateError("validation report status is not a release candidate")
    bindings = {
        "modelSha256": model_hash,
        "modelContractSha256": contract_hash,
        "testManifestSha256": holdout_hash,
        "splitManifestSha256": split_hash,
        "testPartitionSha256": holdout.get("testPartitionSha256"),
        "thresholdProvenance": "validation-selected",
        "scoreThreshold": score,
        "iouThreshold": iou,
    }
    for field, expected in bindings.items():
        if report.get(field) != expected:
            name = "model hash" if field == "modelSha256" else field
            raise ReleaseGateError(f"validation report {name} mismatch")
    identity = f"v1.{split_hash}.{holdout.get('testPartitionSha256')}"
    if report.get("holdoutIdentity") != identity:
        raise ReleaseGateError("validation report holdout identity mismatch")
    records = holdout["test"]
    counts = {
        "holdoutRecordCount": len(records),
        "holdoutUniquePhotoCount": len({record["imageSha256"] for record in records}),
        "holdoutDeckGroupCount": len(decks),
    }
    for field, expected in counts.items():
        if report.get(field) != expected:
            if field == "holdoutUniquePhotoCount":
                raise ReleaseGateError(
                    f"release requires at least {minimum_photos} distinct real photos"
                )
            if field == "holdoutDeckGroupCount":
                raise ReleaseGateError(
                    f"release requires at least {minimum_decks} unseen deck groups"
                )
            raise ReleaseGateError(f"validation report {field} mismatch")
    metrics = report.get("metrics")
    if not isinstance(metrics, Mapping):
        raise ReleaseGateError("validation report metrics metadata is missing")
    accuracy = _unit(metrics.get("exactHandTotalAccuracy"), "exact hand-total accuracy")
    if accuracy < minimum_accuracy:
        raise ReleaseGateError(f"exact hand-total accuracy must be at least {minimum_accuracy:.2f}")
    _unit(metrics.get("exactCardMultisetAccuracy"), "exact card-multiset accuracy")
    abstention = _unit(metrics.get("abstentionRate"), "abstention rate")
    per_class = metrics.get("perClass")
    if not isinstance(per_class, Mapping) or set(per_class) != set(CARD_LABELS):
        raise ReleaseGateError("validation report lacks exact 52-class coverage")
    for label, expected_support in support.items():
        entry = per_class.get(label)
        if not isinstance(entry, Mapping) or entry.get("support") != expected_support:
            raise ReleaseGateError("validation report class support differs from holdout")
        _unit(entry.get("precision"), f"{label} precision")
        _unit(entry.get("recall"), f"{label} recall")
    slices = metrics.get("slices")
    if not isinstance(slices, Mapping) or set(slices) != {"deckGroup", "cardCount", "lighting"}:
        raise ReleaseGateError("validation report failure slices are not disclosed")
    if not all(isinstance(slices.get(name), Mapping) and slices[name] for name in slices):
        raise ReleaseGateError("validation report failure slices are empty")
    if set(slices["deckGroup"]) != decks:
        raise ReleaseGateError("validation report deck slices differ from holdout")
    scenes = metrics.get("scenes")
    if not isinstance(scenes, list) or len(scenes) != len(records):
        raise ReleaseGateError("validation report scene disclosure is incomplete")
    expected_ids = {record["imageId"] for record in records}
    scene_by_id: dict[str, Mapping[str, Any]] = {}
    statuses: list[str] = []
    for scene in scenes:
        if not isinstance(scene, Mapping):
            raise ReleaseGateError("validation report scene disclosure is invalid")
        scene_id = scene.get("sceneId")
        status = scene.get("status")
        if (
            not isinstance(scene_id, str)
            or scene_id in scene_by_id
            or status not in {"EVALUATED", "ABSTAINED"}
            or type(scene.get("exactCardMultiset")) is not bool
        ):
            raise ReleaseGateError("validation report scene disclosure is invalid")
        _unit(scene.get("exactHandTotalAccuracy"), "scene exact hand-total accuracy")
        scene_by_id[scene_id] = scene
        statuses.append(status)
    if set(scene_by_id) != expected_ids:
        raise ReleaseGateError("validation report scene disclosure is invalid")
    if sum(status == "ABSTAINED" for status in statuses) / len(records) != abstention:
        raise ReleaseGateError("validation report abstention disclosure is inconsistent")
    if metrics.get("sceneCount") != len(records):
        raise ReleaseGateError("validation report scene count differs from holdout")
    total_values = [
        float(scene_by_id[record["imageId"]]["exactHandTotalAccuracy"]) for record in records
    ]
    set_values = [bool(scene_by_id[record["imageId"]]["exactCardMultiset"]) for record in records]
    expected_accuracy = sum(total_values) / len(records)
    expected_multiset = sum(set_values) / len(records)
    if not math.isclose(accuracy, expected_accuracy, rel_tol=0.0, abs_tol=1e-12):
        raise ReleaseGateError("validation report exact hand-total metric is not scene-derived")
    if not math.isclose(
        float(metrics["exactCardMultisetAccuracy"]),
        expected_multiset,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise ReleaseGateError("validation report exact card-multiset metric is not scene-derived")
    dimensions = {
        "deckGroup": lambda record: str(record["deckGroupId"]),
        "cardCount": lambda record: str(len(record["boxes"])),
        "lighting": lambda record: str(record["lighting"]),
    }
    for dimension, key_for in dimensions.items():
        groups: dict[str, list[int]] = {}
        for index, record in enumerate(records):
            groups.setdefault(key_for(record), []).append(index)
        if set(slices[dimension]) != set(groups):
            raise ReleaseGateError(f"validation report {dimension} slices differ from holdout")
        for key, indices in groups.items():
            entry = slices[dimension].get(key)
            if not isinstance(entry, Mapping) or entry.get("sceneCount") != len(indices):
                raise ReleaseGateError("validation report slice scene count is inconsistent")
            slice_total = _unit(
                entry.get("exactHandTotalAccuracy"), "slice exact hand-total accuracy"
            )
            slice_set = _unit(
                entry.get("exactCardMultisetAccuracy"),
                "slice exact card-multiset accuracy",
            )
            if not math.isclose(
                slice_total,
                sum(total_values[index] for index in indices) / len(indices),
                rel_tol=0.0,
                abs_tol=1e-12,
            ) or not math.isclose(
                slice_set,
                sum(set_values[index] for index in indices) / len(indices),
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ReleaseGateError("validation report slice metrics are not scene-derived")
    return metrics


def _validate_receipt(
    receipt: Mapping[str, Any],
    *,
    model_hash: str,
    report_payload: bytes,
    report_markdown_payload: bytes,
    report: Mapping[str, Any],
) -> None:
    expected = {
        "schemaVersion": 1,
        "modelSha256": model_hash,
        "holdoutIdentity": report["holdoutIdentity"],
        "splitManifestSha256": report["splitManifestSha256"],
        "testPartitionSha256": report["testPartitionSha256"],
        "reportJsonSha256": _digest_bytes(report_payload),
        "reportMarkdownSha256": _digest_bytes(report_markdown_payload),
    }
    if dict(receipt) != expected:
        raise ReleaseGateError("Stage 7 one-use validation receipt is invalid or mismatched")


def _validate_inspected_model(model_payload: bytes, contract: Mapping[str, Any]) -> None:
    variant = contract.get("quantization", {}).get("variant")
    if variant not in {"float16", "int8"}:
        raise ReleaseGateError("model contract quantization cannot be inspected")
    with tempfile.TemporaryDirectory(prefix="pontinho-release-inspection-") as directory:
        snapshot = Path(directory) / _RELEASE_MODEL
        snapshot.write_bytes(model_payload)
        try:
            from pontinho_cards.export import ExportContractError, inspect_tflite

            inspected = inspect_tflite(snapshot, expected_quantization=str(variant))
        except (ExportContractError, ImportError, OSError, RuntimeError, ValueError) as error:
            raise ReleaseGateError(
                f"candidate is not an inspected TFLite artifact: {error}"
            ) from error
    inspection_keys = {
        "modelSha256",
        "input",
        "outputs",
        "customOps",
        "labels",
        "normalization",
        "quantization",
        "interpreterInvocation",
    }
    declared = {key: contract.get(key) for key in inspection_keys}
    if declared != inspected:
        raise ReleaseGateError("fresh inspected TFLite contract differs from Stage 6 evidence")


def _replay_holdout_metrics(
    *,
    model_payload: bytes,
    contract: Mapping[str, Any],
    holdout: Mapping[str, Any],
    image_payloads: Mapping[str, bytes],
    reported_metrics: Mapping[str, Any],
) -> None:
    """Independently rerun the snapshotted model and full frozen holdout."""

    records = holdout.get("test")
    if not isinstance(records, list):
        raise ReleaseGateError("holdout replay records are missing")
    try:
        from pontinho_cards.evaluate import EvaluationError, _infer_scenes, evaluate_scenes

        with tempfile.TemporaryDirectory(prefix="pontinho-release-holdout-replay-") as directory:
            root = Path(directory)
            model = root / _RELEASE_MODEL
            model.write_bytes(model_payload)
            snapshotted_records: list[Mapping[str, Any]] = []
            for record in records:
                if not isinstance(record, Mapping):
                    raise ReleaseGateError("holdout replay record is invalid")
                image_id = record.get("imageId")
                payload = image_payloads.get(str(image_id))
                if payload is None:
                    raise ReleaseGateError("holdout replay image snapshot is missing")
                target = _safe_record_path(root, record.get("path"))
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists() and target.read_bytes() != payload:
                    raise ReleaseGateError("holdout replay image paths alias different bytes")
                target.write_bytes(payload)
                snapshotted_records.append(dict(record))
            snapshot_manifest = root / "final-holdout.json"
            snapshot = {**holdout, "test": snapshotted_records}
            snapshot_manifest.write_bytes(_canonical_json(snapshot))
            scenes = _infer_scenes(
                model,
                contract,
                {"test": snapshotted_records},
                snapshot_manifest,
            )
        replayed = evaluate_scenes(
            scenes,
            confidence_threshold=float(contract["scoreThreshold"]),
            iou_threshold=float(contract["iouThreshold"]),
            seed=int(holdout.get("seed", 20261014)),
        )
    except (EvaluationError, KeyError, TypeError, ValueError, OSError) as error:
        raise ReleaseGateError(f"holdout replay failed: {error}") from error
    if _canonical_json(replayed) != _canonical_json(dict(reported_metrics)):
        raise ReleaseGateError("validation report metrics differ from independent holdout replay")


def _validate_all(inputs: ReleaseInputs) -> tuple[bytes, dict[str, Any]]:
    (
        minimum_photos,
        minimum_decks,
        minimum_accuracy,
        audit_schema,
        audit_hash,
    ) = _load_gate(_PINNED_GATE_PATH)
    model_payload = _regular_bytes(inputs.model, "candidate model")
    model_hash = _digest_bytes(model_payload)
    contract, contract_payload = _read_json(inputs.contract, "model contract")
    report, report_payload = _read_json(inputs.report, "validation report")
    split, split_payload = _read_json(inputs.split_manifest, "frozen split manifest")
    holdout, holdout_payload = _read_json(inputs.holdout_manifest, "frozen holdout manifest")
    source_payload = _regular_bytes(inputs.source_manifest, "source manifest")
    candidate, candidate_payload = _read_json(inputs.candidate_contract, "candidate contract")
    comparison, comparison_payload = _read_json(inputs.comparison, "export comparison")
    validation, validation_payload = _read_json(
        inputs.validation_manifest, "Stage 6 validation manifest"
    )
    training_document, training_payload, synthetic_hash, synthetic_review_hash, generation_hash = (
        _validate_training_input(inputs, split_payload=split_payload, contract=contract)
    )
    if (
        validation.get("schemaVersion") != 1
        or validation.get("kind") != "pontinho-validation-threshold-selection"
        or validation.get("seed") != split.get("seed")
        or validation.get("splitManifestSha256") != _digest_bytes(split_payload)
        or not isinstance(validation.get("records"), list)
        or validation.get("records") != split.get("partitions", {}).get("validation")
        or validation.get("validationPartitionSha256")
        != _digest_bytes(_canonical_json(validation.get("records")))
    ):
        raise ReleaseGateError("Stage 6 validation manifest metadata is invalid")
    model_version, exported_at, score, iou = _validate_contract(
        contract,
        model_hash=model_hash,
        split_hash=_digest_bytes(split_payload),
        training_input_hash=_digest_bytes(training_payload),
        synthetic_manifest_hash=synthetic_hash,
        synthetic_review_hash=synthetic_review_hash,
        synthetic_generation_hash=generation_hash,
        validation_hash=_digest_bytes(validation_payload),
        candidate_hash=_digest_bytes(candidate_payload),
        comparison_hash=_digest_bytes(comparison_payload),
        candidate=candidate,
        comparison=comparison,
    )
    records, support, decks = _validate_frozen_holdout(
        training_document,
        split_payload,
        holdout,
        minimum_photos=minimum_photos,
        minimum_decks=minimum_decks,
    )
    source_hash = _required_digest(report, "sourceManifestSha256", "validation report")
    manifest = _validate_sources(source_payload, source_hash, records)
    metrics = _validate_report(
        report,
        model_hash=model_hash,
        contract_hash=_digest_bytes(contract_payload),
        holdout_hash=_digest_bytes(holdout_payload),
        split_hash=_digest_bytes(split_payload),
        holdout=holdout,
        support=support,
        decks=decks,
        minimum_photos=minimum_photos,
        minimum_decks=minimum_decks,
        minimum_accuracy=minimum_accuracy,
        score=score,
        iou=iou,
    )
    markdown_payload = _regular_bytes(inputs.report.with_suffix(".md"), "validation Markdown")
    canonical_receipt = (
        inputs.holdout_manifest.parent
        / ".evaluation-receipts"
        / (f"{model_hash}.{report['holdoutIdentity']}.json")
    )
    if os.path.normcase(os.path.abspath(inputs.validation_receipt)) != os.path.normcase(
        os.path.abspath(canonical_receipt)
    ):
        raise ReleaseGateError("Stage 7 validation receipt is not at its canonical adjacent path")
    receipt, receipt_payload = _read_json(canonical_receipt, "canonical validation receipt")
    _validate_receipt(
        receipt,
        model_hash=model_hash,
        report_payload=report_payload,
        report_markdown_payload=markdown_payload,
        report=report,
    )
    image_payloads = _validate_dataset_evidence(
        split,
        root=inputs.split_manifest.parent,
        manifest=manifest,
    )
    archives = _validate_source_archives(manifest, inputs.source_archives)
    if inputs.normalized_records is None:
        raise ReleaseGateError("canonical normalized dataset is missing")
    normalized_payload = _regular_bytes(inputs.normalized_records, "normalized dataset")
    trusted_audit_hash = _validate_source_audit(
        manifest,
        source_hash,
        inputs.source_audit_receipt,
        required_schema=audit_schema,
        required_hash=audit_hash,
        split_hash=_digest_bytes(split_payload),
        normalized_hash=_digest_bytes(normalized_payload),
        records=[
            record
            for partition in ("train", "validation", "test")
            for record in split["partitions"][partition]
        ],
        archives=archives,
        image_payloads=image_payloads,
    )
    if inputs.threshold_selection is None:
        raise ReleaseGateError("threshold selection artifact is missing")
    threshold, threshold_payload = _read_json(
        inputs.threshold_selection, "threshold selection artifact"
    )
    threshold_hash = _digest_bytes(threshold_payload)
    if contract.get("thresholdSelectionSha256") != threshold_hash:
        raise ReleaseGateError("model contract threshold-selection hash mismatch")
    if Path(os.path.abspath(inputs.validation_manifest.parent)) != Path(
        os.path.abspath(inputs.split_manifest.parent)
    ):
        raise ReleaseGateError("validation manifest must share the canonical split data root")
    _recompute_threshold_sweep(
        threshold,
        model_payload=model_payload,
        contract=contract,
        validation=validation,
        validation_path=inputs.validation_manifest,
        seed=int(split["seed"]),
    )
    _validate_threshold_selection(
        threshold,
        model_hash=model_hash,
        checkpoint_hash=str(contract["checkpointSha256"]),
        config_hash=str(contract["configSha256"]),
        split_hash=_digest_bytes(split_payload),
        validation_hash=_digest_bytes(validation_payload),
        score_threshold=score,
        iou_threshold=iou,
    )
    _validate_inspected_model(model_payload, contract)
    _replay_holdout_metrics(
        model_payload=model_payload,
        contract=contract,
        holdout=holdout,
        image_payloads=image_payloads,
        reported_metrics=metrics,
    )
    release = {
        "schemaVersion": 1,
        "status": "APPROVED",
        "modelVersion": model_version,
        "modelSha256": model_hash,
        "modelSizeBytes": len(model_payload),
        "labels": list(CARD_LABELS),
        "inputWidth": contract["input"]["shape"][2],
        "inputHeight": contract["input"]["shape"][1],
        "quantization": contract["quantization"],
        "confidenceThreshold": score,
        "iouThreshold": iou,
        "sourceReportPath": inputs.report.as_posix(),
        "sourceReportSha256": _digest_bytes(report_payload),
        "modelContractSha256": _digest_bytes(contract_payload),
        "candidateContractSha256": _digest_bytes(candidate_payload),
        "comparisonSha256": _digest_bytes(comparison_payload),
        "validationManifestSha256": _digest_bytes(validation_payload),
        "thresholdSelectionSha256": threshold_hash,
        "validationReceiptSha256": _digest_bytes(receipt_payload),
        "sourceAuditReceiptSha256": trusted_audit_hash,
        "sourceManifestSha256": source_hash,
        "splitManifestSha256": report["splitManifestSha256"],
        "trainingInputSha256": _digest_bytes(training_payload),
        "syntheticManifestSha256": synthetic_hash,
        "syntheticReviewSha256": synthetic_review_hash,
        "syntheticGenerationConfigSha256": generation_hash,
        "testManifestSha256": report["testManifestSha256"],
        "exactTotalAccuracy": metrics["exactHandTotalAccuracy"],
        "exactCardMultisetAccuracy": metrics["exactCardMultisetAccuracy"],
        "abstentionRate": metrics["abstentionRate"],
        "heldOutPhotoCount": report["holdoutUniquePhotoCount"],
        "heldOutDeckCount": report["holdoutDeckGroupCount"],
        "exportedAt": exported_at,
    }
    return model_payload, release


def _write_stage(path: Path, payload: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())


def _publish(
    inputs: ReleaseInputs,
    model_payload: bytes,
    release: Mapping[str, Any],
    link_fn: Callable[[Path, Path], None],
) -> None:
    assets = inputs.assets_dir
    _assert_safe_asset_directory(assets)
    model_target = assets / _RELEASE_MODEL
    metadata_target = assets / _RELEASE_METADATA
    transaction = assets / _TRANSACTION
    release_payload = _canonical_json(release)
    expected = {
        "schemaVersion": 1,
        "modelSha256": _digest_bytes(model_payload),
        "releaseSha256": _digest_bytes(release_payload),
    }
    transaction_state = _lstat_or_none(transaction)
    if transaction_state is not None:
        if _is_link_or_reparse(transaction_state) or not stat.S_ISDIR(transaction_state.st_mode):
            raise ReleaseGateError("release transaction path is unsafe")
        marker, _ = _read_json(transaction / "transaction.json", "release transaction")
        if dict(marker) != expected:
            raise ReleaseGateError("another release transaction owns the Android assets")
    else:
        if _lstat_or_none(model_target) is not None or _lstat_or_none(metadata_target) is not None:
            if any(
                state is not None and _is_link_or_reparse(state)
                for state in (_lstat_or_none(model_target), _lstat_or_none(metadata_target))
            ):
                raise ReleaseGateError(
                    "Android release target cannot be a symbolic link/reparse point"
                )
            raise ReleaseGateError("Android release assets exist; refusing to replace them")
        assets.mkdir(parents=True, exist_ok=True)
        _assert_safe_asset_directory(assets)
        staging = Path(tempfile.mkdtemp(prefix=f"{_TRANSACTION}.stage.", dir=assets))
        try:
            _write_stage(staging / _RELEASE_MODEL, model_payload)
            _write_stage(staging / _RELEASE_METADATA, release_payload)
            _write_stage(staging / "transaction.json", _canonical_json(expected))
            os.replace(staging, transaction)
        except BaseException as error:
            shutil.rmtree(staging, ignore_errors=True)
            raise ReleaseGateError(f"cannot reserve release transaction: {error}") from error
    staged_model = transaction / _RELEASE_MODEL
    staged_metadata = transaction / _RELEASE_METADATA
    for staged, target, digest in (
        (staged_model, model_target, expected["modelSha256"]),
        (staged_metadata, metadata_target, expected["releaseSha256"]),
    ):
        target_state = _lstat_or_none(target)
        if target_state is not None:
            if _is_link_or_reparse(target_state):
                raise ReleaseGateError(
                    "Android release target cannot be a symbolic link/reparse point"
                )
            if (
                _digest_bytes(_regular_bytes(target, "partially promoted asset")) != digest
                or not staged.exists()
                or not _same_file_identity(staged, target)
            ):
                raise ReleaseGateError(
                    "partial release transaction target is not transaction-owned"
                )
        elif (
            not staged.exists()
            or _digest_bytes(_regular_bytes(staged, "staged release asset")) != digest
        ):
            raise ReleaseGateError("staged release transaction hash mismatch")
    try:
        if _lstat_or_none(model_target) is None:
            link_fn(staged_model, model_target)
        if _lstat_or_none(metadata_target) is None:
            link_fn(staged_metadata, metadata_target)
        if not _same_file_identity(staged_model, model_target) or not _same_file_identity(
            staged_metadata, metadata_target
        ):
            raise FileExistsError("no-clobber publication target already exists")
        if _digest_bytes(_regular_bytes(model_target, "promoted model")) != expected["modelSha256"]:
            raise OSError("post-copy model hash mismatch")
        if (
            _digest_bytes(_regular_bytes(metadata_target, "promoted release JSON"))
            != expected["releaseSha256"]
        ):
            raise OSError("post-copy release JSON hash mismatch")
    except Exception as error:
        for target in (model_target, metadata_target):
            try:
                staged = staged_model if target == model_target else staged_metadata
                if (
                    _lstat_or_none(target) is not None
                    and staged.exists()
                    and _same_file_identity(staged, target)
                ):
                    target.unlink()
            except (OSError, ReleaseGateError):
                pass
        shutil.rmtree(transaction, ignore_errors=True)
        raise ReleaseGateError(
            f"no-clobber promotion transaction failed and was rolled back: {error}"
        ) from error
    shutil.rmtree(transaction)


def promote_release(
    inputs: ReleaseInputs,
) -> dict[str, Any]:
    """Validate immutable evidence, then publish a no-clobber logical transaction."""

    model_payload, release = _validate_all(inputs)
    _publish(inputs, model_payload, release, os.link)
    return release


def _default_inputs(args: argparse.Namespace) -> ReleaseInputs:
    root = Path(__file__).resolve().parents[4]
    card_root = root / "ml" / "card-recognition"
    report, _ = _read_json(args.report, "validation report")
    identity = report.get("holdoutIdentity", "missing")
    model_hash = report.get("modelSha256", "missing")
    receipt_default = (
        card_root / "datasets" / ".evaluation-receipts" / f"{model_hash}.{identity}.json"
    )
    return ReleaseInputs(
        model=args.model,
        report=args.report,
        contract=args.contract,
        source_manifest=args.sources or card_root / "datasets" / "sources.json",
        split_manifest=args.split or card_root / "datasets" / "splits.json",
        holdout_manifest=args.holdout or card_root / "datasets" / "final-holdout.json",
        validation_receipt=args.receipt or receipt_default,
        candidate_contract=args.candidate_contract
        or args.contract.parent / "candidate-contract.json",
        comparison=args.comparison or args.contract.parent / "export-comparison.json",
        validation_manifest=args.validation_manifest
        or card_root / "datasets" / "validation-manifest.json",
        assets_dir=args.assets_dir or root / "app" / "src" / "main" / "assets",
        threshold_selection=args.threshold_selection
        or args.contract.parent / "threshold-selection.json",
        source_audit_receipt=args.source_audit_receipt
        or card_root / "datasets" / "source-audit-receipt.json",
        source_archives=args.source_archives or card_root / "datasets" / "source-archives",
        normalized_records=card_root / "datasets" / "normalized" / "records.jsonl",
        training_input=args.training_input,
        synthetic_manifest=args.synthetic_manifest,
        synthetic_review=args.synthetic_review,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--sources", type=Path)
    parser.add_argument("--split", type=Path)
    parser.add_argument("--holdout", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--candidate-contract", type=Path)
    parser.add_argument("--comparison", type=Path)
    parser.add_argument("--validation-manifest", type=Path)
    parser.add_argument("--threshold-selection", type=Path)
    parser.add_argument("--source-audit-receipt", type=Path)
    parser.add_argument("--source-archives", type=Path)
    parser.add_argument("--training-input", type=Path)
    parser.add_argument("--synthetic-manifest", type=Path)
    parser.add_argument("--synthetic-review", type=Path)
    parser.add_argument("--assets-dir", type=Path)
    args = parser.parse_args(argv)
    try:
        promote_release(_default_inputs(args))
    except ReleaseGateError as error:
        print(f"RELEASE BLOCKED: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
