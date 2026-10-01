"""Fail-closed export and Android TFLite contract tests."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest
import yaml

from pontinho_cards.export import (
    ExportContractError,
    inspect_tflite,
    prepare_export,
    publish_export,
)
from pontinho_cards.labels import CARD_LABELS

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _checkpoint_run(tmp_path: Path, *, status: str = "TRAINED_NOT_RELEASE_APPROVED") -> Path:
    run = tmp_path / "checkpoints" / "run-001"
    run.mkdir(parents=True)
    checkpoint = run / "selected.weights.h5"
    checkpoint.write_bytes(b"selected checkpoint fixture")
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
                "schemaVersion": 1,
                "requirementsLockSha256": "1" * 64,
                "inputManifestSha256": "2" * 64,
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


def _comparison(run: Path, *, selected: str = "float16") -> Path:
    path = run / "export-comparison.json"
    path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "selected": selected,
                "selectionRationale": "float16 retained accuracy and was faster on Android",
                "validationManifestSha256": "3" * 64,
                "variants": {
                    "float16": {"exactTotalAccuracy": 0.96, "androidLatencyMs": 48.2},
                    "int8": {"exactTotalAccuracy": 0.94, "androidLatencyMs": 31.7},
                },
            }
        ),
        encoding="utf-8",
    )
    return path


def test_prepare_export_reuses_the_single_selected_checkpoint_and_comparison(
    tmp_path: Path,
) -> None:
    """Catch exporting arbitrary weights or choosing quantization without Android evidence."""

    run = _checkpoint_run(tmp_path)
    comparison = _comparison(run)

    request = prepare_export(run, tmp_path / "artifacts" / "v1", comparison)

    assert request.checkpoint_path == run / "selected.weights.h5"
    assert request.checkpoint_sha256 == _sha256(request.checkpoint_path)
    assert request.run_git_commit == "0f51cf2"
    assert request.input_manifest_sha256 == "2" * 64
    assert request.quantization == "float16"
    assert request.labels == CARD_LABELS
    assert request.input_size == 320
    assert request.confidence_threshold == 0.5
    assert request.iou_threshold == 0.5


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        ("missing-comparison", "comparison"),
        ("smoke-run", "full trained run"),
        ("checkpoint-hash", "checkpoint hash"),
        ("checkpoint-name", "selected checkpoint"),
        ("labels", "CARD_LABELS"),
        ("missing-int8-latency", "Android latency"),
        ("bad-selection", "float16 or int8"),
    ],
)
def test_prepare_export_rejects_unproven_or_mismatched_inputs(
    tmp_path: Path, mutation: str, message: str
) -> None:
    """Catch an export that is not tied to an exact trained run and measured variant."""

    run = _checkpoint_run(
        tmp_path,
        status="SMOKE_ONLY" if mutation == "smoke-run" else "TRAINED_NOT_RELEASE_APPROVED",
    )
    comparison = _comparison(run, selected="dynamic" if mutation == "bad-selection" else "float16")
    if mutation == "missing-comparison":
        comparison.unlink()
    elif mutation == "checkpoint-hash":
        metrics = json.loads((run / "metrics.json").read_text())
        metrics["selectedCheckpointSha256"] = "f" * 64
        (run / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")
    elif mutation == "checkpoint-name":
        metrics = json.loads((run / "metrics.json").read_text())
        metrics["selectedCheckpoint"] = "other.weights.h5"
        (run / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")
    elif mutation == "labels":
        config = yaml.safe_load((run / "resolved-config.yaml").read_text())
        config["labels"] = list(reversed(CARD_LABELS))
        (run / "resolved-config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")
    elif mutation == "missing-int8-latency":
        document = json.loads(comparison.read_text())
        del document["variants"]["int8"]["androidLatencyMs"]
        comparison.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(ExportContractError, match=message):
        prepare_export(run, tmp_path / "artifacts" / "v1", comparison)

    assert not (tmp_path / "artifacts").exists()


@pytest.mark.parametrize(
    "output",
    [Path("."), Path(".."), Path("artifacts") / "..", Path("artifacts") / "two words"],
)
def test_prepare_export_rejects_unsafe_output_paths(tmp_path: Path, output: Path) -> None:
    """Catch traversal, roots, and ambiguous candidate directory names before writes."""

    run = _checkpoint_run(tmp_path)
    comparison = _comparison(run)

    with pytest.raises(ExportContractError, match="output"):
        prepare_export(run, tmp_path / output, comparison)


def test_publish_export_is_atomic_and_writes_complete_provenance(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch partial candidate publication or a contract detached from run hashes."""

    run = _checkpoint_run(tmp_path)
    request = prepare_export(run, tmp_path / "artifacts" / "v1", _comparison(run))

    def genuine_export(_request: object, target: Path) -> dict[str, object]:
        model = target / "card_detector.tflite"
        model.write_bytes(b"genuine converter output fixture")
        return {
            "modelSha256": _sha256(model),
            "input": {"shape": [1, 320, 320, 3], "dtype": "float32"},
            "outputs": [
                {"semantic": "boxes", "shape": [1, 25, 4], "dtype": "float32"},
                {"semantic": "classes", "shape": [1, 25], "dtype": "float32"},
                {"semantic": "scores", "shape": [1, 25], "dtype": "float32"},
                {"semantic": "count", "shape": [1], "dtype": "float32"},
            ],
            "customOps": ["TFLite_Detection_PostProcess"],
            "labels": list(CARD_LABELS),
            "normalization": {"mean": [127.0], "std": [128.0]},
            "quantization": {"variant": "float16", "input": None},
            "interpreterInvocation": "passed",
        }

    monkeypatch.setattr("pontinho_cards.export._export_and_inspect", genuine_export)
    contract = publish_export(request)

    assert request.output_dir.exists()
    assert not list(request.output_dir.parent.glob(".v1.*"))
    assert json.loads((request.output_dir / "model-contract.json").read_text()) == contract
    assert contract["checkpointSha256"] == request.checkpoint_sha256
    assert contract["inputManifestSha256"] == "2" * 64
    assert contract["runGitCommit"] == "0f51cf2"
    assert contract["scoreThreshold"] == 0.5
    assert contract["iouThreshold"] == 0.5
    assert contract["labels"] == list(CARD_LABELS)


def test_publish_failure_never_leaks_or_replaces_a_candidate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a conversion/inspection failure leaving reusable-looking output."""

    run = _checkpoint_run(tmp_path)
    request = prepare_export(run, tmp_path / "artifacts" / "v1", _comparison(run))

    def fail_after_model(_request: object, target: Path) -> dict[str, object]:
        (target / "card_detector.tflite").write_bytes(b"partial")
        raise ExportContractError("missing TFLite_Detection_PostProcess")

    monkeypatch.setattr("pontinho_cards.export._export_and_inspect", fail_after_model)

    with pytest.raises(ExportContractError, match="Detection_PostProcess"):
        publish_export(request)

    assert not request.output_dir.exists()
    assert not list(request.output_dir.parent.glob(".v1.*"))


def test_publish_rechecks_the_inspected_model_hash_before_atomic_rename(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a changed model byte stream between inspection and publication."""

    run = _checkpoint_run(tmp_path)
    request = prepare_export(run, tmp_path / "artifacts" / "v1", _comparison(run))

    def stale_inspection(_request: object, target: Path) -> dict[str, object]:
        (target / "card_detector.tflite").write_bytes(b"changed after inspection")
        return {"modelSha256": "0" * 64, "labels": list(CARD_LABELS)}

    monkeypatch.setattr("pontinho_cards.export._export_and_inspect", stale_inspection)

    with pytest.raises(ExportContractError, match="model hash"):
        publish_export(request)

    assert not request.output_dir.exists()
    assert not list(request.output_dir.parent.glob(".v1.*"))


@pytest.mark.skipif(
    os.environ.get("PONTINHO_RUN_MODEL_MAKER_EXPORT_SMOKE") != "1",
    reason="set PONTINHO_RUN_MODEL_MAKER_EXPORT_SMOKE=1 for genuine conversion",
)
@pytest.mark.filterwarnings(
    "ignore:^numElems is deprecated\\.$:DeprecationWarning:"
    "tensorflow\\.lite\\.python\\.schema_py_generated$"
)
def test_genuine_model_maker_fixture_has_the_android_detection_contract(
    tmp_path: Path,
) -> None:
    """Catch a model that looks documented but cannot execute its real custom-op graph."""

    from tensorflow_examples.lite.model_maker.core.task import configs
    from tflite_model_maker import object_detector

    label_map = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(tmp_path / "model-maker-cache"),
        epochs=1,
        batch_size=1,
        hparams={"image_size": "320x320"},
    )
    detector = object_detector.ObjectDetector(spec, label_map)
    detector.create_model()
    detector.export(
        str(tmp_path),
        tflite_filename="card_detector.tflite",
        quantization_config=configs.QuantizationConfig.for_float16(),
        with_metadata=True,
    )
    model = tmp_path / "card_detector.tflite"
    inspection = inspect_tflite(model, expected_quantization="float16")

    assert inspection["customOps"] == ["TFLite_Detection_PostProcess"]
    assert inspection["labels"] == list(CARD_LABELS)
    assert inspection["input"] == {
        "shape": [1, 320, 320, 3],
        "dtype": "float32",
    }
    assert [item["semantic"] for item in inspection["outputs"]] == [
        "boxes",
        "classes",
        "scores",
        "count",
    ]
    assert inspection["normalization"] == {"mean": [127.0], "std": [128.0]}
    assert inspection["interpreterInvocation"] == "passed"
