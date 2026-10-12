"""Export inspection, validation-only candidate choice, and bundle publication."""

from __future__ import annotations

import hashlib
import json
import sys
import types
from dataclasses import replace
from pathlib import Path

import pytest

from pontinho_yolo.evaluate import (
    BucketMetrics,
    EvaluationReport,
    ThresholdSelection,
)
from pontinho_yolo.export import (
    ExportError,
    TensorContract,
    UltralyticsExporter,
    export_candidate,
    inspect_contract,
    inspect_tflite,
)
from pontinho_yolo.labels import CARD_LABELS


def _report(
    partition: str,
    *,
    exact_multiset: float,
    exact_total: float,
    candidate_sha256: str | None = None,
) -> EvaluationReport:
    return EvaluationReport(
        partition=partition,
        dataset_sha256="a" * 64,
        candidate_sha256=candidate_sha256,
        confidence=0.5,
        iou=0.5,
        scene_count=10,
        truth_card_count=10,
        predicted_card_count=10,
        true_positive_count=10,
        false_positive_count=0,
        false_negative_count=0,
        precision=1.0,
        recall=1.0,
        false_positive_share=0.0,
        exact_count_accuracy=1.0,
        exact_multiset_accuracy=exact_multiset,
        exact_total_accuracy=exact_total,
        abstention_rate=0.0,
        buckets={count: BucketMetrics.unsupported(count) for count in range(1, 11)},
    )


def _selection() -> ThresholdSelection:
    return ThresholdSelection(
        confidence=0.5,
        iou=0.5,
        validation_dataset_sha256="a" * 64,
        validation_predictions_sha256="b" * 64,
        validation_truth_sha256="c" * 64,
        validation_report=_report("validation", exact_multiset=0.95, exact_total=0.98),
    )


class FakeExporter:
    def __init__(self, int8_report: EvaluationReport) -> None:
        self.int8_report = int8_report
        self.calls: list[tuple[str, str]] = []

    def export(self, checkpoint: Path, destination: Path, *, quantization: str) -> Path:
        self.calls.append((checkpoint.name, quantization))
        destination.write_bytes(f"{quantization}-raw-yolo".encode())
        return destination

    def inspect(self, model: Path) -> TensorContract:
        quantization = model.stem
        return TensorContract(
            input_shape=(1, 640, 640, 3),
            input_dtype="float32" if quantization == "fp32" else "int8",
            output_shape=(1, 56, 8400),
            output_dtype="float32" if quantization == "fp32" else "int8",
            raw_output=True,
            label_count=52,
            input_quantization=(1.0 / 255.0, -128) if quantization == "int8" else None,
            output_quantization=(0.01, -5) if quantization == "int8" else None,
        )

    def evaluate_validation(self, model: Path, selection: ThresholdSelection) -> EvaluationReport:
        assert selection == _selection()
        report = (
            _report("validation", exact_multiset=0.95, exact_total=0.98)
            if model.stem == "fp32"
            else self.int8_report
        )
        return replace(report, candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest())


def test_inspection_requires_exact_raw_single_stage_tensor_contract() -> None:
    """A postprocessed or classifier-shaped model is incompatible with Android decoding."""
    valid = TensorContract(
        input_shape=(1, 640, 640, 3),
        input_dtype="float32",
        output_shape=(1, 56, 8400),
        output_dtype="float32",
        raw_output=True,
        label_count=52,
        input_quantization=None,
        output_quantization=None,
    )
    inspect_contract(valid)

    mutations = (
        replace(valid, input_shape=(1, 320, 320, 3)),
        replace(valid, output_shape=(1, 8400, 56)),
        replace(valid, raw_output=False),
        replace(valid, label_count=51),
        replace(valid, input_dtype="uint8"),
        replace(valid, output_dtype="uint8"),
        replace(valid, input_dtype="int8", input_quantization=None),
        replace(valid, output_dtype="int8", output_quantization=(0.0, 0)),
    )
    for mutation in mutations:
        with pytest.raises(ExportError):
            inspect_contract(mutation)


def test_ultralytics_adapter_exports_raw_fp32_and_train_calibrated_int8_with_fakes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A default validation calibration split or built-in NMS would leak or change the contract."""
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    dataset = tmp_path / "dataset.yaml"
    dataset.write_text(
        "train: train/images\nval: valid/images\ntest: test/images\n", encoding="utf-8"
    )
    calls: list[tuple[str, dict[str, object]]] = []

    class FakeYolo:
        def __init__(self, value: str) -> None:
            assert value == str(checkpoint)

        def export(self, **settings: object) -> str:
            calls.append(("export", dict(settings)))
            generated = tmp_path / f"generated-{len(calls)}.tflite"
            generated.write_bytes(b"fake tflite")
            return str(generated)

    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=FakeYolo))
    contract = TensorContract(
        input_shape=(1, 640, 640, 3),
        input_dtype="float32",
        output_shape=(1, 56, 8400),
        output_dtype="float32",
        raw_output=True,
        label_count=52,
        input_quantization=None,
        output_quantization=None,
    )
    evaluated: list[Path] = []
    adapter = UltralyticsExporter(
        dataset_config=dataset,
        inspector=lambda _path: contract,
        validation_evaluator=lambda model, _selection: evaluated.append(model)
        or _report("validation", exact_multiset=1.0, exact_total=1.0),
    )

    fp32 = adapter.export(checkpoint, tmp_path / "fp32.tflite", quantization="fp32")
    int8 = adapter.export(checkpoint, tmp_path / "int8.tflite", quantization="int8")

    assert fp32.read_bytes() == b"fake tflite"
    assert int8.read_bytes() == b"fake tflite"
    assert calls == [
        (
            "export",
            {
                "format": "tflite",
                "imgsz": 640,
                "batch": 1,
                "nms": False,
                "int8": False,
            },
        ),
        (
            "export",
            {
                "format": "tflite",
                "imgsz": 640,
                "batch": 1,
                "nms": False,
                "int8": True,
                "data": str(dataset),
                "split": "train",
            },
        ),
    ]
    assert adapter.inspect(fp32) == contract
    assert adapter.evaluate_validation(fp32, _selection()).partition == "validation"
    assert evaluated == [fp32]


def test_tflite_inspection_wraps_malformed_flatbuffer_as_export_error(tmp_path: Path) -> None:
    """FlatBuffer parser details must not escape the fail-closed export boundary."""
    malformed = tmp_path / "malformed.tflite"
    malformed.write_bytes(b"not a flatbuffer")

    with pytest.raises(ExportError, match="inspect TFLite"):
        inspect_tflite(malformed)


def test_export_selects_int8_within_one_point_and_publishes_exact_bundle(tmp_path: Path) -> None:
    """Candidate selection must use validation metrics and bind every published byte."""
    exporter = FakeExporter(_report("validation", exact_multiset=0.94, exact_total=0.97))
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    license_file = tmp_path / "LICENSE"
    notice_file = tmp_path / "NOTICE.md"
    license_file.write_text("AGPL", encoding="utf-8")
    notice_file.write_text("dataset notice", encoding="utf-8")
    holdout = _report("test", exact_multiset=0.91, exact_total=0.96)
    output = tmp_path / "selected"

    def evaluate_test(model: Path) -> EvaluationReport:
        return replace(
            holdout,
            candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
        )

    bundle = export_candidate(
        checkpoint=checkpoint,
        destination=output,
        exporter=exporter,
        selection=_selection(),
        evaluate_test=evaluate_test,
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=(license_file, notice_file),
        status="DEBUG_ONLY",
    )

    assert exporter.calls == [("best.pt", "fp32"), ("best.pt", "int8")]
    assert bundle.quantization == "int8"
    assert {path.name for path in output.iterdir()} == {
        "LICENSE",
        "NOTICE.md",
        "card_detector.tflite",
        "card_recognition.release.json",
    }
    model = output / "card_detector.tflite"
    metadata = json.loads((output / "card_recognition.release.json").read_text())
    assert model.read_bytes() == b"int8-raw-yolo"
    assert metadata["schemaVersion"] == 1
    assert metadata["candidateStatus"] == "DEBUG_ONLY"
    assert metadata["productionApproved"] is False
    assert metadata["model"] == {
        "file": "card_detector.tflite",
        "sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
        "sizeBytes": len(model.read_bytes()),
        "quantization": "int8",
    }
    assert tuple(metadata["labels"]) == CARD_LABELS
    assert metadata["input"]["shape"] == [1, 640, 640, 3]
    assert metadata["input"]["dtype"] == "int8"
    assert metadata["input"]["quantization"] == {"scale": 1.0 / 255.0, "zeroPoint": -128}
    assert metadata["output"] == {
        "shape": [1, 56, 8400],
        "dtype": "int8",
        "quantization": {"scale": 0.01, "zeroPoint": -5},
        "layout": "channels-first",
        "raw": True,
        "boxEncoding": "xywh",
        "classCount": 52,
    }
    assert metadata["thresholds"] == {
        "confidence": 0.5,
        "iou": 0.5,
        "comparison": "strict-greater-than",
        "selectedOn": "validation",
        "selectionSha256": _selection().sha256,
    }
    assert metadata["maxCards"] == 10
    assert metadata["datasetReceiptSha256"] == "d" * 64
    assert metadata["datasetAuditSha256"] == "e" * 64
    assert metadata["testEvaluationSha256"] == evaluate_test(model).sha256


def test_export_keeps_fp32_when_int8_loses_more_than_one_point(tmp_path: Path) -> None:
    """INT8 must not win merely because it is smaller when exact accuracy regresses."""
    exporter = FakeExporter(_report("validation", exact_multiset=0.939, exact_total=0.98))
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    bundle = export_candidate(
        checkpoint=checkpoint,
        destination=tmp_path / "selected",
        exporter=exporter,
        selection=_selection(),
        evaluate_test=lambda model: replace(
            _report("test", exact_multiset=0.90, exact_total=0.95),
            candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
        ),
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=tuple(notices),
        status="DEBUG_ONLY",
    )

    assert bundle.quantization == "fp32"
    assert bundle.model.read_bytes() == b"fp32-raw-yolo"


def test_export_rejects_test_metrics_for_candidate_selection_or_release_status(
    tmp_path: Path,
) -> None:
    """Using test metrics or self-asserting approval would leak holdout data into release choice."""
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    test_metric_exporter = FakeExporter(_report("test", exact_multiset=1.0, exact_total=1.0))
    with pytest.raises(ExportError, match="validation"):
        export_candidate(
            checkpoint=checkpoint,
            destination=tmp_path / "leaky",
            exporter=test_metric_exporter,
            selection=_selection(),
            evaluate_test=lambda model: replace(
                _report("test", exact_multiset=1.0, exact_total=1.0),
                candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
            ),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )

    with pytest.raises(ExportError, match="status"):
        export_candidate(
            checkpoint=checkpoint,
            destination=tmp_path / "approved",
            exporter=FakeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
            evaluate_test=lambda model: replace(
                _report("test", exact_multiset=1.0, exact_total=1.0),
                candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
            ),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="APPROVED",
        )


def test_export_failure_never_publishes_partial_bundle(tmp_path: Path) -> None:
    """An invalid candidate must not leave a reusable-looking selected directory."""

    class BadShapeExporter(FakeExporter):
        def inspect(self, model: Path) -> TensorContract:
            return replace(super().inspect(model), output_shape=(1, 8400, 56))

    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)
    destination = tmp_path / "selected"

    with pytest.raises(ExportError):
        export_candidate(
            checkpoint=checkpoint,
            destination=destination,
            exporter=BadShapeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
            evaluate_test=lambda model: replace(
                _report("test", exact_multiset=1.0, exact_total=1.0),
                candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
            ),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )

    assert not destination.exists()


def test_export_rejects_validation_report_not_bound_to_each_candidate(tmp_path: Path) -> None:
    """Reusing one model's metrics for another candidate could select an unevaluated artifact."""

    class UnboundExporter(FakeExporter):
        def evaluate_validation(
            self, model: Path, selection: ThresholdSelection
        ) -> EvaluationReport:
            return _report("validation", exact_multiset=1.0, exact_total=1.0)

    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    with pytest.raises(ExportError, match="candidate bytes"):
        export_candidate(
            checkpoint=checkpoint,
            destination=tmp_path / "selected",
            exporter=UnboundExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
            evaluate_test=lambda model: replace(
                _report("test", exact_multiset=1.0, exact_total=1.0),
                candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
            ),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )
