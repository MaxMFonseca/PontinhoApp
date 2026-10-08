"""One-class training, leakage firewall and measured candidate contracts."""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import sys
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml
from PIL import Image

from pontinho_cards.two_stage_data import DetectorBox, DetectorRecord, TwoStageInputManifest

PROJECT = Path(__file__).resolve().parents[1]


def module():
    # Keep missing implementation a clear RED assertion rather than a collection error.
    assert importlib.util.find_spec("pontinho_cards.train_detector"), "one-class trainer absent"
    return importlib.import_module("pontinho_cards.train_detector")


def inputs(tmp_path):
    records = {}
    for partition, color in (("train", "white"), ("validation", "gray")):
        path = tmp_path / f"{partition}.png"
        Image.new("RGB", (320, 320), color).save(path)
        records[partition] = (
            DetectorRecord(
                partition,
                path,
                320,
                320,
                f"deck-{partition}",
                f"split-{partition}",
                hashlib.sha256(path.read_bytes()).hexdigest(),
                (DetectorBox("card", (32, 32, 160, 288)),),
            ),
        )
    return TwoStageInputManifest(records, {}, tmp_path)


def test_detector_config_enforces_one_class_training_contract():
    config = module().load_detector_config(PROJECT / "config/detector.yaml")
    assert config.labels == ("card",)
    assert config.detector_family == "efficientdet_lite0"
    assert config.image_size == 320
    assert config.seed == 20261015
    assert 1 < config.epochs <= 200
    assert config.early_stopping_enabled and config.early_stopping_patience < config.epochs
    assert (config.train_partition, config.validation_partition) == ("train", "validation")


@pytest.mark.parametrize(
    "key,value",
    [
        ("labels", ["AC"]),
        ("image_size", 224),
        ("detector_family", "other"),
        ("seed", True),
        ("holdout_path", "final-holdout.json"),
        ("partitions", {"train": "train", "validation": "test"}),
        ("confidence_threshold", float("nan")),
        ("confidence_threshold", 0),
        ("iou_threshold", 0),
    ],
)
def test_detector_config_rejects_unsafe_selection(tmp_path, key, value):
    api = module()
    document = yaml.safe_load((PROJECT / "config/detector.yaml").read_text())
    document[key] = value
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump(document))
    with pytest.raises(ValueError):
        api.load_detector_config(path)


@pytest.mark.parametrize("patience,expected_epochs", [(8, 3), (1, 2)])
def test_training_restores_validation_best_at_epoch_limit_or_patience(patience, expected_epochs):
    """Catch publishing last-epoch weights when pinned Keras never triggers patience."""
    api = module()
    assert hasattr(api, "_fit_and_restore_best"), "unconditional best-weight restoration absent"
    import numpy as np
    import tensorflow as tf

    model = tf.keras.Sequential([tf.keras.layers.Dense(1, use_bias=False, input_shape=(1,))])
    model.compile(optimizer=tf.keras.optimizers.SGD(learning_rate=0.0), loss="mse")

    class ValidationTrajectory(tf.keras.callbacks.Callback):
        def on_epoch_end(self, epoch, logs=None):
            # A controlled validation trajectory isolates checkpoint selection
            # from optimizer randomness while using the real Keras lifecycle.
            self.model.set_weights([np.asarray([[epoch + 1.0]], dtype=np.float32)])
            logs["val_loss"] = (0.1, 0.5, 0.6)[epoch]

    early = tf.keras.callbacks.EarlyStopping(
        monitor="val_loss",
        patience=patience,
        restore_best_weights=True,
    )
    history = api._fit_and_restore_best(
        model,
        np.ones((1, 1), dtype=np.float32),
        early_stopping=early,
        y=np.zeros((1, 1), dtype=np.float32),
        epochs=3,
        batch_size=1,
        callbacks=[ValidationTrajectory()],
        verbose=0,
    )
    assert len(history.history["loss"]) == expected_epochs
    assert early.stopped_epoch == (0 if expected_epochs == 3 else 1)
    np.testing.assert_array_equal(model.get_weights()[0], [[1.0]])


def test_snapshot_preserves_negatives_and_never_reads_holdout(tmp_path):
    api = module()
    data = inputs(tmp_path)
    negative_path = tmp_path / "negative.png"
    Image.new("RGB", (320, 320), "black").save(negative_path)
    negative = replace(
        data.detector_partitions["train"][0],
        image_id="negative",
        path=negative_path,
        boxes=(),
        source_image_sha256=hashlib.sha256(negative_path.read_bytes()).hexdigest(),
    )
    data.detector_partitions["train"] += (negative,)
    data.detector_partitions["test"] = object()  # Poison: never enumerate/open holdout.
    staged = api.snapshot_detector_inputs(data, tmp_path / "staging")
    assert len(staged["train"]) == 2
    assert staged["train"][1].boxes == ()
    assert (
        staged["train"][0].path.read_bytes()
        == data.detector_partitions["train"][0].path.read_bytes()
    )
    assert set(staged) == {"train", "validation"}


def test_snapshot_keeps_audited_jpeg_bytes_and_hash(tmp_path):
    api = module()
    data = inputs(tmp_path)
    path = tmp_path / "audited.jpg"
    Image.new("RGB", (320, 320), "red").save(path, format="JPEG")
    payload = path.read_bytes()
    data.detector_partitions["train"] = (
        replace(
            data.detector_partitions["train"][0],
            path=path,
            source_image_sha256=hashlib.sha256(payload).hexdigest(),
        ),
    )
    staged = api.snapshot_detector_inputs(data, tmp_path / "staging")
    assert staged["train"][0].path.read_bytes() == payload


@pytest.mark.parametrize(
    "fault", ["hash", "deck", "split", "pixels", "label", "bounds", "symlink", "outside"]
)
def test_snapshot_rejects_tampering_and_leakage_before_training(tmp_path, fault):
    api = module()
    data = inputs(tmp_path)
    train = data.detector_partitions["train"][0]
    record = data.detector_partitions["validation"][0]
    if fault == "hash":
        record = replace(record, source_image_sha256="0" * 64)
    elif fault == "deck":
        record = replace(record, deck_group_id=train.deck_group_id)
    elif fault == "split":
        record = replace(record, split_group_id=train.split_group_id)
    elif fault == "pixels":
        record = replace(record, path=train.path, source_image_sha256=train.source_image_sha256)
    elif fault == "label":
        record = replace(record, boxes=(DetectorBox("AC", (0, 0, 10, 10)),))
    elif fault == "bounds":
        record = replace(record, boxes=(DetectorBox("card", (0, 0, 321, 10)),))
    elif fault == "symlink":
        link = tmp_path / "linked.png"
        link.symlink_to(record.path)
        record = replace(record, path=link)
    else:
        record = replace(record, path=tmp_path / ".." / "outside.png")
    data.detector_partitions["validation"] = (record,)
    with pytest.raises(ValueError):
        api.snapshot_detector_inputs(data, tmp_path / "staging")
    assert not (tmp_path / "staging").exists()


def test_metrics_measure_misses_false_positives_and_deck_count_slices(tmp_path):
    api = module()
    data = inputs(tmp_path)
    record = data.detector_partitions["validation"][0]
    second = replace(record, image_id="second", boxes=record.boxes * 2)
    metrics = api.measure_detector_predictions(
        (record, second),
        {
            "validation": [(0.9, (0.1, 0.1, 0.5, 0.9)), (0.8, (0.7, 0.1, 0.9, 0.3))],
            "second": [(0.9, (0.1, 0.1, 0.5, 0.9))],
        },
        score_threshold=0.5,
        iou_threshold=0.5,
    )
    assert metrics["recall"] == pytest.approx(2 / 3)
    assert metrics["precision"] == pytest.approx(2 / 3)
    assert metrics["falsePositives"] == 1
    assert metrics["falsePositiveShare"] == pytest.approx(1 / 3)
    assert metrics["AP50"] == pytest.approx(2 / 3)
    assert metrics["byCardCount"]["2"]["falseNegatives"] == 1
    assert metrics["byDeckGroup"]["deck-validation"]["truePositives"] == 2
    assert metrics["status"] == "BLOCKED_DETECTOR_QUALITY"


def test_perfect_tiny_validation_cannot_pass_missing_buckets_or_negatives(tmp_path):
    api = module()
    record = inputs(tmp_path).detector_partitions["validation"][0]
    metrics = api.measure_detector_predictions(
        (record,),
        {"validation": [(1.0, (0.1, 0.1, 0.5, 0.9))]},
        score_threshold=0.5,
        iou_threshold=0.5,
    )
    assert metrics["recall"] == metrics["precision"] == metrics["AP50"] == 1
    assert metrics["status"] == "BLOCKED_DETECTOR_QUALITY"
    assert metrics["missingCardCountBuckets"] == [3, 5, 8, 10]
    assert metrics["negativeSceneCount"] == 0


def test_training_failure_publishes_no_partial_candidate(tmp_path, monkeypatch):
    api = module()
    data = inputs(tmp_path)
    config = api.load_detector_config(PROJECT / "config/detector.yaml")

    def fail(*args, **kwargs):
        raise RuntimeError("training failed")

    monkeypatch.setattr(api, "_fit_detector", fail)
    with pytest.raises(RuntimeError, match="training failed"):
        api.train_detector(data, config, "failed", candidate_root=tmp_path / "detector-candidates")
    assert not (tmp_path / "detector-candidates/failed").exists()


@pytest.mark.parametrize("existing", ["directory", "symlink"])
def test_atomic_publication_never_replaces_existing_run(tmp_path, existing):
    api = module()
    staging = tmp_path / "staging"
    staging.mkdir()
    (staging / "model").write_bytes(b"new model")
    root = tmp_path / "published"
    root.mkdir()
    target = root / "run"
    if existing == "directory":
        target.mkdir()
    else:
        target.symlink_to(staging, target_is_directory=True)
    with pytest.raises(OSError):
        api._publish_candidate(staging, root, "run", ["model"])
    assert list(root.iterdir()) == [target]
    assert target.is_symlink() if existing == "symlink" else list(target.iterdir()) == []


def test_atomic_publication_removes_partial_copy_on_missing_artifact(tmp_path):
    api = module()
    staging = tmp_path / "staging"
    staging.mkdir()
    (staging / "model").write_bytes(b"new model")
    root = tmp_path / "published"
    with pytest.raises(OSError):
        api._publish_candidate(staging, root, "run", ["model", "missing"])
    assert list(root.iterdir()) == []


def test_frozen_run_records_cannot_be_replaced_during_training(tmp_path, monkeypatch):
    api = module()
    data = inputs(tmp_path)
    original = data.detector_partitions["train"][0]
    config = api.load_detector_config(PROJECT / "config/detector.yaml")

    def inspect_snapshot(partitions, config, staging, smoke):
        data.detector_partitions["train"] = ()
        manifest = json.loads((staging / "training-inputs.json").read_text())
        assert manifest["train"][0]["source_image_sha256"] == original.source_image_sha256
        assert manifest["train"][0]["boxes"] == [{"label": "card", "bounds": [32, 32, 160, 288]}]
        raise RuntimeError("snapshot verified")

    monkeypatch.setattr(api, "_fit_detector", inspect_snapshot)
    with pytest.raises(RuntimeError, match="snapshot verified"):
        api.train_detector(data, config, "immutable")


def test_published_candidate_binds_exact_models_metrics_and_training_inputs(tmp_path, monkeypatch):
    """Exercise real staging/publication; replace only expensive native ML operations."""
    api = module()
    import pontinho_cards.export as exporting

    class NativeDetector:
        model_spec = SimpleNamespace(get_default_quantization_config=lambda _: "int8")

        def __init__(self):
            self.model = self

        def save_weights(self, path, save_format):
            Path(path).write_bytes(b"selected weights")

        def load_weights(self, path):
            assert Path(path).read_bytes() == b"selected weights"

        def export(self, directory, tflite_filename, quantization_config, with_metadata):
            Path(directory, tflite_filename).write_bytes(quantization_config.encode())

    monkeypatch.setattr(
        api, "_fit_detector", lambda *args: (NativeDetector(), None, {"loss": [1.0]})
    )
    monkeypatch.setattr(
        api,
        "_infer_detector",
        lambda path, inspected, records: {
            r.image_id: [(0.9, (0.1, 0.1, 0.5, 0.9))] for r in records
        },
    )
    monkeypatch.setattr(
        exporting,
        "inspect_tflite",
        lambda path, **kwargs: {
            "modelSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "labels": ["card"],
        },
    )
    monkeypatch.setitem(
        sys.modules,
        "tensorflow_examples.lite.model_maker.core.task",
        SimpleNamespace(
            configs=SimpleNamespace(
                QuantizationConfig=SimpleNamespace(for_float16=lambda: "float16")
            ),
        ),
    )
    candidate = api.train_detector(
        inputs(tmp_path),
        api.load_detector_config(PROJECT / "config/detector.yaml"),
        "bound",
        candidate_root=tmp_path / "published",
    )
    contract = json.loads(candidate.contract_path.read_text())
    metrics = json.loads(candidate.validation_metrics_path.read_text())
    assert (
        contract["validationMetricsSha256"]
        == hashlib.sha256(candidate.validation_metrics_path.read_bytes()).hexdigest()
    )
    assert (
        contract["trainingInputSha256"]
        == hashlib.sha256(
            (candidate.model_path.parent / "training-inputs.json").read_bytes()
        ).hexdigest()
    )
    assert metrics["status"] == "BLOCKED_DETECTOR_QUALITY"
    assert contract["releaseApproved"] is False
    assert contract["selectedQuantization"] is None
    for variant in ("float16", "int8"):
        actual = hashlib.sha256(
            (candidate.model_path.parent / f"card_detector.{variant}.tflite").read_bytes()
        ).hexdigest()
        assert (
            metrics["variants"][variant]["modelSha256"]
            == contract["variants"][variant]["modelSha256"]
            == actual
        )


@pytest.mark.skipif(
    os.environ.get("PONTINHO_RUN_MODEL_MAKER_SMOKE") != "1",
    reason="opt-in real Model Maker CPU batch and exports",
)
def test_real_one_batch_reloads_weights_and_exports_both_detectors(tmp_path):
    api = module()
    data = inputs(tmp_path)
    for partition, color in (("train", "black"), ("validation", "navy")):
        path = tmp_path / f"negative-{partition}.png"
        Image.new("RGB", (320, 320), color).save(path)
        negative = replace(
            data.detector_partitions[partition][0],
            image_id=f"negative-{partition}",
            path=path,
            boxes=(),
            source_image_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        )
        data.detector_partitions[partition] += (negative,)
    candidate = api.train_detector(
        data,
        api.load_detector_config(PROJECT / "config/detector.yaml"),
        "generated-smoke-only",
        one_batch_smoke=True,
        candidate_root=tmp_path / "candidates",
    )
    contract = json.loads(candidate.contract_path.read_text())
    metrics = json.loads(candidate.validation_metrics_path.read_text())
    assert metrics["status"] == "SMOKE_ONLY"
    assert len(metrics["history"]["loss"]) == 1
    assert contract["labels"] == ["card"]
    assert set(contract["variants"]) == {"float16", "int8"}
    assert all(v["interpreterInvocation"] == "passed" for v in contract["variants"].values())
    assert candidate.model_path.is_file()
    assert (
        contract["validationMetricsSha256"]
        == hashlib.sha256(candidate.validation_metrics_path.read_bytes()).hexdigest()
    )
    assert all(value["negativeSceneCount"] == 1 for value in metrics["variants"].values())
    assert (
        contract["selectedCheckpointSha256"]
        == hashlib.sha256(
            (candidate.model_path.parent / "selected.weights.h5").read_bytes()
        ).hexdigest()
    )
