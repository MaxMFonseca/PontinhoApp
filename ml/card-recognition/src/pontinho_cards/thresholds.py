"""Produce deterministic validation-only detector threshold evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from pontinho_cards.evaluate import _infer_scenes, evaluate_scenes
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.secure_io import secure_read_bytes


def _json(path: Path) -> tuple[dict[str, Any], bytes]:
    payload = secure_read_bytes(path)
    value = json.loads(payload)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain one JSON object")
    return value, payload


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _write_exclusive(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())


def produce_validation_manifest(split_manifest: Path, output: Path) -> dict[str, Any]:
    """Freeze the exact validation partition used for threshold selection."""

    split, split_payload = _json(split_manifest)
    partitions = split.get("partitions")
    validation = partitions.get("validation") if isinstance(partitions, Mapping) else None
    if split.get("schemaVersion") != 1 or not isinstance(validation, list):
        raise ValueError("split manifest validation partition is invalid")
    seed = split.get("seed")
    if type(seed) is not int:
        raise ValueError("split manifest seed is invalid")
    artifact = {
        "schemaVersion": 1,
        "kind": "pontinho-validation-threshold-selection",
        "seed": seed,
        "splitManifestSha256": hashlib.sha256(split_payload).hexdigest(),
        "validationPartitionSha256": hashlib.sha256(_canonical_json(validation)).hexdigest(),
        "records": validation,
    }
    _write_exclusive(output, _canonical_json(artifact))
    return artifact


def produce_threshold_sweep(
    model: Path,
    candidate_contract: Path,
    validation_manifest: Path,
    output: Path,
    *,
    score_thresholds: Sequence[float],
    iou_thresholds: Sequence[float],
) -> dict[str, Any]:
    """Infer each validation image once and evaluate a deterministic threshold grid."""

    if not score_thresholds or not iou_thresholds:
        raise ValueError("threshold grids cannot be empty")
    candidate, _ = _json(candidate_contract)
    validation, validation_payload = _json(validation_manifest)
    model_payload = secure_read_bytes(model)
    model_hash = hashlib.sha256(model_payload).hexdigest()
    variants = candidate.get("variants")
    if not isinstance(variants, dict):
        raise ValueError("candidate contract variants are missing")
    inspections = [
        value
        for value in variants.values()
        if isinstance(value, Mapping) and value.get("modelSha256") == model_hash
    ]
    if len(inspections) != 1:
        raise ValueError("model differs from the candidate contract")
    inspection = dict(inspections[0])
    inspection.pop("filename", None)
    contract = {
        **inspection,
        "checkpointSha256": candidate["checkpointSha256"],
        "configSha256": candidate["configSha256"],
    }
    if (
        validation.get("schemaVersion") != 1
        or validation.get("kind") != "pontinho-validation-threshold-selection"
        or type(validation.get("seed")) is not int
        or not isinstance(validation.get("records"), list)
    ):
        raise ValueError("validation manifest schema is invalid")
    if validation.get("splitManifestSha256") != candidate.get("splitManifestSha256"):
        raise ValueError("validation manifest split binding is invalid")
    if (
        validation.get("validationPartitionSha256")
        != hashlib.sha256(_canonical_json(validation.get("records"))).hexdigest()
    ):
        raise ValueError("validation manifest partition binding is invalid")
    scenes = _infer_scenes(
        model,
        contract,
        {"test": validation.get("records")},
        validation_manifest,
    )
    rows = []
    for score in score_thresholds:
        for iou in iou_thresholds:
            metrics = evaluate_scenes(
                scenes,
                confidence_threshold=float(score),
                iou_threshold=float(iou),
                seed=int(validation.get("seed", 20261014)),
                bootstrap_samples=20,
            )
            rows.append(
                {
                    "scoreThreshold": float(score),
                    "iouThreshold": float(iou),
                    "exactHandTotalAccuracy": metrics["exactHandTotalAccuracy"],
                    "exactCardMultisetAccuracy": metrics["exactCardMultisetAccuracy"],
                    "abstentionRate": metrics["abstentionRate"],
                }
            )
    selected = max(
        rows,
        key=lambda row: (
            row["exactHandTotalAccuracy"],
            row["exactCardMultisetAccuracy"],
            -row["abstentionRate"],
            row["scoreThreshold"],
            row["iouThreshold"],
        ),
    )
    artifact = {
        "schemaVersion": 1,
        "modelSha256": model_hash,
        "checkpointSha256": contract["checkpointSha256"],
        "configSha256": contract["configSha256"],
        "validationManifestSha256": hashlib.sha256(validation_payload).hexdigest(),
        "splitManifestSha256": validation["splitManifestSha256"],
        "labels": list(CARD_LABELS),
        "preprocessing": {
            "input": contract["input"],
            "normalization": contract["normalization"],
        },
        "grid": {
            "scoreThresholds": [float(value) for value in score_thresholds],
            "iouThresholds": [float(value) for value in iou_thresholds],
        },
        "scenes": [
            {
                "sceneId": scene["sceneId"],
                "predictions": [
                    {
                        "label": item.label,
                        "box": list(item.box),
                        "confidence": item.confidence,
                    }
                    for item in scene["predictions"]
                ],
            }
            for scene in scenes
        ],
        "rows": rows,
        "selected": selected,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(artifact, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return artifact


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(argv) if argv is not None else sys.argv[1:]
    if arguments and arguments[0] == "validation-manifest":
        parser = argparse.ArgumentParser(description=produce_validation_manifest.__doc__)
        parser.add_argument("--split", required=True, type=Path)
        parser.add_argument("--output", required=True, type=Path)
        args = parser.parse_args(arguments[1:])
        produce_validation_manifest(args.split, args.output)
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--validation-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scores", required=True, nargs="+", type=float)
    parser.add_argument("--ious", required=True, nargs="+", type=float)
    args = parser.parse_args(arguments)
    produce_threshold_sweep(
        args.model,
        args.contract,
        args.validation_manifest,
        args.output,
        score_thresholds=args.scores,
        iou_thresholds=args.ious,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
