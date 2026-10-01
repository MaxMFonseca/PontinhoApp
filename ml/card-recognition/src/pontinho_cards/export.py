"""Fail-closed EfficientDet-Lite0 export and Android contract inspection."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import yaml

from pontinho_cards.labels import CARD_LABELS

_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_REVISION = re.compile(r"^[0-9a-f]{7,40}$")


class ExportContractError(ValueError):
    """Raised before an unsafe or incompatible candidate can be published."""


@dataclass(frozen=True)
class ExportRequest:
    checkpoint_dir: Path
    checkpoint_path: Path
    checkpoint_sha256: str
    output_dir: Path
    comparison_path: Path
    comparison_sha256: str
    quantization: str
    labels: tuple[str, ...]
    input_size: int
    confidence_threshold: float
    iou_threshold: float
    run_git_commit: str
    input_manifest_sha256: str
    requirements_lock_sha256: str
    validation_manifest_sha256: str
    selection_evidence: dict[str, Any]


def _read_json(path: Path, name: str) -> Mapping[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ExportContractError(f"cannot read {name} {path}: {error}") from error
    if not isinstance(document, Mapping):
        raise ExportContractError(f"{name} must be an object")
    return document


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise ExportContractError(f"cannot hash required input {path}: {error}") from error


def _digest(value: Any, name: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ExportContractError(f"{name} must be a lowercase SHA-256")
    return value


def _finite_unit(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ExportContractError(f"{name} must be a finite number in [0, 1]")
    normalized = float(value)
    if not math.isfinite(normalized) or not 0.0 <= normalized <= 1.0:
        raise ExportContractError(f"{name} must be a finite number in [0, 1]")
    return normalized


def _validate_output_path(checkpoint_dir: Path, output: str | Path) -> Path:
    raw = Path(output)
    if raw.name in {"", ".", ".."} or not _SAFE_NAME.fullmatch(raw.name):
        raise ExportContractError("output must have one safe version-directory name")
    resolved = raw.resolve()
    if resolved == Path(resolved.anchor) or resolved == resolved.parent:
        raise ExportContractError("output cannot be a filesystem root")
    if resolved == checkpoint_dir or checkpoint_dir in resolved.parents:
        raise ExportContractError("output cannot be inside the checkpoint run")
    if resolved.exists():
        raise ExportContractError(f"output already exists: {resolved}")
    parent = resolved.parent
    if parent.exists() and parent.is_symlink():
        raise ExportContractError("output parent cannot be a symbolic link")
    return resolved


def _validate_comparison(path: Path) -> tuple[str, str, dict[str, Any]]:
    comparison = _read_json(path, "export comparison")
    if comparison.get("schemaVersion") != 1:
        raise ExportContractError("export comparison schemaVersion must be 1")
    selected = comparison.get("selected")
    if selected not in {"float16", "int8"}:
        raise ExportContractError("selected quantization must be float16 or int8")
    rationale = comparison.get("selectionRationale")
    if not isinstance(rationale, str) or not rationale.strip():
        raise ExportContractError("export comparison requires a selection rationale")
    validation_hash = _digest(
        comparison.get("validationManifestSha256"),
        "export comparison validationManifestSha256",
    )
    variants = comparison.get("variants")
    if not isinstance(variants, Mapping) or set(variants) != {"float16", "int8"}:
        raise ExportContractError("export comparison must contain float16 and int8 variants")
    normalized: dict[str, Any] = {}
    for variant in ("float16", "int8"):
        evidence = variants.get(variant)
        if not isinstance(evidence, Mapping):
            raise ExportContractError(f"{variant} comparison evidence must be an object")
        accuracy = _finite_unit(evidence.get("exactTotalAccuracy"), f"{variant} accuracy")
        latency = evidence.get("androidLatencyMs")
        if (
            isinstance(latency, bool)
            or not isinstance(latency, (int, float))
            or not math.isfinite(latency)
            or latency <= 0
        ):
            raise ExportContractError(
                f"{variant} comparison requires positive finite Android latency"
            )
        normalized[variant] = {
            "exactTotalAccuracy": accuracy,
            "androidLatencyMs": float(latency),
        }
    return str(selected), validation_hash, {
        "selectionRationale": rationale.strip(),
        "variants": normalized,
    }


def prepare_export(
    checkpoint: str | Path,
    output: str | Path,
    comparison: str | Path | None = None,
) -> ExportRequest:
    """Resolve one Task-5 selected run and validate every input before writing."""

    run = Path(checkpoint).resolve()
    if not run.is_dir() or run.is_symlink():
        raise ExportContractError("checkpoint must be a real Task-5 run directory")
    output_dir = _validate_output_path(run, output)
    metrics = _read_json(run / "metrics.json", "training metrics")
    if metrics.get("status") != "TRAINED_NOT_RELEASE_APPROVED":
        raise ExportContractError("export requires a full trained run, not a smoke checkpoint")
    selected_name = metrics.get("selectedCheckpoint")
    if selected_name != "selected.weights.h5":
        raise ExportContractError("training metrics must name the single selected checkpoint")
    checkpoint_path = run / selected_name
    checkpoint_hash = _sha256(checkpoint_path)
    if checkpoint_hash != metrics.get("selectedCheckpointSha256"):
        raise ExportContractError("selected checkpoint hash does not match training metrics")

    try:
        config = yaml.safe_load((run / "resolved-config.yaml").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise ExportContractError(f"cannot read resolved training config: {error}") from error
    if not isinstance(config, Mapping):
        raise ExportContractError("resolved training config must be an object")
    if config.get("detector_family") != "efficientdet_lite0":
        raise ExportContractError("resolved config must select efficientdet_lite0")
    labels = config.get("labels")
    if not isinstance(labels, list) or tuple(labels) != CARD_LABELS:
        raise ExportContractError("resolved labels must exactly match CARD_LABELS order")
    if config.get("image_size") != 320:
        raise ExportContractError("EfficientDet-Lite0 export input size must be 320")
    confidence = _finite_unit(config.get("confidence_threshold"), "confidence threshold")
    iou = _finite_unit(config.get("iou_threshold"), "IoU threshold")

    metadata = _read_json(run / "run-metadata.json", "run metadata")
    commit = metadata.get("gitCommit")
    if not isinstance(commit, str) or not _GIT_REVISION.fullmatch(commit):
        raise ExportContractError("run metadata gitCommit is invalid")
    input_hash = _digest(metadata.get("inputManifestSha256"), "input manifest hash")
    lock_hash = _digest(metadata.get("requirementsLockSha256"), "requirements lock hash")

    comparison_path = Path(comparison).resolve() if comparison else run / "export-comparison.json"
    quantization, validation_hash, selection_evidence = _validate_comparison(comparison_path)
    if quantization == "int8" and not (run / "annotations.csv").is_file():
        raise ExportContractError("int8 export requires the audited representative annotations.csv")
    return ExportRequest(
        checkpoint_dir=run,
        checkpoint_path=checkpoint_path,
        checkpoint_sha256=checkpoint_hash,
        output_dir=output_dir,
        comparison_path=comparison_path,
        comparison_sha256=_sha256(comparison_path),
        quantization=quantization,
        labels=CARD_LABELS,
        input_size=320,
        confidence_threshold=confidence,
        iou_threshold=iou,
        run_git_commit=commit,
        input_manifest_sha256=input_hash,
        requirements_lock_sha256=lock_hash,
        validation_manifest_sha256=validation_hash,
        selection_evidence=selection_evidence,
    )


def _metadata_contract(model_path: Path) -> tuple[list[str], dict[str, list[float]]]:
    try:
        from tflite_support import metadata as metadata_api

        display = metadata_api.MetadataDisplayer.with_model_file(str(model_path))
        metadata = json.loads(display.get_metadata_json())
        files = display.get_packed_associated_file_list()
    except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as error:
        raise ExportContractError(f"TFLite metadata is missing or unreadable: {error}") from error
    labels: list[str] | None = None
    for filename in files:
        try:
            text = display.get_associated_file_buffer(filename).decode("utf-8")
        except (UnicodeDecodeError, ValueError) as error:
            raise ExportContractError(f"invalid associated metadata file {filename}") from error
        lines = text.splitlines()
        if len(lines) == len(CARD_LABELS):
            labels = lines
            break
    if labels != list(CARD_LABELS):
        raise ExportContractError("embedded metadata labels do not exactly match CARD_LABELS")

    try:
        units = metadata["subgraph_metadata"][0]["input_tensor_metadata"][0][
            "process_units"
        ]
    except (KeyError, IndexError, TypeError) as error:
        raise ExportContractError("input normalization metadata is missing") from error
    options: Mapping[str, Any] | None = None
    for unit in units:
        candidate = unit.get("options") if isinstance(unit, Mapping) else None
        if isinstance(candidate, Mapping) and "mean" in candidate and "std" in candidate:
            options = candidate
            break
    if options is None:
        raise ExportContractError("input normalization metadata is missing")
    mean = options.get("mean")
    std = options.get("std")
    if not isinstance(mean, list) or not isinstance(std, list) or not mean or not std:
        raise ExportContractError("input normalization metadata is invalid")
    try:
        normalization = {
            "mean": [float(value) for value in mean],
            "std": [float(value) for value in std],
        }
    except (TypeError, ValueError) as error:
        raise ExportContractError("input normalization metadata is invalid") from error
    if any(not math.isfinite(value) for values in normalization.values() for value in values):
        raise ExportContractError("input normalization metadata must be finite")
    return labels, normalization


def _postprocess_outputs(model_path: Path) -> tuple[list[str], list[int]]:
    try:
        from tflite_support import schema_py_generated as schema

        model_bytes = model_path.read_bytes()
        model = schema.Model.GetRootAsModel(model_bytes, 0)
        subgraph = model.Subgraphs(0)
    except (OSError, AttributeError, TypeError, ValueError) as error:
        raise ExportContractError(f"invalid TFLite flatbuffer: {error}") from error
    custom_ops: list[str] = []
    outputs: list[int] | None = None
    for index in range(subgraph.OperatorsLength()):
        operator = subgraph.Operators(index)
        opcode = model.OperatorCodes(operator.OpcodeIndex())
        custom = opcode.CustomCode()
        name = custom.decode("utf-8") if custom else ""
        if name:
            custom_ops.append(name)
        if name == "TFLite_Detection_PostProcess":
            if outputs is not None:
                raise ExportContractError("model contains multiple DetectionPostProcess ops")
            outputs = [operator.Outputs(i) for i in range(operator.OutputsLength())]
    if outputs is None:
        raise ExportContractError("model is missing TFLite_Detection_PostProcess")
    if len(outputs) != 4:
        raise ExportContractError("DetectionPostProcess must expose exactly four outputs")
    if set(custom_ops) != {"TFLite_Detection_PostProcess"}:
        raise ExportContractError("model contains unsupported custom ops")
    return custom_ops, outputs


def inspect_tflite(
    model_path: str | Path, *, expected_quantization: str
) -> dict[str, Any]:
    """Validate metadata, graph, tensors, and one genuine interpreter invocation."""

    path = Path(model_path)
    if expected_quantization not in {"float16", "int8"}:
        raise ExportContractError("expected quantization must be float16 or int8")
    labels, normalization = _metadata_contract(path)
    custom_ops, postprocess_indices = _postprocess_outputs(path)
    try:
        import tensorflow as tf

        interpreter = tf.lite.Interpreter(
            model_path=str(path),
            experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
        )
        interpreter.allocate_tensors()
        inputs = interpreter.get_input_details()
        outputs = interpreter.get_output_details()
        tensors = interpreter.get_tensor_details()
    except (OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"TFLite interpreter rejected the model: {error}") from error
    if len(inputs) != 1:
        raise ExportContractError("TFLite model must expose exactly one image input")
    input_detail = inputs[0]
    input_shape = [int(value) for value in input_detail["shape"]]
    if input_shape != [1, 320, 320, 3]:
        raise ExportContractError("TFLite input must have shape [1, 320, 320, 3]")

    by_index = {int(detail["index"]): detail for detail in outputs}
    if any(index not in by_index for index in postprocess_indices):
        raise ExportContractError("DetectionPostProcess outputs are not graph outputs")
    semantic_names = ("boxes", "classes", "scores", "count")
    semantic_outputs: list[dict[str, Any]] = []
    for semantic, index in zip(semantic_names, postprocess_indices):
        detail = by_index[index]
        semantic_outputs.append(
            {
                "semantic": semantic,
                "name": str(detail["name"]),
                "shape": [int(value) for value in detail["shape"]],
                "dtype": np.dtype(detail["dtype"]).name,
            }
        )
    boxes, classes, scores, count = semantic_outputs
    max_results = boxes["shape"][1] if len(boxes["shape"]) == 3 else None
    if (
        boxes["shape"] != [1, max_results, 4]
        or classes["shape"] != [1, max_results]
        or scores["shape"] != [1, max_results]
        or count["shape"] != [1]
        or any(item["dtype"] != "float32" for item in semantic_outputs)
    ):
        raise ExportContractError("DetectionPostProcess output shapes/types are incompatible")

    dtype_name = np.dtype(input_detail["dtype"]).name
    tensor_dtypes = {np.dtype(detail["dtype"]).name for detail in tensors}
    quantization_parameters = input_detail["quantization_parameters"]
    scales = [float(value) for value in quantization_parameters["scales"]]
    zero_points = [int(value) for value in quantization_parameters["zero_points"]]
    if expected_quantization == "float16":
        if dtype_name != "float32" or "float16" not in tensor_dtypes:
            raise ExportContractError("float16 model must retain float32 input and float16 weights")
    elif dtype_name not in {"uint8", "int8"} or not scales or len(scales) != len(zero_points):
        raise ExportContractError("int8 model input must provide explicit scale and zero point")

    try:
        input_value = np.zeros(input_shape, dtype=input_detail["dtype"])
        interpreter.set_tensor(int(input_detail["index"]), input_value)
        interpreter.invoke()
        for detail in outputs:
            interpreter.get_tensor(int(detail["index"]))
    except (RuntimeError, ValueError) as error:
        raise ExportContractError(f"one-image interpreter invocation failed: {error}") from error
    return {
        "modelSha256": _sha256(path),
        "input": {"shape": input_shape, "dtype": dtype_name},
        "outputs": semantic_outputs,
        "customOps": custom_ops,
        "labels": labels,
        "normalization": normalization,
        "quantization": {
            "variant": expected_quantization,
            "input": {"scales": scales, "zeroPoints": zero_points} if scales else None,
        },
        "interpreterInvocation": "passed",
    }


def _export_and_inspect(request: ExportRequest, target: Path) -> dict[str, Any]:
    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    try:
        from tensorflow_examples.lite.model_maker.core.task import configs
        from tflite_model_maker import object_detector
    except ImportError as error:
        raise ExportContractError("locked Model Maker environment is unavailable") from error
    label_map = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    model_dir = target / "model-maker-cache"
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(model_dir),
        epochs=1,
        batch_size=1,
        hparams={"image_size": f"{request.input_size}x{request.input_size}"},
    )
    representative_data = None
    if request.quantization == "int8":
        train_data, _, _ = object_detector.DataLoader.from_csv(
            str(request.checkpoint_dir / "annotations.csv"),
            num_shards=1,
            cache_dir=str(target / "representative-cache"),
        )
        if train_data is None or train_data.label_map != label_map:
            raise ExportContractError("int8 representative data label map is not canonical")
        representative_data = train_data
    detector = object_detector.ObjectDetector(spec, label_map, representative_data)
    detector.create_model()
    try:
        detector.model.load_weights(str(request.checkpoint_path))
    except (OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"selected checkpoint cannot load into Lite0: {error}") from error
    quantization = (
        configs.QuantizationConfig.for_float16()
        if request.quantization == "float16"
        else spec.get_default_quantization_config(representative_data)
    )
    model_path = target / "card_detector.tflite"
    try:
        detector.export(
            str(target),
            tflite_filename=model_path.name,
            quantization_config=quantization,
            with_metadata=True,
        )
    except (OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"Model Maker export failed: {error}") from error
    shutil.rmtree(model_dir, ignore_errors=True)
    shutil.rmtree(target / "representative-cache", ignore_errors=True)
    return inspect_tflite(model_path, expected_quantization=request.quantization)


def _atomic_write(path: Path, payload: bytes) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def publish_export(request: ExportRequest) -> dict[str, Any]:
    """Convert, inspect, write the contract, then atomically publish a candidate."""

    request.output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f".{request.output_dir.name}.", dir=request.output_dir.parent)
    )
    try:
        inspection = _export_and_inspect(request, staging)
        model_path = staging / "card_detector.tflite"
        if inspection.get("modelSha256") != _sha256(model_path):
            raise ExportContractError("inspected model hash changed before publication")
        if inspection.get("labels") != list(CARD_LABELS):
            raise ExportContractError("inspected labels do not exactly match CARD_LABELS")
        contract = {
            "schemaVersion": 1,
            **inspection,
            "checkpointSha256": request.checkpoint_sha256,
            "comparisonSha256": request.comparison_sha256,
            "runGitCommit": request.run_git_commit,
            "inputManifestSha256": request.input_manifest_sha256,
            "requirementsLockSha256": request.requirements_lock_sha256,
            "validationManifestSha256": request.validation_manifest_sha256,
            "scoreThreshold": request.confidence_threshold,
            "iouThreshold": request.iou_threshold,
            "selectionEvidence": request.selection_evidence,
        }
        _atomic_write(
            staging / "model-contract.json",
            (json.dumps(contract, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )
        if request.output_dir.exists():
            raise ExportContractError(f"output already exists: {request.output_dir}")
        os.replace(staging, request.output_dir)
        return contract
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--comparison", type=Path)
    args = parser.parse_args(argv)
    try:
        request = prepare_export(args.checkpoint, args.output, args.comparison)
        publish_export(request)
    except ExportContractError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
