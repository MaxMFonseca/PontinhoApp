"""Fail-closed release gate for the Android card-detector asset pair."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import stat
import sys
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.manifest import ManifestError, SourceManifest, load_source_manifest

_SHA256 = "0123456789abcdef"
_RELEASE_MODEL = "card_detector.tflite"
_RELEASE_METADATA = "card_detector.release.json"
_TRANSACTION = ".card_detector.release.transaction"


class ReleaseGateError(ValueError):
    """Raised before evidence can authorize an unsafe release."""


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
    release_config: Path
    assets_dir: Path


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
        result = path.lstat()
    except OSError as error:
        raise ReleaseGateError(f"cannot read required {name}: {path}: {error}") from error
    if stat.S_ISLNK(result.st_mode) or not stat.S_ISREG(result.st_mode):
        raise ReleaseGateError(f"required {name} must be a regular file")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode):
            os.close(descriptor)
            raise ReleaseGateError(f"required {name} must remain a regular file")
        with os.fdopen(descriptor, "rb") as handle:
            return handle.read()
    except OSError as error:
        raise ReleaseGateError(f"cannot read required {name}: {path}: {error}") from error


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


def _load_gate(path: Path) -> tuple[int, int, float]:
    payload = _regular_bytes(path, "release gate configuration")
    try:
        document = yaml.safe_load(payload)
    except yaml.YAMLError as error:
        raise ReleaseGateError(f"invalid release gate configuration: {error}") from error
    if not isinstance(document, Mapping) or set(document) != {
        "schema_version",
        "minimum_real_photo_count",
        "minimum_unseen_deck_groups",
        "minimum_exact_total_accuracy",
    }:
        raise ReleaseGateError("release gate configuration schema is invalid")
    if document.get("schema_version") != 1:
        raise ReleaseGateError("release gate configuration schema version is unsupported")
    photos = _positive_int(document.get("minimum_real_photo_count"), "photo minimum")
    decks = _positive_int(document.get("minimum_unseen_deck_groups"), "deck minimum")
    accuracy = _unit(document.get("minimum_exact_total_accuracy"), "accuracy minimum")
    if (photos, decks, accuracy) != (200, 10, 0.95):
        raise ReleaseGateError("release thresholds may not weaken the approved 200/10/0.95 gate")
    return photos, decks, accuracy


def _validate_contract(
    contract: Mapping[str, Any],
    *,
    model_hash: str,
    split_hash: str,
    holdout_hash: str,
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
    if contract.get("inputManifestSha256") != split_hash:
        raise ReleaseGateError("model contract is not bound to the frozen split")
    if contract.get("validationManifestSha256") != holdout_hash:
        raise ReleaseGateError("model contract is not bound to the frozen holdout")
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
        "inputManifestSha256": split_hash,
        "requirementsLockSha256": contract.get("requirementsLockSha256"),
        "scoreThreshold": contract.get("scoreThreshold"),
        "iouThreshold": contract.get("iouThreshold"),
        "thresholdProvenance": "validation-selected",
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
        "validationManifestSha256": holdout_hash,
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
    if not isinstance(model_version, str) or not model_version.strip():
        raise ReleaseGateError("model contract metadata modelVersion is missing")
    if not isinstance(exported_at, str) or not exported_at.endswith("Z"):
        raise ReleaseGateError("model contract metadata exportedAt is missing")
    try:
        datetime.fromisoformat(exported_at[:-1] + "+00:00")
    except ValueError as error:
        raise ReleaseGateError("model contract metadata exportedAt is invalid") from error
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
    path: Path,
    expected_hash: str,
    records: Sequence[Mapping[str, Any]],
) -> SourceManifest:
    payload = _regular_bytes(path, "source manifest")
    if _digest_bytes(payload) != expected_hash:
        raise ReleaseGateError("source manifest hash differs from validation report")
    try:
        manifest = load_source_manifest(path)
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
    scene_ids = {scene.get("sceneId") for scene in scenes if isinstance(scene, Mapping)}
    statuses = [scene.get("status") for scene in scenes if isinstance(scene, Mapping)]
    if scene_ids != expected_ids or any(
        status not in {"EVALUATED", "ABSTAINED"} for status in statuses
    ):
        raise ReleaseGateError("validation report scene disclosure is invalid")
    if sum(status == "ABSTAINED" for status in statuses) / len(records) != abstention:
        raise ReleaseGateError("validation report abstention disclosure is inconsistent")
    if metrics.get("sceneCount") != len(records):
        raise ReleaseGateError("validation report scene count differs from holdout")
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


def _validate_all(inputs: ReleaseInputs) -> tuple[bytes, dict[str, Any]]:
    minimum_photos, minimum_decks, minimum_accuracy = _load_gate(inputs.release_config)
    model_payload = _regular_bytes(inputs.model, "candidate model")
    model_hash = _digest_bytes(model_payload)
    contract, contract_payload = _read_json(inputs.contract, "model contract")
    report, report_payload = _read_json(inputs.report, "validation report")
    split, split_payload = _read_json(inputs.split_manifest, "frozen split manifest")
    holdout, holdout_payload = _read_json(inputs.holdout_manifest, "frozen holdout manifest")
    receipt, _ = _read_json(inputs.validation_receipt, "validation receipt")
    candidate, candidate_payload = _read_json(inputs.candidate_contract, "candidate contract")
    comparison, comparison_payload = _read_json(inputs.comparison, "export comparison")
    model_version, exported_at, score, iou = _validate_contract(
        contract,
        model_hash=model_hash,
        split_hash=_digest_bytes(split_payload),
        holdout_hash=_digest_bytes(holdout_payload),
        candidate_hash=_digest_bytes(candidate_payload),
        comparison_hash=_digest_bytes(comparison_payload),
        candidate=candidate,
        comparison=comparison,
    )
    records, support, decks = _validate_frozen_holdout(
        split,
        split_payload,
        holdout,
        minimum_photos=minimum_photos,
        minimum_decks=minimum_decks,
    )
    source_hash = _required_digest(report, "sourceManifestSha256", "validation report")
    _validate_sources(inputs.source_manifest, source_hash, records)
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
    _validate_receipt(
        receipt,
        model_hash=model_hash,
        report_payload=report_payload,
        report_markdown_payload=markdown_payload,
        report=report,
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
        "validationReceiptSha256": _digest_bytes(
            _regular_bytes(inputs.validation_receipt, "validation receipt")
        ),
        "sourceManifestSha256": source_hash,
        "splitManifestSha256": report["splitManifestSha256"],
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
    replace_fn: Callable[[Path, Path], None],
) -> None:
    assets = inputs.assets_dir
    model_target = assets / _RELEASE_MODEL
    metadata_target = assets / _RELEASE_METADATA
    transaction = assets / _TRANSACTION
    release_payload = _canonical_json(release)
    expected = {
        "schemaVersion": 1,
        "modelSha256": _digest_bytes(model_payload),
        "releaseSha256": _digest_bytes(release_payload),
    }
    if transaction.exists():
        if stat.S_ISLNK(transaction.lstat().st_mode) or not transaction.is_dir():
            raise ReleaseGateError("release transaction path is unsafe")
        marker, _ = _read_json(transaction / "transaction.json", "release transaction")
        if dict(marker) != expected:
            raise ReleaseGateError("another release transaction owns the Android assets")
    else:
        if model_target.exists() or metadata_target.exists():
            raise ReleaseGateError("Android release assets exist; refusing to replace them")
        owns_transaction = False
        try:
            assets.mkdir(parents=True, exist_ok=True)
            transaction.mkdir()
            owns_transaction = True
            _write_stage(transaction / _RELEASE_MODEL, model_payload)
            _write_stage(transaction / _RELEASE_METADATA, release_payload)
            _write_stage(transaction / "transaction.json", _canonical_json(expected))
        except BaseException as error:
            if owns_transaction:
                shutil.rmtree(transaction, ignore_errors=True)
            raise ReleaseGateError(f"cannot stage release transaction: {error}") from error
    staged_model = transaction / _RELEASE_MODEL
    staged_metadata = transaction / _RELEASE_METADATA
    for staged, target, digest in (
        (staged_model, model_target, expected["modelSha256"]),
        (staged_metadata, metadata_target, expected["releaseSha256"]),
    ):
        if target.exists():
            if _digest_bytes(_regular_bytes(target, "partially promoted asset")) != digest:
                raise ReleaseGateError("partial release transaction target hash mismatch")
        elif (
            not staged.exists()
            or _digest_bytes(_regular_bytes(staged, "staged release asset")) != digest
        ):
            raise ReleaseGateError("staged release transaction hash mismatch")
    try:
        if not model_target.exists():
            replace_fn(staged_model, model_target)
        if not metadata_target.exists():
            replace_fn(staged_metadata, metadata_target)
        if _digest_bytes(_regular_bytes(model_target, "promoted model")) != expected["modelSha256"]:
            raise OSError("post-copy model hash mismatch")
        if (
            _digest_bytes(_regular_bytes(metadata_target, "promoted release JSON"))
            != expected["releaseSha256"]
        ):
            raise OSError("post-copy release JSON hash mismatch")
    except BaseException as error:
        for target, expected_hash in (
            (model_target, expected["modelSha256"]),
            (metadata_target, expected["releaseSha256"]),
        ):
            try:
                if (
                    target.exists()
                    and _digest_bytes(_regular_bytes(target, "rollback asset")) == expected_hash
                ):
                    target.unlink()
            except (OSError, ReleaseGateError):
                pass
        shutil.rmtree(transaction, ignore_errors=True)
        raise ReleaseGateError(
            f"promotion transaction failed and was rolled back: {error}"
        ) from error
    shutil.rmtree(transaction)


def promote_release(
    inputs: ReleaseInputs,
    *,
    replace_fn: Callable[[Path, Path], None] = os.replace,
) -> dict[str, Any]:
    """Validate immutable evidence, then publish a no-clobber logical transaction."""

    model_payload, release = _validate_all(inputs)
    _publish(inputs, model_payload, release, replace_fn)
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
        release_config=args.release_config or card_root / "config" / "release-gate.yaml",
        assets_dir=args.assets_dir or root / "app" / "src" / "main" / "assets",
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
    parser.add_argument("--release-config", type=Path)
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
