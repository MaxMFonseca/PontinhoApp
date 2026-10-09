from __future__ import annotations

import hashlib
import json
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

from pontinho_cards import evaluate_two_stage as joint
from pontinho_cards.evaluate import Detection
from pontinho_cards.labels import CARD_LABELS


def probabilities(label="AC", confidence=0.99):
    row = np.full(52, (1 - confidence) / 51)
    row[CARD_LABELS.index(label)] = confidence
    return row


def scene(count, *, predicted=None, flags=(), deck="deck"):
    truth = tuple(Detection("AC", (i * 20, 0, i * 20 + 10, 15)) for i in range(count))
    return {
        "imageId": f"photo-{count}",
        "deckGroupId": deck,
        "truth": truth,
        "detector": truth if predicted is None else predicted,
        "predictions": truth if predicted is None else predicted,
        "flags": flags,
    }


@pytest.mark.parametrize("count", [1, 3, 5, 8, 10])
def test_exact_physical_multiset_and_all_coringa_totals(count):
    result = joint.measure_two_stage([scene(count)])
    assert result["exactCardMultisetAccuracy"] == 1
    assert result["exactHandTotalAccuracy"] == 1
    assert result["detectorRecall"] == 1
    assert result["byCardCount"][str(count)]["sceneCount"] == 1
    assert result["byDeck"]["deck"]["exactCardMultisetAccuracy"] == 1
    assert result["scenes"][0]["totals"]["A_BLACK"] == {
        "truth": 20 * count,
        "predicted": 20 * count,
    }


@pytest.mark.parametrize("case", ["miss", "false", "wrong", "abstain", "low", "empty", "eleven"])
def test_failed_scene_never_receives_exact_credit(case):
    record = scene(3)
    if case == "miss":
        record["predictions"] = record["detector"] = record["truth"][:2]
    elif case == "false":
        record["predictions"] = record["detector"] = (
            *record["truth"],
            Detection("2H", (80, 0, 90, 15)),
        )
    elif case == "wrong":
        record["predictions"] = (Detection("AD", record["truth"][0].box), *record["truth"][1:])
    elif case in {"abstain", "low"}:
        record["flags"] = ("low_confidence",)
    elif case == "empty":
        record = scene(0)
    else:
        record = scene(11)
    result = joint.measure_two_stage([record])
    assert result["exactCardMultisetAccuracy"] == 0
    assert result["exactHandTotalAccuracy"] == 0
    if case == "wrong":
        assert result["detectorRecall"] == 1
        assert result["perCardRecall"] == pytest.approx(2 / 3)
    if case == "miss":
        assert result["detectorRecall"] == pytest.approx(2 / 3)
    if case == "false":
        assert result["detectorFalsePositiveShare"] == 0.25


def test_original_resolution_crops_spatial_nms_and_duplicate_labels():
    image = Image.new("RGB", (1000, 500))
    seen = []

    def classify(crop):
        seen.append(crop.size)
        return probabilities()

    boxes = [
        (0.97, (0.6, 0.1, 0.8, 0.5)),
        (0.99, (0.1, 0.1, 0.3, 0.5)),
        (0.98, (0.101, 0.1, 0.301, 0.5)),
    ]
    result = joint.recognize_scene(
        image, boxes, classify, joint.Thresholds(0.5, 0.5, 0.5, 0.1), 1.0
    )
    assert [d.label for d in result["predictions"]] == ["AC", "AC"]
    assert [d.box[0] for d in result["predictions"]] == [100, 600]
    assert seen == [(240, 240), (240, 240)]
    assert result["predictions"][1].confidence == pytest.approx(0.97)


def test_variant_temperature_changes_abstention_without_changing_identity():
    args = (
        Image.new("RGB", (100, 100)),
        [(0.99, (0.1, 0.1, 0.9, 0.9))],
        lambda crop: probabilities(confidence=0.8),
        joint.Thresholds(0.5, 0.7, 0.5, 0),
    )
    assert joint.recognize_scene(*args, 1)["flags"] == ()
    assert joint.recognize_scene(*args, 4)["flags"] == ("low_confidence",)


def test_more_than_ten_is_split_failure_without_truncation():
    boxes = [(0.99, (i / 12, 0.1, (i + 0.5) / 12, 0.9)) for i in range(11)]
    result = joint.recognize_scene(
        Image.new("RGB", (1200, 100)),
        boxes,
        lambda crop: probabilities(),
        joint.Thresholds(0.5, 0.5, 0.5, 0),
        1,
    )
    assert len(result["detector"]) == 11
    assert result["predictions"] == ()
    assert result["flags"] == ("split_capture",)


@pytest.mark.parametrize("box", [(-0.1, 0, 0.5, 0.5), (0, 0, 1.1, 0.5), (0, 0, 0, 0.5)])
def test_incomplete_boxes_abstain(box):
    result = joint.recognize_scene(
        Image.new("RGB", (100, 100)),
        [(0.99, box)],
        lambda crop: probabilities(),
        joint.Thresholds(0.5, 0.5, 0.5, 0),
        1,
    )
    assert result["flags"]
    assert result["predictions"] == ()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def candidate_fixture(tmp_path, kind, records, monkeypatch, *, include_size=False):
    root = tmp_path / kind
    root.mkdir()
    stem = "card_detector" if kind == "detector" else "card_classifier"
    variants = {}
    for variant in ("float16", "int8"):
        filename = f"{stem}.{variant}.tflite"
        payload = f"{kind}-{variant}".encode()
        (root / filename).write_bytes(payload)
        variants[variant] = {
            "filename": filename,
            "modelSha256": hashlib.sha256(payload).hexdigest(),
        }
        if include_size:
            variants[variant]["modelSizeBytes"] = len(payload)
        if kind == "classifier":
            variants[variant]["calibration"] = {
                "partition": "validation",
                "method": "temperature-scaling",
                "temperature": 1.0,
                "scoreTransform": "softmax(log(max(raw_probability,1e-12))/temperature)",
            }
    inputs = {
        "train": [
            {"source_image_sha256": "a" * 64, "deck_group_id": "train", "split_group_id": "train"}
        ],
        "validation": [
            {
                "source_image_sha256": r["imageSha256"],
                "deck_group_id": r["deckGroupId"],
                "split_group_id": r["splitGroupId"],
            }
            for r in records
        ],
    }
    metrics = {
        "partition": "validation",
        "status": "MEASURED",
        "variants": {
            v: {
                "modelSha256": d["modelSha256"],
                **({"calibration": d["calibration"]} if kind == "classifier" else {}),
            }
            for v, d in variants.items()
        },
    }
    contract = {
        "schemaVersion": 1,
        "kind": "ONE_CLASS_DETECTOR_CANDIDATE"
        if kind == "detector"
        else "CARD_CLASSIFIER_CANDIDATE",
        "labels": ["card"] if kind == "detector" else list(CARD_LABELS),
        "variants": variants,
        "trainingInputSha256": write_json(root / "training-inputs.json", inputs),
        "validationMetricsSha256": write_json(root / "validation-metrics.json", metrics),
    }
    for field, name in (
        ("selectedCheckpointSha256", "selected.weights.h5"),
        ("configSha256", "resolved-config.yaml"),
        ("requirementsLockSha256", "requirements.lock"),
    ):
        (root / name).write_bytes(b"bound provenance")
        contract[field] = hashlib.sha256(b"bound provenance").hexdigest()
    write_json(root / "candidate-contract.json", contract)
    monkeypatch.setattr(
        joint,
        "_inspect",
        lambda path, variant, kind: {
            "modelSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        },
    )
    return SimpleNamespace(
        model_path=root / f"{stem}.float16.tflite",
        contract_path=root / "candidate-contract.json",
        validation_metrics_path=root / "validation-metrics.json",
    )


def record_fixture(root, index, count=1, deck="validation"):
    image = root / f"{index}.png"
    Image.new("RGB", (240, 40), (index % 256, index // 256, 10)).save(image)
    return {
        "imageId": str(index),
        "path": image.name,
        "imageSha256": hashlib.sha256(image.read_bytes()).hexdigest(),
        "deckGroupId": deck,
        "splitGroupId": deck,
        "sourceDerivativeId": str(index),
        "isRealPhoto": True,
        "width": 240,
        "height": 40,
        "lighting": "day",
        "boxes": [
            {"label": "AC", "xmin": i * 20, "ymin": 0, "xmax": i * 20 + 10, "ymax": 15}
            for i in range(count)
        ],
    }


def test_validation_selection_binds_variants_and_never_opens_holdout(tmp_path, monkeypatch):
    records = [record_fixture(tmp_path, i, i) for i in range(1, 11)]
    detector = candidate_fixture(tmp_path, "detector", records, monkeypatch)
    classifier = candidate_fixture(tmp_path, "classifier", records, monkeypatch)
    manifest = tmp_path / "validation.json"
    write_json(
        manifest,
        {
            "schemaVersion": 1,
            "partition": "validation",
            "records": records,
            "test": "must-not-be-read",
        },
    )
    monkeypatch.setattr(
        joint, "_run_joint", lambda snapshots, records, *args: [scene(i) for i in range(1, 11)]
    )
    selected = joint.select_two_stage_thresholds(
        detector,
        classifier,
        manifest,
        [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
        tmp_path / "selection.json",
    )
    document = json.loads(selected.path.read_bytes())
    assert document["partition"] == "validation"
    assert len(document["sweep"]) == 4
    assert document["selected"]["classifierVariant"] == "float16"
    assert document["selected"]["detectorVariant"] == "float16"
    assert selected.sha256 == hashlib.sha256(selected.path.read_bytes()).hexdigest()
    changed = json.loads(manifest.read_bytes())
    changed["partition"] = "test"
    write_json(manifest, changed)
    with pytest.raises(ValueError, match="validation"):
        joint.select_two_stage_thresholds(
            detector,
            classifier,
            manifest,
            [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
            tmp_path / "bad.json",
        )


def test_candidate_model_and_validation_hash_tampering_fails_closed(tmp_path, monkeypatch):
    records = [record_fixture(tmp_path, 1)]
    candidate = candidate_fixture(tmp_path, "detector", records, monkeypatch)
    candidate.model_path.write_bytes(b"changed model")
    with pytest.raises(ValueError, match="hash"):
        joint.snapshot_candidate(candidate, "detector", tmp_path / "snapshot")


def test_holdout_claim_is_global_to_partition_and_never_retried_after_failure(tmp_path):
    joint.claim_holdout(tmp_path, "b" * 64, {"candidate": "one"})
    with pytest.raises(ValueError, match="already"):
        joint.claim_holdout(tmp_path, "b" * 64, {"candidate": "two"})


def evaluation_fixture(tmp_path, monkeypatch, *, include_size=False):
    validation = [record_fixture(tmp_path, i, i) for i in range(1, 11)]
    detector = candidate_fixture(
        tmp_path, "detector", validation, monkeypatch, include_size=include_size
    )
    classifier = candidate_fixture(
        tmp_path, "classifier", validation, monkeypatch, include_size=include_size
    )
    validation_path = tmp_path / "validation.json"
    write_json(
        validation_path, {"schemaVersion": 1, "partition": "validation", "records": validation}
    )

    def inference(snapshots, records, *args):
        results = []
        for r in records:
            truth = tuple(
                Detection(b["label"], tuple(b[k] for k in ("xmin", "ymin", "xmax", "ymax")))
                for b in r["boxes"]
            )
            results.append(
                {
                    "imageId": r["imageId"],
                    "deckGroupId": r["deckGroupId"],
                    "truth": truth,
                    "predictions": truth,
                    "detector": truth,
                    "flags": (),
                }
            )
        return results

    monkeypatch.setattr(joint, "_run_joint", inference)
    selection = joint.select_two_stage_thresholds(
        detector,
        classifier,
        validation_path,
        [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
        tmp_path / "selection.json",
    )
    test = [record_fixture(tmp_path, i + 100, i % 10 + 1, f"unseen-{i % 10}") for i in range(200)]
    for i, record in enumerate(test):
        record["boxes"][0]["label"] = CARD_LABELS[i % 52]
    train = [
        {
            "imageId": "train",
            "imageSha256": "a" * 64,
            "sourceDerivativeId": "train",
            "deckGroupId": "train",
            "splitGroupId": "train",
        }
    ]
    split_hash = write_json(
        tmp_path / "split.json",
        {
            "schemaVersion": 1,
            "seed": 1,
            "partitions": {"train": train, "validation": validation, "test": test},
        },
    )
    holdout = tmp_path / "holdout.json"
    write_json(
        holdout,
        {
            "schemaVersion": 1,
            "kind": "pontinho-frozen-holdout",
            "seed": 1,
            "splitManifest": "split.json",
            "splitManifestSha256": split_hash,
            "testPartitionSha256": hashlib.sha256(joint.canonical(test)).hexdigest(),
            "test": test,
        },
    )
    return detector, classifier, selection, validation_path, holdout


def test_frozen_holdout_runs_once_and_binds_immutable_report(tmp_path, monkeypatch):
    args = evaluation_fixture(tmp_path, monkeypatch)
    result = joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    report = json.loads(result.path.read_bytes())
    assert report["metrics"]["exactCardMultisetAccuracy"] == 1
    assert report["metrics"]["uniquePhotoCount"] == 200
    assert report["metrics"]["unseenDeckCount"] == 10
    assert json.loads(result.receipt_path.read_bytes())["reportSha256"] == result.sha256
    with pytest.raises(ValueError, match="already"):
        joint.evaluate_two_stage(*args, tmp_path / "another.json")


def test_final_inference_failure_consumes_holdout(tmp_path, monkeypatch):
    args = evaluation_fixture(tmp_path, monkeypatch)

    def fail(*args):
        raise RuntimeError("native failure")

    monkeypatch.setattr(joint, "_run_joint", fail)
    with pytest.raises(RuntimeError, match="native"):
        joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    with pytest.raises(ValueError, match="already"):
        joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    assert not (tmp_path / "evaluation.json").exists()


def test_changed_selection_rejected_before_holdout_consumption(tmp_path, monkeypatch):
    args = evaluation_fixture(tmp_path, monkeypatch)
    args[2].path.write_bytes(b"{}")
    with pytest.raises(ValueError, match="selection hash"):
        joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    assert not (tmp_path / ".two-stage-evaluation-receipts").exists()


def test_reordering_same_holdout_photos_cannot_bypass_one_use(tmp_path, monkeypatch):
    args = evaluation_fixture(tmp_path, monkeypatch)
    joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    split = json.loads((tmp_path / "split.json").read_bytes())
    split["partitions"]["test"].reverse()
    holdout = json.loads(args[4].read_bytes())
    holdout["splitManifestSha256"] = write_json(tmp_path / "split.json", split)
    holdout["test"] = split["partitions"]["test"]
    holdout["testPartitionSha256"] = hashlib.sha256(joint.canonical(holdout["test"])).hexdigest()
    write_json(args[4], holdout)
    with pytest.raises(ValueError, match="already"):
        joint.evaluate_two_stage(*args, tmp_path / "another.json")


def test_validation_can_include_real_negative_scenes(tmp_path, monkeypatch):
    records = [record_fixture(tmp_path, i, i) for i in range(11)]
    detector = candidate_fixture(tmp_path, "detector", records, monkeypatch)
    classifier = candidate_fixture(tmp_path, "classifier", records[1:], monkeypatch)
    manifest = tmp_path / "validation.json"
    write_json(manifest, {"schemaVersion": 1, "partition": "validation", "records": records})
    monkeypatch.setattr(
        joint, "_run_joint", lambda snapshots, records, *args: [scene(i) for i in range(11)]
    )
    result = joint.select_two_stage_thresholds(
        detector,
        classifier,
        manifest,
        [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
        tmp_path / "selection.json",
    )
    assert json.loads(result.path.read_bytes())["sweep"][0]["metrics"]["sceneCount"] == 11
    assert (
        json.loads(result.path.read_bytes())["sweep"][0]["metrics"]["exactCardMultisetAccuracy"]
        == 1
    )


def test_threshold_selection_prefers_joint_hard_envelope_over_better_single_metric(
    tmp_path, monkeypatch
):
    records = [record_fixture(tmp_path, i, i) for i in range(1, 11)]
    detector = candidate_fixture(tmp_path, "detector", records, monkeypatch)
    classifier = candidate_fixture(tmp_path, "classifier", records, monkeypatch)
    manifest = tmp_path / "validation.json"
    write_json(manifest, {"schemaVersion": 1, "partition": "validation", "records": records})

    # Float16 produces an extra false detector box that is absent from classification.
    # Both have perfect hand labels; only int8 meets detector precision.
    def inference(snapshots, records, dv, cv, threshold):
        rows = [scene(i) for i in range(1, 11)]
        if dv == "float16":
            for row in rows:
                row["detector"] = (*row["detector"], Detection("AC", (230, 20, 239, 39)))
        return rows

    monkeypatch.setattr(joint, "_run_joint", inference)
    result = joint.select_two_stage_thresholds(
        detector,
        classifier,
        manifest,
        [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
        tmp_path / "selection.json",
    )
    assert json.loads(result.path.read_bytes())["selected"]["detectorVariant"] == "int8"


def test_evaluation_cli_missing_reviewed_inputs_fails_without_report(tmp_path, capsys):
    result = joint.main(
        [
            "select",
            "--detector",
            str(tmp_path / "detector"),
            "--classifier",
            str(tmp_path / "classifier"),
            "--validation",
            str(tmp_path / "missing.json"),
            "--output",
            str(tmp_path / "selection.json"),
        ]
    )
    assert result == 2
    assert "BLOCKED_TWO_STAGE_INPUTS" in capsys.readouterr().err
    assert not (tmp_path / "selection.json").exists()


def test_selection_ranking_requires_joint_quality_envelope():
    good = {
        "sceneCount": 100,
        "exactCardMultisetAccuracy": 0.95,
        "exactHandTotalAccuracy": 0.97,
        "detectorRecall": 0.98,
        "detectorFalsePositiveShare": 0.005,
    }
    bad = {**good, "exactCardMultisetAccuracy": 0.99, "detectorRecall": 0.96}
    good["byCardCount"] = {str(i): dict(good) for i in range(1, 11)}
    bad["byCardCount"] = {str(i): dict(bad) for i in range(1, 11)}
    assert joint.selection_key(good) > joint.selection_key(bad)


def test_unselected_sweep_choice_cannot_be_substituted_before_holdout(tmp_path, monkeypatch):
    from dataclasses import replace

    args = list(evaluation_fixture(tmp_path, monkeypatch))
    document = json.loads(args[2].path.read_bytes())
    document["selected"]["detectorVariant"] = "int8"  # Equal scores canonically select float16.
    new_hash = write_json(args[2].path, document)
    args[2] = replace(args[2], sha256=new_hash)
    with pytest.raises(ValueError, match="selected optimum"):
        joint.evaluate_two_stage(*args, tmp_path / "evaluation.json")
    assert not (tmp_path / ".two-stage-evaluation-receipts").exists()


def test_candidate_roles_support_separate_detector_scenes_and_classifier_crops(
    tmp_path, monkeypatch
):
    records = [record_fixture(tmp_path, 1), record_fixture(tmp_path, 2)]
    records[0]["taskRoles"] = ["DETECTOR_SCENE"]
    records[1]["taskRoles"] = ["CLASSIFIER_CROP"]
    detector = candidate_fixture(tmp_path, "detector", records[:1], monkeypatch)
    classifier = candidate_fixture(tmp_path, "classifier", records[1:], monkeypatch)
    manifest = tmp_path / "validation.json"
    write_json(manifest, {"schemaVersion": 1, "partition": "validation", "records": records})
    monkeypatch.setattr(joint, "_run_joint", lambda *args: [scene(1)])
    result = joint.select_two_stage_thresholds(
        detector,
        classifier,
        manifest,
        [joint.Thresholds(0.5, 0.5, 0.5, 0.1)],
        tmp_path / "selection.json",
    )
    assert json.loads(result.path.read_bytes())["status"] == "BLOCKED_TWO_STAGE_QUALITY"
