"""Export, inspect, select, and atomically publish one single-stage detector."""

from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
import tempfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from pontinho_yolo.evaluate import (
    EvaluationError,
    EvaluationReport,
    ThresholdSelection,
    _discover_repository_identity,
    consume_holdout_once,
    verify_holdout_receipt,
)
from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.postprocess import OUTPUT_BOX_COORDINATE_UNITS, PHYSICAL_DUPLICATE_IOU

_ALLOWED_DTYPES = frozenset({"float32", "int8"})
_SHA256_CHARS = frozenset("0123456789abcdef")


class ExportError(RuntimeError):
    """Raised when an export cannot satisfy the candidate contract."""


@dataclass(frozen=True)
class TensorContract:
    input_shape: tuple[int, ...]
    input_dtype: str
    output_shape: tuple[int, ...]
    output_dtype: str
    raw_output: bool
    label_count: int
    input_quantization: tuple[float, int] | None
    output_quantization: tuple[float, int] | None
    internal_int8_tensor_count: int = 0
    weighted_compute_operator_count: int = 0
    quantized_compute_operator_count: int = 0
    quantized_compute_operator_codes: tuple[str, ...] = ()

    @property
    def quantized_compute_coverage(self) -> float:
        if self.weighted_compute_operator_count <= 0:
            return 0.0
        return self.quantized_compute_operator_count / self.weighted_compute_operator_count


@dataclass(frozen=True)
class CandidateBundle:
    directory: Path
    model: Path
    metadata: Path
    model_sha256: str
    quantization: str
    validation_report: EvaluationReport
    test_report: EvaluationReport
    holdout_receipt: Path
    holdout_receipt_sha256: str


class CandidateExporter(Protocol):
    def export(self, checkpoint: Path, destination: Path, *, quantization: str) -> Path: ...

    def inspect(self, model: Path) -> TensorContract: ...

    def evaluate_validation(
        self, model: Path, selection: ThresholdSelection
    ) -> EvaluationReport: ...

    def evaluate_test(self, model: Path, selection: ThresholdSelection) -> EvaluationReport: ...


class UltralyticsExporter:
    """Thin production adapter around pinned Ultralytics export operations."""

    def __init__(
        self,
        *,
        dataset_config: Path,
        inspector: Callable[[Path], TensorContract] | None = None,
        validation_evaluator: Callable[[Path, ThresholdSelection], EvaluationReport],
        test_evaluator: Callable[[Path, ThresholdSelection], EvaluationReport],
    ) -> None:
        self.dataset_config = Path(dataset_config)
        self._inspector = inspector or inspect_tflite
        self._validation_evaluator = validation_evaluator
        self._test_evaluator = test_evaluator

    def export(self, checkpoint: Path, destination: Path, *, quantization: str) -> Path:
        checkpoint = Path(checkpoint)
        destination = Path(destination)
        if checkpoint.is_symlink() or not checkpoint.is_file():
            raise ExportError("checkpoint must be a regular file")
        if destination.exists():
            raise ExportError("refusing to replace an existing exported model")
        if quantization not in {"fp32", "int8"}:
            raise ExportError("quantization must be fp32 or int8")
        if quantization == "int8" and (
            self.dataset_config.is_symlink() or not self.dataset_config.is_file()
        ):
            raise ExportError("INT8 calibration dataset config must be a regular file")

        from ultralytics import YOLO

        settings: dict[str, object] = {
            "format": "tflite",
            "imgsz": 640,
            "batch": 1,
            "nms": False,
            "int8": quantization == "int8",
        }
        if quantization == "int8":
            settings.update(data=str(self.dataset_config), split="train")
        generated = Path(str(YOLO(str(checkpoint)).export(**settings)))
        if generated.is_symlink() or not generated.is_file():
            raise ExportError("Ultralytics did not return a regular TFLite file")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(generated, destination)
        return destination

    def inspect(self, model: Path) -> TensorContract:
        return self._inspector(Path(model))

    def evaluate_validation(self, model: Path, selection: ThresholdSelection) -> EvaluationReport:
        return self._validation_evaluator(Path(model), selection)

    def evaluate_test(self, model: Path, selection: ThresholdSelection) -> EvaluationReport:
        return self._test_evaluator(Path(model), selection)


def inspect_tflite(model_path: Path) -> TensorContract:
    """Read the one-input/one-output TFLite tensor contract without invoking inference."""
    model_path = Path(model_path)
    if model_path.is_symlink() or not model_path.is_file():
        raise ExportError("TFLite candidate must be a regular file")
    try:
        import tflite

        payload = model_path.read_bytes()
        model = tflite.Model.GetRootAsModel(payload, 0)
        if model.SubgraphsLength() != 1:
            raise ExportError("TFLite candidate must contain exactly one subgraph")
        subgraph = model.Subgraphs(0)
        if subgraph.InputsLength() != 1 or subgraph.OutputsLength() != 1:
            raise ExportError("TFLite candidate must have exactly one input and one output")
        input_tensor = subgraph.Tensors(subgraph.Inputs(0))
        output_tensor = subgraph.Tensors(subgraph.Outputs(0))
        input_shape = tuple(int(value) for value in input_tensor.ShapeAsNumpy().tolist())
        output_shape = tuple(int(value) for value in output_tensor.ShapeAsNumpy().tolist())
        dtypes = {
            tflite.TensorType.FLOAT32: "float32",
            tflite.TensorType.INT8: "int8",
        }
        tensor_count = subgraph.TensorsLength()
        buffer_count = model.BuffersLength()
        int8_tensors: set[int] = set()
        for tensor_index in range(tensor_count):
            tensor = subgraph.Tensors(tensor_index)
            if not 0 <= int(tensor.Buffer()) < buffer_count:
                raise ExportError("TFLite tensor buffer reference is out of bounds")
            if tensor.Type() == tflite.TensorType.INT8:
                quantization = _tensor_quantization(tensor, allow_per_axis=True)
                if quantization is not None:
                    int8_tensors.add(tensor_index)
        weighted_codes = {
            tflite.BuiltinOperator.CONV_2D,
            tflite.BuiltinOperator.DEPTHWISE_CONV_2D,
            tflite.BuiltinOperator.FULLY_CONNECTED,
        }
        weighted_compute_operator_count = 0
        quantized_compute_operator_count = 0
        quantized_compute_operator_codes: set[str] = set()
        runtime_tensors = {int(subgraph.Inputs(index)) for index in range(subgraph.InputsLength())}
        _validate_tensor_references(runtime_tensors, tensor_count)
        for operator_index in range(subgraph.OperatorsLength()):
            operator = subgraph.Operators(operator_index)
            operator_code = model.OperatorCodes(operator.OpcodeIndex()).BuiltinCode()
            inputs = _tensor_indices(operator.InputsAsNumpy())
            outputs = _tensor_indices(operator.OutputsAsNumpy())
            _validate_tensor_references(inputs, tensor_count, allow_optional=True)
            _validate_tensor_references(outputs, tensor_count)
            if operator_code in weighted_codes:
                weighted_compute_operator_count += 1
                if len(inputs) >= 2 and outputs:
                    activation_index, weight_index = inputs[:2]
                    output_index = outputs[0]
                    if (
                        activation_index in int8_tensors
                        and activation_index in runtime_tensors
                        and weight_index in int8_tensors
                        and weight_index not in runtime_tensors
                        and _tensor_has_constant_storage(
                            model, subgraph.Tensors(weight_index), payload
                        )
                        and output_index in int8_tensors
                    ):
                        quantized_compute_operator_count += 1
                        quantized_compute_operator_codes.add(tflite.opcode2name(operator_code))
            runtime_tensors.update(index for index in outputs if index >= 0)
            intermediates_method = getattr(operator, "IntermediatesAsNumpy", None)
            if intermediates_method is not None:
                intermediates = _tensor_indices(intermediates_method())
                _validate_tensor_references(intermediates, tensor_count, allow_optional=True)
                runtime_tensors.update(index for index in intermediates if index >= 0)
        contract = TensorContract(
            input_shape=input_shape,
            input_dtype=dtypes.get(input_tensor.Type(), "unsupported"),
            output_shape=output_shape,
            output_dtype=dtypes.get(output_tensor.Type(), "unsupported"),
            raw_output=output_shape == (1, 56, 8400),
            label_count=output_shape[1] - 4 if len(output_shape) == 3 else -1,
            input_quantization=_tensor_quantization(input_tensor),
            output_quantization=_tensor_quantization(output_tensor),
            internal_int8_tensor_count=len(int8_tensors),
            weighted_compute_operator_count=weighted_compute_operator_count,
            quantized_compute_operator_count=quantized_compute_operator_count,
            quantized_compute_operator_codes=tuple(sorted(quantized_compute_operator_codes)),
        )
    except ExportError:
        raise
    except Exception as error:
        raise ExportError(f"cannot inspect TFLite candidate: {error}") from error
    inspect_contract(contract)
    return contract


def inspect_contract(contract: TensorContract) -> None:
    """Fail closed unless the model exposes the exact raw one-stage tensor contract."""
    if contract.input_shape != (1, 640, 640, 3):
        raise ExportError("model must have one 640x640 RGB input")
    if contract.output_shape != (1, 56, 8400):
        raise ExportError("model must have one raw (1,56,8400) output")
    if contract.input_dtype not in _ALLOWED_DTYPES or contract.output_dtype not in _ALLOWED_DTYPES:
        raise ExportError("model tensors must use float32 or calibrated int8")
    if not contract.raw_output:
        raise ExportError("model output must remain raw; built-in NMS is forbidden")
    if contract.label_count != len(CARD_LABELS):
        raise ExportError("model output must bind exactly 52 labels")
    _validate_tensor_quantization(contract.input_dtype, contract.input_quantization, "input")
    _validate_tensor_quantization(contract.output_dtype, contract.output_quantization, "output")


def export_candidate(
    *,
    checkpoint: Path,
    exporter: CandidateExporter,
    selection: ThresholdSelection,
    dataset_receipt_sha256: str,
    dataset_audit_sha256: str,
    notices: Sequence[Path],
    status: str,
) -> CandidateBundle:
    """Select on validation, evaluate the winner once on test, and publish atomically."""
    checkpoint = Path(checkpoint)
    repository = _discover_repository_identity()
    destination = repository.yolo_root / "exports" / "selected"
    if checkpoint.is_symlink() or not checkpoint.is_file():
        raise ExportError("checkpoint must be a regular file")
    if destination.exists() or destination.is_symlink():
        raise ExportError("refusing to replace an existing candidate bundle")
    if status != "DEBUG_ONLY":
        raise ExportError("Stage 5 candidate status must be DEBUG_ONLY")
    _validate_sha256(dataset_receipt_sha256, "dataset receipt SHA-256")
    _validate_sha256(dataset_audit_sha256, "dataset audit SHA-256")
    if selection.validation_report.partition != "validation":
        raise ExportError("threshold selection must be based on validation")
    if selection.validation_dataset_sha256 != selection.validation_report.dataset_sha256:
        raise ExportError("threshold selection validation provenance differs")
    checkpoint_sha256 = _hash_file(checkpoint)
    if selection.source_checkpoint_sha256 != checkpoint_sha256:
        raise ExportError("threshold selection is not bound to the source checkpoint bytes")
    notice_map = _validated_notices(notices)

    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{destination.name}-", dir=destination.parent
    ) as temp:
        workspace = Path(temp)
        models: dict[str, Path] = {}
        reports: dict[str, EvaluationReport] = {}
        contracts: dict[str, TensorContract] = {}
        for quantization in ("fp32", "int8"):
            model = workspace / f"{quantization}.tflite"
            returned = Path(exporter.export(checkpoint, model, quantization=quantization))
            if returned != model or model.is_symlink() or not model.is_file():
                raise ExportError(f"{quantization} exporter did not create the requested file")
            contract = exporter.inspect(model)
            inspect_contract(contract)
            if quantization == "fp32" and (
                contract.input_dtype != "float32" or contract.output_dtype != "float32"
            ):
                raise ExportError("FP32 candidate tensors must both be float32")
            if quantization == "int8":
                if contract.input_dtype != "float32" or contract.output_dtype != "float32":
                    raise ExportError("static INT8 export must expose pinned FP32 graph I/O")
                if (
                    contract.input_quantization is not None
                    or contract.output_quantization is not None
                    or contract.internal_int8_tensor_count <= 0
                    or contract.weighted_compute_operator_count <= 0
                    or contract.quantized_compute_operator_count <= 0
                ):
                    raise ExportError("static INT8 export lacks internal quantized computation")
                if contract.quantized_compute_coverage < 0.80:
                    raise ExportError("static INT8 weighted-kernel coverage is below 80%")
            report = exporter.evaluate_validation(model, selection)
            _validate_validation_report(report, selection, _hash_file(model))
            models[quantization] = model
            reports[quantization] = report
            contracts[quantization] = contract

        fp32 = reports["fp32"]
        int8 = reports["int8"]
        quantization = (
            "int8"
            if int8.exact_detection_accuracy + 0.01 >= fp32.exact_detection_accuracy
            and int8.exact_multiset_accuracy + 0.01 >= fp32.exact_multiset_accuracy
            and int8.exact_total_accuracy + 0.01 >= fp32.exact_total_accuracy
            else "fp32"
        )
        selected = models[quantization]
        selected_hash = _hash_file(selected)
        if _hash_file(checkpoint) != checkpoint_sha256:
            raise ExportError("source checkpoint changed during export")
        invocation_sha256 = _hash_json(
            {
                "checkpointSha256": checkpoint_sha256,
                "selectionSha256": selection.sha256,
                "candidateSha256": selected_hash,
                "datasetReceiptSha256": dataset_receipt_sha256,
                "datasetAuditSha256": dataset_audit_sha256,
                "destination": "exports/selected",
            }
        )
        try:
            test_report = consume_holdout_once(
                lambda: exporter.evaluate_test(selected, selection),
                selection=selection,
                candidate_sha256=selected_hash,
                dataset_receipt_sha256=dataset_receipt_sha256,
                dataset_audit_sha256=dataset_audit_sha256,
                invocation_sha256=invocation_sha256,
            )
            holdout_record, holdout_receipt_sha256 = verify_holdout_receipt()
        except EvaluationError as error:
            raise ExportError(str(error)) from error
        if (
            holdout_record.get("sourceCheckpointSha256") != checkpoint_sha256
            or holdout_record.get("invocationSha256") != invocation_sha256
        ):
            raise ExportError("holdout receipt is not bound to this export invocation")
        if _hash_file(checkpoint) != checkpoint_sha256:
            raise ExportError("source checkpoint changed during holdout evaluation")

        bundle_directory = workspace / "bundle"
        bundle_directory.mkdir()
        model_path = bundle_directory / "card_detector.tflite"
        shutil.copyfile(selected, model_path)
        for name, path in notice_map.items():
            shutil.copyfile(path, bundle_directory / name)
        metadata_path = bundle_directory / "card_recognition.release.json"
        metadata = _release_metadata(
            model_path=model_path,
            quantization=quantization,
            contract=contracts[quantization],
            selection=selection,
            validation_report=reports[quantization],
            test_report=test_report,
            checkpoint_sha256=checkpoint_sha256,
            dataset_receipt_sha256=dataset_receipt_sha256,
            dataset_audit_sha256=dataset_audit_sha256,
            holdout_receipt_sha256=holdout_receipt_sha256,
            invocation_sha256=invocation_sha256,
            status=status,
        )
        metadata_path.write_text(
            json.dumps(metadata, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        os.replace(bundle_directory, destination)

    return CandidateBundle(
        directory=destination,
        model=destination / "card_detector.tflite",
        metadata=destination / "card_recognition.release.json",
        model_sha256=selected_hash,
        quantization=quantization,
        validation_report=reports[quantization],
        test_report=test_report,
        holdout_receipt=repository.yolo_root / "reports" / "test-evaluation-receipt.json",
        holdout_receipt_sha256=holdout_receipt_sha256,
    )


def _validate_validation_report(
    report: EvaluationReport, selection: ThresholdSelection, candidate_sha256: str
) -> None:
    if report.partition != "validation":
        raise ExportError("candidate selection metrics must come from validation")
    if report.dataset_sha256 != selection.validation_dataset_sha256:
        raise ExportError("candidate validation report belongs to a different dataset")
    if report.confidence != selection.confidence or report.iou != selection.iou:
        raise ExportError("candidate validation report used different thresholds")
    if report.candidate_sha256 != candidate_sha256:
        raise ExportError("candidate validation report is not bound to the candidate bytes")


def _validated_notices(notices: Sequence[Path]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for value in notices:
        path = Path(value)
        if path.name not in {"LICENSE", "NOTICE.md"} or path.name in result:
            raise ExportError("bundle notices must be exactly LICENSE and NOTICE.md")
        if path.is_symlink() or not path.is_file():
            raise ExportError(f"notice is not a regular file: {path}")
        result[path.name] = path
    if set(result) != {"LICENSE", "NOTICE.md"}:
        raise ExportError("bundle notices must include LICENSE and NOTICE.md")
    return result


def _release_metadata(
    *,
    model_path: Path,
    quantization: str,
    contract: TensorContract,
    selection: ThresholdSelection,
    validation_report: EvaluationReport,
    test_report: EvaluationReport,
    checkpoint_sha256: str,
    dataset_receipt_sha256: str,
    dataset_audit_sha256: str,
    holdout_receipt_sha256: str,
    invocation_sha256: str,
    status: str,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "candidateStatus": status,
        "productionApproved": False,
        "model": {
            "file": "card_detector.tflite",
            "sha256": _hash_file(model_path),
            "sizeBytes": model_path.stat().st_size,
            "quantization": quantization,
            "sourceCheckpointSha256": checkpoint_sha256,
            "internalQuantization": {
                "int8TensorCount": contract.internal_int8_tensor_count,
                "weightedComputeOperatorCount": contract.weighted_compute_operator_count,
                "quantizedComputeOperatorCount": contract.quantized_compute_operator_count,
                "quantizedComputeCoverage": contract.quantized_compute_coverage,
                "operatorCodes": list(contract.quantized_compute_operator_codes),
            },
        },
        "input": {
            "shape": list(contract.input_shape),
            "dtype": contract.input_dtype,
            "colorSpace": "RGB",
            "normalization": {"scale": 1.0 / 255.0, "offset": 0.0},
            "quantization": _quantization_metadata(contract.input_quantization),
            "resizePolicy": "centered-letterbox-bilinear",
        },
        "output": {
            "shape": list(contract.output_shape),
            "dtype": contract.output_dtype,
            "quantization": _quantization_metadata(contract.output_quantization),
            "layout": "channels-first",
            "raw": True,
            "boxEncoding": "xywh",
            "boxCoordinateUnits": OUTPUT_BOX_COORDINATE_UNITS,
            "boxCoordinateReference": {"width": 640, "height": 640},
            "classCount": len(CARD_LABELS),
        },
        "labels": list(CARD_LABELS),
        "thresholds": {
            "confidence": selection.confidence,
            "iou": selection.iou,
            "comparison": "strict-greater-than",
            "selectedOn": "validation",
            "selectionSha256": selection.sha256,
            "sourceCheckpointSha256": selection.source_checkpoint_sha256,
        },
        "nms": {
            "classAwareFirst": True,
            "physicalDuplicateSuppression": True,
            "physicalDuplicateIou": PHYSICAL_DUPLICATE_IOU,
            "tieBreak": "score-desc-box-class-anchor",
            "finalOrder": "left-top-right-bottom-class-score-anchor",
        },
        "maxCards": 10,
        "datasetReceiptSha256": dataset_receipt_sha256,
        "datasetAuditSha256": dataset_audit_sha256,
        "holdoutReceiptSha256": holdout_receipt_sha256,
        "exportInvocationSha256": invocation_sha256,
        "validationEvaluationSha256": validation_report.sha256,
        "testEvaluationSha256": test_report.sha256,
    }


def _validate_sha256(value: str, description: str) -> None:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in _SHA256_CHARS for character in value)
    ):
        raise ExportError(f"{description} is invalid")


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _hash_json(payload: object) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    ).hexdigest()


def _tensor_indices(values: object) -> tuple[int, ...]:
    if values is None or isinstance(values, int):
        return ()
    return tuple(int(index) for index in values.tolist())


def _validate_tensor_references(
    indices: Sequence[int], tensor_count: int, *, allow_optional: bool = False
) -> None:
    for index in indices:
        if allow_optional and index == -1:
            continue
        if not 0 <= index < tensor_count:
            raise ExportError("TFLite operator tensor reference is out of bounds")


def _tensor_has_constant_storage(model: object, tensor: object, payload: bytes) -> bool:
    """Validate dense INT8 tensor bytes from embedded or schema external storage."""
    shape_values = tensor.ShapeAsNumpy()
    if shape_values is None or isinstance(shape_values, int):
        return False
    shape = tuple(int(value) for value in shape_values.tolist())
    if not shape or any(value <= 0 for value in shape):
        return False
    expected_size = math.prod(shape)
    buffer = model.Buffers(int(tensor.Buffer()))
    embedded_size = int(buffer.DataLength())
    external_offset = int(buffer.Offset())
    external_size = int(buffer.Size())
    if embedded_size:
        data = buffer.DataAsNumpy()
        return (
            external_offset == 0
            and external_size == 0
            and not isinstance(data, int)
            and len(data) == embedded_size == expected_size
        )
    if external_offset == 0 and external_size == 0:
        return False
    if external_offset <= 0 or external_size != expected_size:
        return False
    return external_offset <= len(payload) and external_size <= len(payload) - external_offset


def _tensor_quantization(
    tensor: object, *, allow_per_axis: bool = False
) -> tuple[float, int] | None:
    quantization = tensor.Quantization()
    if quantization is None:
        return None
    scales = quantization.ScaleAsNumpy()
    zero_points = quantization.ZeroPointAsNumpy()
    if scales is None or zero_points is None or len(scales) == 0 or len(zero_points) == 0:
        return None
    if len(scales) != len(zero_points):
        raise ExportError("tensor quantization scales and zero points differ")
    if len(scales) != 1 and not allow_per_axis:
        raise ExportError("per-axis tensor quantization is unsupported")
    for scale, zero_point in zip(scales, zero_points, strict=True):
        if (
            not math.isfinite(float(scale))
            or float(scale) <= 0
            or not -128 <= int(zero_point) <= 127
        ):
            raise ExportError("internal INT8 tensor quantization is invalid")
    return float(scales[0]), int(zero_points[0])


def _validate_tensor_quantization(
    dtype: str, quantization: tuple[float, int] | None, description: str
) -> None:
    if dtype == "float32":
        if quantization is not None:
            raise ExportError(f"float32 {description} must not declare affine quantization")
        return
    if dtype != "int8" or quantization is None:
        raise ExportError(f"int8 {description} requires affine quantization")
    scale, zero_point = quantization
    if not math.isfinite(scale) or scale <= 0.0 or not -128 <= zero_point <= 127:
        raise ExportError(f"int8 {description} quantization is invalid")


def _quantization_metadata(
    quantization: tuple[float, int] | None,
) -> dict[str, float | int] | None:
    if quantization is None:
        return None
    return {"scale": quantization[0], "zeroPoint": quantization[1]}
