"""Training contract tests: reproducibility, isolation, and fail-closed inputs."""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml
from PIL import Image

import pontinho_cards.train as train_module
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.train import (
    TrainingConfigError,
    TrainingOutcome,
    assert_model_maker_label_maps,
    load_training_config,
    main,
    prepare_training_run,
    run_training,
    validate_validation_metrics,
    write_model_maker_csv,
)

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _record(
    image_path: Path, label: str = "AC", *, full_vocabulary: bool = False
) -> dict[str, object]:
    payload = image_path.read_bytes()
    labels = CARD_LABELS if full_vocabulary else (label,)
    return {
        "imageId": image_path.stem,
        "path": str(image_path),
        "width": 32,
        "height": 32,
        "sourceId": "fixture-source",
        "deckGroupId": f"deck-{image_path.stem}",
        "splitGroupId": f"split-{image_path.stem}",
        "sourceDerivativeId": f"derivative-{image_path.stem}",
        "augmentationParentId": None,
        "imageSha256": hashlib.sha256(payload).hexdigest(),
        "isRealPhoto": True,
        "boxes": [
            {
                "label": box_label,
                "xmin": 2.0,
                "ymin": 3.0,
                "xmax": 22.0,
                "ymax": 25.0,
            }
            for box_label in labels
        ],
    }


def _split_document(tmp_path: Path, *, full_vocabulary: bool = False) -> Path:
    train_image = tmp_path / "train.png"
    validation_image = tmp_path / "validation.png"
    Image.new("RGB", (32, 32), "white").save(train_image)
    Image.new("RGB", (32, 32), "gray").save(validation_image)
    path = tmp_path / "splits.json"
    path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "seed": 20261014,
                "partitions": {
                    "train": [_record(train_image, full_vocabulary=full_vocabulary)],
                    "validation": [_record(validation_image, "2C")],
                    # Deliberately invalid: training must never inspect this partition.
                    "test": [{"path": "final-holdout-must-not-be-read.jpg"}],
                },
            }
        ),
        encoding="utf-8",
    )
    return path


def test_repository_config_defines_a_bounded_reproducible_training_contract() -> None:
    """Catch an unbounded/non-deterministic job or a detector with the wrong vocabulary."""

    config = load_training_config(PROJECT_ROOT / "config" / "model.yaml")

    assert config.detector_family == "efficientdet_lite0"
    assert config.labels == CARD_LABELS
    assert config.seed == 20261014
    assert config.image_size == 320
    assert 1 <= config.batch_size <= 64
    assert config.confidence_threshold == 0.5
    assert config.iou_threshold == 0.5
    assert 1 <= config.epochs <= 200
    assert config.early_stopping_enabled is True
    assert 1 <= config.early_stopping_patience < config.epochs
    assert config.train_partition == "train"
    assert config.validation_partition == "validation"
    assert config.augmentation == {
        "autoaugment": False,
        "horizontal_flip": True,
        "scale_jitter": True,
    }


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        ({"detector_family": "efficientdet_lite1"}, "efficientdet_lite0"),
        ({"labels": list(CARD_LABELS[:-1])}, "exactly CARD_LABELS"),
        ({"seed": None}, "seed"),
        ({"batch_size": 0}, "batch_size"),
        ({"batch_size": True}, "batch_size"),
        ({"batch_size": 65}, "batch_size"),
        ({"image_size": 321}, "image_size"),
        ({"image_size": True}, "image_size"),
        ({"confidence_threshold": True}, "confidence_threshold"),
        ({"confidence_threshold": math.nan}, "confidence_threshold"),
        ({"confidence_threshold": math.inf}, "confidence_threshold"),
        ({"confidence_threshold": -0.01}, "confidence_threshold"),
        ({"confidence_threshold": 1.01}, "confidence_threshold"),
        ({"iou_threshold": True}, "iou_threshold"),
        ({"iou_threshold": math.nan}, "iou_threshold"),
        ({"iou_threshold": math.inf}, "iou_threshold"),
        ({"iou_threshold": -0.01}, "iou_threshold"),
        ({"iou_threshold": 1.01}, "iou_threshold"),
        ({"training": {"epochs": 0}}, "epochs"),
        ({"training": {"early_stopping": {"enabled": False, "patience": 8}}}, "early stopping"),
        ({"partitions": {"train": "train", "validation": "test"}}, "frozen test"),
    ],
)
def test_config_rejects_unsafe_training_changes(
    tmp_path: Path, mutation: dict[str, object], message: str
) -> None:
    """Catch a config mutation that could train the wrong or non-reproducible model."""

    document = yaml.safe_load((PROJECT_ROOT / "config" / "model.yaml").read_text())
    for key, value in mutation.items():
        if isinstance(value, dict) and isinstance(document.get(key), dict):
            document[key].update(value)
        else:
            document[key] = value
    path = tmp_path / "model.yaml"
    path.write_text(yaml.safe_dump(document), encoding="utf-8")

    with pytest.raises(TrainingConfigError, match=message):
        load_training_config(path)


@pytest.mark.parametrize(
    ("box", "message"),
    [
        ({"label": "joker", "xmin": 2, "ymin": 3, "xmax": 22, "ymax": 25}, "label"),
        ({"label": "AC", "xmin": 2, "ymin": 3, "xmax": 2, "ymax": 25}, "positive area"),
        ({"label": "AC", "xmin": -1, "ymin": 3, "xmax": 22, "ymax": 25}, "inside"),
    ],
)
def test_model_maker_csv_rejects_invalid_labels_and_boxes(
    tmp_path: Path, box: dict[str, object], message: str
) -> None:
    """Catch malformed examples before Model Maker or a checkpoint sees them."""

    split_path = _split_document(tmp_path)
    document = json.loads(split_path.read_text(encoding="utf-8"))
    document["partitions"]["train"][0]["boxes"] = [box]
    split_path.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(TrainingConfigError, match=message):
        write_model_maker_csv(
            split_path,
            tmp_path / "annotations.csv",
            train_partition="train",
            validation_partition="validation",
        )


def test_model_maker_csv_reads_only_train_and_validation(tmp_path: Path) -> None:
    """Catch accidental use of the frozen final partition during model selection."""

    split_path = _split_document(tmp_path)
    csv_path = tmp_path / "annotations.csv"

    counts = write_model_maker_csv(
        split_path,
        csv_path,
        train_partition="train",
        validation_partition="validation",
    )

    lines = csv_path.read_text(encoding="utf-8").splitlines()
    assert counts == {"train": 1, "validation": 1}
    assert len(lines) == 2
    assert lines[0].startswith("TRAIN,")
    assert lines[1].startswith("VAL,")
    assert all("TEST" not in line for line in lines)
    assert "final-holdout-must-not-be-read" not in csv_path.read_text(encoding="utf-8")


def test_model_maker_label_first_occurrences_are_canonical_when_boxes_are_shuffled(
    tmp_path: Path,
) -> None:
    """Catch class indices that change with manifest or box ordering."""

    split_path = _split_document(tmp_path, full_vocabulary=True)
    document = json.loads(split_path.read_text(encoding="utf-8"))
    document["partitions"]["train"][0]["boxes"].reverse()
    document["partitions"]["validation"][0]["boxes"] = list(
        reversed(document["partitions"]["train"][0]["boxes"])
    )
    split_path.write_text(json.dumps(document), encoding="utf-8")
    csv_path = tmp_path / "annotations.csv"

    write_model_maker_csv(
        split_path,
        csv_path,
        train_partition="train",
        validation_partition="validation",
    )

    rows = [line.split(",") for line in csv_path.read_text(encoding="utf-8").splitlines()]
    first_occurrences: list[str] = []
    for row in rows:
        if row[2] not in first_occurrences:
            first_occurrences.append(row[2])
    assert tuple(first_occurrences) == CARD_LABELS


def test_model_maker_loaders_must_share_exact_canonical_label_map() -> None:
    """Catch a loader whose output indices differ from the Android contract."""

    canonical = {index: label for index, label in enumerate(CARD_LABELS, start=1)}
    assert_model_maker_label_maps(
        SimpleNamespace(label_map=canonical), SimpleNamespace(label_map=canonical)
    )
    shuffled = dict(reversed(tuple(canonical.items())))
    shuffled[1], shuffled[52] = shuffled[52], shuffled[1]

    with pytest.raises(TrainingConfigError, match="canonical label map"):
        assert_model_maker_label_maps(
            SimpleNamespace(label_map=shuffled), SimpleNamespace(label_map=canonical)
        )


def test_empty_training_inputs_fail_before_creating_a_run(tmp_path: Path) -> None:
    """Catch fake checkpoints/metrics when audited training crops do not exist."""

    splits = tmp_path / "splits.json"
    splits.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "seed": 20261014,
                "partitions": {"train": [], "validation": [], "test": []},
            }
        ),
        encoding="utf-8",
    )
    checkpoint_root = tmp_path / "checkpoints"

    with pytest.raises(TrainingConfigError, match="no audited training records"):
        prepare_training_run(
            config_path=PROJECT_ROOT / "config" / "model.yaml",
            splits_path=splits,
            run_id="blocked-run",
            checkpoint_root=checkpoint_root,
            requirements_lock=PROJECT_ROOT / "requirements.lock",
            git_commit="172a705d",
        )

    assert not checkpoint_root.exists()


def test_cli_reports_missing_audited_splits_without_a_traceback(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Catch an opaque crash when the intentionally absent real dataset blocks training."""

    with pytest.raises(SystemExit) as exit_info:
        main(
            [
                "--config",
                str(PROJECT_ROOT / "config" / "model.yaml"),
                "--splits",
                str(tmp_path / "absent-splits.json"),
                "--run-id",
                "blocked-real-run",
                "--checkpoint-root",
                str(tmp_path / "checkpoints"),
                "--requirements-lock",
                str(PROJECT_ROOT / "requirements.lock"),
            ]
        )

    error = capsys.readouterr().err
    assert exit_info.value.code == 2
    assert "cannot read split manifest" in error
    assert "Traceback" not in error
    assert not (tmp_path / "checkpoints").exists()


def test_incomplete_training_vocabulary_fails_before_creating_a_run(tmp_path: Path) -> None:
    """Catch a detector whose output label map silently omits card classes."""

    checkpoint_root = tmp_path / "checkpoints"

    with pytest.raises(TrainingConfigError, match="all 52 canonical classes"):
        prepare_training_run(
            config_path=PROJECT_ROOT / "config" / "model.yaml",
            splits_path=_split_document(tmp_path),
            run_id="incomplete-vocabulary",
            checkpoint_root=checkpoint_root,
            requirements_lock=PROJECT_ROOT / "requirements.lock",
            git_commit="172a705d",
        )

    assert not checkpoint_root.exists()


@pytest.mark.parametrize("run_id", ["../escape", ".", "two words", "a/b", "a\\b", ""])
def test_run_id_cannot_escape_the_checkpoint_root(tmp_path: Path, run_id: str) -> None:
    """Catch path traversal or ambiguous output directories before any write."""

    with pytest.raises(TrainingConfigError, match="run-id"):
        prepare_training_run(
            config_path=PROJECT_ROOT / "config" / "model.yaml",
            splits_path=_split_document(tmp_path),
            run_id=run_id,
            checkpoint_root=tmp_path / "checkpoints",
            requirements_lock=PROJECT_ROOT / "requirements.lock",
            git_commit="172a705d",
        )


def test_run_metadata_hashes_every_reproducibility_input_atomically(tmp_path: Path) -> None:
    """Catch an output that cannot be tied back to config, lock, manifest, and Git."""

    split_path = _split_document(tmp_path, full_vocabulary=True)
    lock_path = tmp_path / "requirements.lock"
    lock_path.write_bytes(b"package==1.0 --hash=sha256:abc\n")
    checkpoint_root = tmp_path / "checkpoints"

    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=split_path,
        run_id="smoke-001",
        checkpoint_root=checkpoint_root,
        requirements_lock=lock_path,
        git_commit="0123456789abcdef",
    )

    metadata = json.loads((prepared.staging_dir / "run-metadata.json").read_text(encoding="utf-8"))
    assert metadata == {
        "gitCommit": "0123456789abcdef",
        "inputManifestSha256": hashlib.sha256(split_path.read_bytes()).hexdigest(),
        "requirementsLockSha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
        "schemaVersion": 1,
    }
    assert (prepared.staging_dir / "resolved-config.yaml").exists()
    assert not prepared.output_dir.exists()
    assert not list(prepared.staging_dir.glob(".*.tmp"))
    assert not (prepared.staging_dir / "metrics.json").exists()
    assert not list(prepared.staging_dir.glob("*.ckpt"))


def test_metadata_failure_never_publishes_a_partial_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a half-written run directory if the second metadata write fails."""

    split_path = _split_document(tmp_path, full_vocabulary=True)
    checkpoint_root = tmp_path / "checkpoints"
    real_atomic_write = train_module._atomic_write
    writes = 0

    def fail_second_write(path: Path, payload: bytes) -> None:
        nonlocal writes
        writes += 1
        if writes == 2:
            raise OSError("simulated metadata disk failure")
        real_atomic_write(path, payload)

    monkeypatch.setattr(train_module, "_atomic_write", fail_second_write)

    with pytest.raises(OSError, match="simulated metadata disk failure"):
        prepare_training_run(
            config_path=PROJECT_ROOT / "config" / "model.yaml",
            splits_path=split_path,
            run_id="atomic-run",
            checkpoint_root=checkpoint_root,
            requirements_lock=PROJECT_ROOT / "requirements.lock",
            git_commit="172a705d",
        )

    assert not (checkpoint_root / "atomic-run").exists()


class _FakeModel:
    def __init__(self) -> None:
        self.loaded_selected_weights = False

    def save_weights(self, path: str, save_format: str) -> None:
        assert save_format == "h5"
        Path(path).write_bytes(b"restored-best-weights")

    def load_weights(self, path: str) -> None:
        assert Path(path).read_bytes() == b"restored-best-weights"
        self.loaded_selected_weights = True


class _FakeDetector:
    def __init__(self, model: _FakeModel) -> None:
        self.model = model

    def evaluate(self, validation_data: object) -> dict[str, float]:
        assert validation_data is not None
        assert self.model.loaded_selected_weights is True
        return {
            "AP": 0.25,
            **{f"AP_/{label}": 0.1 for label in CARD_LABELS},
        }


def _fake_outcome() -> TrainingOutcome:
    model = _FakeModel()
    return TrainingOutcome(
        model=model,
        detector=_FakeDetector(model),
        validation_data=object(),
        history={"loss": [2.0, 1.0]},
        record_counts={"train": 1, "validation": 1},
    )


def test_success_publishes_one_selected_checkpoint_and_matching_metrics(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch ambiguous checkpoints or evaluation of weights other than the selected best."""

    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=_split_document(tmp_path, full_vocabulary=True),
        run_id="selected-best",
        checkpoint_root=tmp_path / "checkpoints",
        requirements_lock=PROJECT_ROOT / "requirements.lock",
        git_commit="172a705d",
    )
    monkeypatch.setattr(train_module, "_run_model_maker_training", lambda *_: _fake_outcome())

    metrics = run_training(prepared)

    selected = prepared.output_dir / "selected.weights.h5"
    assert selected.read_bytes() == b"restored-best-weights"
    selected_hash = hashlib.sha256(selected.read_bytes()).hexdigest()
    assert metrics["selectedCheckpoint"] == "selected.weights.h5"
    assert metrics["selectedCheckpointSha256"] == selected_hash
    assert json.loads((prepared.output_dir / "metrics.json").read_text()) == metrics
    assert not prepared.staging_dir.exists()
    assert not list((tmp_path / "checkpoints").glob(".selected-best.*"))


def test_mid_training_failure_cleans_staging_and_never_publishes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a failed fit that leaves a reusable-looking run."""

    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=_split_document(tmp_path, full_vocabulary=True),
        run_id="fit-fails",
        checkpoint_root=tmp_path / "checkpoints",
        requirements_lock=PROJECT_ROOT / "requirements.lock",
        git_commit="172a705d",
    )

    def fail_training(*_args: object) -> TrainingOutcome:
        raise RuntimeError("simulated fit failure")

    monkeypatch.setattr(train_module, "_run_model_maker_training", fail_training)

    with pytest.raises(RuntimeError, match="simulated fit failure"):
        run_training(prepared)

    assert not prepared.output_dir.exists()
    assert not prepared.staging_dir.exists()
    assert not list((tmp_path / "checkpoints").glob(".fit-fails.*"))


def test_publication_failure_cleans_complete_staging_without_replacing_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a failed final rename that leaks a complete but unpublished run."""

    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=_split_document(tmp_path, full_vocabulary=True),
        run_id="publish-fails",
        checkpoint_root=tmp_path / "checkpoints",
        requirements_lock=PROJECT_ROOT / "requirements.lock",
        git_commit="172a705d",
    )
    monkeypatch.setattr(train_module, "_run_model_maker_training", lambda *_: _fake_outcome())

    def fail_publication(_prepared: object) -> None:
        raise OSError("simulated publication failure")

    monkeypatch.setattr(train_module, "_publish_staged_run", fail_publication)

    with pytest.raises(OSError, match="simulated publication failure"):
        run_training(prepared)

    assert not prepared.output_dir.exists()
    assert not prepared.staging_dir.exists()
    assert not list((tmp_path / "checkpoints").glob(".publish-fails.*"))


def test_validation_metrics_require_aggregate_and_exactly_52_per_class_ap() -> None:
    """Catch incomplete evaluation output before metrics are published."""

    valid = {"AP": 0.25, **{f"AP_/{label}": 0.1 for label in CARD_LABELS}}
    validate_validation_metrics(valid)

    with pytest.raises(TrainingConfigError, match="aggregate AP"):
        validate_validation_metrics({key: value for key, value in valid.items() if key != "AP"})
    with pytest.raises(TrainingConfigError, match="52 canonical per-class AP"):
        validate_validation_metrics(
            {key: value for key, value in valid.items() if key != "AP_/KS"}
        )
    with pytest.raises(TrainingConfigError, match="finite"):
        validate_validation_metrics({**valid, "AP_/KS": math.nan})
