"""Validation-only joint selection and one-use frozen two-stage evaluation.

Detector boxes entering recognize_scene use normalized x/y bounds (the Stage 3
adapter converts native y/x outputs). Crops always use original image pixels.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import sys
import tempfile
from collections import Counter
from dataclasses import asdict, dataclass
from itertools import product
from pathlib import Path

import numpy as np
from PIL import Image

from pontinho_cards.evaluate import (
    JOKER_CONTEXTS,
    Detection,
    EvaluationError,
    _iou,
    _match,
    _validate_detection,
    _validate_holdout_record,
    _validate_partition_disjointness,
    pontinho_total,
)
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.secure_io import (
    _open_absolute_directory,
    safe_name,
    safe_relative_posix,
    secure_read_bytes,
    secure_write_bytes,
    secure_write_bytes_immutable,
)
from pontinho_cards.train_classifier import _infer_classifier, apply_temperature
from pontinho_cards.train_detector import _infer_detector
from pontinho_cards.two_stage_data import ClassifierCrop, DetectorRecord


@dataclass(frozen=True)
class Thresholds:
    detector_score: float
    combined_score: float
    iou: float
    crop_margin: float

    def __post_init__(self):
        for name in ("detector_score", "combined_score", "iou", "crop_margin"):
            value = getattr(self, name)
            minimum = 0 if name == "crop_margin" else 1e-12
            maximum = 0.25 if name == "crop_margin" else 1
            if (
                type(value) not in (int, float)
                or not math.isfinite(value)
                or not minimum <= value <= maximum
            ):
                raise EvaluationError(f"invalid threshold {name}")


@dataclass(frozen=True)
class ThresholdSelection:
    path: Path
    sha256: str


@dataclass(frozen=True)
class TwoStageEvaluation:
    path: Path
    sha256: str
    receipt_path: Path


def canonical(value):
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n").encode()


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def read_document(path):
    payload = secure_read_bytes(path)
    value = json.loads(payload)
    if not isinstance(value, dict):
        raise EvaluationError("evidence must be a JSON object")
    return value, payload


def recognize_scene(image, raw_boxes, classify, thresholds, temperature):
    """Spatial NMS, original-resolution padded crops and calibrated confidence."""
    flags, candidates = set(), []
    for score, box in raw_boxes:
        if not math.isfinite(score) or not 0 <= score <= 1 or len(box) != 4:
            raise EvaluationError("invalid detector output")
        if not all(math.isfinite(v) for v in box):
            raise EvaluationError("non-finite detector box")
        if score < thresholds.detector_score:
            if score >= thresholds.detector_score * 0.8:
                flags.add("low_confidence")
            continue
        x0, y0, x1, y1 = box
        if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
            flags.add("incomplete_card")
            continue
        candidates.append(
            Detection(
                "AC",
                (x0 * image.width, y0 * image.height, x1 * image.width, y1 * image.height),
                score,
            )
        )
    kept = []
    for candidate in sorted(candidates, key=lambda d: (-d.confidence, *d.box)):
        if all(_iou(candidate, other) <= thresholds.iou for other in kept):
            kept.append(candidate)
    kept.sort(key=lambda d: (d.box[1], d.box[0], d.box[3], d.box[2]))
    if len(kept) > 10:
        return {"detector": tuple(kept), "predictions": (), "flags": ("split_capture",)}
    predictions = []
    for detection in kept:
        x0, y0, x1, y1 = detection.box
        mx, my = (x1 - x0) * thresholds.crop_margin, (y1 - y0) * thresholds.crop_margin
        bounds = (
            max(0, math.floor(x0 - mx)),
            max(0, math.floor(y0 - my)),
            min(image.width, math.ceil(x1 + mx)),
            min(image.height, math.ceil(y1 + my)),
        )
        scores = apply_temperature(np.asarray(classify(image.crop(bounds)))[None, :], temperature)[
            0
        ]
        index = int(scores.argmax())
        confidence = min(detection.confidence, float(scores[index]))
        predictions.append(Detection(CARD_LABELS[index], detection.box, confidence))
        if confidence < thresholds.combined_score:
            flags.add("low_confidence")
    if not predictions:
        flags.add("no_cards")
    return {
        "detector": tuple(kept),
        "predictions": tuple(predictions),
        "flags": tuple(sorted(flags)),
    }


def measure_two_stage(scenes):
    """Count physical observations; total is exact in all 26 coringa contexts."""
    scenes = list(scenes)
    if not scenes:
        raise EvaluationError("evaluation requires scenes")
    reports = []
    for scene in scenes:
        truth, predicted, detected = (tuple(scene[k]) for k in ("truth", "predictions", "detector"))
        for item in (*truth, *predicted, *detected):
            _validate_detection(item)
        flags = set(scene["flags"])
        if not predicted:
            flags.add("no_cards")
        if len(predicted) > 10 or len(detected) > 10:
            flags.add("split_capture")
        actual_labels, labels = [d.label for d in truth], [d.label for d in predicted]
        totals = {
            f"{r}_{c}": {
                "truth": pontinho_total(actual_labels, r, c),
                "predicted": pontinho_total(labels, r, c) if not flags else None,
            }
            for r, c in JOKER_CONTEXTS
        }
        _, fp, fn, _ = _match(truth, predicted, 0.5)
        _, dfp, dfn, _ = _match(
            [Detection("AC", d.box) for d in truth],
            [Detection("AC", d.box, d.confidence) for d in detected],
            0.5,
        )
        reports.append(
            {
                "imageId": scene["imageId"],
                "deckGroupId": scene["deckGroupId"],
                "cardCount": len(truth),
                "flags": sorted(flags),
                "totals": totals,
                "exactList": not flags and Counter(actual_labels) == Counter(labels),
                "exactTotal": not flags
                and all(v["truth"] == v["predicted"] for v in totals.values()),
                "tp": len(truth) - fn,
                "fp": fp,
                "fn": fn,
                "detectorTp": len(truth) - dfn,
                "detectorFp": dfp,
                "detectorFn": dfn,
            }
        )

    def summarize(rows):
        supported = [r for r in rows if 1 <= r["cardCount"] <= 10]
        dtp, dfp, dfn, tp, fp, fn = (
            sum(row[k] for row in rows)
            for k in ("detectorTp", "detectorFp", "detectorFn", "tp", "fp", "fn")
        )
        return {
            "sceneCount": len(rows),
            "detectorRecall": dtp / (dtp + dfn) if dtp + dfn else 0,
            "detectorFalsePositiveShare": dfp / (dtp + dfp) if dtp + dfp else 0,
            "detectorPrecision": dtp / (dtp + dfp) if dtp + dfp else 0,
            "detectorTp": dtp,
            "detectorFp": dfp,
            "detectorFn": dfn,
            "perCardRecall": tp / (tp + fn) if tp + fn else 0,
            "perCardPrecision": tp / (tp + fp) if tp + fp else 0,
            "falsePositives": fp,
            "falseNegatives": fn,
            "supportedSceneCount": len(supported),
            "exactCardMultisetAccuracy": sum(r["exactList"] for r in supported) / len(supported)
            if supported
            else 0,
            "exactHandTotalAccuracy": sum(r["exactTotal"] for r in supported) / len(supported)
            if supported
            else 0,
            "abstentionRate": sum(bool(r["flags"]) for r in rows) / len(rows),
        }

    return {
        **summarize(reports),
        "scenes": reports,
        "byDeck": {
            g: summarize([r for r in reports if r["deckGroupId"] == g])
            for g in sorted({r["deckGroupId"] for r in reports})
        },
        "byCardCount": {
            str(g): summarize([r for r in reports if r["cardCount"] == g])
            for g in sorted({r["cardCount"] for r in reports})
        },
    }


def _inspect(path, variant, kind):
    from pontinho_cards.export import inspect_classifier_tflite, inspect_tflite

    if kind == "detector":
        return inspect_tflite(path, expected_quantization=variant, expected_labels=("card",))
    return inspect_classifier_tflite(path, expected_quantization=variant)


def snapshot_candidate(candidate, kind, staging):
    """Bind actual models, inspection, calibration and original training evidence."""
    contract, payload = read_document(candidate.contract_path)
    expected = "ONE_CLASS_DETECTOR_CANDIDATE" if kind == "detector" else "CARD_CLASSIFIER_CANDIDATE"
    labels = ["card"] if kind == "detector" else list(CARD_LABELS)
    if (
        contract.get("schemaVersion") != 1
        or contract.get("kind") != expected
        or contract.get("labels") != labels
    ):
        raise EvaluationError("invalid candidate kind or labels")
    root = candidate.contract_path.parent
    snapshots = {}
    for field, filename in (
        ("trainingInputSha256", "training-inputs.json"),
        ("selectedCheckpointSha256", "selected.weights.h5"),
        ("configSha256", "resolved-config.yaml"),
        ("requirementsLockSha256", "requirements.lock"),
    ):
        content = secure_read_bytes(root / filename, root=root)
        if digest(content) != contract.get(field):
            raise EvaluationError(f"candidate hash mismatch: {field}")
        snapshots[filename] = content
    metrics_payload = secure_read_bytes(candidate.validation_metrics_path, root=root)
    if digest(metrics_payload) != contract.get("validationMetricsSha256"):
        raise EvaluationError("validation metrics hash mismatch")
    metrics = json.loads(metrics_payload)
    if metrics.get("partition") != "validation" or metrics.get("status") == "SMOKE_ONLY":
        raise EvaluationError("candidate requires non-smoke validation evidence")
    if set(contract.get("variants", {})) != {"float16", "int8"}:
        raise EvaluationError("candidate must provide both measured variants")
    paths = {}
    for variant, declared in contract["variants"].items():
        filename = safe_name(declared["filename"], field="candidate model filename")
        content = secure_read_bytes(root / filename, root=root)
        if digest(content) != declared.get("modelSha256"):
            raise EvaluationError("candidate model hash mismatch")
        secure_write_bytes(staging, filename, content)
        paths[variant] = staging / filename
        inspected = _inspect(paths[variant], variant, kind)
        if any(declared.get(k) != v for k, v in inspected.items()):
            raise EvaluationError("candidate inspected contract mismatch")
        measured = metrics.get("variants", {}).get(variant, {})
        if measured.get("modelSha256") != declared["modelSha256"]:
            raise EvaluationError("variant validation model hash mismatch")
        if kind == "classifier":
            calibration = declared.get("calibration", {})
            if (
                calibration != measured.get("calibration")
                or calibration.get("partition") != "validation"
                or calibration.get("method") != "temperature-scaling"
                or calibration.get("scoreTransform")
                != "softmax(log(max(raw_probability,1e-12))/temperature)"
            ):
                raise EvaluationError("variant calibration is not validation-bound")
            apply_temperature(np.full((1, 52), 1 / 52), calibration.get("temperature"))
    inputs = json.loads(snapshots["training-inputs.json"])
    if set(inputs) != {"train", "validation"} or not all(inputs.values()):
        raise EvaluationError("candidate train/validation provenance missing")
    for field in ("source_image_sha256", "deck_group_id"):
        train = {r.get(field) for r in inputs["train"]}
        validation = {r.get(field) for r in inputs["validation"]}
        if None in train | validation or train & validation:
            raise EvaluationError("candidate train/validation leakage")
    return {
        "contract": contract,
        "contractSha256": digest(payload),
        "paths": paths,
        "inputs": inputs,
    }


def _bind_validation(records, snapshots):
    for kind, snapshot in snapshots.items():
        role = "DETECTOR_SCENE" if kind == "detector" else "CLASSIFIER_CROP"
        expected = {
            (r["imageSha256"], r["deckGroupId"])
            for r in records
            if role in r.get("taskRoles", [role]) and (kind == "detector" or r["boxes"])
        }
        observed = {
            (r["source_image_sha256"], r["deck_group_id"]) for r in snapshot["inputs"]["validation"]
        }
        if observed != expected:
            raise EvaluationError("validation scenes differ from candidate validation partition")
        if {r["deckGroupId"] for r in records} & {
            r["deck_group_id"] for r in snapshot["inputs"]["train"]
        }:
            raise EvaluationError("validation deck appeared in candidate training")


def snapshot_scenes(records, root, staging):
    if not isinstance(records, list) or not records:
        raise EvaluationError("scene records are missing")
    seen, hashes, result = set(), set(), []
    for record in records:
        if record.get("boxes") == []:
            # Negative validation scenes have the same metadata obligations.
            if (
                not isinstance(record.get("imageId"), str)
                or not record["imageId"]
                or record["imageId"] in seen
                or any(
                    type(record.get(k)) is not int or record[k] <= 0 for k in ("width", "height")
                )
                or any(
                    not isinstance(record.get(k), str) or not record[k]
                    for k in ("deckGroupId", "splitGroupId", "lighting")
                )
                or not isinstance(record.get("imageSha256"), str)
                or len(record["imageSha256"]) != 64
                or any(c not in "0123456789abcdef" for c in record["imageSha256"])
            ):
                raise EvaluationError("invalid negative scene metadata")
            seen.add(record["imageId"])
        else:
            _validate_holdout_record(record, seen)
        if record.get("isRealPhoto") is not True:
            raise EvaluationError("evaluation requires real photos")
        if record["imageSha256"] in hashes:
            raise EvaluationError("evaluation photos must be unique")
        hashes.add(record["imageSha256"])
        member = safe_relative_posix(record["path"], field="scene path")
        content = secure_read_bytes(root / member, root=root)
        if digest(content) != record["imageSha256"]:
            raise EvaluationError("scene image hash mismatch")
        with Image.open(io.BytesIO(content)) as image:
            image.load()
            if image.size != (record["width"], record["height"]):
                raise EvaluationError("scene dimensions mismatch")
        filename = f"{len(result)}.image"
        secure_write_bytes(staging, filename, content)
        result.append({**record, "snapshotPath": staging / filename})
    return result


def _run_joint(snapshots, records, detector_variant, classifier_variant, thresholds):
    detector, classifier = snapshots["detector"], snapshots["classifier"]
    dcontract = detector["contract"]["variants"][detector_variant]
    ccontract = classifier["contract"]["variants"][classifier_variant]
    projected = [
        DetectorRecord(
            r["imageId"],
            r["snapshotPath"],
            r["width"],
            r["height"],
            r["deckGroupId"],
            r["splitGroupId"],
            r["imageSha256"],
            (),
        )
        for r in records
    ]
    raw = _infer_detector(detector["paths"][detector_variant], dcontract, projected)
    scenes = []
    with tempfile.TemporaryDirectory(prefix="pontinho-joint-crops-") as directory:
        crop_path = Path(directory) / "crop.png"
        for record in records:

            def classify(crop):
                buffer = io.BytesIO()
                crop.save(buffer, format="PNG")
                secure_write_bytes(crop_path.parent, crop_path.name, buffer.getvalue())
                item = ClassifierCrop(
                    "AC",
                    (0, 0, crop.width, crop.height),
                    record["deckGroupId"],
                    record["imageSha256"],
                    crop_path,
                )
                return _infer_classifier(classifier["paths"][classifier_variant], [item])[0]

            with Image.open(io.BytesIO(secure_read_bytes(record["snapshotPath"]))) as source:
                result = recognize_scene(
                    source.convert("RGB"),
                    raw[record["imageId"]],
                    classify,
                    thresholds,
                    ccontract["calibration"]["temperature"],
                )
            scenes.append(
                {
                    "imageId": record["imageId"],
                    "deckGroupId": record["deckGroupId"],
                    "truth": tuple(
                        Detection(b["label"], tuple(b[k] for k in ("xmin", "ymin", "xmax", "ymax")))
                        for b in record["boxes"]
                    ),
                    **result,
                }
            )
    return scenes


def _snapshots(detector, classifier, staging):
    return {
        "detector": snapshot_candidate(detector, "detector", staging / "detector"),
        "classifier": snapshot_candidate(classifier, "classifier", staging / "classifier"),
    }


def _bindings(snapshots):
    return {
        kind: {
            "contractSha256": s["contractSha256"],
            "modelHashes": {v: d["modelSha256"] for v, d in s["contract"]["variants"].items()},
        }
        for kind, s in snapshots.items()
    }


def selection_key(metrics):
    def quality(row):
        for field, lower, upper in (
            ("detectorRecall", 0.97, 1),
            ("detectorFalsePositiveShare", 0, 0.01),
            ("exactCardMultisetAccuracy", 0.9, 1),
            ("exactHandTotalAccuracy", 0.95, 1),
        ):
            value = row.get(field)
            if (
                type(value) not in (int, float)
                or not math.isfinite(value)
                or not lower <= value <= upper
            ):
                return False
        return True

    feasible = quality(metrics) and all(
        quality(metrics.get("byCardCount", {}).get(str(i), {})) for i in range(1, 11)
    )
    return (
        feasible,
        metrics["exactCardMultisetAccuracy"],
        metrics["exactHandTotalAccuracy"],
        metrics["detectorRecall"],
        -metrics["detectorFalsePositiveShare"],
    )


def select_two_stage_thresholds(detector, classifier, validation_manifest, thresholds, output):
    manifest, payload = read_document(validation_manifest)
    if manifest.get("schemaVersion") != 1 or manifest.get("partition") != "validation":
        raise EvaluationError("selection requires a validation-only manifest")
    thresholds = tuple(thresholds)
    if not thresholds or not all(isinstance(t, Thresholds) for t in thresholds):
        raise EvaluationError("explicit threshold candidates required")
    with tempfile.TemporaryDirectory(prefix="pontinho-joint-selection-") as directory:
        staging = Path(directory)
        snapshots = _snapshots(detector, classifier, staging)
        records = manifest.get("records")
        _bind_validation(records, snapshots)
        records = snapshot_scenes(records, validation_manifest.parent, staging / "scenes")
        sweep = []
        for dv, cv, threshold in product(("float16", "int8"), ("float16", "int8"), thresholds):
            metrics = measure_two_stage(_run_joint(snapshots, records, dv, cv, threshold))
            sweep.append(
                {
                    "detectorVariant": dv,
                    "classifierVariant": cv,
                    "thresholds": asdict(threshold),
                    "metrics": metrics,
                }
            )
        # Stable iteration order breaks equal-quality ties without reading holdout.
        best = max(range(len(sweep)), key=lambda i: (*selection_key(sweep[i]["metrics"]), -i))
        document = {
            "schemaVersion": 1,
            "kind": "TWO_STAGE_THRESHOLD_SELECTION",
            "partition": "validation",
            "status": "VALIDATION_SELECTED_NOT_RELEASE_APPROVED"
            if selection_key(sweep[best]["metrics"])[0]
            else "BLOCKED_TWO_STAGE_QUALITY",
            "validationManifestSha256": digest(payload),
            "bindings": _bindings(snapshots),
            "selected": {k: v for k, v in sweep[best].items() if k != "metrics"},
            "sweep": sweep,
        }
        content = canonical(document)
        secure_write_bytes_immutable(output.parent, output.name, content)
    return ThresholdSelection(output, digest(content))


def claim_holdout(root, partition_hash, metadata):
    """Consume the partition before inference, regardless of candidate or outcome."""
    if len(partition_hash) != 64 or any(c not in "0123456789abcdef" for c in partition_hash):
        raise EvaluationError("invalid holdout identity")
    parent, descriptors = _open_absolute_directory(
        root / ".two-stage-evaluation-receipts", create=True
    )
    try:
        try:
            fd = os.open(
                f"{partition_hash}.claim",
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent,
            )
        except FileExistsError as error:
            raise EvaluationError(
                "frozen holdout already consumed; retuning/retry prohibited"
            ) from error
        with os.fdopen(fd, "wb") as handle:
            handle.write(canonical(metadata))
            handle.flush()
            os.fsync(handle.fileno())
        os.fsync(parent)
    finally:
        for fd in reversed(descriptors):
            os.close(fd)
    return root / ".two-stage-evaluation-receipts" / f"{partition_hash}.json"


def validate_selection(selection, validation_manifest, snapshots):
    document, payload = read_document(selection.path)
    if digest(payload) != selection.sha256:
        raise EvaluationError("selection hash mismatch")
    validation, validation_payload = read_document(validation_manifest)
    if (
        document.get("kind") != "TWO_STAGE_THRESHOLD_SELECTION"
        or document.get("partition") != "validation"
        or validation.get("partition") != "validation"
        or document.get("validationManifestSha256") != digest(validation_payload)
        or document.get("bindings") != _bindings(snapshots)
    ):
        raise EvaluationError("selection candidate/validation bindings mismatch")
    _bind_validation(validation["records"], snapshots)
    selected = document.get("selected")
    if not isinstance(selected, dict) or not any(
        selected == {k: v for k, v in row.items() if k != "metrics"}
        for row in document.get("sweep", [])
    ):
        raise EvaluationError("selection is absent from validation sweep")
    Thresholds(**selected["thresholds"])
    best = max(document["sweep"], key=lambda row: selection_key(row["metrics"]))
    if selected != {k: v for k, v in best.items() if k != "metrics"}:
        raise EvaluationError("selection differs from validation-selected optimum")
    if not selection_key(best["metrics"])[0]:
        raise EvaluationError("BLOCKED_TWO_STAGE_QUALITY: validation envelope failed")
    return document, validation


def validate_holdout(holdout_manifest, validation, snapshots):
    holdout, payload = read_document(holdout_manifest)
    if holdout.get("schemaVersion") != 1 or holdout.get("kind") != "pontinho-frozen-holdout":
        raise EvaluationError("frozen holdout manifest required")
    name = safe_name(holdout.get("splitManifest"), field="split manifest")
    split, split_payload = read_document(holdout_manifest.parent / name)
    if digest(split_payload) != holdout.get("splitManifestSha256"):
        raise EvaluationError("frozen split hash mismatch")
    partitions = split["partitions"]
    _validate_partition_disjointness(partitions)
    if partitions["validation"] != validation["records"]:
        raise EvaluationError("frozen split validation differs from threshold selection")
    for snapshot in snapshots.values():
        for partition in ("train", "validation"):
            expected = {(r["imageSha256"], r["deckGroupId"]) for r in partitions[partition]}
            observed = {
                (r["source_image_sha256"], r["deck_group_id"])
                for r in snapshot["inputs"][partition]
            }
            if not observed or not observed <= expected:
                raise EvaluationError("candidate training differs from frozen split")
    records = holdout.get("test")
    if not isinstance(records, list) or records != partitions["test"]:
        raise EvaluationError("frozen holdout differs from split.test")
    if digest(canonical(records)) != holdout.get("testPartitionSha256"):
        raise EvaluationError("frozen holdout partition hash mismatch")
    if len(records) < 200 or len({r["imageSha256"] for r in records}) < 200:
        raise EvaluationError("holdout requires 200 unique photos")
    if len({r["deckGroupId"] for r in records}) < 10:
        raise EvaluationError("holdout requires ten unseen decks")
    counts = {len(r["boxes"]) for r in records}
    if counts != set(range(1, 11)):
        raise EvaluationError("holdout requires every supported card-count bucket 1 through 10")
    if {b["label"] for r in records for b in r["boxes"]} != set(CARD_LABELS):
        raise EvaluationError("holdout requires 52-class coverage")
    return holdout, digest(payload), split


def evaluate_two_stage(
    detector, classifier, selection, validation_manifest, holdout_manifest, output
):
    """Run only the frozen validation-selected pair once; failures consume the holdout."""
    if output.exists() or output.is_symlink():
        raise EvaluationError("evaluation output already exists")
    with tempfile.TemporaryDirectory(prefix="pontinho-joint-holdout-") as directory:
        staging = Path(directory)
        snapshots = _snapshots(detector, classifier, staging)
        selected, validation = validate_selection(selection, validation_manifest, snapshots)
        holdout, holdout_hash, _ = validate_holdout(holdout_manifest, validation, snapshots)
        # Snapshot and validate all bytes before consuming; inference never reopens caller files.
        records = snapshot_scenes(holdout["test"], holdout_manifest.parent, staging / "scenes")
        bindings = _bindings(snapshots)
        identity = holdout_photo_identity(holdout)
        claim = {
            "holdoutPartitionSha256": holdout["testPartitionSha256"],
            "holdoutIdentity": identity,
            "holdoutManifestSha256": holdout_hash,
            "selectionSha256": selection.sha256,
            "bindings": bindings,
        }
        receipt_path = claim_holdout(holdout_manifest.parent, identity, claim)
        choice = selected["selected"]
        scenes = _run_joint(
            snapshots,
            records,
            choice["detectorVariant"],
            choice["classifierVariant"],
            Thresholds(**choice["thresholds"]),
        )
        metrics = measure_two_stage(scenes)
        metrics.update(
            uniquePhotoCount=len({r["imageSha256"] for r in records}),
            unseenDeckCount=len({r["deckGroupId"] for r in records}),
        )
        document = {
            "schemaVersion": 1,
            "kind": "TWO_STAGE_FINAL_EVALUATION",
            "partition": "test",
            **claim,
            "metrics": metrics,
            "selected": choice,
            "rawScenes": [
                {
                    **s,
                    **{k: [asdict(d) for d in s[k]] for k in ("truth", "predictions", "detector")},
                }
                for s in scenes
            ],
        }
        payload = canonical(document)
        secure_write_bytes_immutable(output.parent, output.name, payload)
        secure_write_bytes_immutable(
            receipt_path.parent,
            receipt_path.name,
            canonical({**claim, "status": "COMPLETE", "reportSha256": digest(payload)}),
        )
    return TwoStageEvaluation(output, digest(payload), receipt_path)


def holdout_photo_identity(holdout):
    return digest(canonical(sorted(r["imageSha256"] for r in holdout["test"])))


def candidate_paths(root, kind):
    from pontinho_cards.train_classifier import ClassifierCandidate
    from pontinho_cards.train_detector import DetectorCandidate

    cls = DetectorCandidate if kind == "detector" else ClassifierCandidate
    return cls(
        root / f"card_{kind}.float16.tflite",
        root / "candidate-contract.json",
        root / "validation-metrics.json",
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("select", "evaluate"))
    parser.add_argument(
        "--detector", type=Path, required=True, help="reviewed detector candidate directory"
    )
    parser.add_argument(
        "--classifier", type=Path, required=True, help="reviewed classifier candidate directory"
    )
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--grid", type=Path, help="JSON object with a thresholds list")
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--holdout", type=Path)
    args = parser.parse_args(argv)
    try:
        detector, classifier = (
            candidate_paths(args.detector, "detector"),
            candidate_paths(args.classifier, "classifier"),
        )
        if args.operation == "select":
            grid = (
                read_document(args.grid)[0]["thresholds"]
                if args.grid
                else [
                    {"detector_score": d, "combined_score": c, "iou": i, "crop_margin": m}
                    for d, c, i, m in product(
                        (0.4, 0.5, 0.6), (0.5, 0.7, 0.9), (0.3, 0.5), (0.0, 0.1)
                    )
                ]
            )
            select_two_stage_thresholds(
                detector, classifier, args.validation, [Thresholds(**r) for r in grid], args.output
            )
        else:
            if args.selection is None or args.holdout is None:
                raise EvaluationError("evaluate requires --selection and --holdout")
            selection = ThresholdSelection(
                args.selection, digest(secure_read_bytes(args.selection))
            )
            evaluate_two_stage(
                detector, classifier, selection, args.validation, args.holdout, args.output
            )
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"BLOCKED_TWO_STAGE_INPUTS: {error}", file=sys.stderr)
        return 2
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
