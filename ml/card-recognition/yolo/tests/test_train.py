"""Contracts for deterministic, receipt-bound YOLOv8n training."""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import types
from pathlib import Path

import pytest
import yaml

from pontinho_yolo import train as train_module
from pontinho_yolo.dataset import audit_dataset
from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.source import EXPORT_FORMAT, SOURCE_URL, SourceReceipt
from pontinho_yolo.train import (
    TrainingError,
    TrainingReceipt,
    load_train_config,
    train_candidate,
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_training_inputs(tmp_path: Path) -> tuple[Path, dict[str, Path]]:
    package = tmp_path / "yolo"
    config_directory = package / "config"
    reports = package / "reports"
    checkpoints = package / "checkpoints"
    for directory in (config_directory, reports, checkpoints):
        directory.mkdir(parents=True)

    dataset_root = package / "datasets" / "extracted" / "playing-cards-ow27d-v4"
    dataset_root.mkdir(parents=True)
    (dataset_root / "data.yaml").write_text(
        yaml.safe_dump(
            {
                "train": "../train/images",
                "val": "../valid/images",
                "test": "../test/images",
                "nc": 52,
                "names": list(CARD_LABELS),
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    for index, split in enumerate(("train", "valid", "test")):
        stem = f"{split}-card_jpg.rf.{index:032x}"
        image = dataset_root / split / "images" / f"{stem}.jpg"
        label = dataset_root / split / "labels" / f"{stem}.txt"
        image.parent.mkdir(parents=True)
        label.parent.mkdir(parents=True)
        image.write_bytes(f"{split}-image".encode())
        label.write_text(f"{index} 0.5 0.5 0.2 0.2\n", encoding="utf-8")

    members = tuple(
        path.relative_to(dataset_root).as_posix()
        for path in sorted(path for path in dataset_root.rglob("*") if path.is_file())
    )
    source_receipt_value = SourceReceipt(
        source_url=SOURCE_URL,
        version="4",
        format=EXPORT_FORMAT,
        byte_length=123,
        sha256="1" * 64,
        members=members,
        retrieved_at="2026-10-16T00:00:00Z",
    )
    source_receipt = package / "datasets" / "playing-cards-ow27d-v4.source.json"
    source_receipt.write_text(
        json.dumps(source_receipt_value.to_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    dataset = config_directory / "dataset.yaml"
    relative_root = "../datasets/extracted/playing-cards-ow27d-v4"
    dataset.write_text(
        yaml.safe_dump(
            {
                "train": f"{relative_root}/train/images",
                "val": f"{relative_root}/valid/images",
                "test": f"{relative_root}/test/images",
                "names": dict(enumerate(CARD_LABELS)),
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    audit = reports / "dataset-audit.json"
    audit_value = audit_dataset(dataset_root, source_receipt_value)
    audit.write_text(
        json.dumps(audit_value.to_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    lock = package / "uv.lock"
    lock.write_text("version = 1\nrevision = 5\n", encoding="utf-8")
    base_weights = checkpoints / "yolov8n.pt"
    base_weights.write_bytes(b"pinned yolov8n base weights")

    config = config_directory / "train.yaml"
    config.write_text(
        yaml.safe_dump(
            {
                "schema_version": 1,
                "model": "yolov8n.pt",
                "data": "dataset.yaml",
                "dataset_root": "../datasets/extracted/playing-cards-ow27d-v4",
                "dataset_audit": "../reports/dataset-audit.json",
                "source_receipt": "../datasets/playing-cards-ow27d-v4.source.json",
                "base_weights": "../checkpoints/yolov8n.pt",
                "dependency_lock": "../uv.lock",
                "runs": "../runs",
                "receipt": "../reports/training-receipt.json",
                "imgsz": 640,
                "epochs": 100,
                "batch": 16,
                "patience": 20,
                "seed": 1337,
                "deterministic": True,
                "device": 0,
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    return config, {
        "dataset": dataset,
        "dataset_root": dataset_root,
        "audit": audit,
        "source_receipt": source_receipt,
        "lock": lock,
        "base_weights": base_weights,
        "receipt": reports / "training-receipt.json",
        "runs": package / "runs",
    }


def _gpu_environment() -> dict[str, object]:
    return {
        "python": "3.11.17",
        "platform": "Windows-11-test",
        "machine": "AMD64",
        "ultralytics": "8.4.177",
        "torch": "2.9.0+cu130",
        "cuda_available": True,
        "cuda_version": "13.0",
        "cuda_device": 0,
        "cuda_device_name": "Fake NVIDIA GPU",
    }


class _FakeTrainer:
    calls: list[dict[str, object]] = []
    initializations: list[tuple[str, Path, bytes]] = []
    architecture_identity = "ultralytics:yolov8:detect:n"

    def __init__(self, model_name: str, base_weights: Path) -> None:
        self.initializations.append((model_name, base_weights, base_weights.read_bytes()))

    def train(self, settings: dict[str, object]) -> Path:
        self.calls.append(dict(settings))
        checkpoint = Path(str(settings["project"])) / str(settings["name"]) / "weights" / "best.pt"
        checkpoint.parent.mkdir(parents=True)
        checkpoint.write_bytes(b"selected validation-best checkpoint")
        return checkpoint


@pytest.fixture(autouse=True)
def _reset_fake_trainer() -> None:
    _FakeTrainer.calls.clear()
    _FakeTrainer.initializations.clear()


def test_cpu_only_training_fails_before_constructing_the_yolo_adapter(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Dropping the CUDA gate would accidentally start the full candidate on CPU."""
    config_path, _ = _write_training_inputs(tmp_path)
    environment = {**_gpu_environment(), "cuda_available": False, "cuda_device_name": None}
    monkeypatch.setattr(train_module, "_capture_environment", lambda: environment)

    class AdapterMustNotInitialize:
        def __init__(self, *_args: object, **_kwargs: object) -> None:
            raise AssertionError("YOLO adapter initialized before CUDA validation")

    monkeypatch.setattr(train_module, "YoloTrainer", AdapterMustNotInitialize)

    with pytest.raises(TrainingError, match="CUDA GPU is required"):
        train_candidate(load_train_config(config_path), "cpu-must-fail")


def test_candidate_uses_explicit_deterministic_yolov8n_settings_only(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Changing a seed, device, or training source would make the candidate irreproducible."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)

    receipt = train_candidate(load_train_config(config_path), "deterministic-run")

    model_name, staged_weights, staged_bytes = _FakeTrainer.initializations[0]
    assert model_name == "yolov8n.pt"
    assert staged_weights.name == "yolov8n.pt"
    assert staged_weights != paths["base_weights"].resolve()
    assert staged_bytes == b"pinned yolov8n base weights"
    assert _FakeTrainer.calls == [
        {
            "data": str(paths["dataset"].resolve()),
            "imgsz": 640,
            "epochs": 100,
            "batch": 16,
            "patience": 20,
            "seed": 1337,
            "deterministic": True,
            "device": 0,
            "project": str(paths["runs"].resolve()),
            "name": "deterministic-run",
            "exist_ok": False,
            "resume": False,
        }
    ]
    settings = _FakeTrainer.calls[0]
    assert "train" not in settings and "test" not in settings
    assert not any(
        "valid/images" in str(value) or "test/images" in str(value) for value in settings.values()
    )
    assert receipt.selected_checkpoint == "runs/deterministic-run/weights/best.pt"


def test_receipt_binds_config_dataset_weights_environment_and_lock(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Omitting any input hash would let a different training run masquerade as this one."""
    config_path, paths = _write_training_inputs(tmp_path)
    environment = _gpu_environment()
    monkeypatch.setattr(train_module, "_capture_environment", lambda: environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)

    receipt = train_candidate(load_train_config(config_path), "bound-run")
    checkpoint = paths["runs"] / "bound-run" / "weights" / "best.pt"
    serialized = json.loads(paths["receipt"].read_text(encoding="utf-8"))

    assert receipt == TrainingReceipt.from_dict(serialized)
    assert receipt.config_sha256 == _sha256(config_path)
    assert receipt.dataset_config_sha256 == _sha256(paths["dataset"])
    assert receipt.dataset_audit_sha256 == _sha256(paths["audit"])
    assert receipt.source_receipt_file_sha256 == _sha256(paths["source_receipt"])
    assert receipt.dataset_archive_sha256 == "1" * 64
    assert receipt.dataset_tree_sha256 == json.loads(paths["audit"].read_text())["tree_sha256"]
    assert receipt.base_weights_sha256 == _sha256(paths["base_weights"])
    assert receipt.base_model_architecture == "ultralytics:yolov8:detect:n"
    assert receipt.selected_checkpoint_sha256 == _sha256(checkpoint)
    assert receipt.dependency_lock_sha256 == _sha256(paths["lock"])
    assert receipt.environment == environment
    assert (
        receipt.environment_sha256
        == hashlib.sha256(
            json.dumps(environment, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
    )
    assert (
        receipt.training_settings_sha256
        == hashlib.sha256(
            json.dumps(_FakeTrainer.calls[0], sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
    )
    assert receipt.command == (
        "python",
        "-m",
        "pontinho_yolo.train",
        "--config",
        str(config_path.resolve()),
        "--run-id",
        "bound-run",
    )


def test_exact_existing_run_is_reused_without_training_or_resume(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """An idempotent retry must verify the complete run instead of training it again."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)
    config = load_train_config(config_path)
    original = train_candidate(config, "same-run")
    assert len(_FakeTrainer.calls) == 1

    class AdapterMustNotInitialize:
        def __init__(self, *_args: object, **_kwargs: object) -> None:
            raise AssertionError("verified run must not initialize the adapter")

    monkeypatch.setattr(train_module, "YoloTrainer", AdapterMustNotInitialize)
    resumed = train_candidate(config, "same-run")

    assert resumed == original
    assert paths["receipt"].is_file()
    assert (paths["runs"] / "same-run" / "training-receipt.json").is_file()
    assert _FakeTrainer.calls[0]["resume"] is False


@pytest.mark.parametrize("mutation", ["checkpoint", "config", "receipt", "missing-receipt"])
def test_existing_run_id_is_rejected_when_any_binding_differs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, mutation: str
) -> None:
    """A stale or partial directory must never trigger Ultralytics' implicit resume behavior."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)
    config = load_train_config(config_path)
    train_candidate(config, "occupied-run")
    _FakeTrainer.calls.clear()

    if mutation == "checkpoint":
        (paths["runs"] / "occupied-run" / "weights" / "best.pt").write_bytes(b"tampered")
    elif mutation == "config":
        config_path.write_text(config_path.read_text(encoding="utf-8") + "# changed\n")
        config = load_train_config(config_path)
    elif mutation == "receipt":
        local_receipt = paths["runs"] / "occupied-run" / "training-receipt.json"
        receipt = json.loads(local_receipt.read_text(encoding="utf-8"))
        receipt["command"][-1] = "different-run"
        local_receipt.write_text(json.dumps(receipt), encoding="utf-8")
    else:
        (paths["runs"] / "occupied-run" / "training-receipt.json").unlink()

    with pytest.raises(TrainingError, match="existing run ID"):
        train_candidate(config, "occupied-run")

    assert _FakeTrainer.calls == []


def test_training_reaudits_actual_dataset_before_constructing_adapter(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A post-audit image mutation must fail before any model or GPU work starts."""
    config_path, paths = _write_training_inputs(tmp_path)
    image = next((paths["dataset_root"] / "train" / "images").iterdir())
    image.write_bytes(b"mutated after committed audit")
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)

    class AdapterMustNotInitialize:
        def __init__(self, *_args: object, **_kwargs: object) -> None:
            raise AssertionError("adapter initialized before dataset verification")

    monkeypatch.setattr(train_module, "YoloTrainer", AdapterMustNotInitialize)

    with pytest.raises(TrainingError, match="dataset.*audit"):
        train_candidate(load_train_config(config_path), "mutated-dataset")


def test_dataset_yaml_must_resolve_to_exact_audited_split_directories(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A valid-looking redirected training path must not bypass the audited root."""
    config_path, paths = _write_training_inputs(tmp_path)
    payload = yaml.safe_load(paths["dataset"].read_text(encoding="utf-8"))
    payload["train"] = "../datasets/extracted/playing-cards-ow27d-v4/train/labels"
    paths["dataset"].write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)

    with pytest.raises(TrainingError, match="exact audited split"):
        train_candidate(load_train_config(config_path), "redirected-dataset")

    assert _FakeTrainer.calls == []


def test_committed_receipt_without_ignored_run_does_not_block_clean_reproduction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A clean clone keeps committed evidence but has no ignored checkpoint to resume."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)
    config = load_train_config(config_path)
    original = train_candidate(config, "clean-reproduction")
    committed_bytes = paths["receipt"].read_bytes()
    shutil.rmtree(paths["runs"])
    assert paths["receipt"].is_file()

    reproduced = train_candidate(config, "clean-reproduction")

    assert reproduced == original
    assert len(_FakeTrainer.calls) == 2
    assert paths["receipt"].read_bytes() == committed_bytes
    local_receipt = paths["runs"] / "clean-reproduction" / "training-receipt.json"
    assert json.loads(local_receipt.read_text(encoding="utf-8")) == original.to_dict()


def test_clean_reproduction_rejects_changed_checkpoint_without_overwriting_shared_receipt(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A same-run reproduction with different output must not rewrite committed evidence."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)
    config = load_train_config(config_path)
    train_candidate(config, "clean-reproduction-mismatch")
    committed_bytes = paths["receipt"].read_bytes()
    shutil.rmtree(paths["runs"])

    class DifferentCheckpointTrainer(_FakeTrainer):
        def train(self, settings: dict[str, object]) -> Path:
            checkpoint = super().train(settings)
            checkpoint.write_bytes(b"different validation-best checkpoint")
            return checkpoint

    monkeypatch.setattr(train_module, "YoloTrainer", DifferentCheckpointTrainer)

    with pytest.raises(TrainingError, match="shared receipt.*reproduction evidence"):
        train_candidate(config, "clean-reproduction-mismatch")

    assert paths["receipt"].read_bytes() == committed_bytes
    assert not (paths["runs"] / "clean-reproduction-mismatch" / "training-receipt.json").exists()


def test_different_new_run_ids_are_not_blocked_by_committed_receipt(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The canonical report is evidence, not a global lock on all future run IDs."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)
    config = load_train_config(config_path)

    train_candidate(config, "first-run")
    second = train_candidate(config, "second-run")

    assert second.run_id == "second-run"
    assert len(_FakeTrainer.calls) == 2
    assert json.loads(paths["receipt"].read_text())["run_id"] == "second-run"
    assert (paths["runs"] / "first-run" / "training-receipt.json").is_file()
    assert (paths["runs"] / "second-run" / "training-receipt.json").is_file()


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("model", "yolov8s.pt", "fixed deterministic YOLOv8n"),
        ("base_weights", "../checkpoints/renamed.pt", "base_weights.*yolov8n.pt"),
    ],
)
def test_config_rejects_wrong_or_renamed_base_model(
    tmp_path: Path, field: str, value: str, message: str
) -> None:
    """A different architecture or misleading filename must fail before training."""
    config_path, paths = _write_training_inputs(tmp_path)
    document = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    document[field] = value
    if field == "base_weights":
        (paths["base_weights"].parent / "renamed.pt").write_bytes(
            paths["base_weights"].read_bytes()
        )
    config_path.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")

    with pytest.raises(TrainingError, match=message):
        load_train_config(config_path)


@pytest.mark.parametrize(
    ("scale", "expected"),
    [
        ("n", "ultralytics:yolov8:detect:n"),
        ("s", "ultralytics:yolov8:detect:s"),
    ],
)
def test_production_adapter_derives_architecture_from_loaded_checkpoint_metadata(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    scale: str,
    expected: str,
) -> None:
    """Trusting the staged filename instead would misidentify renamed YOLOv8s bytes."""
    staged_weights = tmp_path / "yolov8n.pt"
    staged_weights.write_bytes(b"checkpoint bytes inspected by fake Ultralytics")
    ultralytics = types.ModuleType("ultralytics")

    class MetadataYOLO:
        def __init__(self, path: str) -> None:
            assert path == str(staged_weights)
            self.task = "detect"
            self.model = types.SimpleNamespace(
                yaml={"yaml_file": f"yolov8{scale}.yaml", "scale": scale}
            )

    ultralytics.YOLO = MetadataYOLO  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "ultralytics", ultralytics)

    trainer = train_module.YoloTrainer("yolov8n.pt", staged_weights)

    assert trainer.architecture_identity == expected


def test_candidate_rejects_renamed_yolov8s_checkpoint_before_training(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A checkpoint whose loaded metadata says scale=s must never reach train()."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)

    class SmallArchitectureTrainer(_FakeTrainer):
        architecture_identity = "ultralytics:yolov8:detect:s"

        def train(self, settings: dict[str, object]) -> Path:
            raise AssertionError("wrong architecture reached training")

    monkeypatch.setattr(train_module, "YoloTrainer", SmallArchitectureTrainer)

    with pytest.raises(TrainingError, match="loaded base model architecture"):
        train_candidate(load_train_config(config_path), "renamed-small-checkpoint")

    assert not paths["receipt"].exists()


def test_base_weights_are_staged_before_adapter_and_substitution_fails_closed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The adapter must train from captured bytes, while a concurrent source swap aborts receipt."""
    config_path, paths = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)

    class SubstitutingTrainer(_FakeTrainer):
        def train(self, settings: dict[str, object]) -> Path:
            paths["base_weights"].write_bytes(b"substituted after staging")
            return super().train(settings)

    monkeypatch.setattr(train_module, "YoloTrainer", SubstitutingTrainer)

    with pytest.raises(TrainingError, match="base weights changed"):
        train_candidate(load_train_config(config_path), "weight-substitution")

    _, staged_path, staged_bytes = SubstitutingTrainer.initializations[0]
    assert staged_path.name == "yolov8n.pt"
    assert staged_path != paths["base_weights"].resolve()
    assert staged_bytes == b"pinned yolov8n base weights"
    assert not paths["receipt"].exists()


@pytest.mark.parametrize("run_id", ["../escape", "nested/run", "", ".", "a" * 65])
def test_unsafe_run_ids_are_rejected_before_training(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, run_id: str
) -> None:
    """Unvalidated run IDs could overwrite data outside the ignored runs directory."""
    config_path, _ = _write_training_inputs(tmp_path)
    monkeypatch.setattr(train_module, "_capture_environment", _gpu_environment)
    monkeypatch.setattr(train_module, "YoloTrainer", _FakeTrainer)

    with pytest.raises(TrainingError, match="run ID"):
        train_candidate(load_train_config(config_path), run_id)

    assert _FakeTrainer.calls == []
