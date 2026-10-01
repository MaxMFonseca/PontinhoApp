"""Reproducible EfficientDet-Lite0 training with a frozen-holdout firewall."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import random
import re
import shutil
import subprocess
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from pathlib import Path
from typing import Any

import yaml
from PIL import Image

from pontinho_cards.labels import CARD_LABELS, parse_card_label

_RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_PARTITION_TO_CSV = {"train": "TRAIN", "validation": "VAL"}


class TrainingConfigError(ValueError):
    """Raised before training when a reproducibility or data contract is unsafe."""


@dataclass(frozen=True)
class TrainingConfig:
    detector_family: str
    labels: tuple[str, ...]
    seed: int
    image_size: int
    batch_size: int
    confidence_threshold: float
    iou_threshold: float
    epochs: int
    early_stopping_enabled: bool
    early_stopping_patience: int
    train_partition: str
    validation_partition: str
    train_whole_model: bool
    augmentation: dict[str, bool]
    document: dict[str, Any]


@dataclass(frozen=True)
class PreparedRun:
    config: TrainingConfig
    staging_dir: Path
    output_dir: Path
    splits_path: Path
    requirements_lock: Path


@dataclass(frozen=True)
class TrainingOutcome:
    model: Any
    detector: Any
    validation_data: Any
    history: dict[str, list[Any]]
    record_counts: dict[str, int]


def _mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise TrainingConfigError(f"{name} must be an object")
    return value


def _positive_int(value: Any, name: str) -> int:
    if type(value) is not int or value <= 0:
        raise TrainingConfigError(f"{name} must be a positive integer")
    return value


def _unit_interval(value: Any, name: str) -> float:
    if type(value) not in {int, float} or not math.isfinite(value):
        raise TrainingConfigError(f"{name} must be a finite number in [0, 1]")
    normalized = float(value)
    if not 0.0 <= normalized <= 1.0:
        raise TrainingConfigError(f"{name} must be a finite number in [0, 1]")
    return normalized


def load_training_config(path: str | Path) -> TrainingConfig:
    """Load and strictly validate the complete training-selection contract."""

    config_path = Path(path)
    try:
        document = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise TrainingConfigError(f"cannot read training config {config_path}: {error}") from error
    root = _mapping(document, "training config")
    if root.get("detector_family") != "efficientdet_lite0":
        raise TrainingConfigError("detector_family must be efficientdet_lite0")
    labels = root.get("labels")
    if not isinstance(labels, list) or tuple(labels) != CARD_LABELS:
        raise TrainingConfigError("labels must be exactly CARD_LABELS in canonical order")
    seed = root.get("seed")
    if type(seed) is not int:
        raise TrainingConfigError("seed must be an integer")
    image_size = root.get("image_size")
    if type(image_size) is not int or image_size != 320:
        raise TrainingConfigError("image_size must be exactly 320 for EfficientDet-Lite0")
    batch_size = _positive_int(root.get("batch_size"), "batch_size")
    if batch_size > 64:
        raise TrainingConfigError("batch_size must be between 1 and 64")
    confidence_threshold = _unit_interval(
        root.get("confidence_threshold"), "confidence_threshold"
    )
    iou_threshold = _unit_interval(root.get("iou_threshold"), "iou_threshold")

    partitions = _mapping(root.get("partitions"), "partitions")
    train_partition = partitions.get("train")
    validation_partition = partitions.get("validation")
    if train_partition != "train" or validation_partition != "validation":
        raise TrainingConfigError("partitions must never reference the frozen test partition")
    if any("test" in str(value).casefold() for value in partitions.values()):
        raise TrainingConfigError("partitions must never reference the frozen test partition")

    training = _mapping(root.get("training"), "training")
    epochs = _positive_int(training.get("epochs"), "epochs")
    if epochs > 200:
        raise TrainingConfigError("epochs must be bounded at 200")
    early = _mapping(training.get("early_stopping"), "training.early_stopping")
    if early.get("enabled") is not True:
        raise TrainingConfigError("early stopping must be enabled")
    patience = _positive_int(early.get("patience"), "early stopping patience")
    if patience >= epochs:
        raise TrainingConfigError("early stopping patience must be smaller than epochs")
    train_whole_model = training.get("train_whole_model")
    if type(train_whole_model) is not bool:
        raise TrainingConfigError("train_whole_model must be a boolean")
    augmentation = _mapping(training.get("augmentation"), "training.augmentation")
    expected_switches = {"autoaugment", "horizontal_flip", "scale_jitter"}
    if set(augmentation) != expected_switches or not all(
        type(value) is bool for value in augmentation.values()
    ):
        raise TrainingConfigError(
            "augmentation must define boolean autoaugment, horizontal_flip, and scale_jitter"
        )
    return TrainingConfig(
        detector_family="efficientdet_lite0",
        labels=tuple(labels),
        seed=seed,
        image_size=image_size,
        batch_size=batch_size,
        confidence_threshold=confidence_threshold,
        iou_threshold=iou_threshold,
        epochs=epochs,
        early_stopping_enabled=True,
        early_stopping_patience=patience,
        train_partition=str(train_partition),
        validation_partition=str(validation_partition),
        train_whole_model=train_whole_model,
        augmentation=dict(augmentation),
        document=dict(root),
    )


def _load_training_partitions(
    path: Path, *, train_partition: str, validation_partition: str
) -> tuple[list[Mapping[str, Any]], list[Mapping[str, Any]]]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise TrainingConfigError(f"cannot read split manifest {path}: {error}") from error
    root = _mapping(document, "split manifest")
    partitions = _mapping(root.get("partitions"), "split manifest partitions")
    # Intentionally address only these two keys. The frozen final holdout lives in
    # final-holdout.json and the optional `test` member is never validated or used.
    train_records = partitions.get(train_partition)
    validation_records = partitions.get(validation_partition)
    if not isinstance(train_records, list) or not isinstance(validation_records, list):
        raise TrainingConfigError("split manifest requires train and validation lists")
    if not all(isinstance(record, Mapping) for record in train_records + validation_records):
        raise TrainingConfigError("train and validation records must be objects")
    return train_records, validation_records


def _checked_image(record: Mapping[str, Any]) -> tuple[Path, int, int, str]:
    path_value = record.get("path")
    if not isinstance(path_value, str) or not path_value:
        raise TrainingConfigError("training record path must be non-empty text")
    path = Path(path_value)
    width = _positive_int(record.get("width"), "record width")
    height = _positive_int(record.get("height"), "record height")
    digest = record.get("imageSha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise TrainingConfigError("training record imageSha256 must be lowercase SHA-256")
    try:
        payload = path.read_bytes()
        with Image.open(path) as image:
            actual_size = image.size
            image_format = image.format
            image.verify()
    except (OSError, SyntaxError) as error:
        raise TrainingConfigError(f"cannot read training image {path}") from error
    if hashlib.sha256(payload).hexdigest() != digest:
        raise TrainingConfigError(f"training image hash mismatch: {path}")
    if actual_size != (width, height):
        raise TrainingConfigError(f"training image dimensions mismatch: {path}")
    return path.resolve(), width, height, str(image_format)


def _model_maker_image(
    source: Path, *, source_format: str, digest: str, staging_dir: Path | None
) -> Path:
    if source_format == "JPEG" or staging_dir is None:
        return source
    staging_dir.mkdir(parents=True, exist_ok=True)
    target = staging_dir / f"{digest}.jpg"
    if target.exists():
        return target
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.stem}.", suffix=".jpg", dir=staging_dir
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with Image.open(source) as image:
            image.convert("RGB").save(
                temporary,
                format="JPEG",
                quality=95,
                optimize=False,
                progressive=False,
            )
        temporary.replace(target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return target


def _csv_rows(
    record: Mapping[str, Any], set_name: str, *, staging_dir: Path | None = None
) -> list[list[Any]]:
    source_path, width, height, source_format = _checked_image(record)
    digest = str(record["imageSha256"])
    image_path = _model_maker_image(
        source_path,
        source_format=source_format,
        digest=digest,
        staging_dir=staging_dir,
    )
    boxes = record.get("boxes")
    if not isinstance(boxes, list) or not boxes:
        raise TrainingConfigError("training record boxes must be a non-empty list")
    rows: list[list[Any]] = []
    for box in boxes:
        if not isinstance(box, Mapping):
            raise TrainingConfigError("training box must be an object")
        label = box.get("label")
        try:
            parse_card_label(label)  # type: ignore[arg-type]
        except ValueError as error:
            raise TrainingConfigError(f"invalid training label: {label!r}") from error
        coordinates: list[float] = []
        for key in ("xmin", "ymin", "xmax", "ymax"):
            value = box.get(key)
            if type(value) not in {int, float} or not math.isfinite(value):
                raise TrainingConfigError(f"box {key} must be finite numeric data")
            coordinates.append(float(value))
        xmin, ymin, xmax, ymax = coordinates
        if not (0 <= xmin < xmax <= width and 0 <= ymin < ymax <= height):
            if xmax <= xmin or ymax <= ymin:
                raise TrainingConfigError("training box must have positive area")
            raise TrainingConfigError("training box must remain inside the image")
        rows.append(
            [
                set_name,
                str(image_path),
                label,
                xmin / width,
                ymin / height,
                "",
                "",
                xmax / width,
                ymax / height,
                "",
                "",
            ]
        )
    return rows


def write_model_maker_csv(
    splits_path: str | Path,
    output_path: str | Path,
    *,
    train_partition: str,
    validation_partition: str,
) -> dict[str, int]:
    """Translate only train/validation records to Model Maker's CSV contract."""

    source = Path(splits_path)
    train, validation = _load_training_partitions(
        source,
        train_partition=train_partition,
        validation_partition=validation_partition,
    )
    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging_dir = target.parent / "model-maker-images"
    train_rows: list[list[Any]] = []
    for record in train:
        train_rows.extend(
            _csv_rows(record, _PARTITION_TO_CSV["train"], staging_dir=staging_dir)
        )
    validation_rows: list[list[Any]] = []
    for record in validation:
        validation_rows.extend(
            _csv_rows(record, _PARTITION_TO_CSV["validation"], staging_dir=staging_dir)
        )
    label_index = {label: index for index, label in enumerate(CARD_LABELS)}

    def canonical_row_order(row: list[Any]) -> tuple[int, str, float, float]:
        return label_index[str(row[2])], str(row[1]), float(row[3]), float(row[4])

    train_rows.sort(key=canonical_row_order)
    validation_rows.sort(key=canonical_row_order)
    rows = [*train_rows, *validation_rows]
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerows(rows)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return {"train": len(train), "validation": len(validation)}


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


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise TrainingConfigError(f"cannot hash required input {path}: {error}") from error


def _git_commit(project_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=project_root,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise TrainingConfigError("cannot resolve the Git commit for this run") from error
    return result.stdout.strip()


def prepare_training_run(
    *,
    config_path: str | Path,
    splits_path: str | Path,
    run_id: str,
    checkpoint_root: str | Path,
    requirements_lock: str | Path,
    git_commit: str | None = None,
) -> PreparedRun:
    """Validate all inputs before atomically creating reproducibility metadata."""

    if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id) or run_id in {".", ".."}:
        raise TrainingConfigError("run-id must be 1-64 safe filename characters")
    config_file = Path(config_path).resolve()
    splits_file = Path(splits_path).resolve()
    lock_file = Path(requirements_lock).resolve()
    config = load_training_config(config_file)
    train, validation = _load_training_partitions(
        splits_file,
        train_partition=config.train_partition,
        validation_partition=config.validation_partition,
    )
    if not train:
        raise TrainingConfigError("no audited training records; refusing to create a run")
    if not validation:
        raise TrainingConfigError("no audited validation records; refusing to create a run")
    # Validate every used byte before the first checkpoint-directory write.
    for record in train + validation:
        _csv_rows(record, "TRAIN")
    training_labels = {
        str(box["label"])
        for record in train
        for box in record["boxes"]
        if isinstance(box, Mapping) and "label" in box
    }
    if training_labels != set(CARD_LABELS):
        raise TrainingConfigError("training data must contain all 52 canonical classes")
    lock_hash = _sha256(lock_file)
    manifest_hash = _sha256(splits_file)
    commit = git_commit or _git_commit(config_file.parents[3])
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit):
        raise TrainingConfigError("git commit must be a hexadecimal revision")

    root = Path(checkpoint_root).resolve()
    output_dir = (root / run_id).resolve()
    if output_dir.parent != root:
        raise TrainingConfigError("run-id resolves outside the checkpoint root")
    if output_dir.exists():
        raise TrainingConfigError(f"run output already exists: {output_dir}")
    root.mkdir(parents=True, exist_ok=True)
    staging_dir = Path(tempfile.mkdtemp(prefix=f".{run_id}.", dir=root))
    resolved_yaml = yaml.safe_dump(config.document, sort_keys=True).encode("utf-8")
    metadata = {
        "schemaVersion": 1,
        "requirementsLockSha256": lock_hash,
        "inputManifestSha256": manifest_hash,
        "gitCommit": commit,
    }
    try:
        _atomic_write(staging_dir / "resolved-config.yaml", resolved_yaml)
        _atomic_write(
            staging_dir / "run-metadata.json",
            (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )
    except BaseException:
        shutil.rmtree(staging_dir, ignore_errors=True)
        raise
    return PreparedRun(config, staging_dir, output_dir, splits_file, lock_file)


def _augmentation_hparams(config: TrainingConfig) -> dict[str, Any]:
    return {
        "image_size": f"{config.image_size}x{config.image_size}",
        "input_rand_hflip": config.augmentation["horizontal_flip"],
        "jitter_min": 0.1 if config.augmentation["scale_jitter"] else 1.0,
        "jitter_max": 2.0 if config.augmentation["scale_jitter"] else 1.0,
        "autoaugment_policy": "v0" if config.augmentation["autoaugment"] else None,
    }


def assert_model_maker_label_maps(train_data: Any, validation_data: Any) -> None:
    """Require output indices 1..52 to match the canonical Android contract."""

    expected = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    if train_data.label_map != expected or validation_data.label_map != expected:
        raise TrainingConfigError(
            "Model Maker train and validation loaders must use the canonical label map"
        )


def validate_validation_metrics(metrics: Mapping[str, Any]) -> None:
    """Require aggregate AP and one finite per-class AP for every canonical card."""

    aggregate = metrics.get("AP")
    if (
        isinstance(aggregate, bool)
        or not isinstance(aggregate, Real)
        or not math.isfinite(aggregate)
    ):
        raise TrainingConfigError("validation metrics require a finite aggregate AP")
    expected = {f"AP_/{label}" for label in CARD_LABELS}
    actual = {key for key in metrics if key.startswith("AP_/")}
    if actual != expected:
        raise TrainingConfigError(
            "validation metrics require exactly 52 canonical per-class AP keys"
        )
    if any(
        isinstance(metrics[key], bool)
        or not isinstance(metrics[key], Real)
        or not math.isfinite(metrics[key])
        for key in expected
    ):
        raise TrainingConfigError("validation AP metrics must be finite")


def _run_model_maker_training(
    prepared: PreparedRun, one_batch_smoke: bool
) -> TrainingOutcome:
    """Execute Model Maker through fit; publishing is handled by the caller."""

    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    import numpy as np
    import tensorflow as tf
    from tensorflow_examples.lite.model_maker.third_party.efficientdet.keras import (
        train,
        train_lib,
    )
    from tflite_model_maker import object_detector

    config = prepared.config
    random.seed(config.seed)
    np.random.seed(config.seed)
    tf.random.set_seed(config.seed)
    csv_path = prepared.staging_dir / "annotations.csv"
    counts = write_model_maker_csv(
        prepared.splits_path,
        csv_path,
        train_partition=config.train_partition,
        validation_partition=config.validation_partition,
    )
    cache_dir = prepared.staging_dir / "model-maker-cache"
    train_data, validation_data, test_data = object_detector.DataLoader.from_csv(
        str(csv_path), num_shards=1, cache_dir=str(cache_dir)
    )
    if train_data is None or validation_data is None or test_data is not None:
        raise TrainingConfigError("Model Maker must receive train/validation data and no test data")
    assert_model_maker_label_maps(train_data, validation_data)
    batch_size = 1 if one_batch_smoke else config.batch_size
    if len(train_data) < batch_size:
        raise TrainingConfigError("training record count is smaller than batch_size")
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(prepared.staging_dir / "model"),
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        steps_per_execution=1,
        hparams=_augmentation_hparams(config),
        tf_random_seed=config.seed,
    )
    detector = object_detector.create(
        train_data,
        model_spec=spec,
        validation_data=validation_data,
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        train_whole_model=False if one_batch_smoke else config.train_whole_model,
        do_train=False,
    )
    train_ds, train_steps, _ = detector._get_dataset_and_steps(  # noqa: SLF001
        train_data, batch_size, is_training=True
    )
    validation_ds, validation_steps, validation_json = detector._get_dataset_and_steps(  # noqa: SLF001
        validation_data, batch_size, is_training=False
    )
    if one_batch_smoke:
        train_steps = 1
        validation_ds = None
        validation_steps = 0
        validation_json = None
    model = detector.create_model()
    spec.config.update(
        {
            "steps_per_epoch": train_steps,
            "eval_samples": batch_size * validation_steps,
            "val_json_file": validation_json,
            "batch_size": batch_size,
        }
    )
    train.setup_model(model, spec.config)
    train.init_experimental(spec.config)
    callbacks = train_lib.get_callbacks(spec.config.as_dict(), validation_ds)
    callbacks.append(
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss" if validation_ds is not None else "loss",
            patience=0 if one_batch_smoke else config.early_stopping_patience,
            restore_best_weights=True,
        )
    )
    history = model.fit(
        train_ds,
        epochs=1 if one_batch_smoke else config.epochs,
        steps_per_epoch=train_steps,
        callbacks=callbacks,
        validation_data=validation_ds,
        validation_steps=validation_steps or None,
        verbose=2,
    )
    detector.model = model
    return TrainingOutcome(
        model=model,
        detector=detector,
        validation_data=validation_data,
        history={name: list(values) for name, values in history.history.items()},
        record_counts=counts,
    )


def _save_selected_weights(model: Any, path: Path) -> str:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.stem}.", suffix=".weights.h5", dir=path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        model.save_weights(str(temporary), save_format="h5")
        with temporary.open("rb") as handle:
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return _sha256(path)


def _finalize_training_result(
    prepared: PreparedRun, outcome: TrainingOutcome, *, one_batch_smoke: bool
) -> dict[str, Any]:
    selected = prepared.staging_dir / "selected.weights.h5"
    selected_hash = _save_selected_weights(outcome.model, selected)
    outcome.model.load_weights(str(selected))
    outcome.detector.model = outcome.model
    validation_metrics = outcome.detector.evaluate(outcome.validation_data)
    validate_validation_metrics(validation_metrics)

    # Intermediate callback checkpoints are not candidates for Stage 6. Only the
    # restored-best, reloaded, evaluated checkpoint survives publication.
    shutil.rmtree(prepared.staging_dir / "model", ignore_errors=True)
    metrics = {
        "schemaVersion": 1,
        "status": "SMOKE_ONLY" if one_batch_smoke else "TRAINED_NOT_RELEASE_APPROVED",
        "recordCounts": outcome.record_counts,
        "history": {
            name: [float(value) for value in values] for name, values in outcome.history.items()
        },
        "validation": {name: float(value) for name, value in validation_metrics.items()},
        "selectedCheckpoint": selected.name,
        "selectedCheckpointSha256": selected_hash,
    }
    _atomic_write(
        prepared.staging_dir / "metrics.json",
        (json.dumps(metrics, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    return metrics


def _publish_staged_run(prepared: PreparedRun) -> None:
    if prepared.output_dir.exists():
        raise TrainingConfigError(f"run output already exists: {prepared.output_dir}")
    os.replace(prepared.staging_dir, prepared.output_dir)


def run_training(prepared: PreparedRun, *, one_batch_smoke: bool = False) -> dict[str, Any]:
    """Train, validate, then atomically publish one complete run directory."""

    try:
        outcome = _run_model_maker_training(prepared, one_batch_smoke)
        metrics = _finalize_training_result(
            prepared, outcome, one_batch_smoke=one_batch_smoke
        )
        _publish_staged_run(prepared)
        return metrics
    except BaseException:
        shutil.rmtree(prepared.staging_dir, ignore_errors=True)
        raise


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--splits", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--checkpoint-root", type=Path, default=Path("checkpoints"))
    parser.add_argument("--requirements-lock", type=Path, default=Path("requirements.lock"))
    parser.add_argument("--one-batch-smoke", action="store_true")
    args = parser.parse_args(argv)
    try:
        prepared = prepare_training_run(
            config_path=args.config,
            splits_path=args.splits,
            run_id=args.run_id,
            checkpoint_root=args.checkpoint_root,
            requirements_lock=args.requirements_lock,
        )
        run_training(prepared, one_batch_smoke=args.one_batch_smoke)
    except TrainingConfigError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
