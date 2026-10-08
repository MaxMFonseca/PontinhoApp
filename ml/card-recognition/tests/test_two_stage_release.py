from __future__ import annotations

import json
from copy import deepcopy

import pytest
from test_two_stage_metrics import evaluation_fixture, write_json

from pontinho_cards import evaluate_two_stage as joint
from pontinho_cards import release_two_stage as release


def promotion_fixture(tmp_path, monkeypatch):
    detector, classifier, selection, validation, holdout = evaluation_fixture(tmp_path, monkeypatch)
    evaluation = joint.evaluate_two_stage(
        detector, classifier, selection, validation, holdout, tmp_path / "evaluation.json"
    )
    report = json.loads(evaluation.path.read_bytes())
    benchmark = tmp_path / "benchmark.json"
    write_json(
        benchmark,
        {
            "schemaVersion": 1,
            "reportSha256": evaluation.sha256,
            "bindings": report["bindings"],
            "selected": report["selected"],
            "device": "physical representative midrange",
            "androidVersion": "15",
            "physicalDevice": True,
            "representativeMidrange": True,
            "coldStartMs": 1500,
            "peakMemoryBytes": 100000000,
            "warmEndToEndMs": {str(i): [1000, 1200, 1400] for i in range(1, 11)},
        },
    )
    monkeypatch.setattr(
        release,
        "_validate_source_evidence",
        lambda inputs, split, split_hash: {
            "sourceManifestSha256": "c" * 64,
            "sourceAuditReceiptSha256": "d" * 64,
        },
    )
    return release.ReleaseInputs(
        detector,
        classifier,
        selection,
        evaluation,
        validation,
        holdout,
        benchmark,
        tmp_path / "sources.json",
        tmp_path / "audit.json",
        tmp_path / "archives",
        tmp_path / "normalized.json",
    )


def test_promote_complete_verified_evidence_and_no_second_holdout_inference(tmp_path, monkeypatch):
    inputs = promotion_fixture(tmp_path, monkeypatch)

    def forbidden(*args):
        raise AssertionError("holdout inference must not repeat during promotion")

    monkeypatch.setattr(joint, "_run_joint", forbidden)
    receipt = release.promote_two_stage_release(inputs, tmp_path / "bundle")
    bundle = json.loads((receipt.bundle_path / "card_recognition.release.json").read_bytes())
    assert bundle["releaseApproved"] is True
    assert bundle["maximumCardCount"] == 10
    assert bundle["classifier"]["calibration"]["temperature"] == 1
    assert bundle["benchmark"]["medianEndToEndMs"] == 1200


@pytest.mark.parametrize("target", ["model", "report", "receipt", "benchmark", "source"])
def test_promotion_rejects_tampering_and_missing_evidence_without_assets(
    tmp_path, monkeypatch, target
):
    inputs = promotion_fixture(tmp_path, monkeypatch)
    if target == "model":
        inputs.detector.model_path.write_bytes(b"changed")
    elif target == "report":
        inputs.evaluation.path.write_bytes(b"{}")
    elif target == "receipt":
        inputs.evaluation.receipt_path.write_bytes(b"{}")
    elif target == "benchmark":
        value = json.loads(inputs.benchmark.read_bytes())
        value["warmEndToEndMs"]["10"] = [2001, 3000, 4000]
        write_json(inputs.benchmark, value)
    else:

        def missing(*args):
            raise ValueError("BLOCKED_SOURCE_EVIDENCE")

        monkeypatch.setattr(release, "_validate_source_evidence", missing)
    with pytest.raises((ValueError, OSError)):
        release.promote_two_stage_release(inputs, tmp_path / "bundle")
    assert not (tmp_path / "bundle").exists()


def good_metrics():
    return {
        "sceneCount": 200,
        "uniquePhotoCount": 200,
        "unseenDeckCount": 10,
        "detectorRecall": 0.97,
        "detectorFalsePositiveShare": 0.01,
        "exactCardMultisetAccuracy": 0.90,
        "exactHandTotalAccuracy": 0.95,
        "byCardCount": {
            str(i): {
                "sceneCount": 20,
                "exactCardMultisetAccuracy": 0.9,
                "exactHandTotalAccuracy": 0.95,
                "detectorRecall": 0.97,
                "detectorFalsePositiveShare": 0.01,
            }
            for i in range(1, 11)
        },
    }


@pytest.mark.parametrize(
    "field,value",
    [
        ("detectorRecall", 0.969),
        ("detectorFalsePositiveShare", 0.011),
        ("exactCardMultisetAccuracy", 0.899),
        ("exactHandTotalAccuracy", 0.949),
        ("sceneCount", 199),
        ("uniquePhotoCount", 199),
        ("unseenDeckCount", 9),
        ("detectorRecall", float("nan")),
        ("exactCardMultisetAccuracy", True),
    ],
)
def test_hard_gates_reject_subthreshold_or_invalid_evidence(field, value):
    metrics = good_metrics()
    metrics[field] = value
    with pytest.raises(ValueError):
        release.validate_quality_gates(metrics)


def test_every_supported_bucket_must_exist_and_pass():
    release.validate_quality_gates(good_metrics())
    for count in range(1, 11):
        metrics = deepcopy(good_metrics())
        del metrics["byCardCount"][str(count)]
        with pytest.raises(ValueError, match="bucket"):
            release.validate_quality_gates(metrics)
        metrics = deepcopy(good_metrics())
        metrics["byCardCount"][str(count)]["exactCardMultisetAccuracy"] = 0.89
        with pytest.raises(ValueError):
            release.validate_quality_gates(metrics)


def test_bundle_publication_is_one_directory_and_cannot_replace_existing_assets(tmp_path):
    payloads = {
        "card_detector.tflite": b"detector",
        "card_classifier.tflite": b"classifier",
        "card_recognition.release.json": b"release",
    }
    result = release.publish_bundle(tmp_path / "bundle", payloads)
    assert {p.name: p.read_bytes() for p in result.iterdir()} == payloads
    with pytest.raises(OSError):
        release.publish_bundle(tmp_path / "bundle", {k: b"changed" for k in payloads})
    assert {p.name: p.read_bytes() for p in result.iterdir()} == payloads


def test_failed_staging_leaves_no_partial_bundle(tmp_path, monkeypatch):
    from pontinho_cards import train_detector

    original = train_detector.secure_read_bytes

    def fail_second(path, **kwargs):
        if path.name == "card_classifier.tflite":
            raise OSError("interrupted copy")
        return original(path, **kwargs)

    monkeypatch.setattr(train_detector, "secure_read_bytes", fail_second)
    with pytest.raises(OSError, match="interrupted"):
        release.publish_bundle(
            tmp_path / "bundle",
            {
                "card_detector.tflite": b"a",
                "card_classifier.tflite": b"b",
                "card_recognition.release.json": b"c",
            },
        )
    assert not (tmp_path / "bundle").exists()
    assert list(tmp_path.iterdir()) == []
