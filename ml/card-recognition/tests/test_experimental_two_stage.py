"""The one-deck exception must stay explicit and cannot enter production release."""

import copy
import importlib
import json
from dataclasses import replace
from pathlib import Path

import pytest
from test_two_stage_data import _split_document

from pontinho_cards.manifest import load_source_manifest
from pontinho_cards.split import validate_split_document
from pontinho_cards.train_classifier import snapshot_classifier_inputs
from pontinho_cards.train_detector import snapshot_detector_inputs
from pontinho_cards.two_stage_data import build_two_stage_inputs

PROJECT = Path(__file__).resolve().parents[1]


def api():
    assert importlib.util.find_spec(
        "pontinho_cards.experimental_two_stage"
    ), "experimental path absent"
    return importlib.import_module("pontinho_cards.experimental_two_stage")


def upstream(root):
    document = _split_document(root, duplicate_ace=True)
    for partition, source, prefix in (
        ("train", "train", "train"),
        ("validation", "valid", "valid"),
        ("test", "test", "test"),
    ):
        record = document["partitions"][partition][0]
        record.pop("taskRoles")
        record["sourceId"] = f"rf100-poker-cards-{source}"
        record["sourceArchiveMember"] = f"{prefix}/images/scene.jpg"
        record["sourceArchiveMemberSha256"] = record["imageSha256"]
    return document


def test_experiment_restores_audited_one_deck_and_preserves_boxes_and_hashes(tmp_path):
    original = upstream(tmp_path)
    before = copy.deepcopy(original)
    manifest = load_source_manifest(PROJECT / "datasets/sources.json")
    document = api().prepare_experimental_document(original, manifest)
    assert original == before
    assert document["evidenceMode"] == "EXPERIMENTAL_ONE_DECK"
    assert document["deckDisjoint"] is False
    assert document["productionHoldout"] is False
    for partition in ("train", "validation", "test"):
        record = document["partitions"][partition][0]
        assert record["deckGroupId"] == "rf100-upstream-deck"
        assert record["imageSha256"] == before["partitions"][partition][0]["imageSha256"]
        assert record["boxes"] == before["partitions"][partition][0]["boxes"]
        assert record["taskRoles"] == ["DETECTOR_SCENE", "CLASSIFIER_CROP"]
    with pytest.raises(ValueError, match="experimental"):
        validate_split_document(document)
    data = build_two_stage_inputs(document, tmp_path, evidence_mode="EXPERIMENTAL_ONE_DECK")
    assert data.evidence_mode == "EXPERIMENTAL_ONE_DECK"
    assert set(data.detector_partitions) == {"train", "validation"}
    assert len(data.classifier_partitions["train"]) == 53
    # Test image deliberately does not exist. Training never opens it.
    assert not (tmp_path / "detector/test").exists()
    assert len(snapshot_detector_inputs(data, tmp_path / "detector-snapshot")["train"]) == 1
    assert len(snapshot_classifier_inputs(data, tmp_path / "classifier-snapshot")["train"]) == 53
    # Removing the experiment marker must restore both strict deck firewalls.
    strict = replace(data, evidence_mode="DECK_DISJOINT")
    with pytest.raises(ValueError, match="leak"):
        snapshot_detector_inputs(strict, tmp_path / "strict-detector")
    with pytest.raises(ValueError, match="leak"):
        snapshot_classifier_inputs(strict, tmp_path / "strict-classifier")


@pytest.mark.parametrize("fault", ["source", "member", "holdout", "duplicate_hash"])
def test_experiment_does_not_bypass_source_or_image_identity(tmp_path, fault):
    original = upstream(tmp_path)
    record = original["partitions"]["validation"][0]
    if fault == "source":
        record["sourceId"] = "unadmitted"
    elif fault == "member":
        record["sourceArchiveMember"] = "train/images/wrong.jpg"
    elif fault == "holdout":
        record["taskRoles"] = ["FINAL_SCENE_HOLDOUT"]
    else:
        record["imageSha256"] = original["partitions"]["train"][0]["imageSha256"]
        record["sourceArchiveMemberSha256"] = record["imageSha256"]
    with pytest.raises(ValueError):
        api().prepare_experimental_document(
            original, load_source_manifest(PROJECT / "datasets/sources.json")
        )


def test_production_candidate_snapshot_rejects_experimental_before_native_io(tmp_path):
    from pontinho_cards.evaluate_two_stage import snapshot_candidate
    from pontinho_cards.train_detector import DetectorCandidate

    contract = tmp_path / "candidate-contract.json"
    contract.write_text(json.dumps({"evidenceMode": "EXPERIMENTAL_ONE_DECK"}))
    candidate = DetectorCandidate(tmp_path / "absent.tflite", contract, tmp_path / "absent.json")
    with pytest.raises(ValueError, match="experimental"):
        snapshot_candidate(candidate, "detector", tmp_path / "output")


def test_experimental_candidate_snapshot_binds_evidence_but_never_admits_shared_photos(
    tmp_path, monkeypatch
):
    from test_two_stage_metrics import candidate_fixture, record_fixture, write_json

    from pontinho_cards.evaluate_two_stage import snapshot_candidate

    records = [record_fixture(tmp_path, 1, deck="train")]
    candidate = candidate_fixture(tmp_path, "detector", records, monkeypatch)
    contract = json.loads(candidate.contract_path.read_text())
    metrics = json.loads(candidate.validation_metrics_path.read_text())
    contract["evidenceMode"] = metrics["evidenceMode"] = "EXPERIMENTAL_ONE_DECK"
    contract["validationMetricsSha256"] = write_json(candidate.validation_metrics_path, metrics)
    write_json(candidate.contract_path, contract)
    with pytest.raises(ValueError, match="experimental"):
        snapshot_candidate(candidate, "detector", tmp_path / "strict")
    snapshot = snapshot_candidate(
        candidate, "detector", tmp_path / "experiment", evidence_mode="EXPERIMENTAL_ONE_DECK"
    )
    assert snapshot["inputs"]["validation"][0]["deck_group_id"] == "train"
    verified_metrics = snapshot["metrics"]
    candidate.validation_metrics_path.write_text('{"tampered": true}')
    assert snapshot["metrics"] == verified_metrics
    write_json(candidate.validation_metrics_path, metrics)
    inputs_path = candidate.contract_path.parent / "training-inputs.json"
    inputs = json.loads(inputs_path.read_text())
    inputs["validation"][0]["source_image_sha256"] = "a" * 64
    contract["trainingInputSha256"] = write_json(inputs_path, inputs)
    write_json(candidate.contract_path, contract)
    with pytest.raises(ValueError, match="leakage"):
        snapshot_candidate(
            candidate, "detector", tmp_path / "leaked", evidence_mode="EXPERIMENTAL_ONE_DECK"
        )


def test_experimental_publisher_cannot_write_main_even_with_an_experimental_label(tmp_path):
    assert importlib.util.find_spec(
        "pontinho_cards.evaluate_experimental"
    ), "experimental evaluation absent"
    evaluation = importlib.import_module("pontinho_cards.evaluate_experimental")
    with pytest.raises(ValueError, match="debug"):
        evaluation.publish_experimental_bundle(tmp_path / "app/src/main/assets", {})


def test_experimental_publisher_uses_portable_no_replace_fallback(tmp_path, monkeypatch):
    from pontinho_cards import evaluate_experimental as evaluation
    from pontinho_cards import train_detector

    class UnsupportedRenameAt2:
        @staticmethod
        def renameat2(*_args):
            return -1

    monkeypatch.setattr(
        train_detector.ctypes, "CDLL", lambda *_args, **_kwargs: UnsupportedRenameAt2()
    )
    monkeypatch.setattr(train_detector.ctypes, "get_errno", lambda: 22)
    destination = tmp_path / "app/src/debug/assets"
    payloads = {
        "card_detector.tflite": b"detector",
        "card_classifier.tflite": b"classifier",
        "card_recognition.release.json": b"{}",
    }

    published = evaluation.publish_experimental_bundle(destination, payloads)

    assert published == destination
    assert {path.name: path.read_bytes() for path in destination.iterdir()} == payloads


def test_experimental_selection_uses_validation_accuracy_even_when_below_release_floor():
    assert importlib.util.find_spec(
        "pontinho_cards.evaluate_experimental"
    ), "experimental evaluation absent"
    evaluation = importlib.import_module("pontinho_cards.evaluate_experimental")
    entries = [
        {
            "id": "wrong",
            "metrics": {
                "exactCardMultisetAccuracy": 0.1,
                "exactHandTotalAccuracy": 0.1,
                "detectorRecall": 0.95,
                "detectorFalsePositiveShare": 0.1,
            },
        },
        {
            "id": "best",
            "metrics": {
                "exactCardMultisetAccuracy": 0.4,
                "exactHandTotalAccuracy": 0.5,
                "detectorRecall": 0.8,
                "detectorFalsePositiveShare": 0.05,
            },
        },
    ]
    assert evaluation.select_experimental_entry(entries)["id"] == "best"


def test_persistent_native_classifier_keeps_rgb_identity_and_probability_mass(tmp_path):
    from pontinho_cards import evaluate_experimental as evaluation

    assert hasattr(evaluation, "NativeClassifier"), "persistent native crop inference absent"
    import numpy as np
    import tensorflow as tf
    from PIL import Image

    from pontinho_cards.train_classifier import _export_classifier

    model = tf.keras.Sequential(
        [
            tf.keras.layers.InputLayer(input_shape=(224, 224, 3)),
            tf.keras.layers.GlobalAveragePooling2D(),
            tf.keras.layers.Dense(52, activation="softmax", use_bias=False),
        ]
    )
    weights = np.zeros((3, 52), dtype=np.float32)
    weights[0, 0] = 5
    weights[2, 1] = 5
    model.layers[-1].set_weights([weights])
    filename = _export_classifier(model, [], tmp_path, "float16")
    classifier = evaluation.NativeClassifier(tmp_path / filename)
    red = classifier(Image.new("RGB", (330, 270), "red"))
    blue = classifier(Image.new("RGB", (224, 224), "blue"))
    assert int(np.argmax(red)) == 0
    assert int(np.argmax(blue)) == 1
    assert red[0] == pytest.approx(0.74425, abs=0.001)
    assert blue.sum() == pytest.approx(1.0)
