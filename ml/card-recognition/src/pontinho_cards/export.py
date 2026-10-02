"""Generate measured TFLite candidates, then promote the exact selected bytes."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import stat
import sys
import tempfile
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
import yaml

from pontinho_cards.labels import CARD_LABELS

_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_REVISION = re.compile(r"^[0-9a-f]{7,40}$")
_RUN_CORE_FILES = (
    "metrics.json",
    "resolved-config.yaml",
    "run-metadata.json",
    "selected.weights.h5",
)
_VARIANT_FILES = {
    "float16": "card_detector.float16.tflite",
    "int8": "card_detector.int8.tflite",
}
_METADATA_NAMES = {
    "boxes": "location",
    "classes": "category",
    "scores": "score",
    "count": "number of detections",
}


class ExportContractError(ValueError):
    """Raised before an unsafe or incompatible export can be published."""


@dataclass(frozen=True)
class RunSnapshot:
    source_dir: Path
    root: Path
    source_hashes: dict[Path, str]
    checkpoint_sha256: str
    config_sha256: str
    run_git_commit: str
    split_manifest_sha256: str
    training_input_sha256: str
    synthetic_manifest_sha256: str | None
    synthetic_review_sha256: str | None
    synthetic_generation_config_sha256: str | None
    requirements_lock_sha256: str
    confidence_threshold: float
    iou_threshold: float
    input_size: int


@dataclass(frozen=True)
class GraphContract:
    input_index: int
    graph_output_indices: list[int]
    postprocess_output_indices: list[int]
    tensor_names: dict[int, str]
    shapes: dict[int, list[int]]
    shape_signatures: dict[int, list[int]]
    tensor_types: dict[int, str]
    constant_tensor_types: dict[int, str]
    constant_consumers: dict[int, list[dict[str, Any]]]
    postprocess_input_indices: list[int]
    nonempty_constant_indices: list[int]
    nonempty_constant_tensor_types: list[str]
    quantized_constant_count: int
    constant_quantization: dict[int, dict[str, Any]]
    input_quantization: dict[str, Any]
    custom_ops: list[str]


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    try:
        return _sha256_bytes(path.read_bytes())
    except OSError as error:
        raise ExportContractError(f"cannot hash required input {path}: {error}") from error


def _digest(value: Any, name: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ExportContractError(f"{name} must be a lowercase SHA-256")
    return value


def _nullable_digest(value: Any, name: str) -> str | None:
    if value is None:
        return None
    return _digest(value, name)


def _finite_unit(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ExportContractError(f"{name} must be a finite number in [0, 1]")
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise ExportContractError(f"{name} must be a finite number in [0, 1]")
    return number


def _lstat(path: Path, name: str, *, directory: bool = False) -> os.stat_result:
    try:
        result = path.lstat()
    except OSError as error:
        raise ExportContractError(f"cannot access {name} {path}: {error}") from error
    if stat.S_ISLNK(result.st_mode):
        raise ExportContractError(f"{name} cannot be a symbolic link")
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected(result.st_mode):
        kind = "directory" if directory else "regular file"
        raise ExportContractError(f"{name} must be a {kind}")
    return result


def _read_regular(path: Path, name: str) -> bytes:
    _lstat(path, name)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode):
            raise ExportContractError(f"{name} must remain a regular file")
        with os.fdopen(descriptor, "rb") as handle:
            return handle.read()
    except OSError as error:
        raise ExportContractError(f"cannot read {name} {path}: {error}") from error


def _atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
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


def _read_json_bytes(payload: bytes, name: str) -> Mapping[str, Any]:
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExportContractError(f"invalid {name}: {error}") from error
    if not isinstance(document, Mapping):
        raise ExportContractError(f"{name} must be an object")
    return document


def _safe_output_path(output: str | Path, forbidden: Sequence[Path]) -> Path:
    raw = Path(output)
    if raw.name in {"", ".", ".."} or not _SAFE_NAME.fullmatch(raw.name):
        raise ExportContractError("output must have one safe version-directory name")
    absolute = Path(os.path.abspath(raw))
    for component in reversed((absolute.parent, *absolute.parent.parents)):
        if component.exists() and stat.S_ISLNK(component.lstat().st_mode):
            raise ExportContractError("output ancestors cannot be symbolic links")
    if absolute == Path(absolute.anchor) or absolute == absolute.parent:
        raise ExportContractError("output cannot be a filesystem root")
    for blocked in forbidden:
        blocked_absolute = Path(os.path.abspath(blocked))
        if absolute == blocked_absolute or blocked_absolute in absolute.parents:
            raise ExportContractError("output cannot be inside an input directory")
    try:
        absolute.lstat()
    except FileNotFoundError:
        pass
    else:
        raise ExportContractError(f"output already exists: {absolute}")
    return absolute


def _snapshot_file(source: Path, target: Path, name: str) -> str:
    payload = _read_regular(source, name)
    _atomic_write(target, payload)
    return _sha256_bytes(payload)


def _snapshot_annotations(
    source: Path, target: Path, images_dir: Path, source_hashes: dict[Path, str]
) -> str:
    payload = _read_regular(source, "annotations.csv")
    try:
        rows = list(csv.reader(payload.decode("utf-8").splitlines()))
    except (UnicodeDecodeError, csv.Error) as error:
        raise ExportContractError(f"invalid annotations.csv: {error}") from error
    rewritten: list[list[str]] = []
    copied: dict[Path, Path] = {}
    for row in rows:
        if len(row) < 2:
            raise ExportContractError("annotations.csv contains a malformed row")
        image_source = Path(row[1])
        if image_source not in copied:
            image_payload = _read_regular(image_source, "representative image")
            image_hash = _sha256_bytes(image_payload)
            image_target = images_dir / f"{image_hash}{image_source.suffix.lower()}"
            _atomic_write(image_target, image_payload)
            copied[image_source] = image_target
            source_hashes[image_source] = image_hash
        updated = list(row)
        updated[1] = str(copied[image_source])
        rewritten.append(updated)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            csv.writer(handle, lineterminator="\n").writerows(rewritten)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return _sha256_bytes(payload)


def _snapshot_run(run_path: str | Path, staging: Path, *, include_calibration: bool) -> RunSnapshot:
    source = Path(run_path)
    _lstat(source, "checkpoint run", directory=True)
    source = Path(os.path.abspath(source))
    root = staging / "run-inputs"
    root.mkdir(parents=True)
    source_hashes: dict[Path, str] = {}
    for filename in _RUN_CORE_FILES:
        original = source / filename
        source_hashes[original] = _snapshot_file(original, root / filename, filename)
    if include_calibration:
        annotations = source / "annotations.csv"
        source_hashes[annotations] = _snapshot_annotations(
            annotations,
            root / "annotations.csv",
            root / "representative-images",
            source_hashes,
        )

    metrics = _read_json_bytes((root / "metrics.json").read_bytes(), "training metrics")
    if metrics.get("status") != "TRAINED_NOT_RELEASE_APPROVED":
        raise ExportContractError("export requires a full trained run")
    if metrics.get("selectedCheckpoint") != "selected.weights.h5":
        raise ExportContractError("training metrics must name the single selected checkpoint")
    checkpoint_hash = source_hashes[source / "selected.weights.h5"]
    if metrics.get("selectedCheckpointSha256") != checkpoint_hash:
        raise ExportContractError("selected checkpoint hash does not match training metrics")
    try:
        config = yaml.safe_load((root / "resolved-config.yaml").read_text(encoding="utf-8"))
    except (UnicodeError, yaml.YAMLError) as error:
        raise ExportContractError(f"invalid resolved training config: {error}") from error
    if not isinstance(config, Mapping):
        raise ExportContractError("resolved training config must be an object")
    if config.get("detector_family") != "efficientdet_lite0":
        raise ExportContractError("resolved config must select efficientdet_lite0")
    if config.get("image_size") != 320:
        raise ExportContractError("EfficientDet-Lite0 input size must be 320")
    if not isinstance(config.get("labels"), list) or tuple(config["labels"]) != CARD_LABELS:
        raise ExportContractError("resolved labels must exactly match CARD_LABELS order")
    metadata = _read_json_bytes((root / "run-metadata.json").read_bytes(), "run metadata")
    if metadata.get("schemaVersion") != 2:
        raise ExportContractError("run metadata schemaVersion must be 2")
    commit = metadata.get("gitCommit")
    if not isinstance(commit, str) or not _GIT_REVISION.fullmatch(commit):
        raise ExportContractError("run metadata gitCommit is invalid")
    return RunSnapshot(
        source_dir=source,
        root=root,
        source_hashes=source_hashes,
        checkpoint_sha256=checkpoint_hash,
        config_sha256=source_hashes[source / "resolved-config.yaml"],
        run_git_commit=commit,
        split_manifest_sha256=_digest(
            metadata.get("splitManifestSha256"), "split manifest hash"
        ),
        training_input_sha256=_digest(
            metadata.get("trainingInputSha256"), "training input hash"
        ),
        synthetic_manifest_sha256=_nullable_digest(
            metadata.get("syntheticManifestSha256"), "synthetic manifest hash"
        ),
        synthetic_review_sha256=_nullable_digest(
            metadata.get("syntheticReviewSha256"), "synthetic review hash"
        ),
        synthetic_generation_config_sha256=_nullable_digest(
            metadata.get("syntheticGenerationConfigSha256"),
            "synthetic generation config hash",
        ),
        requirements_lock_sha256=_digest(
            metadata.get("requirementsLockSha256"), "requirements lock hash"
        ),
        confidence_threshold=_finite_unit(
            config.get("confidence_threshold"), "confidence threshold"
        ),
        iou_threshold=_finite_unit(config.get("iou_threshold"), "IoU threshold"),
        input_size=320,
    )


def _reverify_sources(source_hashes: Mapping[Path, str]) -> None:
    for source, expected in source_hashes.items():
        if _sha256_bytes(_read_regular(source, "snapshotted source")) != expected:
            raise ExportContractError(f"source changed during export: {source}")


def _publish_reserved(staging: Path, output: Path, files: Sequence[str]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    try:
        output.mkdir()
    except FileExistsError as error:
        raise ExportContractError(f"cannot reserve output; target exists: {output}") from error
    sentinel = output / ".pontinho-reservation"
    try:
        _atomic_write(sentinel, token.encode("ascii"))
        for filename in files:
            os.replace(staging / filename, output / filename)
        sentinel.unlink()
    except BaseException:
        try:
            if sentinel.read_text(encoding="ascii") == token:
                shutil.rmtree(output)
        except OSError:
            pass
        raise


def _generate_and_inspect_variant(
    snapshot: RunSnapshot, variant: str, target: Path
) -> dict[str, Any]:
    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    try:
        from tensorflow_examples.lite.model_maker.core.task import configs
        from tflite_model_maker import object_detector
    except ImportError as error:
        raise ExportContractError("locked Model Maker environment is unavailable") from error
    label_map = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    cache = target.parent / f".{variant}-cache"
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(cache), epochs=1, batch_size=1, hparams={"image_size": "320x320"}
    )
    representative_data = None
    if variant == "int8":
        train_data, _, _ = object_detector.DataLoader.from_csv(
            str(snapshot.root / "annotations.csv"),
            num_shards=1,
            cache_dir=str(cache / "representative"),
        )
        if train_data is None or train_data.label_map != label_map:
            raise ExportContractError("int8 representative label map is not canonical")
        representative_data = train_data
    detector = object_detector.ObjectDetector(spec, label_map, representative_data)
    detector.create_model()
    try:
        detector.model.load_weights(str(snapshot.root / "selected.weights.h5"))
    except (OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"selected checkpoint cannot load into Lite0: {error}") from error
    quantization = (
        configs.QuantizationConfig.for_float16()
        if variant == "float16"
        else spec.get_default_quantization_config(representative_data)
    )
    try:
        detector.export(
            str(target.parent),
            tflite_filename=target.name,
            quantization_config=quantization,
            with_metadata=True,
        )
    except (OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"Model Maker {variant} export failed: {error}") from error
    finally:
        shutil.rmtree(cache, ignore_errors=True)
    return inspect_tflite(target, expected_quantization=variant)


def generate_candidates(checkpoint: str | Path, output: str | Path) -> dict[str, Any]:
    """Atomically emit both exact binaries that must be benchmarked before promotion."""

    checkpoint_path = Path(checkpoint)
    _lstat(checkpoint_path, "checkpoint run", directory=True)
    output_path = _safe_output_path(output, [checkpoint_path])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output_path.name}.", dir=output_path.parent))
    try:
        snapshot = _snapshot_run(checkpoint_path, staging, include_calibration=True)
        variants: dict[str, Any] = {}
        for variant, filename in _VARIANT_FILES.items():
            inspection = _generate_and_inspect_variant(snapshot, variant, staging / filename)
            if inspection.get("modelSha256") != _sha256(staging / filename):
                raise ExportContractError(f"{variant} inspected candidate hash changed")
            variants[variant] = {"filename": filename, **inspection}
        _reverify_sources(snapshot.source_hashes)
        contract = {
            "schemaVersion": 1,
            "checkpointSha256": snapshot.checkpoint_sha256,
            "configSha256": snapshot.config_sha256,
            "runGitCommit": snapshot.run_git_commit,
            "splitManifestSha256": snapshot.split_manifest_sha256,
            "trainingInputSha256": snapshot.training_input_sha256,
            "syntheticManifestSha256": snapshot.synthetic_manifest_sha256,
            "syntheticReviewSha256": snapshot.synthetic_review_sha256,
            "syntheticGenerationConfigSha256": snapshot.synthetic_generation_config_sha256,
            "requirementsLockSha256": snapshot.requirements_lock_sha256,
            "scoreThreshold": snapshot.confidence_threshold,
            "iouThreshold": snapshot.iou_threshold,
            "thresholdProvenance": "configured-not-validation-selected",
            "labels": list(CARD_LABELS),
            "variants": variants,
        }
        _atomic_write(
            staging / "candidate-contract.json",
            (json.dumps(contract, indent=2, sort_keys=True) + "\n").encode(),
        )
        _publish_reserved(
            staging,
            output_path,
            [*_VARIANT_FILES.values(), "candidate-contract.json"],
        )
        return contract
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _snapshot_external(
    source: Path, target: Path, name: str, source_hashes: dict[Path, str]
) -> str:
    digest = _snapshot_file(source, target, name)
    source_hashes[source] = digest
    return digest


def _validate_comparison(
    document: Mapping[str, Any], candidate: Mapping[str, Any], comparison_hash: str
) -> tuple[str, str, dict[str, Any]]:
    if document.get("schemaVersion") != 1:
        raise ExportContractError("export comparison schemaVersion must be 1")
    selected = document.get("selected")
    if selected not in _VARIANT_FILES:
        raise ExportContractError("selected quantization must be float16 or int8")
    if document.get("candidateContractSha256") != comparison_hash:
        raise ExportContractError("comparison does not name the exact candidate contract")
    if document.get("selectedCheckpointSha256") != candidate.get("checkpointSha256"):
        raise ExportContractError("comparison checkpoint hash does not match candidates")
    if document.get("runGitCommit") != candidate.get("runGitCommit"):
        raise ExportContractError("comparison Git revision does not match candidates")
    for field, name in (
        ("splitManifestSha256", "split manifest"),
        ("trainingInputSha256", "training input"),
        ("syntheticManifestSha256", "synthetic manifest"),
        ("syntheticReviewSha256", "synthetic review"),
        ("syntheticGenerationConfigSha256", "synthetic generation config"),
    ):
        if document.get(field) != candidate.get(field):
            raise ExportContractError(f"comparison {name} hash does not match candidates")
    rationale = document.get("selectionRationale")
    device = document.get("androidDevice")
    if not isinstance(rationale, str) or not rationale.strip():
        raise ExportContractError("comparison requires a selection rationale")
    if not isinstance(device, str) or not device.strip():
        raise ExportContractError("comparison requires the measured Android device")
    validation_hash = _digest(document.get("validationManifestSha256"), "validation hash")
    variants = document.get("variants")
    candidate_variants = candidate.get("variants")
    if not isinstance(variants, Mapping) or set(variants) != set(_VARIANT_FILES):
        raise ExportContractError("comparison must contain float16 and int8 variants")
    if not isinstance(candidate_variants, Mapping):
        raise ExportContractError("candidate contract variants are invalid")
    normalized: dict[str, Any] = {}
    for variant in _VARIANT_FILES:
        evidence = variants.get(variant)
        candidate_variant = candidate_variants.get(variant)
        if not isinstance(evidence, Mapping) or not isinstance(candidate_variant, Mapping):
            raise ExportContractError(f"{variant} comparison evidence is invalid")
        model_hash = _digest(evidence.get("modelSha256"), f"{variant} modelSha256")
        if model_hash != candidate_variant.get("modelSha256"):
            raise ExportContractError(f"{variant} comparison candidate hash mismatch")
        latency = evidence.get("androidLatencyMs")
        if (
            isinstance(latency, bool)
            or not isinstance(latency, (int, float))
            or not math.isfinite(latency)
            or latency <= 0
        ):
            raise ExportContractError(f"{variant} requires positive finite Android latency")
        normalized[variant] = {
            "modelSha256": model_hash,
            "exactTotalAccuracy": _finite_unit(
                evidence.get("exactTotalAccuracy"), f"{variant} accuracy"
            ),
            "androidLatencyMs": float(latency),
        }
    return (
        str(selected),
        validation_hash,
        {
            "selectionRationale": rationale.strip(),
            "androidDevice": device.strip(),
            "variants": normalized,
        },
    )


def _validate_inspection_declaration(
    document: Mapping[str, Any], variant: str, model_hash: str
) -> None:
    expected_keys = {
        "filename",
        "modelSha256",
        "input",
        "outputs",
        "customOps",
        "labels",
        "normalization",
        "quantization",
        "interpreterInvocation",
    }
    if set(document) != expected_keys:
        raise ExportContractError(f"{variant} candidate contract schema is invalid")
    if document.get("filename") != _VARIANT_FILES[variant]:
        raise ExportContractError(f"{variant} candidate filename is invalid")
    if document.get("modelSha256") != model_hash:
        raise ExportContractError(f"{variant} candidate hash mismatch")
    input_contract = document.get("input")
    if not isinstance(input_contract, Mapping) or set(input_contract) != {
        "name",
        "shape",
        "shapeSignature",
        "dtype",
    }:
        raise ExportContractError(f"{variant} candidate input schema is invalid")
    expected_dtype = "float32" if variant == "float16" else {"int8", "uint8"}
    dtype = input_contract.get("dtype")
    if (
        not isinstance(input_contract.get("name"), str)
        or not input_contract["name"]
        or input_contract.get("shape") != [1, 320, 320, 3]
        or input_contract.get("shapeSignature") != [1, 320, 320, 3]
        or (
            dtype != expected_dtype
            if isinstance(expected_dtype, str)
            else dtype not in expected_dtype
        )
    ):
        raise ExportContractError(f"{variant} candidate input contract is invalid")
    outputs = document.get("outputs")
    if not isinstance(outputs, list) or len(outputs) != 4:
        raise ExportContractError(f"{variant} candidate output schema is invalid")
    expected_semantics = ("boxes", "classes", "scores", "count")
    names: set[str] = set()
    max_results: int | None = None
    for index, (output, semantic) in enumerate(zip(outputs, expected_semantics)):
        if not isinstance(output, Mapping) or set(output) != {
            "semantic",
            "tensorName",
            "metadataName",
            "shape",
            "shapeSignature",
            "dtype",
        }:
            raise ExportContractError(f"{variant} candidate output schema is invalid")
        name = output.get("tensorName")
        if not isinstance(name, str) or not name or name in names:
            raise ExportContractError(f"{variant} candidate output tensor names are invalid")
        names.add(name)
        if (
            output.get("semantic") != semantic
            or output.get("metadataName") != _METADATA_NAMES[semantic]
        ):
            raise ExportContractError(f"{variant} candidate output mapping is invalid")
        shape = output.get("shape")
        if index == 0:
            if (
                not isinstance(shape, list)
                or len(shape) != 3
                or shape[0] != 1
                or not isinstance(shape[1], int)
                or shape[1] <= 0
                or shape[2] != 4
            ):
                raise ExportContractError(f"{variant} candidate output shape is invalid")
            max_results = shape[1]
        expected_shape = (
            [1, max_results, 4]
            if semantic == "boxes"
            else [1, max_results]
            if semantic in {"classes", "scores"}
            else [1]
        )
        if (
            shape != expected_shape
            or output.get("shapeSignature") != expected_shape
            or output.get("dtype") != "float32"
        ):
            raise ExportContractError(f"{variant} candidate output contract is invalid")
    if document.get("customOps") != ["TFLite_Detection_PostProcess"]:
        raise ExportContractError(f"{variant} candidate custom ops are invalid")
    if document.get("labels") != list(CARD_LABELS):
        raise ExportContractError(f"{variant} candidate labels are invalid")
    normalization = document.get("normalization")
    if not isinstance(normalization, Mapping) or set(normalization) != {"mean", "std"}:
        raise ExportContractError(f"{variant} candidate normalization schema is invalid")
    mean = _expand_rgb(normalization.get("mean"), "normalization mean")
    std = _expand_rgb(normalization.get("std"), "normalization std", positive=True)
    if normalization.get("mean") != mean or normalization.get("std") != std:
        raise ExportContractError(f"{variant} candidate normalization must name three channels")
    quantization = document.get("quantization")
    if not isinstance(quantization, Mapping) or set(quantization) != {
        "variant",
        "input",
        "nonemptyConstantTensorTypes",
        "quantizedConstantCount",
    }:
        raise ExportContractError(f"{variant} candidate quantization schema is invalid")
    if quantization.get("variant") != variant:
        raise ExportContractError(f"{variant} candidate quantization declaration is invalid")
    count = quantization.get("quantizedConstantCount")
    kinds = quantization.get("nonemptyConstantTensorTypes")
    if (
        isinstance(count, bool)
        or not isinstance(count, int)
        or count < 0
        or not isinstance(kinds, list)
        or any(not isinstance(kind, str) for kind in kinds)
    ):
        raise ExportContractError(f"{variant} candidate quantization declaration is invalid")
    if variant == "float16":
        if quantization.get("input") is not None or count != 0 or "FLOAT16" not in kinds:
            raise ExportContractError("float16 candidate quantization declaration is invalid")
    else:
        _validate_quantization_parameters(
            quantization.get("input"),
            "UINT8" if dtype == "uint8" else "INT8",
            [1, 320, 320, 3],
            "int8 candidate input",
        )
        if count <= 0 or not any(kind in {"INT8", "UINT8"} for kind in kinds):
            raise ExportContractError("int8 candidate quantization declaration is invalid")
    if document.get("interpreterInvocation") != "passed":
        raise ExportContractError(f"{variant} candidate interpreter invocation is invalid")


def _validate_candidate_contract(
    candidate: Mapping[str, Any], snapshot: RunSnapshot, model_snapshots: Mapping[str, Path]
) -> Mapping[str, Any]:
    if set(candidate) != {
        "schemaVersion",
        "checkpointSha256",
        "configSha256",
        "runGitCommit",
        "splitManifestSha256",
        "trainingInputSha256",
        "syntheticManifestSha256",
        "syntheticReviewSha256",
        "syntheticGenerationConfigSha256",
        "requirementsLockSha256",
        "scoreThreshold",
        "iouThreshold",
        "thresholdProvenance",
        "labels",
        "variants",
    }:
        raise ExportContractError("candidate contract schema is invalid")
    if candidate.get("schemaVersion") != 1:
        raise ExportContractError("candidate contract schemaVersion must be 1")
    bindings = (
        ("checkpointSha256", snapshot.checkpoint_sha256, "checkpoint hash"),
        ("configSha256", snapshot.config_sha256, "config hash"),
        ("runGitCommit", snapshot.run_git_commit, "Git revision"),
        ("splitManifestSha256", snapshot.split_manifest_sha256, "split manifest hash"),
        ("trainingInputSha256", snapshot.training_input_sha256, "training input hash"),
        (
            "syntheticManifestSha256",
            snapshot.synthetic_manifest_sha256,
            "synthetic manifest hash",
        ),
        (
            "syntheticReviewSha256",
            snapshot.synthetic_review_sha256,
            "synthetic review hash",
        ),
        (
            "syntheticGenerationConfigSha256",
            snapshot.synthetic_generation_config_sha256,
            "synthetic generation config hash",
        ),
        ("requirementsLockSha256", snapshot.requirements_lock_sha256, "requirements lock hash"),
        ("scoreThreshold", snapshot.confidence_threshold, "score threshold"),
        ("iouThreshold", snapshot.iou_threshold, "IoU threshold"),
        (
            "thresholdProvenance",
            "configured-not-validation-selected",
            "threshold provenance",
        ),
        ("labels", list(CARD_LABELS), "labels"),
    )
    for field, expected, name in bindings:
        if candidate.get(field) != expected:
            raise ExportContractError(f"candidate {name} does not match selected run")
    variants = candidate.get("variants")
    if not isinstance(variants, Mapping) or set(variants) != set(_VARIANT_FILES):
        raise ExportContractError("candidate variants are invalid")
    for variant, model_path in model_snapshots.items():
        entry = variants.get(variant)
        if not isinstance(entry, Mapping):
            raise ExportContractError(f"{variant} candidate entry is invalid")
        _validate_inspection_declaration(entry, variant, _sha256(model_path))
    return variants


def promote_candidate(
    checkpoint: str | Path,
    candidates: str | Path,
    comparison: str | Path,
    output: str | Path,
    *,
    threshold_selection: str | Path | None = None,
    model_version: str | None = None,
    exported_at: str | None = None,
) -> dict[str, Any]:
    """Reinspect and promote the exact measured selected candidate without conversion."""

    run = Path(checkpoint)
    candidate_dir = Path(candidates)
    comparison_path = Path(comparison)
    _lstat(run, "checkpoint run", directory=True)
    _lstat(candidate_dir, "candidate directory", directory=True)
    _lstat(comparison_path, "comparison")
    output_path = _safe_output_path(output, [run, candidate_dir])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output_path.name}.", dir=output_path.parent))
    try:
        snapshot = _snapshot_run(run, staging, include_calibration=False)
        external_hashes: dict[Path, str] = {}
        candidate_contract_path = staging / "candidate-inputs" / "candidate-contract.json"
        candidate_contract_hash = _snapshot_external(
            candidate_dir / "candidate-contract.json",
            candidate_contract_path,
            "candidate contract",
            external_hashes,
        )
        comparison_snapshot = staging / "candidate-inputs" / "export-comparison.json"
        comparison_hash = _snapshot_external(
            comparison_path, comparison_snapshot, "comparison", external_hashes
        )
        strong_release = (
            threshold_selection is not None or model_version is not None or exported_at is not None
        )
        if strong_release and (
            threshold_selection is None or model_version is None or exported_at is None
        ):
            raise ExportContractError(
                "threshold selection, model version, and export timestamp are required together"
            )
        if strong_release:
            if not isinstance(model_version, str) or not _SAFE_NAME.fullmatch(model_version):
                raise ExportContractError("model version is invalid")
            if not isinstance(exported_at, str) or not exported_at.endswith("Z"):
                raise ExportContractError("export timestamp must be UTC RFC 3339")
            try:
                datetime.fromisoformat(exported_at[:-1] + "+00:00")
            except ValueError as error:
                raise ExportContractError("export timestamp must be UTC RFC 3339") from error
        threshold_snapshot: Path | None = None
        threshold_hash: str | None = None
        if threshold_selection is not None:
            threshold_snapshot = staging / "candidate-inputs" / "threshold-selection.json"
            threshold_hash = _snapshot_external(
                Path(threshold_selection),
                threshold_snapshot,
                "threshold selection",
                external_hashes,
            )
        model_snapshots: dict[str, Path] = {}
        for variant, filename in _VARIANT_FILES.items():
            target = staging / "candidate-inputs" / filename
            _snapshot_external(candidate_dir / filename, target, filename, external_hashes)
            model_snapshots[variant] = target
        candidate = _read_json_bytes(candidate_contract_path.read_bytes(), "candidate contract")
        candidate_variants = _validate_candidate_contract(candidate, snapshot, model_snapshots)
        comparison_document = _read_json_bytes(comparison_snapshot.read_bytes(), "comparison")
        selected, validation_hash, evidence = _validate_comparison(
            comparison_document, candidate, candidate_contract_hash
        )
        selected_snapshot = model_snapshots[selected]
        inspection = inspect_tflite(selected_snapshot, expected_quantization=selected)
        generated_inspection = dict(candidate_variants[selected])
        generated_inspection.pop("filename")
        if inspection != generated_inspection:
            raise ExportContractError("selected inspection differs from generation-time contract")
        if inspection.get("modelSha256") != evidence["variants"][selected]["modelSha256"]:
            raise ExportContractError("selected candidate hash changed after inspection")
        selected_score = snapshot.confidence_threshold
        selected_iou = snapshot.iou_threshold
        threshold_provenance = "configured-not-validation-selected"
        if threshold_snapshot is not None:
            from pontinho_cards.release import ReleaseGateError, _validate_threshold_selection

            threshold_document = _read_json_bytes(
                threshold_snapshot.read_bytes(), "threshold selection"
            )
            declared_selected = threshold_document.get("selected")
            if not isinstance(declared_selected, Mapping):
                raise ExportContractError("threshold selection is missing selected row")
            selected_score = _finite_unit(
                declared_selected.get("scoreThreshold"), "selected score threshold"
            )
            selected_iou = _finite_unit(
                declared_selected.get("iouThreshold"), "selected IoU threshold"
            )
            try:
                _validate_threshold_selection(
                    threshold_document,
                    model_hash=str(inspection["modelSha256"]),
                    checkpoint_hash=snapshot.checkpoint_sha256,
                    config_hash=snapshot.config_sha256,
                    split_hash=snapshot.split_manifest_sha256,
                    validation_hash=validation_hash,
                    score_threshold=selected_score,
                    iou_threshold=selected_iou,
                )
            except ReleaseGateError as error:
                raise ExportContractError(str(error)) from error
            threshold_provenance = "validation-selected"
        (staging / "card_detector.tflite").write_bytes(selected_snapshot.read_bytes())
        _reverify_sources({**snapshot.source_hashes, **external_hashes})
        contract = {
            "schemaVersion": 1,
            **inspection,
            "checkpointSha256": snapshot.checkpoint_sha256,
            "configSha256": snapshot.config_sha256,
            "candidateContractSha256": candidate_contract_hash,
            "comparisonSha256": comparison_hash,
            "runGitCommit": snapshot.run_git_commit,
            "splitManifestSha256": snapshot.split_manifest_sha256,
            "trainingInputSha256": snapshot.training_input_sha256,
            "syntheticManifestSha256": snapshot.synthetic_manifest_sha256,
            "syntheticReviewSha256": snapshot.synthetic_review_sha256,
            "syntheticGenerationConfigSha256": snapshot.synthetic_generation_config_sha256,
            "requirementsLockSha256": snapshot.requirements_lock_sha256,
            "validationManifestSha256": validation_hash,
            "scoreThreshold": selected_score,
            "iouThreshold": selected_iou,
            "thresholdProvenance": threshold_provenance,
            "selectionEvidence": evidence,
        }
        if threshold_hash is not None:
            contract.update(
                {
                    "thresholdSelectionSha256": threshold_hash,
                    "modelVersion": model_version,
                    "exportedAt": exported_at,
                }
            )
        _atomic_write(
            staging / "model-contract.json",
            (json.dumps(contract, indent=2, sort_keys=True) + "\n").encode(),
        )
        _publish_reserved(staging, output_path, ["card_detector.tflite", "model-contract.json"])
        return contract
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _tensor_type_names(schema: Any) -> dict[int, str]:
    return {
        value: name
        for name, value in vars(schema.TensorType).items()
        if name.isupper() and isinstance(value, int)
    }


def _builtin_operator_names(schema: Any) -> dict[int, str]:
    return {
        value: name
        for name, value in vars(schema.BuiltinOperator).items()
        if name.isupper() and isinstance(value, int)
    }


def _flatbuffer_graph(model_path: Path) -> GraphContract:
    try:
        from tflite_support import schema_py_generated as schema

        model = schema.Model.GetRootAsModel(model_path.read_bytes(), 0)
        subgraph = model.Subgraphs(0)
    except (OSError, AttributeError, TypeError, ValueError) as error:
        raise ExportContractError(f"invalid TFLite flatbuffer: {error}") from error
    if model.SubgraphsLength() != 1 or subgraph.InputsLength() != 1:
        raise ExportContractError("TFLite model must contain one subgraph and one input")
    type_names = _tensor_type_names(schema)
    tensor_names: dict[int, str] = {}
    shapes: dict[int, list[int]] = {}
    signatures: dict[int, list[int]] = {}
    tensor_types: dict[int, str] = {}
    constant_types: list[str] = []
    constant_indices: list[int] = []
    constant_tensor_types: dict[int, str] = {}
    constant_consumers: dict[int, list[dict[str, Any]]] = {}
    constant_quantization: dict[int, dict[str, Any]] = {}
    quantized_constants = 0
    for index in range(subgraph.TensorsLength()):
        tensor = subgraph.Tensors(index)
        name = tensor.Name()
        tensor_names[index] = name.decode("utf-8") if name else ""
        shapes[index] = [tensor.Shape(i) for i in range(tensor.ShapeLength())]
        signature = [tensor.ShapeSignature(i) for i in range(tensor.ShapeSignatureLength())]
        signatures[index] = signature or list(shapes[index])
        tensor_type = type_names.get(tensor.Type(), f"UNKNOWN_{tensor.Type()}")
        tensor_types[index] = tensor_type
        buffer = model.Buffers(tensor.Buffer())
        if buffer is not None and buffer.DataLength() > 0:
            constant_indices.append(index)
            constant_types.append(tensor_type)
            constant_tensor_types[index] = tensor_type
            constant_consumers[index] = []
            quantization = tensor.Quantization()
            constant_quantization[index] = {
                "scales": (
                    [float(quantization.Scale(i)) for i in range(quantization.ScaleLength())]
                    if quantization
                    else []
                ),
                "zeroPoints": (
                    [int(quantization.ZeroPoint(i)) for i in range(quantization.ZeroPointLength())]
                    if quantization
                    else []
                ),
                "quantizedDimension": int(quantization.QuantizedDimension()) if quantization else 0,
            }
            if tensor_type in {"INT8", "UINT8"} and constant_quantization[index]["scales"]:
                quantized_constants += 1
    custom_ops: list[str] = []
    builtin_names = _builtin_operator_names(schema)
    postprocess: list[int] | None = None
    postprocess_inputs: list[int] | None = None
    for index in range(subgraph.OperatorsLength()):
        operator = subgraph.Operators(index)
        opcode = model.OperatorCodes(operator.OpcodeIndex())
        custom = opcode.CustomCode()
        custom_name = custom.decode("utf-8") if custom else ""
        operator_name = custom_name or builtin_names.get(
            opcode.BuiltinCode(), f"UNKNOWN_BUILTIN_{opcode.BuiltinCode()}"
        )
        for input_position in range(operator.InputsLength()):
            tensor_index = operator.Inputs(input_position)
            if tensor_index in constant_consumers:
                constant_consumers[tensor_index].append(
                    {"op": operator_name, "inputPosition": input_position}
                )
        if custom_name:
            custom_ops.append(custom_name)
        if custom_name == "TFLite_Detection_PostProcess":
            if postprocess is not None:
                raise ExportContractError("model contains multiple DetectionPostProcess ops")
            postprocess = [operator.Outputs(i) for i in range(operator.OutputsLength())]
            postprocess_inputs = [operator.Inputs(i) for i in range(operator.InputsLength())]
    if postprocess is None:
        raise ExportContractError("model is missing TFLite_Detection_PostProcess")
    input_index = subgraph.Inputs(0)
    input_tensor = subgraph.Tensors(input_index)
    quantization = input_tensor.Quantization()
    scales = (
        [float(quantization.Scale(i)) for i in range(quantization.ScaleLength())]
        if quantization
        else []
    )
    zero_points = (
        [int(quantization.ZeroPoint(i)) for i in range(quantization.ZeroPointLength())]
        if quantization
        else []
    )
    return GraphContract(
        input_index=input_index,
        graph_output_indices=[subgraph.Outputs(i) for i in range(subgraph.OutputsLength())],
        postprocess_output_indices=postprocess,
        tensor_names=tensor_names,
        shapes=shapes,
        shape_signatures=signatures,
        tensor_types=tensor_types,
        constant_tensor_types=constant_tensor_types,
        constant_consumers=constant_consumers,
        postprocess_input_indices=postprocess_inputs or [],
        nonempty_constant_indices=constant_indices,
        nonempty_constant_tensor_types=constant_types,
        quantized_constant_count=quantized_constants,
        constant_quantization=constant_quantization,
        input_quantization={
            "scales": scales,
            "zeroPoints": zero_points,
            "quantizedDimension": int(quantization.QuantizedDimension()) if quantization else 0,
        },
        custom_ops=custom_ops,
    )


def _validate_quantization_parameters(
    metadata: Any, tensor_type: str, shape: Any, name: str
) -> None:
    if not isinstance(metadata, Mapping):
        raise ExportContractError(f"{name} quantization metadata is missing")
    scales = metadata.get("scales")
    zero_points = metadata.get("zeroPoints")
    dimension = metadata.get("quantizedDimension")
    if not isinstance(scales, list) or not scales:
        raise ExportContractError(f"{name} requires positive finite scales")
    if any(
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
        or float(value) <= 0
        for value in scales
    ):
        raise ExportContractError(f"{name} scales must be positive finite numbers")
    if not isinstance(zero_points, list) or len(zero_points) != len(scales):
        raise ExportContractError(f"{name} scale and zero-point cardinality must match")
    ranges = {
        "INT8": (-128, 127),
        "UINT8": (0, 255),
        "INT32": (-(2**31), 2**31 - 1),
    }
    if tensor_type not in ranges:
        raise ExportContractError(f"{name} has unsupported quantized type {tensor_type}")
    lower, upper = ranges[tensor_type]
    if any(
        isinstance(value, bool) or not isinstance(value, int) or not lower <= value <= upper
        for value in zero_points
    ):
        raise ExportContractError(f"{name} zero points are outside the {tensor_type} range")
    if (
        isinstance(dimension, bool)
        or not isinstance(dimension, int)
        or not isinstance(shape, list)
        or not 0 <= dimension < len(shape)
    ):
        raise ExportContractError(f"{name} quantized dimension is invalid")
    if len(scales) > 1 and shape[dimension] != len(scales):
        raise ExportContractError(f"{name} per-axis scale cardinality does not match its dimension")


def _validate_int32_constant_role(graph: Any, index: int) -> None:
    name = f"INT32 constant tensor {index}"
    shape = graph.shapes.get(index)
    consumers = graph.constant_consumers.get(index)
    quantization = graph.constant_quantization.get(index)
    if not isinstance(consumers, list) or not consumers:
        raise ExportContractError(f"{name} must have a narrowly identified consumer")
    bias_ops = {"CONV_2D", "DEPTHWISE_CONV_2D", "FULLY_CONNECTED"}
    is_bias = all(
        isinstance(consumer, Mapping)
        and consumer.get("op") in bias_ops
        and consumer.get("inputPosition") == 2
        for consumer in consumers
    )
    if is_bias:
        if not isinstance(shape, list) or len(shape) != 1 or shape[0] <= 0:
            raise ExportContractError(f"{name} bias shape is invalid")
        _validate_quantization_parameters(quantization, "INT32", shape, name)
        if any(value != 0 for value in quantization["zeroPoints"]):
            raise ExportContractError(f"{name} bias zero points must be zero")
        return
    shape_inputs = {
        "RESHAPE": {1},
        "RESIZE_BILINEAR": {1},
        "RESIZE_NEAREST_NEIGHBOR": {1},
        "SLICE": {1, 2},
        "STRIDED_SLICE": {1, 2, 3},
    }
    is_shape = all(
        isinstance(consumer, Mapping)
        and consumer.get("op") in shape_inputs
        and consumer.get("inputPosition") in shape_inputs[str(consumer.get("op"))]
        for consumer in consumers
    )
    scales = quantization.get("scales") if isinstance(quantization, Mapping) else None
    zero_points = quantization.get("zeroPoints") if isinstance(quantization, Mapping) else None
    if (
        not is_shape
        or not isinstance(shape, list)
        or len(shape) != 1
        or not isinstance(shape[0], int)
        or not 1 <= shape[0] <= 8
        or scales != []
        or zero_points != []
    ):
        raise ExportContractError(f"{name} is outside the allowed bias/shape roles")


def _classify_int8_constants(graph: Any) -> None:
    all_constants = set(graph.constant_tensor_types)
    observed_indices = graph.nonempty_constant_indices
    if (
        not isinstance(observed_indices, list)
        or len(observed_indices) != len(all_constants)
        or len(set(observed_indices)) != len(observed_indices)
        or set(observed_indices) != all_constants
        or len(graph.nonempty_constant_tensor_types) != len(all_constants)
    ):
        raise ExportContractError("nonempty constant index accounting is inconsistent")
    classified: set[int] = set()
    for index, tensor_type in graph.constant_tensor_types.items():
        if tensor_type in {"INT8", "UINT8"}:
            _validate_quantization_parameters(
                graph.constant_quantization.get(index),
                tensor_type,
                graph.shapes.get(index),
                f"constant tensor {index}",
            )
        elif tensor_type == "INT32":
            _validate_int32_constant_role(graph, index)
        elif tensor_type == "FLOAT32":
            consumers = graph.constant_consumers.get(index)
            if index not in graph.postprocess_input_indices or consumers != [
                {"op": "TFLite_Detection_PostProcess", "inputPosition": 2}
            ]:
                raise ExportContractError(
                    f"FLOAT32 DetectionPostProcess constant tensor {index} is not exclusive"
                )
        elif tensor_type == "FLOAT16":
            raise ExportContractError("int8 model cannot contain float16 constants")
        else:
            raise ExportContractError(
                f"unsupported nonempty constant tensor {index} type {tensor_type}"
            )
        classified.add(index)
    if classified != all_constants:
        raise ExportContractError("a nonempty constant escaped int8 classification")


def _validate_graph_contract(graph: Any, *, expected_quantization: str) -> None:
    if expected_quantization not in _VARIANT_FILES:
        raise ExportContractError("expected quantization must be float16 or int8")
    if graph.custom_ops != ["TFLite_Detection_PostProcess"]:
        raise ExportContractError("model custom ops must be exactly DetectionPostProcess")
    if len(graph.postprocess_output_indices) != 4:
        raise ExportContractError("DetectionPostProcess must expose exactly four outputs")
    if set(graph.graph_output_indices) != set(graph.postprocess_output_indices):
        raise ExportContractError("graph outputs must be exactly DetectionPostProcess outputs")
    indices = [graph.input_index, *graph.postprocess_output_indices]
    if any(not graph.tensor_names.get(index) for index in indices) or len(
        {graph.tensor_names[index] for index in indices}
    ) != len(indices):
        raise ExportContractError("input and output tensor names must be non-empty and unique")
    if graph.shapes.get(graph.input_index) != [1, 320, 320, 3]:
        raise ExportContractError("input shape must be [1, 320, 320, 3]")
    if graph.shape_signatures.get(graph.input_index) != [1, 320, 320, 3]:
        raise ExportContractError("input shape_signature must be fixed [1, 320, 320, 3]")
    boxes_index, classes_index, scores_index, count_index = graph.postprocess_output_indices
    boxes_shape = graph.shapes.get(boxes_index)
    max_results = (
        boxes_shape[1]
        if isinstance(boxes_shape, list)
        and len(boxes_shape) == 3
        and boxes_shape[0] == 1
        and boxes_shape[2] == 4
        and isinstance(boxes_shape[1], int)
        and boxes_shape[1] > 0
        else None
    )
    expected_shapes = (
        [1, max_results, 4],
        [1, max_results],
        [1, max_results],
        [1],
    )
    output_indices = (boxes_index, classes_index, scores_index, count_index)
    for tensor_index, expected_shape in zip(output_indices, expected_shapes):
        if graph.shapes.get(tensor_index) != expected_shape:
            actual_shapes = [graph.shapes.get(index) for index in output_indices]
            raise ExportContractError(
                f"DetectionPostProcess output shapes are incompatible: {actual_shapes}"
            )
        if graph.shape_signatures.get(tensor_index) != expected_shape:
            raise ExportContractError("DetectionPostProcess shape_signature must be fixed")
        if graph.tensor_types.get(tensor_index) != "FLOAT32":
            raise ExportContractError("DetectionPostProcess outputs must be float32")
    constants = graph.nonempty_constant_tensor_types
    non_postprocess_float_constants = {
        index: tensor_type
        for index, tensor_type in graph.constant_tensor_types.items()
        if index not in graph.postprocess_input_indices and tensor_type in {"FLOAT16", "FLOAT32"}
    }
    if expected_quantization == "float16":
        if graph.tensor_types.get(graph.input_index) != "FLOAT32":
            raise ExportContractError("float16 model input must remain float32")
        if "FLOAT16" not in constants:
            raise ExportContractError("float16 model requires float16 constant buffers")
        if any(kind in {"INT8", "UINT8"} for kind in constants):
            raise ExportContractError("float16 model cannot contain integer-quantized constants")
        if any(
            tensor_type != "FLOAT16" for tensor_type in non_postprocess_float_constants.values()
        ):
            raise ExportContractError(
                "float16 model has a non-postprocess floating constant that is not float16"
            )
    else:
        input_type = graph.tensor_types.get(graph.input_index)
        if input_type not in {"INT8", "UINT8"}:
            raise ExportContractError("int8 model input must be int8 or uint8")
        _validate_quantization_parameters(
            graph.input_quantization,
            input_type,
            graph.shapes.get(graph.input_index),
            "int8 input",
        )
        byte_constants = {
            index
            for index, tensor_type in graph.constant_tensor_types.items()
            if tensor_type in {"INT8", "UINT8"}
        }
        if not byte_constants:
            raise ExportContractError("int8 model requires quantized constant buffers")
        _classify_int8_constants(graph)
        if graph.quantized_constant_count != len(byte_constants):
            raise ExportContractError("int8 model quantized constant count is inconsistent")


def _expand_rgb(values: Any, name: str, *, positive: bool = False) -> list[float]:
    if not isinstance(values, list) or len(values) not in {1, 3}:
        raise ExportContractError(f"{name} must define one scalar or three RGB channels")
    try:
        normalized = [float(value) for value in values]
    except (TypeError, ValueError) as error:
        raise ExportContractError(f"{name} must contain finite numbers") from error
    if any(not math.isfinite(value) for value in normalized):
        raise ExportContractError(f"{name} must contain finite numbers")
    if positive and any(value <= 0 for value in normalized):
        raise ExportContractError(f"{name} must contain positive values")
    return normalized * 3 if len(normalized) == 1 else normalized


def _metadata_contract_from_document(
    metadata: Mapping[str, Any],
    associated_files: Mapping[str, bytes],
    *,
    graph_output_indices: list[int],
    postprocess_output_indices: list[int],
) -> tuple[list[str], dict[str, list[float]], dict[str, str]]:
    try:
        subgraph = metadata["subgraph_metadata"][0]
        input_metadata = subgraph["input_tensor_metadata"][0]
        output_metadata = subgraph["output_tensor_metadata"]
    except (KeyError, IndexError, TypeError) as error:
        raise ExportContractError("TFLite tensor metadata is incomplete") from error
    if not isinstance(output_metadata, list) or len(output_metadata) != len(graph_output_indices):
        raise ExportContractError("output metadata must align with every graph output")
    semantic_names = ("boxes", "classes", "scores", "count")
    metadata_names: dict[str, str] = {}
    class_metadata: Mapping[str, Any] | None = None
    for semantic, tensor_index in zip(semantic_names, postprocess_output_indices):
        try:
            position = graph_output_indices.index(tensor_index)
            item = output_metadata[position]
        except (ValueError, IndexError) as error:
            raise ExportContractError("output metadata order does not match the graph") from error
        if not isinstance(item, Mapping) or item.get("name") != _METADATA_NAMES[semantic]:
            raise ExportContractError(f"{semantic} output metadata name is incompatible")
        metadata_names[semantic] = str(item["name"])
        if semantic == "classes":
            class_metadata = item
    if class_metadata is None:
        raise ExportContractError("class-output metadata is missing")
    references = class_metadata.get("associated_files")
    if not isinstance(references, list) or len(references) != 1:
        raise ExportContractError("class-output metadata must reference one labels file")
    reference = references[0]
    if not isinstance(reference, Mapping) or not isinstance(reference.get("name"), str):
        raise ExportContractError("class-output metadata labels reference is invalid")
    label_payload = associated_files.get(reference["name"])
    if label_payload is None:
        raise ExportContractError("class-output metadata labels file is missing")
    try:
        labels = label_payload.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ExportContractError("class-output metadata labels are not UTF-8") from error
    if labels != list(CARD_LABELS):
        raise ExportContractError("class-output metadata labels do not match CARD_LABELS")
    units = input_metadata.get("process_units") if isinstance(input_metadata, Mapping) else None
    if not isinstance(units, list):
        raise ExportContractError("input normalization metadata is missing")
    options: Mapping[str, Any] | None = None
    for unit in units:
        candidate = unit.get("options") if isinstance(unit, Mapping) else None
        if isinstance(candidate, Mapping) and "mean" in candidate and "std" in candidate:
            options = candidate
            break
    if options is None:
        raise ExportContractError("input normalization metadata is missing")
    normalization = {
        "mean": _expand_rgb(options.get("mean"), "normalization mean"),
        "std": _expand_rgb(options.get("std"), "normalization std", positive=True),
    }
    return labels, normalization, metadata_names


def _metadata_contract(
    model_path: Path, graph: GraphContract
) -> tuple[list[str], dict[str, list[float]], dict[str, str]]:
    try:
        from tflite_support import metadata as metadata_api

        display = metadata_api.MetadataDisplayer.with_model_file(str(model_path))
        document = json.loads(display.get_metadata_json())
        files = {
            name: bytes(display.get_associated_file_buffer(name))
            for name in display.get_packed_associated_file_list()
        }
    except (ImportError, OSError, ValueError, json.JSONDecodeError, RuntimeError) as error:
        raise ExportContractError(f"TFLite metadata is missing or unreadable: {error}") from error
    if not isinstance(document, Mapping):
        raise ExportContractError("TFLite metadata root must be an object")
    return _metadata_contract_from_document(
        document,
        files,
        graph_output_indices=graph.graph_output_indices,
        postprocess_output_indices=graph.postprocess_output_indices,
    )


def inspect_tflite(model_path: str | Path, *, expected_quantization: str) -> dict[str, Any]:
    """Inspect the real graph/metadata and execute one fixed-shape image."""

    path = Path(model_path)
    graph = _flatbuffer_graph(path)
    try:
        import tensorflow as tf

        interpreter = tf.lite.Interpreter(
            model_path=str(path),
            experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
        )
        interpreter.allocate_tensors()
        inputs = interpreter.get_input_details()
        outputs = interpreter.get_output_details()
    except (ImportError, OSError, RuntimeError, ValueError) as error:
        raise ExportContractError(f"TFLite interpreter rejected the model: {error}") from error
    if len(inputs) != 1:
        raise ExportContractError("TFLite model must expose exactly one image input")
    by_index = {int(detail["index"]): detail for detail in outputs}
    input_detail = inputs[0]
    if int(input_detail["index"]) != graph.input_index:
        raise ExportContractError("interpreter input does not match FlatBuffer input")
    runtime_details = {graph.input_index: input_detail, **by_index}
    runtime_shapes = dict(graph.shapes)
    runtime_signatures = dict(graph.shape_signatures)
    for index in [graph.input_index, *graph.graph_output_indices]:
        detail = runtime_details.get(index)
        if detail is None:
            raise ExportContractError("FlatBuffer input/output is missing from interpreter")
        runtime_shapes[index] = [int(value) for value in detail["shape"]]
        runtime_signatures[index] = [
            int(value) for value in detail.get("shape_signature", detail["shape"])
        ]
    graph = replace(
        graph,
        shapes=runtime_shapes,
        shape_signatures=runtime_signatures,
    )
    _validate_graph_contract(graph, expected_quantization=expected_quantization)
    labels, normalization, metadata_names = _metadata_contract(path, graph)
    semantics = ("boxes", "classes", "scores", "count")
    inspected_outputs: list[dict[str, Any]] = []
    for semantic, index in zip(semantics, graph.postprocess_output_indices):
        if index not in by_index:
            raise ExportContractError("DetectionPostProcess output is not an interpreter output")
        detail = by_index[index]
        actual_name = str(detail["name"])
        if actual_name != graph.tensor_names[index]:
            raise ExportContractError("interpreter output tensor name differs from FlatBuffer")
        inspected_outputs.append(
            {
                "semantic": semantic,
                "tensorName": actual_name,
                "metadataName": metadata_names[semantic],
                "shape": graph.shapes[index],
                "shapeSignature": graph.shape_signatures[index],
                "dtype": np.dtype(detail["dtype"]).name,
            }
        )
    try:
        interpreter.set_tensor(
            int(input_detail["index"]),
            np.zeros(graph.shapes[graph.input_index], dtype=input_detail["dtype"]),
        )
        interpreter.invoke()
        for detail in outputs:
            interpreter.get_tensor(int(detail["index"]))
    except (RuntimeError, ValueError) as error:
        raise ExportContractError(f"one-image interpreter invocation failed: {error}") from error
    return {
        "modelSha256": _sha256(path),
        "input": {
            "name": graph.tensor_names[graph.input_index],
            "shape": graph.shapes[graph.input_index],
            "shapeSignature": graph.shape_signatures[graph.input_index],
            "dtype": graph.tensor_types[graph.input_index].lower(),
        },
        "outputs": inspected_outputs,
        "customOps": graph.custom_ops,
        "labels": labels,
        "normalization": normalization,
        "quantization": {
            "variant": expected_quantization,
            "input": graph.input_quantization if graph.input_quantization["scales"] else None,
            "nonemptyConstantTensorTypes": sorted(set(graph.nonempty_constant_tensor_types)),
            "quantizedConstantCount": graph.quantized_constant_count,
        },
        "interpreterInvocation": "passed",
    }


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments and arguments[0] not in {"generate", "promote", "-h", "--help"}:
        arguments.insert(0, "generate")
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("generate", help="generate both benchmark candidates")
    generate.add_argument("--checkpoint", required=True, type=Path)
    generate.add_argument("--output", required=True, type=Path)
    promote = commands.add_parser("promote", help="promote the exact measured candidate")
    promote.add_argument("--checkpoint", required=True, type=Path)
    promote.add_argument("--candidates", required=True, type=Path)
    promote.add_argument("--comparison", required=True, type=Path)
    promote.add_argument("--threshold-selection", type=Path)
    promote.add_argument("--model-version")
    promote.add_argument("--exported-at")
    promote.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(arguments)
    try:
        if args.command == "generate":
            generate_candidates(args.checkpoint, args.output)
        else:
            promote_candidate(
                args.checkpoint,
                args.candidates,
                args.comparison,
                args.output,
                threshold_selection=args.threshold_selection,
                model_version=args.model_version,
                exported_at=args.exported_at,
            )
    except ExportContractError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
