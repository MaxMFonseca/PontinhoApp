"""Two-phase, fail-closed Android detector export tests."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

import pontinho_cards.export as export_module
from pontinho_cards.export import (
    ExportContractError,
    _metadata_contract_from_document,
    _validate_graph_contract,
    generate_candidates,
    inspect_tflite,
    main,
    promote_candidate,
)
from pontinho_cards.labels import CARD_LABELS

FLOAT16_BYTES = b"measured float16 candidate"
INT8_BYTES = b"measured int8 candidate"


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _checkpoint_run(tmp_path: Path, *, status: str = "TRAINED_NOT_RELEASE_APPROVED") -> Path:
    run = tmp_path / "checkpoints" / "run-001"
    run.mkdir(parents=True)
    checkpoint = run / "selected.weights.h5"
    checkpoint.write_bytes(b"selected checkpoint fixture")
    representative = run / "representative.jpg"
    representative.write_bytes(b"representative image fixture")
    (run / "annotations.csv").write_text(f"TRAIN,{representative},AC,0,0,,,1,1,,\n")
    (run / "resolved-config.yaml").write_text(
        yaml.safe_dump(
            {
                "schema_version": 1,
                "detector_family": "efficientdet_lite0",
                "image_size": 320,
                "confidence_threshold": 0.5,
                "iou_threshold": 0.5,
                "labels": list(CARD_LABELS),
            }
        ),
        encoding="utf-8",
    )
    (run / "run-metadata.json").write_text(
        json.dumps(
            {
                "schemaVersion": 2,
                "requirementsLockSha256": "1" * 64,
                "splitManifestSha256": "2" * 64,
                "trainingInputSha256": "5" * 64,
                "syntheticManifestSha256": "6" * 64,
                "syntheticReviewSha256": "7" * 64,
                "syntheticGenerationConfigSha256": "8" * 64,
                "gitCommit": "0f51cf2",
            }
        ),
        encoding="utf-8",
    )
    (run / "metrics.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "status": status,
                "selectedCheckpoint": checkpoint.name,
                "selectedCheckpointSha256": _sha256(checkpoint),
            }
        ),
        encoding="utf-8",
    )
    return run


def _inspection(path: Path, variant: str) -> dict[str, object]:
    return {
        "modelSha256": _sha256(path),
        "input": {
            "name": "serving_default_images:0",
            "shape": [1, 320, 320, 3],
            "shapeSignature": [1, 320, 320, 3],
            "dtype": "float32" if variant == "float16" else "uint8",
        },
        "outputs": [
            {
                "semantic": "boxes",
                "tensorName": "StatefulPartitionedCall:1",
                "metadataName": "location",
                "shape": [1, 25, 4],
                "shapeSignature": [1, 25, 4],
                "dtype": "float32",
            },
            {
                "semantic": "classes",
                "tensorName": "StatefulPartitionedCall:3",
                "metadataName": "category",
                "shape": [1, 25],
                "shapeSignature": [1, 25],
                "dtype": "float32",
            },
            {
                "semantic": "scores",
                "tensorName": "StatefulPartitionedCall:0",
                "metadataName": "score",
                "shape": [1, 25],
                "shapeSignature": [1, 25],
                "dtype": "float32",
            },
            {
                "semantic": "count",
                "tensorName": "StatefulPartitionedCall:2",
                "metadataName": "number of detections",
                "shape": [1],
                "shapeSignature": [1],
                "dtype": "float32",
            },
        ],
        "customOps": ["TFLite_Detection_PostProcess"],
        "labels": list(CARD_LABELS),
        "normalization": {"mean": [127.0] * 3, "std": [128.0] * 3},
        "quantization": {
            "variant": variant,
            "input": (
                None
                if variant == "float16"
                else {"scales": [0.5], "zeroPoints": [128], "quantizedDimension": 0}
            ),
            "nonemptyConstantTensorTypes": ["FLOAT16" if variant == "float16" else "INT8"],
            "quantizedConstantCount": 0 if variant == "float16" else 1,
        },
        "interpreterInvocation": "passed",
    }


def _fake_generate(_snapshot: object, variant: str, target: Path) -> dict[str, object]:
    target.write_bytes(FLOAT16_BYTES if variant == "float16" else INT8_BYTES)
    return _inspection(target, variant)


def _comparison(candidates: Path, *, selected: str = "float16") -> Path:
    manifest = json.loads((candidates / "candidate-contract.json").read_text())
    path = candidates.parent / "export-comparison.json"
    path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "candidateContractSha256": _sha256(candidates / "candidate-contract.json"),
                "selected": selected,
                "selectionRationale": "measured accuracy and Android latency",
                "selectedCheckpointSha256": manifest["checkpointSha256"],
                "runGitCommit": manifest["runGitCommit"],
                "splitManifestSha256": manifest["splitManifestSha256"],
                "trainingInputSha256": manifest["trainingInputSha256"],
                "syntheticManifestSha256": manifest["syntheticManifestSha256"],
                "syntheticReviewSha256": manifest["syntheticReviewSha256"],
                "syntheticGenerationConfigSha256": manifest[
                    "syntheticGenerationConfigSha256"
                ],
                "validationManifestSha256": "3" * 64,
                "androidDevice": "Pixel 8 / Android 16",
                "variants": {
                    variant: {
                        "modelSha256": manifest["variants"][variant]["modelSha256"],
                        "exactTotalAccuracy": 0.96 if variant == "float16" else 0.95,
                        "androidLatencyMs": 48.2 if variant == "float16" else 31.7,
                    }
                    for variant in ("float16", "int8")
                },
            }
        ),
        encoding="utf-8",
    )
    return path


def _candidates(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Path, Path]:
    run = _checkpoint_run(tmp_path)
    output = tmp_path / "artifacts" / "candidate-v1"
    monkeypatch.setattr(export_module, "_generate_and_inspect_variant", _fake_generate)
    generate_candidates(run, output)
    return run, output


def test_candidate_generation_atomically_emits_both_exact_measurable_variants(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a workflow that selects or regenerates a model before measurement."""

    run = _checkpoint_run(tmp_path)
    output = tmp_path / "artifacts" / "candidate-v1"
    monkeypatch.setattr(export_module, "_generate_and_inspect_variant", _fake_generate)

    contract = generate_candidates(run, output)

    assert (output / "card_detector.float16.tflite").read_bytes() == FLOAT16_BYTES
    assert (output / "card_detector.int8.tflite").read_bytes() == INT8_BYTES
    assert json.loads((output / "candidate-contract.json").read_text()) == contract
    assert contract["checkpointSha256"] == _sha256(run / "selected.weights.h5")
    assert contract["thresholdProvenance"] == "configured-not-validation-selected"
    assert contract["variants"]["float16"]["modelSha256"] == _sha256_bytes(FLOAT16_BYTES)
    assert contract["variants"]["int8"]["modelSha256"] == _sha256_bytes(INT8_BYTES)
    assert not list(output.parent.glob(".candidate-v1.*"))


def test_promotion_copies_the_exact_measured_selected_binary_without_regeneration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch regenerating a supposedly measured variant during promotion."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates, selected="int8")
    output = tmp_path / "artifacts" / "release-v1"
    monkeypatch.setattr(
        export_module,
        "inspect_tflite",
        lambda path, *, expected_quantization: _inspection(Path(path), expected_quantization),
    )
    monkeypatch.setattr(
        export_module,
        "_generate_and_inspect_variant",
        lambda *_args, **_kwargs: pytest.fail("promotion must never regenerate"),
    )
    (run / "annotations.csv").unlink()
    (run / "representative.jpg").unlink()

    contract = promote_candidate(run, candidates, comparison, output)

    assert (output / "card_detector.tflite").read_bytes() == INT8_BYTES
    assert contract["modelSha256"] == _sha256_bytes(INT8_BYTES)
    assert contract["quantization"]["variant"] == "int8"
    assert contract["comparisonSha256"] == _sha256(comparison)
    assert contract["thresholdProvenance"] == "configured-not-validation-selected"
    assert json.loads((output / "model-contract.json").read_text()) == contract


def test_release_candidate_contract_binds_recomputed_threshold_selection(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch Stage 6 emitting release provenance without the exact threshold sweep."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates, selected="int8")
    candidate = json.loads((candidates / "candidate-contract.json").read_text())
    threshold = tmp_path / "threshold-selection.json"
    selected = {
        "scoreThreshold": 0.6,
        "iouThreshold": 0.4,
        "exactHandTotalAccuracy": 0.97,
        "exactCardMultisetAccuracy": 0.96,
        "abstentionRate": 0.02,
    }
    threshold.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "modelSha256": candidate["variants"]["int8"]["modelSha256"],
                "checkpointSha256": candidate["checkpointSha256"],
                "configSha256": candidate["configSha256"],
                "splitManifestSha256": candidate["splitManifestSha256"],
                "validationManifestSha256": "3" * 64,
                "rows": [selected],
                "selected": selected,
            }
        ),
        encoding="utf-8",
    )
    output = tmp_path / "artifacts" / "release-v2"
    monkeypatch.setattr(
        export_module,
        "inspect_tflite",
        lambda path, *, expected_quantization: _inspection(Path(path), expected_quantization),
    )

    contract = promote_candidate(
        run,
        candidates,
        comparison,
        output,
        threshold_selection=threshold,
        model_version="2026.10.15-int8",
        exported_at="2026-10-15T12:00:00Z",
    )

    assert contract["thresholdProvenance"] == "validation-selected"
    assert contract["scoreThreshold"] == 0.6
    assert contract["iouThreshold"] == 0.4
    assert contract["thresholdSelectionSha256"] == _sha256(threshold)
    assert contract["modelVersion"] == "2026.10.15-int8"
    assert contract["exportedAt"] == "2026-10-15T12:00:00Z"


def test_promotion_rejects_comparison_for_any_other_candidate_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch stale evidence authorizing a changed candidate binary."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates)
    (candidates / "card_detector.float16.tflite").write_bytes(b"mutated after benchmark")

    with pytest.raises(ExportContractError, match="candidate hash"):
        promote_candidate(run, candidates, comparison, tmp_path / "artifacts" / "release-v1")
    assert not (tmp_path / "artifacts" / "release-v1").exists()


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("schemaVersion", 2, "schemaVersion"),
        ("splitManifestSha256", "9" * 64, "split manifest"),
        ("trainingInputSha256", "9" * 64, "training input"),
        ("syntheticManifestSha256", "9" * 64, "synthetic manifest"),
        ("syntheticReviewSha256", "9" * 64, "synthetic review"),
        ("requirementsLockSha256", "8" * 64, "requirements lock"),
        ("scoreThreshold", 0.75, "score threshold"),
        ("iouThreshold", 0.75, "IoU threshold"),
        ("labels", list(reversed(CARD_LABELS)), "labels"),
    ],
)
def test_promotion_rejects_candidate_contract_not_bound_to_fresh_run(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    value: object,
    message: str,
) -> None:
    """Catch benchmark evidence authorizing a contract from another training run."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    contract_path = candidates / "candidate-contract.json"
    contract = json.loads(contract_path.read_text())
    contract[field] = value
    contract_path.write_text(json.dumps(contract), encoding="utf-8")
    comparison = _comparison(candidates)

    with pytest.raises(ExportContractError, match=message):
        promote_candidate(run, candidates, comparison, tmp_path / "artifacts" / "release-v1")


def test_promotion_rejects_mutated_candidate_inspection_declaration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a valid binary paired with altered generated contract declarations."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    contract_path = candidates / "candidate-contract.json"
    contract = json.loads(contract_path.read_text())
    contract["variants"]["float16"]["normalization"]["std"] = [0.0, 0.0, 0.0]
    contract_path.write_text(json.dumps(contract), encoding="utf-8")
    comparison = _comparison(candidates)

    with pytest.raises(ExportContractError, match="normalization std"):
        promote_candidate(run, candidates, comparison, tmp_path / "artifacts" / "release-v1")


def test_promotion_rejects_fresh_inspection_that_differs_from_generated_contract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch promotion silently replacing generation-time inspection facts."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates)

    def changed_inspection(path: Path, *, expected_quantization: str) -> dict[str, object]:
        result = _inspection(Path(path), expected_quantization)
        result["input"]["name"] = "changed:0"  # type: ignore[index]
        return result

    monkeypatch.setattr(export_module, "inspect_tflite", changed_inspection)
    with pytest.raises(ExportContractError, match="generation-time contract"):
        promote_candidate(run, candidates, comparison, tmp_path / "artifacts" / "release-v1")


def test_original_generate_cli_remains_a_backward_compatible_alias(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[tuple[Path, Path]] = []
    monkeypatch.setattr(
        export_module,
        "generate_candidates",
        lambda checkpoint, output: calls.append((checkpoint, output)),
    )

    assert main(["--checkpoint", str(tmp_path / "run"), "--output", str(tmp_path / "out")]) == 0
    assert calls == [(tmp_path / "run", tmp_path / "out")]


@pytest.mark.parametrize("target", ["run", "weights", "comparison"])
def test_symlinked_security_inputs_are_rejected_before_read(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, target: str
) -> None:
    """Catch resolve-before-lstat accepting attacker-controlled links."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates)
    if target == "run":
        link = tmp_path / "run-link"
        link.symlink_to(run, target_is_directory=True)
        with pytest.raises(ExportContractError, match="symbolic link"):
            generate_candidates(link, tmp_path / "artifacts" / "other")
    elif target == "weights":
        weights = run / "selected.weights.h5"
        real = run / "real.weights.h5"
        weights.replace(real)
        weights.symlink_to(real)
        with pytest.raises(ExportContractError, match="symbolic link"):
            generate_candidates(run, tmp_path / "artifacts" / "other")
    else:
        real = comparison.with_name("real-comparison.json")
        comparison.replace(real)
        comparison.symlink_to(real)
        with pytest.raises(ExportContractError, match="symbolic link"):
            promote_candidate(run, candidates, comparison, tmp_path / "artifacts" / "release-v1")


def test_source_mutation_after_snapshot_aborts_without_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch TOCTOU changes between validation, conversion, and publication."""

    run = _checkpoint_run(tmp_path)
    output = tmp_path / "artifacts" / "candidate-v1"

    def mutate_source(snapshot: object, variant: str, target: Path) -> dict[str, object]:
        if variant == "float16":
            (run / "selected.weights.h5").write_bytes(b"changed during conversion")
        return _fake_generate(snapshot, variant, target)

    monkeypatch.setattr(export_module, "_generate_and_inspect_variant", mutate_source)
    with pytest.raises(ExportContractError, match="changed during export"):
        generate_candidates(run, output)
    assert not output.exists()
    assert not list(output.parent.glob(".candidate-v1.*"))


def test_comparison_mutation_after_snapshot_aborts_promotion(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch comparison evidence changing while the selected model is reinspected."""

    run, candidates = _candidates(tmp_path, monkeypatch)
    comparison = _comparison(candidates)

    def mutate_comparison(path: Path, *, expected_quantization: str) -> dict[str, object]:
        comparison.write_text("{}", encoding="utf-8")
        return _inspection(Path(path), expected_quantization)

    monkeypatch.setattr(export_module, "inspect_tflite", mutate_comparison)

    with pytest.raises(ExportContractError, match="changed during export"):
        promote_candidate(
            run,
            candidates,
            comparison,
            tmp_path / "artifacts" / "release-v1",
        )

    assert not (tmp_path / "artifacts" / "release-v1").exists()


def test_concurrent_target_reservation_never_replaces_the_other_writer(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch check-then-rename clobbering a concurrently created target."""

    run = _checkpoint_run(tmp_path)
    output = tmp_path / "artifacts" / "candidate-v1"

    def concurrent_writer(snapshot: object, variant: str, target: Path) -> dict[str, object]:
        if variant == "int8":
            output.mkdir(parents=True)
            (output / "owner.txt").write_text("other writer", encoding="utf-8")
        return _fake_generate(snapshot, variant, target)

    monkeypatch.setattr(export_module, "_generate_and_inspect_variant", concurrent_writer)
    with pytest.raises(ExportContractError, match="reserve output"):
        generate_candidates(run, output)
    assert (output / "owner.txt").read_text(encoding="utf-8") == "other writer"


def _metadata(mean: list[float], std: list[float], label_file: str) -> dict[str, object]:
    return {
        "subgraph_metadata": [
            {
                "input_tensor_metadata": [
                    {"process_units": [{"options": {"mean": mean, "std": std}}]}
                ],
                "output_tensor_metadata": [
                    {"name": "score"},
                    {"name": "location"},
                    {"name": "number of detections"},
                    {"name": "category", "associated_files": [{"name": label_file}]},
                ],
            }
        ]
    }


def test_class_labels_follow_the_class_tensor_associated_file_not_a_decoy() -> None:
    """Catch canonical decoy labels hiding a reversed true class map."""

    files = {
        "actual-labels.txt": "\n".join(reversed(CARD_LABELS)).encode(),
        "canonical-decoy.txt": "\n".join(CARD_LABELS).encode(),
    }
    with pytest.raises(ExportContractError, match="class-output metadata labels"):
        _metadata_contract_from_document(
            _metadata([127.0], [128.0], "actual-labels.txt"),
            files,
            graph_output_indices=[10, 11, 12, 13],
            postprocess_output_indices=[11, 13, 10, 12],
        )


@pytest.mark.parametrize(
    ("mean", "std"), [([127.0, 127.0], [128.0, 128.0]), ([127.0] * 3, [128.0, 0.0, 128.0])]
)
def test_normalization_must_expand_to_three_channels_with_positive_std(
    mean: list[float], std: list[float]
) -> None:
    """Catch malformed metadata that cannot define RGB preprocessing exactly."""

    with pytest.raises(ExportContractError, match="three RGB channels|positive"):
        _metadata_contract_from_document(
            _metadata(mean, std, "labels.txt"),
            {"labels.txt": "\n".join(CARD_LABELS).encode()},
            graph_output_indices=[10, 11, 12, 13],
            postprocess_output_indices=[11, 13, 10, 12],
        )


def _graph(**changes: object) -> SimpleNamespace:
    base = {
        "input_index": 1,
        "graph_output_indices": [10, 11, 12, 13],
        "postprocess_output_indices": [11, 13, 10, 12],
        "tensor_names": {
            1: "serving_default_images:0",
            10: "StatefulPartitionedCall:0",
            11: "StatefulPartitionedCall:1",
            12: "StatefulPartitionedCall:2",
            13: "StatefulPartitionedCall:3",
        },
        "shapes": {1: [1, 320, 320, 3], 10: [1, 25], 11: [1, 25, 4], 12: [1], 13: [1, 25]},
        "shape_signatures": {
            1: [1, 320, 320, 3],
            10: [1, 25],
            11: [1, 25, 4],
            12: [1],
            13: [1, 25],
        },
        "tensor_types": {
            1: "FLOAT32",
            10: "FLOAT32",
            11: "FLOAT32",
            12: "FLOAT32",
            13: "FLOAT32",
            20: "FLOAT16",
        },
        "constant_tensor_types": {20: "FLOAT16"},
        "constant_consumers": {20: [{"op": "CONV_2D", "inputPosition": 1}]},
        "postprocess_input_indices": [30, 31, 20],
        "nonempty_constant_indices": [20],
        "nonempty_constant_tensor_types": ["FLOAT16", "FLOAT16"],
        "quantized_constant_count": 0,
        "constant_quantization": {20: {"scales": [], "zeroPoints": [], "quantizedDimension": 0}},
        "input_quantization": {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
        "custom_ops": ["TFLite_Detection_PostProcess"],
    }
    base.update(changes)
    return SimpleNamespace(**base)


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        ({"shape_signatures": {1: [-1, 320, 320, 3]}}, "shape_signature"),
        ({"tensor_names": {1: "", 10: "a", 11: "b", 12: "c", 13: "d"}}, "tensor names"),
        ({"nonempty_constant_tensor_types": ["FLOAT32"]}, "float16 constant"),
        (
            {
                "constant_tensor_types": {20: "FLOAT16", 21: "FLOAT32"},
                "nonempty_constant_tensor_types": ["FLOAT16", "FLOAT32"],
            },
            "non-postprocess floating constant",
        ),
        ({"custom_ops": ["TFLite_Detection_PostProcess", "Mystery"]}, "custom ops"),
    ],
)
def test_graph_contract_rejects_dynamic_shapes_names_and_partial_quantization(
    mutation: dict[str, object], message: str
) -> None:
    """Catch superficially compatible tensors hiding an incompatible graph."""

    with pytest.raises(ExportContractError, match=message):
        _validate_graph_contract(_graph(**mutation), expected_quantization="float16")


def test_int8_graph_requires_model_wide_quantized_constants() -> None:
    """Catch an integer-looking input on a model whose weights stayed float."""

    graph = _graph(
        tensor_types={1: "UINT8", 10: "FLOAT32", 11: "FLOAT32", 12: "FLOAT32", 13: "FLOAT32"},
        constant_tensor_types={20: "FLOAT32"},
        nonempty_constant_tensor_types=["FLOAT32"],
        quantized_constant_count=0,
        constant_quantization={20: {"scales": [], "zeroPoints": [], "quantizedDimension": 0}},
        input_quantization={"scales": [0.5], "zeroPoints": [128], "quantizedDimension": 0},
    )
    with pytest.raises(ExportContractError, match="quantized constant"):
        _validate_graph_contract(graph, expected_quantization="int8")


def _int8_graph(**changes: object) -> SimpleNamespace:
    base = {
        "tensor_types": {
            1: "UINT8",
            10: "FLOAT32",
            11: "FLOAT32",
            12: "FLOAT32",
            13: "FLOAT32",
            20: "INT8",
            21: "UINT8",
            22: "INT32",
        },
        "shapes": {
            1: [1, 320, 320, 3],
            10: [1, 25],
            11: [1, 25, 4],
            12: [1],
            13: [1, 25],
            20: [2, 2],
            21: [2],
            22: [1],
        },
        "constant_tensor_types": {20: "INT8", 21: "UINT8", 22: "INT32"},
        "nonempty_constant_indices": [20, 21, 22],
        "constant_consumers": {
            20: [{"op": "CONV_2D", "inputPosition": 1}],
            21: [{"op": "CONV_2D", "inputPosition": 1}],
            22: [{"op": "RESIZE_NEAREST_NEIGHBOR", "inputPosition": 1}],
        },
        "nonempty_constant_tensor_types": ["INT8", "UINT8", "INT32"],
        "quantized_constant_count": 2,
        "constant_quantization": {
            20: {"scales": [0.1, 0.2], "zeroPoints": [0, 0], "quantizedDimension": 0},
            21: {"scales": [0.3], "zeroPoints": [128], "quantizedDimension": 0},
            22: {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
        },
        "input_quantization": {
            "scales": [0.5],
            "zeroPoints": [128],
            "quantizedDimension": 0,
        },
    }
    base.update(changes)
    return _graph(**base)


def test_int8_graph_accepts_every_quantized_byte_constant_and_explicit_int32_exception() -> None:
    _validate_graph_contract(_int8_graph(), expected_quantization="int8")


@pytest.mark.parametrize("tensor_type", ["INT64", "BOOL", "UINT16", "UNKNOWN_255"])
def test_int8_graph_rejects_every_unclassified_nonempty_constant(tensor_type: str) -> None:
    graph = _int8_graph(
        tensor_types={**_int8_graph().tensor_types, 23: tensor_type},
        shapes={**_int8_graph().shapes, 23: [1]},
        constant_tensor_types={**_int8_graph().constant_tensor_types, 23: tensor_type},
        nonempty_constant_indices=[*_int8_graph().nonempty_constant_indices, 23],
        constant_consumers={
            **_int8_graph().constant_consumers,
            23: [{"op": "RESHAPE", "inputPosition": 1}],
        },
        nonempty_constant_tensor_types=[
            *_int8_graph().nonempty_constant_tensor_types,
            tensor_type,
        ],
        constant_quantization={
            **_int8_graph().constant_quantization,
            23: {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
        },
    )
    with pytest.raises(ExportContractError, match="unsupported nonempty constant"):
        _validate_graph_contract(graph, expected_quantization="int8")


def test_int8_graph_rejects_nonempty_constant_index_that_escaped_inventory() -> None:
    graph = _int8_graph(nonempty_constant_indices=[20, 21, 22, 23])
    with pytest.raises(ExportContractError, match="index accounting"):
        _validate_graph_contract(graph, expected_quantization="int8")


@pytest.mark.parametrize(
    ("consumers", "shape"),
    [
        ([{"op": "CONV_2D", "inputPosition": 1}], [1]),
        ([{"op": "RESHAPE", "inputPosition": 1}], [9]),
        ([], [1]),
    ],
)
def test_int8_graph_rejects_int32_constant_outside_narrow_shape_role(
    consumers: list[dict[str, object]], shape: list[int]
) -> None:
    graph = _int8_graph(
        shapes={**_int8_graph().shapes, 22: shape},
        constant_consumers={**_int8_graph().constant_consumers, 22: consumers},
    )
    with pytest.raises(ExportContractError, match="INT32 constant tensor 22"):
        _validate_graph_contract(graph, expected_quantization="int8")


def test_int8_graph_rejects_float32_constant_not_exclusive_to_detection_postprocess() -> None:
    graph = _int8_graph(
        tensor_types={**_int8_graph().tensor_types, 23: "FLOAT32"},
        shapes={**_int8_graph().shapes, 23: [4]},
        constant_tensor_types={**_int8_graph().constant_tensor_types, 23: "FLOAT32"},
        constant_consumers={
            **_int8_graph().constant_consumers,
            23: [
                {"op": "TFLite_Detection_PostProcess", "inputPosition": 2},
                {"op": "ADD", "inputPosition": 1},
            ],
        },
        postprocess_input_indices=[30, 31, 23],
        nonempty_constant_indices=[*_int8_graph().nonempty_constant_indices, 23],
        nonempty_constant_tensor_types=[
            *_int8_graph().nonempty_constant_tensor_types,
            "FLOAT32",
        ],
        constant_quantization={
            **_int8_graph().constant_quantization,
            23: {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
        },
    )
    with pytest.raises(ExportContractError, match="DetectionPostProcess constant"):
        _validate_graph_contract(graph, expected_quantization="int8")


def test_int8_graph_rejects_mixed_quantized_and_unquantized_byte_constants() -> None:
    graph = _int8_graph(
        constant_quantization={
            20: {"scales": [0.1, 0.2], "zeroPoints": [0, 0], "quantizedDimension": 0},
            21: {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
            22: {"scales": [], "zeroPoints": [], "quantizedDimension": 0},
        }
    )
    with pytest.raises(ExportContractError, match="constant tensor 21.*scale"):
        _validate_graph_contract(graph, expected_quantization="int8")


@pytest.mark.parametrize(
    ("metadata", "message"),
    [
        ({"scales": [0.0], "zeroPoints": [0], "quantizedDimension": 0}, "positive finite"),
        ({"scales": [float("nan")], "zeroPoints": [0], "quantizedDimension": 0}, "positive finite"),
        ({"scales": [0.1, 0.2], "zeroPoints": [0], "quantizedDimension": 0}, "cardinality"),
        ({"scales": [0.1], "zeroPoints": [200], "quantizedDimension": 0}, "range"),
        ({"scales": [0.1, 0.2], "zeroPoints": [0, 0], "quantizedDimension": 2}, "dimension"),
    ],
)
def test_int8_graph_rejects_invalid_constant_quantization(
    metadata: dict[str, object], message: str
) -> None:
    constants = dict(_int8_graph().constant_quantization)
    constants[20] = metadata
    with pytest.raises(ExportContractError, match=message):
        _validate_graph_contract(
            _int8_graph(constant_quantization=constants), expected_quantization="int8"
        )


@pytest.mark.parametrize(
    ("metadata", "message"),
    [
        ({"scales": [0.0], "zeroPoints": [128], "quantizedDimension": 0}, "positive finite"),
        ({"scales": [0.5], "zeroPoints": [], "quantizedDimension": 0}, "cardinality"),
        ({"scales": [0.5], "zeroPoints": [256], "quantizedDimension": 0}, "range"),
        ({"scales": [0.5], "zeroPoints": [128], "quantizedDimension": 4}, "dimension"),
    ],
)
def test_int8_graph_rejects_invalid_input_quantization(
    metadata: dict[str, object], message: str
) -> None:
    with pytest.raises(ExportContractError, match=message):
        _validate_graph_contract(
            _int8_graph(input_quantization=metadata), expected_quantization="int8"
        )


@pytest.mark.skipif(
    os.environ.get("PONTINHO_RUN_MODEL_MAKER_EXPORT_SMOKE") != "1",
    reason="set PONTINHO_RUN_MODEL_MAKER_EXPORT_SMOKE=1 for genuine conversion",
)
@pytest.mark.filterwarnings(
    "ignore:^numElems is deprecated\\.$:DeprecationWarning:"
    "tensorflow\\.lite\\.python\\.schema_py_generated$"
)
def test_genuine_model_maker_fixture_has_the_android_detection_contract(tmp_path: Path) -> None:
    """Catch a documented contract the genuine converter cannot satisfy."""

    import tensorflow as tf
    from tensorflow_examples.lite.model_maker.core.task import configs
    from tflite_model_maker import object_detector

    class RepresentativeData:
        def gen_dataset(self, model_spec: object, batch_size: int, is_training: bool) -> object:
            del model_spec, batch_size, is_training
            image = tf.zeros([1, 320, 320, 3], dtype=tf.float32)
            return tf.data.Dataset.from_tensors((image, tf.constant(0)))

    label_map = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(tmp_path / "model-maker-cache"),
        epochs=1,
        batch_size=1,
        hparams={"image_size": "320x320"},
    )
    representative_data = RepresentativeData()
    detector = object_detector.ObjectDetector(spec, label_map, representative_data)
    detector.create_model()
    detector.export(
        str(tmp_path),
        tflite_filename="card_detector.float16.tflite",
        quantization_config=configs.QuantizationConfig.for_float16(),
        with_metadata=True,
    )
    inspection = inspect_tflite(
        tmp_path / "card_detector.float16.tflite", expected_quantization="float16"
    )

    assert inspection["customOps"] == ["TFLite_Detection_PostProcess"]
    assert inspection["labels"] == list(CARD_LABELS)
    assert inspection["input"]["shapeSignature"] == [1, 320, 320, 3]
    assert [item["metadataName"] for item in inspection["outputs"]] == [
        "location",
        "category",
        "score",
        "number of detections",
    ]
    assert inspection["normalization"] == {
        "mean": [127.0, 127.0, 127.0],
        "std": [128.0, 128.0, 128.0],
    }
    assert inspection["interpreterInvocation"] == "passed"

    detector.export(
        str(tmp_path),
        tflite_filename="card_detector.int8.tflite",
        quantization_config=spec.get_default_quantization_config(representative_data),
        with_metadata=True,
    )
    int8_inspection = inspect_tflite(
        tmp_path / "card_detector.int8.tflite", expected_quantization="int8"
    )
    assert int8_inspection["quantization"]["variant"] == "int8"
    assert int8_inspection["quantization"]["quantizedConstantCount"] > 0
    assert int8_inspection["interpreterInvocation"] == "passed"
