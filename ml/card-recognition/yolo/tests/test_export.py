"""Export inspection, validation-only candidate choice, and bundle publication."""

from __future__ import annotations

import hashlib
import json
import sys
import types
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from pontinho_yolo import evaluate as evaluate_module
from pontinho_yolo import export as export_module
from pontinho_yolo.evaluate import (
    BucketMetrics,
    ClassMetrics,
    EvaluationReport,
    RepositoryIdentity,
    ThresholdSelection,
)
from pontinho_yolo.export import (
    ExportError,
    TensorContract,
    UltralyticsExporter,
    _tensor_quantization,
    export_candidate,
    inspect_contract,
    inspect_tflite,
)
from pontinho_yolo.labels import CARD_LABELS

CHECKPOINT_SHA256 = hashlib.sha256(b"checkpoint").hexdigest()


def _tflite_offset_vector(builder, start, offsets: tuple[int, ...]) -> int:
    start(builder, len(offsets))
    for offset in reversed(offsets):
        builder.PrependUOffsetTRelative(offset)
    return builder.EndVector()


def _tflite_int_vector(builder, start, values: tuple[int, ...]) -> int:
    start(builder, len(values))
    for value in reversed(values):
        builder.PrependInt32(value)
    return builder.EndVector()


def _serialized_quantized_conv(
    *,
    weight_data: bytes = b"\x01",
    weight_buffer_index: int = 3,
    runtime_buffer_indices: tuple[int, int, int] = (1, 4, 5),
    external_weight: tuple[int, int] | None = None,
) -> bytes:
    """Build the pinned FP32-boundary/INT8-kernel shape through the real schema API."""
    import flatbuffers
    import tflite

    builder = flatbuffers.Builder(2048)

    scales = []
    for scale in (0.01, 0.02, 0.04):
        tflite.QuantizationParametersStartScaleVector(builder, 1)
        builder.PrependFloat32(scale)
        scale_vector = builder.EndVector()
        tflite.QuantizationParametersStartZeroPointVector(builder, 1)
        builder.PrependInt64(0)
        zero_point_vector = builder.EndVector()
        tflite.QuantizationParametersStart(builder)
        tflite.QuantizationParametersAddScale(builder, scale_vector)
        tflite.QuantizationParametersAddZeroPoint(builder, zero_point_vector)
        scales.append(tflite.QuantizationParametersEnd(builder))

    tensor_specs = (
        ((1, 640, 640, 3), tflite.TensorType.FLOAT32, 0, None),
        (
            (1, 640, 640, 3),
            tflite.TensorType.INT8,
            runtime_buffer_indices[0],
            scales[0],
        ),
        ((1,), tflite.TensorType.INT8, weight_buffer_index, scales[1]),
        ((1, 56, 8400), tflite.TensorType.INT8, runtime_buffer_indices[1], scales[2]),
        ((1, 56, 8400), tflite.TensorType.FLOAT32, runtime_buffer_indices[2], None),
    )
    tensors = []
    for shape, dtype, buffer_index, quantization in tensor_specs:
        shape_vector = _tflite_int_vector(builder, tflite.TensorStartShapeVector, shape)
        tflite.TensorStart(builder)
        tflite.TensorAddShape(builder, shape_vector)
        tflite.TensorAddType(builder, dtype)
        tflite.TensorAddBuffer(builder, buffer_index)
        if quantization is not None:
            tflite.TensorAddQuantization(builder, quantization)
        tensors.append(tflite.TensorEnd(builder))

    operators = []
    for opcode_index, inputs, outputs in (
        (0, (0,), (1,)),
        (1, (1, 2), (3,)),
        (2, (3,), (4,)),
    ):
        input_vector = _tflite_int_vector(builder, tflite.OperatorStartInputsVector, inputs)
        output_vector = _tflite_int_vector(builder, tflite.OperatorStartOutputsVector, outputs)
        tflite.OperatorStart(builder)
        tflite.OperatorAddOpcodeIndex(builder, opcode_index)
        tflite.OperatorAddInputs(builder, input_vector)
        tflite.OperatorAddOutputs(builder, output_vector)
        operators.append(tflite.OperatorEnd(builder))

    operator_codes = []
    for code in (
        tflite.BuiltinOperator.QUANTIZE,
        tflite.BuiltinOperator.CONV_2D,
        tflite.BuiltinOperator.DEQUANTIZE,
    ):
        tflite.OperatorCodeStart(builder)
        tflite.OperatorCodeAddDeprecatedBuiltinCode(builder, code)
        tflite.OperatorCodeAddBuiltinCode(builder, code)
        operator_codes.append(tflite.OperatorCodeEnd(builder))

    buffers = []
    for index in range(6):
        data_vector = None
        if index == weight_buffer_index and weight_data:
            data_vector = builder.CreateByteVector(weight_data)
        tflite.BufferStart(builder)
        if data_vector is not None:
            tflite.BufferAddData(builder, data_vector)
        if index == weight_buffer_index and external_weight is not None:
            tflite.BufferAddOffset(builder, external_weight[0])
            tflite.BufferAddSize(builder, external_weight[1])
        buffers.append(tflite.BufferEnd(builder))

    tensor_vector = _tflite_offset_vector(
        builder, tflite.SubGraphStartTensorsVector, tuple(tensors)
    )
    input_vector = _tflite_int_vector(builder, tflite.SubGraphStartInputsVector, (0,))
    output_vector = _tflite_int_vector(builder, tflite.SubGraphStartOutputsVector, (4,))
    operator_vector = _tflite_offset_vector(
        builder, tflite.SubGraphStartOperatorsVector, tuple(operators)
    )
    tflite.SubGraphStart(builder)
    tflite.SubGraphAddTensors(builder, tensor_vector)
    tflite.SubGraphAddInputs(builder, input_vector)
    tflite.SubGraphAddOutputs(builder, output_vector)
    tflite.SubGraphAddOperators(builder, operator_vector)
    subgraph = tflite.SubGraphEnd(builder)

    operator_code_vector = _tflite_offset_vector(
        builder, tflite.ModelStartOperatorCodesVector, tuple(operator_codes)
    )
    subgraph_vector = _tflite_offset_vector(builder, tflite.ModelStartSubgraphsVector, (subgraph,))
    buffer_vector = _tflite_offset_vector(builder, tflite.ModelStartBuffersVector, tuple(buffers))
    tflite.ModelStart(builder)
    tflite.ModelAddVersion(builder, 3)
    tflite.ModelAddOperatorCodes(builder, operator_code_vector)
    tflite.ModelAddSubgraphs(builder, subgraph_vector)
    tflite.ModelAddBuffers(builder, buffer_vector)
    model = tflite.ModelEnd(builder)
    builder.Finish(model, file_identifier=b"TFL3")
    return bytes(builder.Output())


@pytest.fixture(autouse=True)
def _canonical_test_repository(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    common_git_dir = tmp_path / "common-git"
    common_git_dir.mkdir()
    (tmp_path / "ml" / "card-recognition" / "yolo" / "reports").mkdir(parents=True)
    identity = RepositoryIdentity(tmp_path, common_git_dir)
    monkeypatch.setattr(evaluate_module, "_discover_repository_identity", lambda: identity)
    monkeypatch.setattr(export_module, "_discover_repository_identity", lambda: identity)


def _report(
    partition: str,
    *,
    exact_multiset: float,
    exact_total: float,
    exact_detection: float | None = None,
    abstention_rate: float = 0.0,
    candidate_sha256: str | None = None,
) -> EvaluationReport:
    return EvaluationReport(
        partition=partition,
        dataset_sha256="a" * 64,
        candidate_sha256=candidate_sha256,
        confidence=0.5,
        iou=0.5,
        localization_iou=0.5,
        scene_count=10,
        truth_card_count=10,
        predicted_card_count=10,
        true_positive_count=10,
        false_positive_count=0,
        false_negative_count=0,
        precision=1.0,
        recall=1.0,
        false_positive_share=0.0,
        exact_detection_accuracy=(exact_multiset if exact_detection is None else exact_detection),
        exact_count_accuracy=1.0,
        exact_multiset_accuracy=exact_multiset,
        exact_total_accuracy=exact_total,
        abstention_count=round(abstention_rate * 10),
        abstention_rate=abstention_rate,
        abstention_reasons=(
            {"INCOMPLETE_CARDS": round(abstention_rate * 10)} if abstention_rate else {}
        ),
        per_class={label: ClassMetrics.from_counts(0, 0, 0) for label in CARD_LABELS},
        confusion={},
        buckets={count: BucketMetrics.unsupported(count) for count in range(1, 11)},
    )


def _selection() -> ThresholdSelection:
    return ThresholdSelection(
        source_checkpoint_sha256=CHECKPOINT_SHA256,
        confidence=0.5,
        iou=0.5,
        validation_dataset_sha256="a" * 64,
        validation_predictions_sha256="b" * 64,
        validation_truth_sha256="c" * 64,
        validation_report=_report("validation", exact_multiset=0.95, exact_total=0.98),
    )


class FakeExporter:
    def __init__(
        self, int8_report: EvaluationReport, test_report: EvaluationReport | None = None
    ) -> None:
        self.int8_report = int8_report
        self.test_report = test_report or _report("test", exact_multiset=0.91, exact_total=0.96)
        self.calls: list[tuple[str, str]] = []

    def export(self, checkpoint: Path, destination: Path, *, quantization: str) -> Path:
        self.calls.append((checkpoint.name, quantization))
        destination.write_bytes(f"{quantization}-raw-yolo".encode())
        return destination

    def inspect(self, model: Path) -> TensorContract:
        quantization = model.stem
        return TensorContract(
            input_shape=(1, 640, 640, 3),
            input_dtype="float32",
            output_shape=(1, 56, 8400),
            output_dtype="float32",
            raw_output=True,
            label_count=52,
            input_quantization=None,
            output_quantization=None,
            internal_int8_tensor_count=48 if quantization == "int8" else 0,
            weighted_compute_operator_count=24,
            quantized_compute_operator_count=24 if quantization == "int8" else 0,
            quantized_compute_operator_codes=(
                ("CONV_2D", "DEPTHWISE_CONV_2D") if quantization == "int8" else ()
            ),
        )

    def evaluate_validation(self, model: Path, selection: ThresholdSelection) -> EvaluationReport:
        assert selection == _selection()
        report = (
            _report("validation", exact_multiset=0.95, exact_total=0.98)
            if model.stem == "fp32"
            else self.int8_report
        )
        return replace(report, candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest())

    def evaluate_test(self, model: Path, selection: ThresholdSelection) -> EvaluationReport:
        assert selection == _selection()
        return replace(
            self.test_report,
            candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest(),
        )


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
    wrapped: list[object] = []

    class FakeYolo:
        def __init__(self, value: str) -> None:
            assert value == str(checkpoint)
            self.model = object()

        def export(self, **settings: object) -> str:
            calls.append(("export", dict(settings)))
            if settings["quantize"] == 8:
                cache = tmp_path / "train" / "labels.cache"
                cache.parent.mkdir(parents=True, exist_ok=True)
                cache.write_bytes(b"ultralytics generated cache")
            generated = tmp_path / f"generated-{len(calls)}"
            generated.mkdir()
            suffix = "float32" if settings["quantize"] is None else "integer_quant"
            (generated / f"best_{suffix}.tflite").write_bytes(b"fake tflite")
            return str(generated)

    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=FakeYolo))
    monkeypatch.setitem(
        sys.modules,
        "ultralytics.utils.export.tensorflow",
        types.SimpleNamespace(tf_wrapper=lambda model: wrapped.append(model) or model),
    )
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
        test_evaluator=lambda _model, _selection: _report(
            "test", exact_multiset=1.0, exact_total=1.0
        ),
    )

    fp32 = adapter.export(checkpoint, tmp_path / "fp32.tflite", quantization="fp32")
    int8 = adapter.export(checkpoint, tmp_path / "int8.tflite", quantization="int8")

    assert fp32.read_bytes() == b"fake tflite"
    assert int8.read_bytes() == b"fake tflite"
    assert calls == [
        (
            "export",
            {
                "format": "saved_model",
                "imgsz": 640,
                "batch": 1,
                "nms": False,
                "quantize": None,
            },
        ),
        (
            "export",
            {
                "format": "saved_model",
                "imgsz": 640,
                "batch": 1,
                "nms": False,
                "quantize": 8,
                "data": str(tmp_path / "int8.calibration.yaml"),
                "fraction": 0.02,
            },
        ),
    ]
    assert len(wrapped) == 2
    calibration = (tmp_path / "int8.calibration.yaml").read_text(encoding="utf-8")
    assert str((tmp_path / "train/images").resolve()).replace("\\", "/") in calibration
    assert "test:" not in calibration
    assert not (tmp_path / "train" / "labels.cache").exists()
    assert adapter.inspect(fp32) == contract
    assert adapter.evaluate_validation(fp32, _selection()).partition == "validation"
    assert evaluated == [fp32]


def test_pinned_ultralytics_tensorflow_decoder_normalizes_xywh_by_input_grid(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Execute the pinned exporter helper so decoder units track the real integration."""
    config_directory = tmp_path / "ultralytics-config"
    config_directory.mkdir()
    monkeypatch.setenv("YOLO_CONFIG_DIR", str(config_directory))
    import torch
    import ultralytics
    from ultralytics.utils.export.tensorflow import _tf_decode_boxes

    assert ultralytics.__version__ == "8.4.177"

    class FakeHead:
        format = "tflite"
        dynamic = False
        shape = (1, 1, 80, 40)
        strides = torch.tensor([[8.0]])
        stride = torch.tensor([8.0])
        anchors = torch.zeros((2, 1))

        @staticmethod
        def dfl(boxes):
            return boxes

        @staticmethod
        def decode_bboxes(distances, _anchors, _angle):
            return distances

    decoded = _tf_decode_boxes(
        FakeHead(),
        {
            "feats": [torch.zeros((1, 1, 80, 40))],
            "boxes": torch.ones((1, 4, 1)),
        },
    )

    assert decoded.flatten().tolist() == pytest.approx([1 / 40, 1 / 80, 1 / 40, 1 / 80])


def test_tflite_inspection_wraps_malformed_flatbuffer_as_export_error(tmp_path: Path) -> None:
    """FlatBuffer parser details must not escape the fail-closed export boundary."""
    malformed = tmp_path / "malformed.tflite"
    malformed.write_bytes(b"not a flatbuffer")

    with pytest.raises(ExportError, match="inspect TFLite"):
        inspect_tflite(malformed)


def test_serialized_tflite_rejects_empty_nonzero_weight_buffer(tmp_path: Path) -> None:
    """A nonzero buffer ID is not proof that an INT8 weight has constant bytes."""
    model_path = tmp_path / "empty-weight.tflite"
    model_path.write_bytes(
        _serialized_quantized_conv(weight_data=b"", runtime_buffer_indices=(0, 0, 0))
    )

    contract = inspect_tflite(model_path)

    assert contract.weighted_compute_operator_count == 1
    assert contract.quantized_compute_operator_count == 0
    assert contract.quantized_compute_operator_codes == ()


def test_serialized_tflite_accepts_graph_runtime_tensors_with_nonzero_empty_buffers(
    tmp_path: Path,
) -> None:
    """Real schema buffers may be empty/nonzero while graph provenance proves runtime data."""
    model_path = tmp_path / "embedded-weight.tflite"
    model_path.write_bytes(_serialized_quantized_conv())

    contract = inspect_tflite(model_path)

    assert contract.internal_int8_tensor_count == 3
    assert contract.weighted_compute_operator_count == 1
    assert contract.quantized_compute_operator_count == 1
    assert contract.quantized_compute_operator_codes == ("CONV_2D",)
    assert contract.quantized_compute_coverage == 1.0
    assert contract.quantized_compute_coverage == 1.0


def test_serialized_tflite_bounds_checks_tensor_buffer_references(tmp_path: Path) -> None:
    model_path = tmp_path / "invalid-buffer-reference.tflite"
    model_path.write_bytes(_serialized_quantized_conv(weight_buffer_index=99))

    with pytest.raises(ExportError, match="buffer reference"):
        inspect_tflite(model_path)


def test_serialized_tflite_accepts_in_bounds_external_weight_storage(tmp_path: Path) -> None:
    provisional = _serialized_quantized_conv(weight_data=b"", external_weight=(1, 1))
    model = _serialized_quantized_conv(weight_data=b"", external_weight=(len(provisional), 1))
    assert len(model) == len(provisional)
    model_path = tmp_path / "external-weight.tflite"
    model_path.write_bytes(model + b"\x01")

    contract = inspect_tflite(model_path)

    assert contract.quantized_compute_operator_count == 1
    assert contract.quantized_compute_operator_codes == ("CONV_2D",)


def test_tflite_inspector_attests_pinned_static_int8_compute_pattern(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Pinned export uses FP32 boundaries around INT8 activation/weight/output kernels."""
    import tflite

    class Quantization:
        def __init__(self, scales=(), zero_points=()) -> None:
            self.scales = np.asarray(scales, dtype=np.float32)
            self.zero_points = np.asarray(zero_points, dtype=np.int64)

        def ScaleAsNumpy(self):
            return self.scales

        def ZeroPointAsNumpy(self):
            return self.zero_points

    class Tensor:
        def __init__(self, shape, dtype, *, scales=(), zero_points=(), buffer=0) -> None:
            self.shape = np.asarray(shape, dtype=np.int32)
            self.dtype = dtype
            self.quantization = (
                Quantization(scales, zero_points) if scales and zero_points else None
            )
            self.buffer = buffer

        def ShapeAsNumpy(self):
            return self.shape

        def Type(self):
            return self.dtype

        def Quantization(self):
            return self.quantization

        def Buffer(self):
            return self.buffer

    tensors = (
        Tensor((1, 640, 640, 3), tflite.TensorType.FLOAT32),
        Tensor((1, 640, 640, 3), tflite.TensorType.INT8, scales=(0.01,), zero_points=(0,)),
        Tensor(
            (16, 3, 3, 3),
            tflite.TensorType.INT8,
            scales=(0.02, 0.03),
            zero_points=(0, 0),
            buffer=1,
        ),
        Tensor((1, 320, 320, 16), tflite.TensorType.INT8, scales=(0.04,), zero_points=(0,)),
        Tensor((1, 56, 8400), tflite.TensorType.FLOAT32),
    )

    class Operator:
        def __init__(self, opcode_index: int, inputs: tuple[int, ...], outputs: tuple[int, ...]):
            self.opcode_index = opcode_index
            self.inputs = np.asarray(inputs, dtype=np.int32)
            self.outputs = np.asarray(outputs, dtype=np.int32)

        def OpcodeIndex(self):
            return self.opcode_index

        def InputsAsNumpy(self):
            return self.inputs

        def OutputsAsNumpy(self):
            return self.outputs

    operators = (
        Operator(0, (0,), (1,)),
        Operator(1, (1, 2), (3,)),
        Operator(2, (3,), (4,)),
    )

    class OperatorCode:
        def __init__(self, code: int) -> None:
            self.code = code

        def BuiltinCode(self):
            return self.code

    operator_codes = (
        OperatorCode(tflite.BuiltinOperator.QUANTIZE),
        OperatorCode(tflite.BuiltinOperator.CONV_2D),
        OperatorCode(tflite.BuiltinOperator.DEQUANTIZE),
    )

    class Buffer:
        def __init__(self, data: bytes = b"") -> None:
            self.data = np.frombuffer(data, dtype=np.uint8)

        def DataLength(self):
            return len(self.data)

        def DataAsNumpy(self):
            return self.data

        def Offset(self):
            return 0

        def Size(self):
            return 0

    buffers = (Buffer(), Buffer(bytes(16 * 3 * 3 * 3)))

    class Subgraph:
        def InputsLength(self):
            return 1

        def OutputsLength(self):
            return 1

        def Inputs(self, _index):
            return 0

        def Outputs(self, _index):
            return 4

        def TensorsLength(self):
            return len(tensors)

        def Tensors(self, index):
            return tensors[index]

        def OperatorsLength(self):
            return len(operators)

        def Operators(self, index):
            return operators[index]

    class Model:
        def SubgraphsLength(self):
            return 1

        def Subgraphs(self, _index):
            return Subgraph()

        def OperatorCodes(self, index):
            return operator_codes[index]

        def BuffersLength(self):
            return len(buffers)

        def Buffers(self, index):
            return buffers[index]

    monkeypatch.setattr(tflite.Model, "GetRootAsModel", lambda _payload, _offset: Model())
    model_path = tmp_path / "static-int8.tflite"
    model_path.write_bytes(b"pinned-realistic-flatbuffer-contract")

    contract = inspect_tflite(model_path)

    assert contract.input_dtype == "float32"
    assert contract.output_dtype == "float32"
    assert contract.internal_int8_tensor_count == 3
    assert contract.weighted_compute_operator_count == 1
    assert contract.quantized_compute_operator_count == 1
    assert contract.quantized_compute_operator_codes == ("CONV_2D",)


def test_empty_flatbuffer_quantization_vectors_are_treated_as_absent() -> None:
    quantization = types.SimpleNamespace(ScaleAsNumpy=lambda: 0, ZeroPointAsNumpy=lambda: 0)
    tensor = types.SimpleNamespace(Quantization=lambda: quantization)

    assert _tensor_quantization(tensor) is None


def test_export_selects_int8_within_one_point_and_publishes_exact_bundle(tmp_path: Path) -> None:
    """Candidate selection must use validation metrics and bind every published byte."""
    holdout = _report("test", exact_multiset=0.91, exact_total=0.96)
    exporter = FakeExporter(_report("validation", exact_multiset=0.94, exact_total=0.97), holdout)
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    license_file = tmp_path / "LICENSE"
    notice_file = tmp_path / "NOTICE.md"
    license_file.write_text("AGPL", encoding="utf-8")
    notice_file.write_text("dataset notice", encoding="utf-8")
    output = tmp_path / "ml" / "card-recognition" / "yolo" / "exports" / "selected"

    bundle = export_candidate(
        checkpoint=checkpoint,
        exporter=exporter,
        selection=_selection(),
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
        "sourceCheckpointSha256": CHECKPOINT_SHA256,
        "internalQuantization": {
            "int8TensorCount": 48,
            "weightedComputeOperatorCount": 24,
            "quantizedComputeOperatorCount": 24,
            "quantizedComputeCoverage": 1.0,
            "operatorCodes": ["CONV_2D", "DEPTHWISE_CONV_2D"],
        },
    }
    assert tuple(metadata["labels"]) == CARD_LABELS
    assert metadata["input"]["shape"] == [1, 640, 640, 3]
    assert metadata["input"]["dtype"] == "float32"
    assert metadata["input"]["quantization"] is None
    assert metadata["output"] == {
        "shape": [1, 56, 8400],
        "dtype": "float32",
        "quantization": None,
        "layout": "channels-first",
        "raw": True,
        "boxEncoding": "xywh",
        "boxCoordinateUnits": "normalized-input-dimensions",
        "boxCoordinateReference": {"width": 640, "height": 640},
        "classCount": 52,
    }
    assert metadata["thresholds"] == {
        "confidence": 0.5,
        "iou": 0.5,
        "comparison": "strict-greater-than",
        "selectedOn": "validation",
        "selectionSha256": _selection().sha256,
        "sourceCheckpointSha256": CHECKPOINT_SHA256,
    }
    assert metadata["maxCards"] == 10
    assert metadata["datasetReceiptSha256"] == "d" * 64
    assert metadata["datasetAuditSha256"] == "e" * 64
    assert (
        metadata["testEvaluationSha256"]
        == replace(holdout, candidate_sha256=hashlib.sha256(model.read_bytes()).hexdigest()).sha256
    )
    assert metadata["model"]["sourceCheckpointSha256"] == CHECKPOINT_SHA256
    assert metadata["model"]["internalQuantization"] == {
        "int8TensorCount": 48,
        "weightedComputeOperatorCount": 24,
        "quantizedComputeOperatorCount": 24,
        "quantizedComputeCoverage": 1.0,
        "operatorCodes": ["CONV_2D", "DEPTHWISE_CONV_2D"],
    }
    assert bundle.holdout_receipt_sha256 == metadata["holdoutReceiptSha256"]


def test_export_can_publish_explicit_fp32_only_time_bounded_candidate(tmp_path: Path) -> None:
    exporter = FakeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0))
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    bundle = export_candidate(
        checkpoint=checkpoint,
        exporter=exporter,
        selection=_selection(),
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=tuple(notices),
        status="DEBUG_ONLY",
        quantizations=("fp32",),
    )

    assert exporter.calls == [("best.pt", "fp32")]
    assert bundle.quantization == "fp32"
    assert bundle.model.read_bytes() == b"fp32-raw-yolo"


def test_export_refuses_broken_fp32_parity_before_claiming_holdout(tmp_path: Path) -> None:
    exporter = FakeExporter(_report("validation", exact_multiset=0.95, exact_total=0.98))
    original = exporter.evaluate_validation

    def broken_fp32(model: Path, selection: ThresholdSelection) -> EvaluationReport:
        report = original(model, selection)
        if model.stem == "fp32":
            report = replace(
                report,
                exact_detection_accuracy=0.0,
                exact_multiset_accuracy=0.0,
                exact_total_accuracy=0.0,
                abstention_rate=1.0,
            )
        return report

    exporter.evaluate_validation = broken_fp32  # type: ignore[method-assign]
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    license_file = tmp_path / "LICENSE"
    notice_file = tmp_path / "NOTICE.md"
    license_file.write_text("AGPL", encoding="utf-8")
    notice_file.write_text("dataset notice", encoding="utf-8")

    with pytest.raises(ExportError, match="FP32 export regressed"):
        export_candidate(
            checkpoint=checkpoint,
            exporter=exporter,
            selection=_selection(),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=(license_file, notice_file),
            status="DEBUG_ONLY",
        )

    assert not evaluate_module.canonical_holdout_active_path().exists()
    assert not evaluate_module.canonical_holdout_receipt_path().exists()


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
        exporter=exporter,
        selection=_selection(),
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=tuple(notices),
        status="DEBUG_ONLY",
    )

    assert bundle.quantization == "fp32"
    assert bundle.model.read_bytes() == b"fp32-raw-yolo"


def test_export_gates_int8_on_localization_aware_accuracy(tmp_path: Path) -> None:
    """Label-only parity cannot hide an INT8 localization regression."""
    exporter = FakeExporter(
        _report(
            "validation",
            exact_multiset=0.95,
            exact_total=0.98,
            exact_detection=0.93,
        )
    )
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    bundle = export_candidate(
        checkpoint=checkpoint,
        exporter=exporter,
        selection=_selection(),
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=tuple(notices),
        status="DEBUG_ONLY",
    )

    assert bundle.quantization == "fp32"


def test_export_gates_int8_on_whole_capture_abstention_rate(tmp_path: Path) -> None:
    """Quantization must not win while causing materially more incomplete-capture abstentions."""
    exporter = FakeExporter(
        _report(
            "validation",
            exact_multiset=0.95,
            exact_total=0.98,
            abstention_rate=0.20,
        )
    )
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    bundle = export_candidate(
        checkpoint=checkpoint,
        exporter=exporter,
        selection=_selection(),
        dataset_receipt_sha256="d" * 64,
        dataset_audit_sha256="e" * 64,
        notices=tuple(notices),
        status="DEBUG_ONLY",
    )

    assert bundle.quantization == "fp32"


def test_export_rejects_int8_label_without_internal_quantized_graph(tmp_path: Path) -> None:
    """FP32 boundaries are expected, but an INT8 candidate needs quantized operators."""

    class FakeInt8LabelOnly(FakeExporter):
        def inspect(self, model: Path) -> TensorContract:
            return replace(
                super().inspect(model),
                internal_int8_tensor_count=0,
                weighted_compute_operator_count=24,
                quantized_compute_operator_count=0,
                quantized_compute_operator_codes=(),
            )

    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    with pytest.raises(ExportError, match="internal quantized"):
        export_candidate(
            checkpoint=checkpoint,
            exporter=FakeInt8LabelOnly(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )


def test_export_rejects_sparse_int8_attestation_without_meaningful_kernel_coverage(
    tmp_path: Path,
) -> None:
    """Quantize/dequantize wrappers or one token INT8 kernel cannot attest the graph."""

    class SparseQuantizedExporter(FakeExporter):
        def inspect(self, model: Path) -> TensorContract:
            contract = super().inspect(model)
            if model.stem != "int8":
                return contract
            return replace(
                contract,
                internal_int8_tensor_count=4,
                weighted_compute_operator_count=20,
                quantized_compute_operator_count=1,
                quantized_compute_operator_codes=("CONV_2D",),
            )

    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    with pytest.raises(ExportError, match="coverage"):
        export_candidate(
            checkpoint=checkpoint,
            exporter=SparseQuantizedExporter(
                _report("validation", exact_multiset=1.0, exact_total=1.0)
            ),
            selection=_selection(),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )


def test_export_rejects_selection_from_different_checkpoint(tmp_path: Path) -> None:
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(b"checkpoint")
    notices = []
    for name in ("LICENSE", "NOTICE.md"):
        path = tmp_path / name
        path.write_text(name, encoding="utf-8")
        notices.append(path)

    with pytest.raises(ExportError, match="source checkpoint"):
        export_candidate(
            checkpoint=checkpoint,
            exporter=FakeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=replace(_selection(), source_checkpoint_sha256="0" * 64),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )


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
            exporter=test_metric_exporter,
            selection=_selection(),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )

    with pytest.raises(ExportError, match="status"):
        export_candidate(
            checkpoint=checkpoint,
            exporter=FakeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
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
    destination = tmp_path / "ml" / "card-recognition" / "yolo" / "exports" / "selected"

    with pytest.raises(ExportError):
        export_candidate(
            checkpoint=checkpoint,
            exporter=BadShapeExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
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
            exporter=UnboundExporter(_report("validation", exact_multiset=1.0, exact_total=1.0)),
            selection=_selection(),
            dataset_receipt_sha256="d" * 64,
            dataset_audit_sha256="e" * 64,
            notices=tuple(notices),
            status="DEBUG_ONLY",
        )
