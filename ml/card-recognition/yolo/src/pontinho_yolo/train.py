"""Run one deterministic, GPU-only YOLOv8n training candidate."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import re
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from itertools import chain
from pathlib import Path
from typing import Any

import yaml

from pontinho_yolo.dataset import DatasetValidationError, audit_dataset
from pontinho_yolo.source import DATASET_DIRECTORY, SourceReceipt, SourceVerificationError

MODEL_NAME = "yolov8n.pt"
BASE_MODEL_ARCHITECTURE = "ultralytics:yolov8:detect:n"
RECEIPT_SCHEMA_VERSION = 2
IMAGE_SIZE = 640
EPOCHS = 100
BATCH_SIZE = 16
PATIENCE = 20
SEED = 1337
DEVICE = 0
_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_CONFIG_KEYS = {
    "schema_version",
    "model",
    "data",
    "dataset_root",
    "dataset_audit",
    "source_receipt",
    "base_weights",
    "dependency_lock",
    "runs",
    "receipt",
    "imgsz",
    "epochs",
    "batch",
    "patience",
    "seed",
    "deterministic",
    "device",
}


class TrainingError(RuntimeError):
    """Raised when a training run cannot satisfy the reproducibility contract."""


@dataclass(frozen=True)
class TrainConfig:
    """Validated paths and immutable settings for one YOLOv8n candidate."""

    source: Path
    project_root: Path
    model: str
    dataset_config: Path
    dataset_root: Path
    dataset_audit: Path
    source_receipt: Path
    base_weights: Path
    dependency_lock: Path
    runs: Path
    receipt: Path
    imgsz: int
    epochs: int
    batch: int
    patience: int
    seed: int
    deterministic: bool
    device: int
    config_sha256: str
    dataset_config_sha256: str
    dataset_audit_sha256: str
    dependency_lock_sha256: str
    source_receipt_file_sha256: str
    dataset_archive_sha256: str
    dataset_tree_sha256: str


@dataclass(frozen=True)
class TrainingReceipt:
    """Content-addressed record of every input and selected training output."""

    schema_version: int
    run_id: str
    created_at: str
    config_sha256: str
    dataset_config_sha256: str
    dataset_audit_sha256: str
    source_receipt_file_sha256: str
    dataset_archive_sha256: str
    dataset_tree_sha256: str
    base_weights: str
    base_weights_sha256: str
    base_model_architecture: str
    selected_checkpoint: str
    selected_checkpoint_sha256: str
    dependency_lock_sha256: str
    environment: Mapping[str, object]
    environment_sha256: str
    training_settings: Mapping[str, object]
    training_settings_sha256: str
    command: tuple[str, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "run_id": self.run_id,
            "created_at": self.created_at,
            "config_sha256": self.config_sha256,
            "dataset_config_sha256": self.dataset_config_sha256,
            "dataset_audit_sha256": self.dataset_audit_sha256,
            "source_receipt_file_sha256": self.source_receipt_file_sha256,
            "dataset_archive_sha256": self.dataset_archive_sha256,
            "dataset_tree_sha256": self.dataset_tree_sha256,
            "base_weights": self.base_weights,
            "base_weights_sha256": self.base_weights_sha256,
            "base_model_architecture": self.base_model_architecture,
            "selected_checkpoint": self.selected_checkpoint,
            "selected_checkpoint_sha256": self.selected_checkpoint_sha256,
            "dependency_lock_sha256": self.dependency_lock_sha256,
            "environment": dict(self.environment),
            "environment_sha256": self.environment_sha256,
            "training_settings": dict(self.training_settings),
            "training_settings_sha256": self.training_settings_sha256,
            "command": list(self.command),
        }

    @classmethod
    def from_dict(cls, payload: object) -> TrainingReceipt:
        if not isinstance(payload, dict):
            raise TrainingError("training receipt must be a JSON object")
        expected = set(cls.__dataclass_fields__)
        if set(payload) != expected:
            raise TrainingError("training receipt fields differ from the required schema")
        environment = payload.get("environment")
        settings = payload.get("training_settings")
        command = payload.get("command")
        if not isinstance(environment, dict) or not isinstance(settings, dict):
            raise TrainingError("training receipt mappings are invalid")
        if not isinstance(command, list) or not all(isinstance(value, str) for value in command):
            raise TrainingError("training receipt command is invalid")
        string_fields = expected - {
            "schema_version",
            "environment",
            "training_settings",
            "command",
        }
        if payload.get("schema_version") != RECEIPT_SCHEMA_VERSION or any(
            not isinstance(payload.get(field), str) for field in string_fields
        ):
            raise TrainingError("training receipt values are invalid")
        for field in (
            "config_sha256",
            "dataset_config_sha256",
            "dataset_audit_sha256",
            "source_receipt_file_sha256",
            "dataset_archive_sha256",
            "dataset_tree_sha256",
            "base_weights_sha256",
            "selected_checkpoint_sha256",
            "dependency_lock_sha256",
            "environment_sha256",
            "training_settings_sha256",
        ):
            if not _SHA256.fullmatch(str(payload[field])):
                raise TrainingError(f"training receipt {field} is invalid")
        return cls(
            schema_version=RECEIPT_SCHEMA_VERSION,
            run_id=str(payload["run_id"]),
            created_at=str(payload["created_at"]),
            config_sha256=str(payload["config_sha256"]),
            dataset_config_sha256=str(payload["dataset_config_sha256"]),
            dataset_audit_sha256=str(payload["dataset_audit_sha256"]),
            source_receipt_file_sha256=str(payload["source_receipt_file_sha256"]),
            dataset_archive_sha256=str(payload["dataset_archive_sha256"]),
            dataset_tree_sha256=str(payload["dataset_tree_sha256"]),
            base_weights=str(payload["base_weights"]),
            base_weights_sha256=str(payload["base_weights_sha256"]),
            base_model_architecture=str(payload["base_model_architecture"]),
            selected_checkpoint=str(payload["selected_checkpoint"]),
            selected_checkpoint_sha256=str(payload["selected_checkpoint_sha256"]),
            dependency_lock_sha256=str(payload["dependency_lock_sha256"]),
            environment=dict(environment),
            environment_sha256=str(payload["environment_sha256"]),
            training_settings=dict(settings),
            training_settings_sha256=str(payload["training_settings_sha256"]),
            command=tuple(command),
        )


class YoloTrainer:
    """Narrow adapter around Ultralytics so unit tests never initialize CUDA."""

    def __init__(self, model_name: str, base_weights: Path) -> None:
        if model_name != MODEL_NAME or Path(base_weights).name != MODEL_NAME:
            raise TrainingError(f"trainer requires an exact {MODEL_NAME} weight snapshot")
        self.model_name = model_name
        self.base_weights = Path(base_weights)
        from ultralytics import YOLO

        self._model = YOLO(str(self.base_weights))
        self.architecture_identity = _architecture_identity(self._model)

    def train(self, settings: Mapping[str, object]) -> Path:
        """Train with Ultralytics and return its validation-best checkpoint."""
        self._model.train(**dict(settings))
        return Path(str(settings["project"])) / str(settings["name"]) / "weights" / "best.pt"


def _architecture_identity(model: object) -> str:
    """Attest family, task, and scale from the loaded Ultralytics checkpoint."""
    task = getattr(model, "task", None)
    internal_model = getattr(model, "model", None)
    metadata = getattr(internal_model, "yaml", None)
    if not isinstance(metadata, dict):
        raise TrainingError("loaded base model does not expose Ultralytics architecture metadata")
    scale = metadata.get("scale")
    yaml_file = metadata.get("yaml_file")
    if not isinstance(scale, str) or not isinstance(yaml_file, str):
        raise TrainingError("loaded base model architecture metadata is incomplete")
    match = re.fullmatch(r"yolov8([nslmx])\.yaml", Path(yaml_file).name.lower())
    if task != "detect" or match is None or match.group(1) != scale:
        raise TrainingError("loaded base model architecture metadata is inconsistent")
    return f"ultralytics:yolov8:{task}:{scale}"


def load_train_config(path: str | Path) -> TrainConfig:
    """Load and validate the fixed Stage 4 training contract."""
    source = Path(path).resolve()
    raw = _read_file(source, "training config")
    try:
        payload = yaml.safe_load(raw)
    except (UnicodeError, yaml.YAMLError) as error:
        raise TrainingError(f"cannot parse training config {source}: {error}") from error
    if not isinstance(payload, dict) or set(payload) != _CONFIG_KEYS:
        raise TrainingError("training config fields differ from the required schema")
    required_values = {
        "schema_version": 1,
        "model": MODEL_NAME,
        "imgsz": IMAGE_SIZE,
        "epochs": EPOCHS,
        "batch": BATCH_SIZE,
        "patience": PATIENCE,
        "seed": SEED,
        "deterministic": True,
        "device": DEVICE,
    }
    if any(
        type(payload.get(key)) is not type(value) or payload.get(key) != value
        for key, value in required_values.items()
    ):
        raise TrainingError("training config must use the fixed deterministic YOLOv8n settings")

    project_root = source.parent.parent.resolve()
    dataset_config = _resolve_path(source.parent, payload["data"], "data")
    dataset_root = _resolve_path(source.parent, payload["dataset_root"], "dataset_root")
    dataset_audit = _resolve_path(source.parent, payload["dataset_audit"], "dataset_audit")
    source_receipt = _resolve_path(source.parent, payload["source_receipt"], "source_receipt")
    base_weights = _resolve_path(source.parent, payload["base_weights"], "base_weights")
    dependency_lock = _resolve_path(source.parent, payload["dependency_lock"], "dependency_lock")
    runs = _resolve_path(source.parent, payload["runs"], "runs")
    receipt = _resolve_path(source.parent, payload["receipt"], "receipt")
    for candidate, field in (
        (dataset_config, "data"),
        (dataset_root, "dataset_root"),
        (dataset_audit, "dataset_audit"),
        (source_receipt, "source_receipt"),
        (base_weights, "base_weights"),
        (dependency_lock, "dependency_lock"),
        (runs, "runs"),
        (receipt, "receipt"),
    ):
        if not candidate.is_relative_to(project_root):
            raise TrainingError(f"{field} must stay inside the YOLO package")
    expected_root = project_root / "datasets" / "extracted" / DATASET_DIRECTORY
    expected_receipt = project_root / "datasets" / f"{DATASET_DIRECTORY}.source.json"
    expected_weights = project_root / "checkpoints" / MODEL_NAME
    expected_paths = {
        "training config": project_root / "config" / "train.yaml",
        "data": project_root / "config" / "dataset.yaml",
        "dataset_audit": project_root / "reports" / "dataset-audit.json",
        "dependency_lock": project_root / "uv.lock",
        "runs": project_root / "runs",
        "receipt": project_root / "reports" / "training-receipt.json",
    }
    actual_paths = {
        "training config": source,
        "data": dataset_config,
        "dataset_audit": dataset_audit,
        "dependency_lock": dependency_lock,
        "runs": runs,
        "receipt": receipt,
    }
    for field, expected in expected_paths.items():
        if actual_paths[field] != expected:
            raise TrainingError(f"{field} must use the canonical YOLO package path")
    if dataset_root != expected_root:
        raise TrainingError(f"dataset_root must identify {DATASET_DIRECTORY}")
    if source_receipt != expected_receipt:
        raise TrainingError(f"source_receipt must identify {DATASET_DIRECTORY}.source.json")
    if base_weights != expected_weights or base_weights.name != MODEL_NAME:
        raise TrainingError(f"base_weights must be checkpoints/{MODEL_NAME}")

    dataset_payload = _read_file(dataset_config, "dataset config")
    audit_payload = _read_file(dataset_audit, "dataset audit")
    source_receipt_payload = _read_file(source_receipt, "source receipt")
    lock_payload = _read_file(dependency_lock, "dependency lock")
    _validate_dataset_config(dataset_payload)
    audit = _load_dataset_audit(audit_payload)
    try:
        receipt_value = SourceReceipt.from_path(source_receipt)
    except SourceVerificationError as error:
        raise TrainingError(f"cannot load source receipt: {error}") from error
    if audit["receipt_sha256"] != receipt_value.sha256:
        raise TrainingError("dataset audit does not bind the source archive receipt")
    return TrainConfig(
        source=source,
        project_root=project_root,
        model=MODEL_NAME,
        dataset_config=dataset_config,
        dataset_root=dataset_root,
        dataset_audit=dataset_audit,
        source_receipt=source_receipt,
        base_weights=base_weights,
        dependency_lock=dependency_lock,
        runs=runs,
        receipt=receipt,
        imgsz=IMAGE_SIZE,
        epochs=EPOCHS,
        batch=BATCH_SIZE,
        patience=PATIENCE,
        seed=SEED,
        deterministic=True,
        device=DEVICE,
        config_sha256=_hash_bytes(raw),
        dataset_config_sha256=_hash_bytes(dataset_payload),
        dataset_audit_sha256=_hash_bytes(audit_payload),
        dependency_lock_sha256=_hash_bytes(lock_payload),
        source_receipt_file_sha256=_hash_bytes(source_receipt_payload),
        dataset_archive_sha256=receipt_value.sha256,
        dataset_tree_sha256=str(audit["tree_sha256"]),
    )


def train_candidate(config: TrainConfig, run_id: str) -> TrainingReceipt:
    """Train once, or return only an exactly matching existing candidate."""
    _validate_run_id(run_id)
    environment = _capture_environment()
    if environment.get("cuda_available") is not True:
        raise TrainingError("CUDA GPU is required for candidate training; CPU training is disabled")
    environment_hash = _hash_json(environment)
    settings = _training_settings(config, run_id)
    settings_hash = _hash_json(settings)
    run_directory = config.runs / run_id
    expected_checkpoint = run_directory / "weights" / "best.pt"
    local_receipt = run_directory / "training-receipt.json"

    if run_directory.exists():
        receipt = _verify_existing_run(
            config,
            run_id,
            expected_checkpoint,
            local_receipt,
            environment_hash,
            settings_hash,
        )
        _write_receipt(config.receipt, receipt, replace=True)
        return receipt

    shared_receipt = _load_shared_receipt(config.receipt, run_id)
    _verify_dataset(config)
    _require_regular_file(config.base_weights, "base weights")
    base_weights_sha256 = _hash_file(config.base_weights)
    config.runs.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{run_id}-base-", dir=config.runs) as temporary:
        staged_base_weights = Path(temporary) / MODEL_NAME
        staged_base_weights.write_bytes(_read_file(config.base_weights, "base weights"))
        if _hash_file(staged_base_weights) != base_weights_sha256:
            raise TrainingError("staged base weights differ from the configured model")
        if _hash_file(config.base_weights) != base_weights_sha256:
            raise TrainingError("base weights changed while they were staged")
        trainer = YoloTrainer(config.model, staged_base_weights)
        architecture_identity = trainer.architecture_identity
        if architecture_identity != BASE_MODEL_ARCHITECTURE:
            raise TrainingError(
                "loaded base model architecture differs from the required YOLOv8n detector: "
                f"{architecture_identity!r}"
            )
        selected_checkpoint = Path(trainer.train(settings)).resolve()

    if selected_checkpoint != expected_checkpoint.resolve():
        raise TrainingError("trainer returned a checkpoint outside the requested run")
    _require_regular_file(selected_checkpoint, "selected checkpoint")
    if _hash_file(config.base_weights) != base_weights_sha256:
        raise TrainingError("base weights changed during training")
    _verify_dataset(config)
    _verify_snapshots(config)

    receipt = TrainingReceipt(
        schema_version=RECEIPT_SCHEMA_VERSION,
        run_id=run_id,
        created_at=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        config_sha256=config.config_sha256,
        dataset_config_sha256=config.dataset_config_sha256,
        dataset_audit_sha256=config.dataset_audit_sha256,
        source_receipt_file_sha256=config.source_receipt_file_sha256,
        dataset_archive_sha256=config.dataset_archive_sha256,
        dataset_tree_sha256=config.dataset_tree_sha256,
        base_weights=config.base_weights.relative_to(config.project_root).as_posix(),
        base_weights_sha256=base_weights_sha256,
        base_model_architecture=architecture_identity,
        selected_checkpoint=expected_checkpoint.relative_to(config.project_root).as_posix(),
        selected_checkpoint_sha256=_hash_file(selected_checkpoint),
        dependency_lock_sha256=config.dependency_lock_sha256,
        environment=dict(environment),
        environment_sha256=environment_hash,
        training_settings=dict(settings),
        training_settings_sha256=settings_hash,
        command=_training_command(config, run_id),
    )
    if shared_receipt is not None:
        if _reproducibility_evidence(receipt) != _reproducibility_evidence(shared_receipt):
            raise TrainingError(
                "shared receipt for this run ID differs from the reproduction evidence"
            )
        _write_receipt(local_receipt, shared_receipt, replace=False)
        return shared_receipt
    _write_receipt(local_receipt, receipt, replace=False)
    _write_receipt(config.receipt, receipt, replace=True)
    return receipt


def _training_settings(config: TrainConfig, run_id: str) -> dict[str, object]:
    return {
        "data": str(config.dataset_config),
        "imgsz": config.imgsz,
        "epochs": config.epochs,
        "batch": config.batch,
        "patience": config.patience,
        "seed": config.seed,
        "deterministic": config.deterministic,
        "device": config.device,
        "project": str(config.runs),
        "name": run_id,
        "exist_ok": False,
        "resume": False,
    }


def _verify_existing_run(
    config: TrainConfig,
    run_id: str,
    checkpoint: Path,
    local_receipt: Path,
    environment_hash: str,
    settings_hash: str,
) -> TrainingReceipt:
    try:
        if (config.runs / run_id).is_symlink() or not (config.runs / run_id).is_dir():
            raise TrainingError("run directory is not a regular directory")
        _require_regular_file(checkpoint, "selected checkpoint")
        _require_regular_file(config.base_weights, "base weights")
        payload = json.loads(_read_file(local_receipt, "local training receipt"))
        receipt = TrainingReceipt.from_dict(payload)
        _verify_dataset(config)
        _verify_snapshots(config)
        expected = {
            "run_id": run_id,
            "config_sha256": config.config_sha256,
            "dataset_config_sha256": config.dataset_config_sha256,
            "dataset_audit_sha256": config.dataset_audit_sha256,
            "source_receipt_file_sha256": config.source_receipt_file_sha256,
            "dataset_archive_sha256": config.dataset_archive_sha256,
            "dataset_tree_sha256": config.dataset_tree_sha256,
            "base_weights": config.base_weights.relative_to(config.project_root).as_posix(),
            "base_weights_sha256": _hash_file(config.base_weights),
            "base_model_architecture": BASE_MODEL_ARCHITECTURE,
            "selected_checkpoint": checkpoint.relative_to(config.project_root).as_posix(),
            "selected_checkpoint_sha256": _hash_file(checkpoint),
            "dependency_lock_sha256": config.dependency_lock_sha256,
            "environment_sha256": environment_hash,
            "training_settings_sha256": settings_hash,
        }
        if any(getattr(receipt, field) != value for field, value in expected.items()):
            raise TrainingError("receipt binding differs")
        if receipt.environment_sha256 != _hash_json(receipt.environment):
            raise TrainingError("environment payload hash differs")
        if receipt.training_settings_sha256 != _hash_json(receipt.training_settings):
            raise TrainingError("training settings payload hash differs")
        if receipt.command != _training_command(config, run_id):
            raise TrainingError("training command differs")
        return receipt
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TrainingError) as error:
        raise TrainingError(
            f"existing run ID {run_id!r} does not match its exact receipt and checkpoint"
        ) from error


def _load_shared_receipt(path: Path, run_id: str) -> TrainingReceipt | None:
    """Load committed evidence only when it describes the run being reproduced."""
    if not path.exists():
        return None
    try:
        _require_regular_file(path, "shared training receipt")
        receipt = TrainingReceipt.from_dict(json.loads(_read_file(path, "shared training receipt")))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TrainingError) as error:
        raise TrainingError(f"cannot verify shared training receipt {path}") from error
    return receipt if receipt.run_id == run_id else None


def _reproducibility_evidence(receipt: TrainingReceipt) -> dict[str, object]:
    """Return every receipt field except the observation timestamp."""
    evidence = receipt.to_dict()
    del evidence["created_at"]
    return evidence


def _capture_environment() -> dict[str, object]:
    import torch

    available = bool(torch.cuda.is_available())
    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "ultralytics": importlib.metadata.version("ultralytics"),
        "torch": str(torch.__version__),
        "cuda_available": available,
        "cuda_version": str(torch.version.cuda) if torch.version.cuda is not None else None,
        "cuda_device": DEVICE if available else None,
        "cuda_device_name": torch.cuda.get_device_name(DEVICE) if available else None,
    }


def _training_command(config: TrainConfig, run_id: str) -> tuple[str, ...]:
    return (
        "python",
        "-m",
        "pontinho_yolo.train",
        "--config",
        str(config.source),
        "--run-id",
        run_id,
    )


def _verify_snapshots(config: TrainConfig) -> None:
    expected = (
        (config.source, config.config_sha256, "training config"),
        (config.dataset_config, config.dataset_config_sha256, "dataset config"),
        (config.dataset_audit, config.dataset_audit_sha256, "dataset audit"),
        (config.source_receipt, config.source_receipt_file_sha256, "source receipt"),
        (config.dependency_lock, config.dependency_lock_sha256, "dependency lock"),
    )
    for path, digest, description in expected:
        if _hash_file(path) != digest:
            raise TrainingError(f"{description} changed after configuration was loaded")


def _verify_dataset(config: TrainConfig) -> None:
    """Re-audit the exact extracted tree and enforce the normalized split routing."""
    _verify_snapshots(config)
    document = _dataset_document(_read_file(config.dataset_config, "dataset config"))
    expected_splits = {
        "train": config.dataset_root / "train" / "images",
        "val": config.dataset_root / "valid" / "images",
        "test": config.dataset_root / "test" / "images",
    }
    for split, expected in expected_splits.items():
        value = document.get(split)
        if not isinstance(value, str):
            raise TrainingError(f"dataset config {split} must be a path")
        candidate = Path(value)
        resolved = Path(
            os.path.abspath(
                candidate if candidate.is_absolute() else config.dataset_config.parent / candidate
            )
        )
        if resolved != expected:
            raise TrainingError(
                f"dataset config {split} must resolve to the exact audited split directory"
            )

    _reject_symlink_components(config.dataset_root, config.project_root)
    for member in chain((config.dataset_root,), config.dataset_root.rglob("*")):
        if member.is_symlink():
            raise TrainingError(f"dataset redirection through a symlink is forbidden: {member}")
    try:
        source_receipt = SourceReceipt.from_path(config.source_receipt)
        actual = audit_dataset(config.dataset_root, source_receipt).to_dict()
    except (DatasetValidationError, SourceVerificationError) as error:
        raise TrainingError(f"dataset audit verification failed: {error}") from error
    expected_audit = _load_dataset_audit(_read_file(config.dataset_audit, "dataset audit"))
    if actual != expected_audit:
        raise TrainingError("dataset audit verification differs from the committed audit")
    _verify_snapshots(config)


def _validate_dataset_config(payload: bytes) -> None:
    document = _dataset_document(payload)
    for key in ("train", "val", "test"):
        if not isinstance(document.get(key), str) or not document[key]:
            raise TrainingError(f"dataset config requires a nonempty {key} partition")
    if len({document["train"], document["val"], document["test"]}) != 3:
        raise TrainingError("dataset partitions must be distinct")


def _dataset_document(payload: bytes) -> dict[str, object]:
    try:
        document = yaml.safe_load(payload)
    except (UnicodeError, yaml.YAMLError) as error:
        raise TrainingError(f"cannot parse dataset config: {error}") from error
    if not isinstance(document, dict):
        raise TrainingError("dataset config must be a mapping")
    return document


def _load_dataset_audit(payload: bytes) -> Mapping[str, object]:
    try:
        audit = json.loads(payload)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise TrainingError(f"cannot parse dataset audit: {error}") from error
    if not isinstance(audit, dict):
        raise TrainingError("dataset audit must be a JSON object")
    for key in ("receipt_sha256", "tree_sha256"):
        if not isinstance(audit.get(key), str) or not _SHA256.fullmatch(audit[key]):
            raise TrainingError(f"dataset audit {key} is invalid")
    return audit


def _resolve_path(parent: Path, value: Any, field: str) -> Path:
    if not isinstance(value, str) or not value or Path(value).is_absolute():
        raise TrainingError(f"{field} must be a nonempty relative path")
    return Path(os.path.abspath(parent / value))


def _reject_symlink_components(path: Path, root: Path) -> None:
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise TrainingError(f"dataset root escapes the YOLO package: {path}") from error
    candidate = root
    for part in relative.parts:
        candidate /= part
        if candidate.is_symlink():
            raise TrainingError(f"dataset redirection through a symlink is forbidden: {candidate}")


def _validate_run_id(run_id: str) -> None:
    if not isinstance(run_id, str) or not _RUN_ID.fullmatch(run_id):
        raise TrainingError("run ID must be 1-64 safe filename characters")


def _read_file(path: Path, description: str) -> bytes:
    try:
        _require_regular_file(path, description)
        return path.read_bytes()
    except OSError as error:
        raise TrainingError(f"cannot read {description} {path}: {error}") from error


def _require_regular_file(path: Path, description: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise TrainingError(f"{description} is not a regular file: {path}")


def _hash_file(path: Path) -> str:
    return _hash_bytes(_read_file(path, str(path)))


def _hash_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _hash_json(payload: Mapping[str, object]) -> str:
    return _hash_bytes(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())


def _write_receipt(path: Path, receipt: TrainingReceipt, *, replace: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(receipt.to_dict(), stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if path.exists() and not replace:
            raise TrainingError(f"refusing to replace existing training receipt: {path}")
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    return parser.parse_args()


def main() -> None:
    """Train or verify one candidate from the command line."""
    args = _parse_args()
    receipt = train_candidate(load_train_config(args.config), args.run_id)
    print(json.dumps(receipt.to_dict(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
