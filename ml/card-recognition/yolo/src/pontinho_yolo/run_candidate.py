"""Canonical Stage 10 validation, export, and one-shot holdout command."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from collections import Counter
from pathlib import Path

import yaml

from pontinho_yolo.candidate import (
    load_truth_partition,
    predict_checkpoint,
    predict_tflite,
    sha256_file,
)
from pontinho_yolo.dataset import DatasetValidationError, audit_dataset
from pontinho_yolo.evaluate import (
    EvaluationReport,
    PredictionPartition,
    ThresholdSelection,
    TruthPartition,
    evaluate_predictions,
    select_thresholds,
)
from pontinho_yolo.export import CandidateBundle, UltralyticsExporter, export_candidate
from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.postprocess import IncompleteDetections, TooManyDetections, decode_yolo
from pontinho_yolo.source import SourceReceipt, SourceVerificationError

RUN_ID = "playing-cards-v4-yolov8n-20261016"


def run() -> CandidateBundle:
    """Execute selection and export; open test only inside the canonical holdout claim."""
    yolo_root = Path(__file__).resolve().parents[2]
    repository_root = yolo_root.parents[2]
    reports_root = yolo_root / "reports"
    canonical_dataset_root = (
        yolo_root / "datasets" / "extracted" / "playing-cards-ow27d-v4"
    )
    checkpoint = yolo_root / "runs" / RUN_ID / "weights" / "best.pt"
    training_receipt_path = reports_root / "training-receipt.json"
    training = _read_json(training_receipt_path)
    if training.get("run_id") != RUN_ID:
        raise RuntimeError("training receipt run ID differs from the canonical Stage 10 run")
    current_bindings = {
        "dependency_lock_sha256": yolo_root / "uv.lock",
        "dataset_audit_sha256": reports_root / "dataset-audit.json",
        "source_receipt_file_sha256": (
            yolo_root / "datasets" / "playing-cards-ow27d-v4.source.json"
        ),
    }
    for field, path in current_bindings.items():
        if _required_hash(training, field) != sha256_file(path):
            raise RuntimeError(f"training input changed after receipt creation: {field}")
    checkpoint_hash = sha256_file(checkpoint)
    if training.get("selected_checkpoint_sha256") != checkpoint_hash:
        raise RuntimeError("training receipt does not bind the finished best checkpoint")
    dataset_sha256 = _required_hash(training, "dataset_tree_sha256")
    dataset_receipt_sha256 = _required_hash(training, "source_receipt_file_sha256")
    dataset_audit_sha256 = _required_hash(training, "dataset_audit_sha256")
    dataset_root, export_dataset_config = _execution_dataset(
        yolo_root,
        canonical_dataset_root,
        reports_root / "dataset-audit.json",
        yolo_root / "datasets" / "playing-cards-ow27d-v4.source.json",
    )

    validation_truth, validation_images = load_truth_partition(
        dataset_root, "validation", dataset_sha256
    )
    validation_predictions = predict_checkpoint(
        checkpoint,
        validation_images,
        partition="validation",
        dataset_sha256=dataset_sha256,
    )
    selection = select_thresholds(validation_predictions, validation_truth, checkpoint=checkpoint)
    _write_json(
        reports_root / "threshold-selection.json",
        {
            "schemaVersion": 1,
            **selection.to_dict(),
            "validationReport": selection.validation_report.to_dict(),
        },
    )

    candidate_validation: dict[str, EvaluationReport] = {}
    candidate_fixtures: dict[str, dict[str, object]] = {}

    def evaluate_validation(model: Path, selected: ThresholdSelection) -> EvaluationReport:
        predictions = predict_tflite(
            model,
            validation_images,
            partition="validation",
            dataset_sha256=dataset_sha256,
        )
        report = evaluate_predictions(
            predictions,
            validation_truth,
            confidence=selected.confidence,
            iou=selected.iou,
            candidate_sha256=sha256_file(model),
        )
        candidate_validation[model.stem] = report
        candidate_fixtures[model.stem] = _select_android_fixture(
            predictions,
            validation_truth,
            validation_images,
            selected,
            sha256_file(model),
        )
        return report

    def evaluate_test(model: Path, selected: ThresholdSelection) -> EvaluationReport:
        # This is intentionally the only point that resolves or reads test images and labels.
        test_truth, test_images = load_truth_partition(dataset_root, "test", dataset_sha256)
        predictions = predict_tflite(
            model,
            test_images,
            partition="test",
            dataset_sha256=dataset_sha256,
        )
        return evaluate_predictions(
            predictions,
            test_truth,
            confidence=selected.confidence,
            iou=selected.iou,
            candidate_sha256=sha256_file(model),
        )

    exporter = UltralyticsExporter(
        dataset_config=export_dataset_config,
        validation_evaluator=evaluate_validation,
        test_evaluator=evaluate_test,
    )
    bundle = export_candidate(
        checkpoint=checkpoint,
        exporter=exporter,
        selection=selection,
        dataset_receipt_sha256=dataset_receipt_sha256,
        dataset_audit_sha256=dataset_audit_sha256,
        notices=(repository_root / "LICENSE", repository_root / "NOTICE.md"),
        status="DEBUG_ONLY",
        quantizations=("fp32",),
    )
    _write_json(
        reports_root / "candidate-validation-evaluations.json",
        {
            "schemaVersion": 1,
            "selectionSha256": selection.sha256,
            "candidates": {
                name: report.to_dict() for name, report in sorted(candidate_validation.items())
            },
        },
    )
    _write_json(
        reports_root / "experimental-test-evaluation.json",
        {
            "schemaVersion": 1,
            "evidenceScope": "synthetic/public-dataset experimental evidence",
            "productionApproved": False,
            "report": bundle.test_report.to_dict(),
            "gates": _experimental_gates(bundle.test_report),
        },
    )
    _write_json(
        reports_root / "android-runtime-fixture.json",
        candidate_fixtures[bundle.quantization],
    )
    return bundle


def _execution_dataset(
    yolo_root: Path,
    canonical_root: Path,
    audit_path: Path,
    source_receipt_path: Path,
) -> tuple[Path, Path]:
    """Use the canonical tree or a fully re-audited byte mirror for Windows ACL isolation."""
    mirror_value = os.environ.get("PONTINHO_TASK10_DATASET_MIRROR")
    if mirror_value is None:
        return canonical_root, yolo_root / "config" / "dataset.yaml"
    mirror = Path(mirror_value)
    if not mirror.is_absolute() or mirror.is_symlink() or not mirror.is_dir():
        raise RuntimeError("Stage 10 dataset mirror must be an absolute regular directory")
    try:
        receipt = SourceReceipt.from_path(source_receipt_path)
        actual = audit_dataset(mirror, receipt).to_dict()
    except (DatasetValidationError, SourceVerificationError) as error:
        raise RuntimeError(f"Stage 10 dataset mirror failed full audit: {error}") from error
    expected = _read_json(audit_path)
    if actual != expected:
        raise RuntimeError("Stage 10 dataset mirror audit differs from canonical evidence")

    config_path = mirror.parent / "task10-mirror-dataset.yaml"
    config_payload = {
        "train": (mirror / "train" / "images").as_posix(),
        "val": (mirror / "valid" / "images").as_posix(),
        "test": (mirror / "test" / "images").as_posix(),
        "names": dict(enumerate(CARD_LABELS)),
    }
    serialized = yaml.safe_dump(config_payload, sort_keys=False, allow_unicode=True)
    if config_path.exists():
        if config_path.is_symlink() or config_path.read_text(encoding="utf-8") != serialized:
            raise RuntimeError("Stage 10 mirror dataset config differs from expected bytes")
    else:
        config_path.write_text(serialized, encoding="utf-8", newline="\n")
    return mirror, config_path


def _experimental_gates(report: EvaluationReport) -> dict[str, object]:
    measured = {
        "recallAtLeast0.97": report.recall >= 0.97,
        "falsePositiveShareAtMost0.01": report.false_positive_share <= 0.01,
        "exactMultisetAtLeast0.90": report.exact_multiset_accuracy >= 0.90,
        "exactTotalAtLeast0.95": report.exact_total_accuracy >= 0.95,
    }
    return {
        "publicDatasetMetricGates": measured,
        "publicDatasetMetricGatesPassed": all(measured.values()),
        "physicalDeckGateExecuted": False,
        "productionGatePassed": False,
    }


def _select_android_fixture(
    predictions: PredictionPartition,
    truth: TruthPartition,
    image_paths: tuple[Path, ...],
    selection: ThresholdSelection,
    candidate_sha256: str,
) -> dict[str, object]:
    by_name = {path.name: path for path in image_paths}
    choices: list[tuple[int, str, dict[str, object]]] = []
    for raw, expected in zip(predictions.scenes, truth.scenes, strict=True):
        try:
            detections = decode_yolo(
                raw.output,
                raw.geometry,
                selection.confidence,
                selection.iou,
                max_cards=10,
            )
        except (IncompleteDetections, TooManyDetections):
            continue
        if Counter(item.label for item in detections) != Counter(expected.labels):
            continue
        image = by_name[raw.scene_id]
        width = raw.geometry.source_width
        height = raw.geometry.source_height
        fixture = {
            "schemaVersion": 1,
            "evidenceScope": "public-domain validation image; runtime parity only",
            "license": "Public Domain",
            "imageFile": "task10-playing-cards-validation.jpg",
            "sourceArchiveMember": f"valid/images/{image.name}",
            "sourceImageSha256": sha256_file(image),
            "candidateSha256": candidate_sha256,
            "thresholds": {
                "confidence": selection.confidence,
                "iou": selection.iou,
            },
            "normalizedBoxTolerance": 0.001,
            "detections": [
                {
                    "label": item.label,
                    "confidence": item.confidence,
                    "bounds": [
                        item.box.left / width,
                        item.box.top / height,
                        item.box.right / width,
                        item.box.bottom / height,
                    ],
                }
                for item in detections
            ],
        }
        choices.append((image.stat().st_size, image.name, fixture))
    if not choices:
        raise RuntimeError("candidate produced no exact validation scene for Android parity")
    return min(choices, key=lambda item: (item[0], item[1]))[2]


def _required_hash(payload: dict[str, object], key: str) -> str:
    value = payload.get(key)
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise RuntimeError(f"training receipt field is not a SHA-256: {key}")
    return value


def _read_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read required JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"required JSON is not an object: {path}")
    return value


def _write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    ) as stream:
        temporary = Path(stream.name)
        stream.write(serialized)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the canonical Stage 10 validation/export/holdout pipeline"
    )
    parser.parse_args()
    bundle = run()
    print(
        json.dumps(
            {
                "model": str(bundle.model),
                "modelSha256": bundle.model_sha256,
                "quantization": bundle.quantization,
                "validation": bundle.validation_report.to_dict(),
                "test": bundle.test_report.to_dict(),
            },
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
