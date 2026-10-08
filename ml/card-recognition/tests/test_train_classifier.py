"""Classifier identity, leakage, calibration and real export regression tests."""

import hashlib
import importlib
import json
import os
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest
import yaml
from PIL import Image

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.two_stage_data import ClassifierCrop, TwoStageInputManifest

PROJECT = Path(__file__).resolve().parents[1]


def module():
    assert importlib.util.find_spec("pontinho_cards.train_classifier"), "classifier trainer absent"
    return importlib.import_module("pontinho_cards.train_classifier")


def inputs(root):
    partitions = {}
    for partition, offset in (("train", 0), ("validation", 90)):
        records = []
        for index, label in enumerate(CARD_LABELS):
            directory = root / "classifier" / partition / label
            directory.mkdir(parents=True)
            temporary = directory / "image.png"
            Image.new("RGB", (40, 60), (index + offset, 20, 100)).save(temporary)
            digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
            source = hashlib.sha256(f"{partition}-{index}".encode()).hexdigest()
            path = directory / f"{source}-b0000-{label}-{digest}.png"
            temporary.rename(path)
            records.append(ClassifierCrop(label, (10, 20, 50, 80), partition, source, path))
        partitions[partition] = tuple(records)
    return TwoStageInputManifest({}, partitions, root)


def test_config_pins_identity_safe_bounded_training():
    config = module().load_classifier_config(PROJECT / "config/classifier.yaml")
    assert config.labels == CARD_LABELS
    assert config.classifier_family == "efficientnet_lite0"
    assert config.image_size == 224
    assert config.seed == 20261015
    assert 1 < config.epochs <= 200
    assert 0 < config.early_stopping_patience < config.epochs
    assert config.augmentation == {"rotation_180": True, "brightness_delta": 0.1}


@pytest.mark.parametrize(
    "key,value",
    [
        ("labels", sorted(CARD_LABELS)),
        ("image_size", 320),
        ("seed", True),
        ("classifier_family", "mobilenet"),
        ("holdout", "test"),
        ("partitions", {"train": "train", "validation": "test"}),
        ("training", {"epochs": 1000}),
    ],
)
def test_config_rejects_label_reordering_and_unsafe_options(tmp_path, key, value):
    api = module()
    document = yaml.safe_load((PROJECT / "config/classifier.yaml").read_text())
    document[key] = value
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump(document))
    with pytest.raises(ValueError):
        api.load_classifier_config(path)


def test_snapshot_keeps_original_crop_pixels_and_never_reads_holdout(tmp_path):
    api = module()
    data = inputs(tmp_path)
    data.classifier_partitions["test"] = object()
    staged = api.snapshot_classifier_inputs(data, tmp_path / "staging")
    assert set(staged) == {"train", "validation"}
    for partition in staged:
        assert len(staged[partition]) == 52
        assert (
            staged[partition][0].path.read_bytes()
            == data.classifier_partitions[partition][0].path.read_bytes()
        )
        assert Image.open(staged[partition][0].path).size == (40, 60)


@pytest.mark.parametrize(
    "fault",
    [
        "missing_class",
        "deck",
        "source",
        "hash",
        "label",
        "bounds",
        "symlink",
        "outside",
        "duplicate",
    ],
)
def test_snapshot_rejects_tampering_before_writing(tmp_path, fault):
    api = module()
    data = inputs(tmp_path)
    records = list(data.classifier_partitions["validation"])
    record = records[0]
    if fault == "missing_class":
        records = records[1:]
    elif fault == "duplicate":
        records.append(record)
    elif fault == "deck":
        records[0] = replace(record, deck_group_id="train")
    elif fault == "source":
        records[0] = replace(
            record, source_image_sha256=data.classifier_partitions["train"][0].source_image_sha256
        )
    elif fault == "hash":
        Image.new("RGB", (40, 60), "red").save(record.path)
    elif fault == "label":
        records[0] = replace(record, label="2C")
    elif fault == "bounds":
        records[0] = replace(record, source_bounds=(0, 0, 224, 224))
    elif fault == "symlink":
        link = tmp_path / "link.png"
        link.symlink_to(record.path)
        records[0] = replace(record, path=link)
    else:
        records[0] = replace(record, path=tmp_path / ".." / record.path.name)
    data.classifier_partitions["validation"] = tuple(records)
    with pytest.raises(ValueError):
        api.snapshot_classifier_inputs(data, tmp_path / "staging")
    assert not (tmp_path / "staging").exists()


def test_validation_calibration_measures_actual_errors_by_class_rank_suit_and_deck(tmp_path):
    api = module()
    records = inputs(tmp_path).classifier_partitions["validation"]
    probabilities = np.full((52, 52), 0.1 / 51)
    np.fill_diagonal(probabilities, 0.9)
    probabilities[0] = 0.6 / 51
    probabilities[0, 1] = 0.4
    calibrated, calibration = api.calibrate_validation_scores(probabilities, records)
    metrics = api.measure_classifier_predictions(records, calibrated)
    assert calibration["partition"] == "validation"
    assert calibration["nllAfter"] <= calibration["nllBefore"]
    assert metrics["top1Accuracy"] == pytest.approx(51 / 52)
    assert metrics["perClass"]["AC"]["correct"] == 0
    assert metrics["perRank"]["A"]["correct"] == 3
    assert metrics["perSuit"]["C"]["correct"] == 12
    assert metrics["perDeck"]["validation"]["count"] == 52
    assert metrics["confidenceCorrectnessAuc"] == 1
    assert metrics["status"] == "VALIDATION_SEPARATION_OBSERVED_NOT_RELEASE_APPROVED"


def test_calibration_cannot_claim_quality_for_indistinguishable_wrong_scores(tmp_path):
    api = module()
    records = inputs(tmp_path).classifier_partitions["validation"]
    probabilities = np.full((52, 52), 1 / 52)
    calibrated, _ = api.calibrate_validation_scores(probabilities, records)
    metrics = api.measure_classifier_predictions(records, calibrated)
    assert metrics["status"] == "BLOCKED_CLASSIFIER_QUALITY"
    assert metrics["confidenceCorrectnessAuc"] == 0.5


@pytest.mark.parametrize("bad", [np.ones((52, 51)), np.full((52, 52), np.nan), np.ones((52, 52))])
def test_calibration_rejects_invalid_probabilities(tmp_path, bad):
    with pytest.raises(ValueError):
        module().calibrate_validation_scores(
            bad, inputs(tmp_path).classifier_partitions["validation"]
        )


def test_training_failure_publishes_no_candidate(tmp_path, monkeypatch):
    api = module()
    data = inputs(tmp_path)

    def fail(partitions, config, staging, smoke):
        data.classifier_partitions["train"] = ()
        manifest = json.loads((staging / "training-inputs.json").read_text())
        assert len(manifest["train"]) == 52
        raise RuntimeError("native training failed")

    monkeypatch.setattr(api, "_fit_classifier", fail)
    with pytest.raises(RuntimeError, match="native training failed"):
        api.train_classifier(
            data,
            api.load_classifier_config(PROJECT / "config/classifier.yaml"),
            "failed",
            candidate_root=tmp_path / "candidates",
        )
    assert not (tmp_path / "candidates").exists()


@pytest.mark.skipif(
    os.environ.get("PONTINHO_RUN_MODEL_MAKER_SMOKE") != "1",
    reason="opt-in real classifier batch and exports",
)
def test_real_classifier_batch_exports_canonical_models_and_binds_evidence(tmp_path):
    api = module()
    candidate = api.train_classifier(
        inputs(tmp_path),
        api.load_classifier_config(PROJECT / "config/classifier.yaml"),
        "generated-smoke-only",
        one_batch_smoke=True,
        candidate_root=tmp_path / "candidates",
    )
    contract = json.loads(candidate.contract_path.read_text())
    metrics = json.loads(candidate.validation_metrics_path.read_text())
    assert metrics["status"] == "SMOKE_ONLY"
    assert contract["labels"] == list(CARD_LABELS)
    assert contract["releaseApproved"] is False
    assert contract["selectedQuantization"] is None
    assert len(metrics["history"]["loss"]) == 1
    for variant in ("float16", "int8"):
        inspected = contract["variants"][variant]
        assert inspected["labels"] == list(CARD_LABELS)
        assert inspected["interpreterInvocation"] == "passed"
        assert metrics["variants"][variant]["calibration"]["partition"] == "validation"
        assert (
            inspected["modelSha256"]
            == hashlib.sha256(
                (candidate.model_path.parent / inspected["filename"]).read_bytes()
            ).hexdigest()
        )
    for field, filename in (
        ("validationMetricsSha256", "validation-metrics.json"),
        ("trainingInputSha256", "training-inputs.json"),
        ("selectedCheckpointSha256", "selected.weights.h5"),
    ):
        assert (
            contract[field]
            == hashlib.sha256((candidate.model_path.parent / filename).read_bytes()).hexdigest()
        )
