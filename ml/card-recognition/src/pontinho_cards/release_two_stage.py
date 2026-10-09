"""Fail-closed promotion of one immutable two-model directory."""

from __future__ import annotations

import math
import statistics
import tempfile
from dataclasses import dataclass
from pathlib import Path

from pontinho_cards.evaluate import Detection
from pontinho_cards.evaluate_two_stage import (
    ThresholdSelection,
    TwoStageEvaluation,
    _snapshots,
    canonical,
    digest,
    holdout_photo_identity,
    measure_two_stage,
    read_document,
    validate_holdout,
    validate_selection,
)
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.mediapipe_contract import NATIVE_BOX_POLICY
from pontinho_cards.model_resize import RESIZE_POLICY, require_resize_policy
from pontinho_cards.native_nms import NMS_POLICY, require_nms_policy
from pontinho_cards.secure_io import safe_name, secure_read_bytes, secure_write_bytes
from pontinho_cards.train_classifier import ClassifierCandidate
from pontinho_cards.train_detector import DetectorCandidate, _publish_candidate


@dataclass(frozen=True)
class ReleaseReceipt:
    bundle_path: Path
    release_sha256: str


@dataclass(frozen=True)
class ReleaseInputs:
    detector: DetectorCandidate
    classifier: ClassifierCandidate
    selection: ThresholdSelection
    evaluation: TwoStageEvaluation
    validation_manifest: Path
    holdout_manifest: Path
    benchmark: Path
    source_manifest: Path
    source_audit_receipt: Path
    source_archives: Path
    normalized_records: Path


def validate_quality_gates(metrics):
    for field, minimum in (("sceneCount", 200), ("uniquePhotoCount", 200), ("unseenDeckCount", 10)):
        if type(metrics.get(field)) is not int or metrics[field] < minimum:
            raise ValueError(f"BLOCKED_HOLDOUT_EVIDENCE: {field}")

    def check(row):
        for field, minimum, maximum in (
            ("detectorRecall", 0.97, 1),
            ("detectorFalsePositiveShare", 0, 0.01),
            ("exactCardMultisetAccuracy", 0.90, 1),
            ("exactHandTotalAccuracy", 0.95, 1),
        ):
            value = row.get(field)
            if (
                type(value) not in (float, int)
                or not math.isfinite(value)
                or not minimum <= value <= maximum
            ):
                raise ValueError(f"BLOCKED_TWO_STAGE_QUALITY: {field}")

    check(metrics)
    for count in range(1, 11):
        row = metrics.get("byCardCount", {}).get(str(count))
        if (
            not isinstance(row, dict)
            or type(row.get("sceneCount")) is not int
            or row["sceneCount"] < 1
        ):
            raise ValueError(f"BLOCKED_HOLDOUT_EVIDENCE: missing card-count bucket {count}")
        check(row)


def publish_bundle(destination, payloads):
    """Never replace individual assets or an existing directory, even when empty."""
    destination = Path(destination)
    safe_name(destination.name, field="release directory")
    expected = {"card_detector.tflite", "card_classifier.tflite", "card_recognition.release.json"}
    if set(payloads) != expected:
        raise ValueError("complete two-model bundle required")
    with tempfile.TemporaryDirectory(prefix="pontinho-release-") as directory:
        staging = Path(directory)
        for name, payload in payloads.items():
            secure_write_bytes(staging, name, payload)
        return _publish_candidate(staging, destination.parent, destination.name, list(payloads))


def _validate_source_evidence(inputs, split, split_hash):
    """Retain the existing configuration-pinned human audit and archive trust root."""
    from pontinho_cards.release import (
        _load_gate,
        _validate_canonical_image,
        _validate_source_archives,
        _validate_source_audit,
        _validate_sources,
    )

    project = Path(__file__).resolve().parents[2]
    _, _, _, schema, trusted_hash = _load_gate(project / "config/release-gate.yaml")
    payload = secure_read_bytes(inputs.source_manifest)
    manifest = _validate_sources(payload, digest(payload), split["partitions"]["test"])
    if manifest.audit_status != "READY_FOR_TWO_STAGE_EXPERIMENT":
        raise ValueError("BLOCKED_SOURCE_EVIDENCE: incomplete two-stage source inventory")
    sources = {s.source_id: s for s in manifest.sources}
    records = []
    for partition in ("train", "validation", "test"):
        for record in split["partitions"][partition]:
            source = sources.get(record.get("sourceId"))
            required = (
                {"FINAL_SCENE_HOLDOUT"}
                if partition == "test"
                else {"DETECTOR_SCENE", "CLASSIFIER_CROP"}
            )
            if (
                source is None
                or source.deck_group_id != record.get("deckGroupId")
                or not required.intersection(source.task_roles)
                or record.get("isRealPhoto") is not True
            ):
                raise ValueError("BLOCKED_SOURCE_EVIDENCE: role/deck/real-photo binding")
            records.append(record)
    images = {
        r["imageId"]: _validate_canonical_image(inputs.holdout_manifest.parent, r) for r in records
    }
    archives = _validate_source_archives(manifest, inputs.source_archives)
    normalized = secure_read_bytes(inputs.normalized_records)
    audit_hash = _validate_source_audit(
        manifest,
        digest(payload),
        inputs.source_audit_receipt,
        required_schema=schema,
        required_hash=trusted_hash,
        split_hash=split_hash,
        normalized_hash=digest(normalized),
        records=records,
        archives=archives,
        image_payloads=images,
    )
    return {
        "sourceManifestSha256": digest(payload),
        "sourceAuditReceiptSha256": audit_hash,
        "normalizedDatasetSha256": digest(normalized),
    }


def _benchmark(path, report, report_hash):
    value, payload = read_document(path)
    if (
        value.get("schemaVersion") != 1
        or value.get("reportSha256") != report_hash
        or value.get("bindings") != report["bindings"]
        or value.get("selected") != report["selected"]
        or value.get("physicalDevice") is not True
        or value.get("representativeMidrange") is not True
        or not isinstance(value.get("device"), str)
        or not value["device"].strip()
        or not isinstance(value.get("androidVersion"), str)
        or not value["androidVersion"].strip()
    ):
        raise ValueError("BLOCKED_DEVICE_EVIDENCE: device/model/report binding")
    for field in ("coldStartMs", "peakMemoryBytes"):
        number = value.get(field)
        if type(number) not in (int, float) or not math.isfinite(number) or number <= 0:
            raise ValueError(f"BLOCKED_DEVICE_EVIDENCE: {field}")
    all_samples, by_count = [], {}
    for count in range(1, 11):
        samples = value.get("warmEndToEndMs", {}).get(str(count))
        if (
            not isinstance(samples, list)
            or len(samples) < 3
            or any(type(n) not in (int, float) or not math.isfinite(n) or n <= 0 for n in samples)
        ):
            raise ValueError("BLOCKED_DEVICE_EVIDENCE: warm end-to-end samples")
        median = statistics.median(samples)
        if median > 2000:
            raise ValueError("BLOCKED_DEVICE_LATENCY: card-count median exceeds 2000 ms")
        by_count[str(count)] = {
            "medianMs": median,
            "p95Ms": sorted(samples)[math.ceil(0.95 * len(samples)) - 1],
        }
        all_samples.extend(samples)
    return {
        "sha256": digest(payload),
        "device": value["device"],
        "androidVersion": value["androidVersion"],
        "coldStartMs": value["coldStartMs"],
        "peakMemoryBytes": value["peakMemoryBytes"],
        "medianEndToEndMs": statistics.median(all_samples),
        "p95EndToEndMs": sorted(all_samples)[math.ceil(0.95 * len(all_samples)) - 1],
        "byCardCount": by_count,
    }


def _recompute_report(report, holdout):
    rows = report.get("rawScenes")
    if not isinstance(rows, list) or len(rows) != len(holdout["test"]):
        raise ValueError("report scene inventory mismatch")
    scenes = []
    for row, record in zip(rows, holdout["test"]):
        if (
            row.get("imageId") != record["imageId"]
            or row.get("deckGroupId") != record["deckGroupId"]
        ):
            raise ValueError("report scene/deck mismatch")
        scene = {
            **row,
            **{
                key: tuple(
                    Detection(d["label"], tuple(d["box"]), d["confidence"]) for d in row[key]
                )
                for key in ("truth", "predictions", "detector")
            },
        }
        truth = tuple(
            Detection(b["label"], tuple(b[k] for k in ("xmin", "ymin", "xmax", "ymax")))
            for b in record["boxes"]
        )
        if scene["truth"] != truth:
            raise ValueError("report ground truth mismatch")
        scenes.append(scene)
    measured = measure_two_stage(scenes)
    measured.update(
        uniquePhotoCount=len({r["imageSha256"] for r in holdout["test"]}),
        unseenDeckCount=len({r["deckGroupId"] for r in holdout["test"]}),
    )
    if measured != report.get("metrics"):
        raise ValueError("report metrics do not match recorded one-use inference")
    return measured


def promote_two_stage_release(inputs, destination):
    """Check all evidence before publishing; never rerun final holdout inference."""
    report, report_payload = read_document(inputs.evaluation.path)
    report_hash = digest(report_payload)
    if report_hash != inputs.evaluation.sha256:
        raise ValueError("report hash mismatch")
    require_resize_policy(report.get("resizePolicy"))
    require_nms_policy(report.get("nmsPolicy"))
    with tempfile.TemporaryDirectory(prefix="pontinho-release-check-") as directory:
        snapshots = _snapshots(inputs.detector, inputs.classifier, Path(directory))
        selection, validation = validate_selection(
            inputs.selection, inputs.validation_manifest, snapshots
        )
        holdout, holdout_hash, split = validate_holdout(
            inputs.holdout_manifest, validation, snapshots
        )
        identity = holdout_photo_identity(holdout)
        claim = {
            "boxPolicy": NATIVE_BOX_POLICY,
            "resizePolicy": RESIZE_POLICY,
            "nmsPolicy": NMS_POLICY,
            "holdoutPartitionSha256": holdout["testPartitionSha256"],
            "holdoutIdentity": identity,
            "holdoutManifestSha256": holdout_hash,
            "selectionSha256": inputs.selection.sha256,
            "bindings": selection["bindings"],
        }
        if (
            report.get("schemaVersion") != 1
            or report.get("kind") != "TWO_STAGE_FINAL_EVALUATION"
            or report.get("partition") != "test"
            or report.get("selected") != selection["selected"]
            or any(report.get(k) != v for k, v in claim.items())
        ):
            raise ValueError("report candidate/holdout/selection bindings mismatch")
        receipt_path = (
            inputs.holdout_manifest.parent / ".two-stage-evaluation-receipts" / f"{identity}.json"
        )
        if inputs.evaluation.receipt_path.absolute() != receipt_path.absolute():
            raise ValueError("receipt must be at its canonical holdout path")
        receipt, receipt_payload = read_document(receipt_path)
        saved_claim, _ = read_document(receipt_path.with_suffix(".claim"))
        if saved_claim != claim or receipt != {
            **claim,
            "status": "COMPLETE",
            "reportSha256": report_hash,
        }:
            raise ValueError("report receipt hash/claim mismatch")
        metrics = _recompute_report(report, holdout)
        validate_quality_gates(metrics)
        benchmark = _benchmark(inputs.benchmark, report, report_hash)
        evidence = _validate_source_evidence(inputs, split, holdout["splitManifestSha256"])
        selected = selection["selected"]
        models, contracts = {}, {}
        for kind in ("detector", "classifier"):
            variant = selected[f"{kind}Variant"]
            snapshot = snapshots[kind]
            declared = snapshot["contract"]["variants"][variant]
            model_payload = secure_read_bytes(snapshot["paths"][variant])
            model_hash = digest(model_payload)
            if model_hash != declared["modelSha256"]:
                raise ValueError("release model snapshot hash mismatch")
            model_size = len(model_payload)
            if "modelSizeBytes" in declared and (
                type(declared["modelSizeBytes"]) is not int
                or declared["modelSizeBytes"] != model_size
            ):
                raise ValueError("release model snapshot size mismatch")
            models[f"card_{kind}.tflite"] = model_payload
            contracts[kind] = {
                **declared,
                "modelSha256": model_hash,
                "modelSizeBytes": model_size,
                "filename": f"card_{kind}.tflite",
                "variant": variant,
            }
        release = {
            "schemaVersion": 1,
            "kind": "TWO_STAGE_CARD_RECOGNITION_RELEASE",
            "releaseApproved": True,
            "labels": list(CARD_LABELS),
            "maximumCardCount": 10,
            "confidenceCombination": "min(detector,calibratedClassifier)",
            "spatialOrder": "top-left-bottom-right",
            "thresholds": selected["thresholds"],
            **contracts,
            **claim,
            **evidence,
            "sourceReportSha256": report_hash,
            "evaluationReceiptSha256": digest(receipt_payload),
            "benchmark": benchmark,
            "metrics": {k: v for k, v in metrics.items() if k != "scenes"},
        }
        payload = canonical(release)
        path = publish_bundle(destination, {**models, "card_recognition.release.json": payload})
    return ReleaseReceipt(path, digest(payload))
