import json
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from pontinho_cards.evaluate_two_stage import Thresholds, recognize_scene

CASES = json.loads(
    (Path(__file__).parent / "fixtures/android-release/native-nms-golden.json").read_text()
)["cases"]


@pytest.mark.parametrize("policy", [None, {"version": 0}])
def test_nms_policy_selection_rejected_before_holdout(tmp_path, monkeypatch, policy):
    from test_two_stage_metrics import evaluation_fixture, write_json

    from pontinho_cards import evaluate_two_stage as joint

    detector, classifier, selection, validation, holdout = evaluation_fixture(tmp_path, monkeypatch)
    document = json.loads(selection.path.read_bytes())
    document["nmsPolicy"] = policy
    selection = joint.ThresholdSelection(selection.path, write_json(selection.path, document))
    with pytest.raises(ValueError, match="NMS policy"):
        joint.evaluate_two_stage(
            detector, classifier, selection, validation, holdout, tmp_path / "result.json"
        )
    assert not (holdout.parent / ".two-stage-evaluation-receipts").exists()


@pytest.mark.parametrize("kind", ["detector", "classifier"])
@pytest.mark.parametrize("source", ["contract", "metrics"])
def test_nms_policy_candidate_evidence_cannot_be_reused(tmp_path, monkeypatch, kind, source):
    from test_two_stage_metrics import candidate_fixture, record_fixture, write_json

    from pontinho_cards import evaluate_two_stage as joint

    candidate = candidate_fixture(tmp_path, kind, [record_fixture(tmp_path, 1)], monkeypatch)
    contract = json.loads(candidate.contract_path.read_bytes())
    if source == "contract":
        contract.pop("nmsPolicy", None)
    else:
        metrics = json.loads(candidate.validation_metrics_path.read_bytes())
        metrics.pop("nmsPolicy", None)
        contract["validationMetricsSha256"] = write_json(candidate.validation_metrics_path, metrics)
    write_json(candidate.contract_path, contract)
    with pytest.raises(ValueError, match="NMS policy"):
        joint.snapshot_candidate(candidate, kind, tmp_path / "snapshot")


@pytest.mark.parametrize("policy", [None, {"comparison": "greater-or-equal"}])
def test_nms_policy_release_rejects_coherent_but_wrong_policy_evidence(
    tmp_path, monkeypatch, policy
):
    from dataclasses import replace

    from test_two_stage_metrics import write_json
    from test_two_stage_release import promotion_fixture

    from pontinho_cards import release_two_stage as release

    inputs = promotion_fixture(tmp_path, monkeypatch)
    report = json.loads(inputs.evaluation.path.read_bytes())
    report["nmsPolicy"] = policy
    inputs = replace(
        inputs,
        evaluation=replace(inputs.evaluation, sha256=write_json(inputs.evaluation.path, report)),
    )
    receipt = json.loads(inputs.evaluation.receipt_path.read_bytes())
    receipt["reportSha256"] = inputs.evaluation.sha256
    write_json(inputs.evaluation.receipt_path, receipt)
    benchmark = json.loads(inputs.benchmark.read_bytes())
    benchmark["reportSha256"] = inputs.evaluation.sha256
    write_json(inputs.benchmark, benchmark)
    with pytest.raises(ValueError, match="NMS policy"):
        release.promote_two_stage_release(inputs, tmp_path / "wrong-nms")
    assert not (tmp_path / "wrong-nms").exists()


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_integer_nms_shared_goldens_include_invalid_area_and_unbounded_products(case):
    from pontinho_cards.native_nms import native_nms

    inputs = [(item["score"], tuple(item["bounds"])) for item in case["boxes"]]
    if case.get("invalid"):
        with pytest.raises(ValueError):
            native_nms(inputs, case["threshold"])
    else:
        assert native_nms(inputs, case["threshold"]) == case["expected"]


@pytest.mark.parametrize(
    "case",
    [c for c in CASES if not c.get("invalid") and not c.get("helperOnly")],
    ids=lambda c: c["id"],
)
@pytest.mark.parametrize("size", [(320, 320), (1000, 500), (3000, 2000)])
def test_joint_nms_matches_shared_integer_goldens_before_original_pixel_mapping(case, size):
    raw = case.get(
        "rawNormalized",
        [
            [
                (value + (0.25 if axis < 2 else 0.5)) / 320
                for axis, value in enumerate(item["bounds"])
            ]
            for item in case["boxes"]
        ],
    )
    result = recognize_scene(
        Image.new("RGB", size),
        [(item["score"], bounds) for item, bounds in zip(case["boxes"], raw)],
        lambda crop: np.eye(1, 52)[0],
        Thresholds(0.5, 0.5, case["threshold"], 0),
        1,
    )
    observed = [
        [round(value / size[axis % 2] * 320) for axis, value in enumerate(d.box)]
        for d in result["detector"]
    ]
    assert observed == [case["boxes"][index]["bounds"] for index in case["expected"]]
