from __future__ import annotations

import hashlib
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any

import pytest

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.release import ReleaseGateError, ReleaseInputs, promote_release


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _write_json(path: Path, value: Any) -> str:
    payload = _json_bytes(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _source(index: int) -> dict[str, Any]:
    deck = f"deck-{index:02d}"
    source_url = f"https://github.com/example/{deck}"
    evidence_url = f"{source_url}/blob/{'a' * 40}/LICENSE"
    return {
        "id": f"source-{index:02d}",
        "title": f"Deck source {index}",
        "publisher": "Example Publisher",
        "sourceUrl": source_url,
        "archiveUrl": f"{source_url}/archive/{'a' * 40}.zip",
        "retrievedAt": "2026-10-15",
        "sourceRevision": {
            "kind": "GIT_COMMIT",
            "repositoryUrl": source_url,
            "commitSha": "a" * 40,
        },
        "archiveSha256": f"{index + 1:064x}",
        "license": {
            "kind": "CC0-1.0",
            "evidenceUrl": evidence_url,
            "evidenceText": f"Deck source {index} is dedicated under CC0.",
            "legalToolUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        },
        "annotationStatus": "BOUNDING_BOXES",
        "attribution": "No attribution required.",
        "deckGroupId": deck,
        "labels": list(CARD_LABELS),
        "provides": {
            "realScenes": True,
            "isolatedCards": False,
            "annotations": True,
            "distinctPhysicalDeckDesigns": 1,
        },
        "usablePhotoCount": 20,
        "holdoutEligiblePhotoCount": 20,
        "publicDomainApproved": True,
        "approvalNote": f"Reviewed source-specific evidence at {evidence_url}",
    }


def _record(index: int) -> dict[str, Any]:
    deck_index = index % 10
    return {
        "imageId": f"holdout-{index:03d}",
        "path": f"photos/holdout-{index:03d}.jpg",
        "width": 640,
        "height": 480,
        "sourceId": f"source-{deck_index:02d}",
        "imageSha256": f"{1000 + index:064x}",
        "sourceDerivativeId": f"derivative-{index:03d}",
        "deckGroupId": f"deck-{deck_index:02d}",
        "splitGroupId": f"split-{index:03d}",
        "augmentationParentId": None,
        "isRealPhoto": True,
        "lighting": "daylight" if index % 2 else "indoor",
        "boxes": [
            {
                "label": CARD_LABELS[index % len(CARD_LABELS)],
                "xmin": 10,
                "ymin": 20,
                "xmax": 110,
                "ymax": 180,
            }
        ],
    }


def _contract(model_hash: str, split_hash: str, candidate_hash: str, comparison_hash: str) -> dict:
    return {
        "schemaVersion": 1,
        "modelSha256": model_hash,
        "labels": list(CARD_LABELS),
        "input": {
            "name": "serving_default_images:0",
            "shape": [1, 320, 320, 3],
            "shapeSignature": [1, 320, 320, 3],
            "dtype": "uint8",
        },
        "outputs": [
            {"semantic": semantic, "tensorName": f"output_{index}"}
            for index, semantic in enumerate(("boxes", "classes", "scores", "count"))
        ],
        "normalization": {"mean": [0.0, 0.0, 0.0], "std": [255.0, 255.0, 255.0]},
        "quantization": {"variant": "int8"},
        "checkpointSha256": "1" * 64,
        "configSha256": "2" * 64,
        "candidateContractSha256": candidate_hash,
        "comparisonSha256": comparison_hash,
        "runGitCommit": "a" * 40,
        "inputManifestSha256": split_hash,
        "requirementsLockSha256": "3" * 64,
        "validationManifestSha256": "PLACEHOLDER",
        "scoreThreshold": 0.5,
        "iouThreshold": 0.5,
        "thresholdProvenance": "validation-selected",
        "modelVersion": "2026.10.15-int8",
        "exportedAt": "2026-10-15T12:00:00Z",
        "selectionEvidence": {
            "selectionRationale": "Equal accuracy and lower measured latency.",
            "androidDevice": "Pixel 6",
            "variants": {
                "float16": {
                    "modelSha256": "4" * 64,
                    "exactTotalAccuracy": 0.96,
                    "androidLatencyMs": 42.0,
                },
                "int8": {
                    "modelSha256": model_hash,
                    "exactTotalAccuracy": 0.96,
                    "androidLatencyMs": 25.0,
                },
            },
        },
    }


def _passing_evidence(tmp_path: Path) -> ReleaseInputs:
    root = tmp_path / "evidence"
    assets = tmp_path / "assets"
    model = root / "card_detector.tflite"
    model.parent.mkdir(parents=True)
    model.write_bytes(b"genuine-candidate-model")
    model_hash = _sha256(model)

    records = [_record(index) for index in range(200)]
    split = root / "splits.json"
    split_hash = _write_json(
        split,
        {
            "schemaVersion": 1,
            "seed": 20261014,
            "partitions": {"train": [], "validation": [], "test": records},
        },
    )
    test_hash = hashlib.sha256(_json_bytes(records)).hexdigest()
    holdout = root / "final-holdout.json"
    holdout_document = {
        "schemaVersion": 1,
        "kind": "pontinho-frozen-holdout",
        "seed": 20261014,
        "splitManifest": split.name,
        "splitManifestSha256": split_hash,
        "testPartitionSha256": test_hash,
        "test": records,
    }
    holdout_hash = _write_json(holdout, holdout_document)
    identity = f"v1.{split_hash}.{test_hash}"

    sources = root / "sources.json"
    sources_hash = _write_json(
        sources, {"schemaVersion": 1, "sources": [_source(i) for i in range(10)]}
    )
    candidate = root / "candidate-contract.json"
    candidate_hash = _write_json(
        candidate,
        {
            "schemaVersion": 1,
            "checkpointSha256": "1" * 64,
            "configSha256": "2" * 64,
            "runGitCommit": "a" * 40,
            "inputManifestSha256": split_hash,
            "requirementsLockSha256": "3" * 64,
            "scoreThreshold": 0.5,
            "iouThreshold": 0.5,
            "thresholdProvenance": "validation-selected",
            "labels": list(CARD_LABELS),
            "variants": {
                "float16": {"modelSha256": "4" * 64},
                "int8": {"modelSha256": model_hash},
            },
        },
    )
    comparison = root / "export-comparison.json"
    comparison_hash = _write_json(
        comparison,
        {
            "schemaVersion": 1,
            "selected": "int8",
            "candidateContractSha256": candidate_hash,
            "selectedCheckpointSha256": "1" * 64,
            "runGitCommit": "a" * 40,
            "validationManifestSha256": holdout_hash,
            "selectionRationale": "Equal accuracy and lower measured latency.",
            "androidDevice": "Pixel 6",
            "variants": {
                "float16": {
                    "modelSha256": "4" * 64,
                    "exactTotalAccuracy": 0.96,
                    "androidLatencyMs": 42.0,
                },
                "int8": {
                    "modelSha256": model_hash,
                    "exactTotalAccuracy": 0.96,
                    "androidLatencyMs": 25.0,
                },
            },
        },
    )
    contract = root / "model-contract.json"
    contract_document = _contract(model_hash, split_hash, candidate_hash, comparison_hash)
    contract_document["validationManifestSha256"] = holdout_hash
    contract_hash = _write_json(contract, contract_document)

    support = Counter(box["label"] for record in records for box in record["boxes"])
    per_class = {
        label: {"precision": 1.0, "recall": 1.0, "support": support[label]} for label in CARD_LABELS
    }
    report = root / "validation.json"
    report_document = {
        "schemaVersion": 1,
        "status": "RELEASE_CANDIDATE",
        "modelSha256": model_hash,
        "modelContractSha256": contract_hash,
        "testManifestSha256": holdout_hash,
        "holdoutIdentity": identity,
        "splitManifestSha256": split_hash,
        "testPartitionSha256": test_hash,
        "sourceManifestSha256": sources_hash,
        "holdoutRecordCount": 200,
        "holdoutUniquePhotoCount": 200,
        "holdoutDeckGroupCount": 10,
        "thresholdProvenance": "validation-selected",
        "scoreThreshold": 0.5,
        "iouThreshold": 0.5,
        "metrics": {
            "sceneCount": 200,
            "perClass": per_class,
            "exactCardMultisetAccuracy": 0.955,
            "exactHandTotalAccuracy": 0.96,
            "abstentionRate": 0.02,
            "slices": {
                "deckGroup": {
                    f"deck-{i:02d}": {"sceneCount": 20, "exactHandTotalAccuracy": 0.95}
                    for i in range(10)
                },
                "cardCount": {"1": {"sceneCount": 200, "exactHandTotalAccuracy": 0.96}},
                "lighting": {
                    "daylight": {"sceneCount": 100, "exactHandTotalAccuracy": 0.96},
                    "indoor": {"sceneCount": 100, "exactHandTotalAccuracy": 0.96},
                },
            },
            "scenes": [
                {
                    "sceneId": f"holdout-{i:03d}",
                    "status": "ABSTAINED" if i < 4 else "EVALUATED",
                }
                for i in range(200)
            ],
        },
    }
    report_hash = _write_json(report, report_document)
    report_markdown = report.with_suffix(".md")
    report_markdown.write_text("# Frozen validation\n", encoding="utf-8")
    receipt = root / "evaluation-receipt.json"
    _write_json(
        receipt,
        {
            "schemaVersion": 1,
            "modelSha256": model_hash,
            "holdoutIdentity": identity,
            "splitManifestSha256": split_hash,
            "testPartitionSha256": test_hash,
            "reportJsonSha256": report_hash,
            "reportMarkdownSha256": _sha256(report_markdown),
        },
    )
    gate = root / "release-gate.yaml"
    gate.write_text(
        "schema_version: 1\nminimum_real_photo_count: 200\n"
        "minimum_unseen_deck_groups: 10\nminimum_exact_total_accuracy: 0.95\n",
        encoding="utf-8",
    )
    return ReleaseInputs(
        model=model,
        report=report,
        contract=contract,
        source_manifest=sources,
        split_manifest=split,
        holdout_manifest=holdout,
        validation_receipt=receipt,
        candidate_contract=candidate,
        comparison=comparison,
        release_config=gate,
        assets_dir=assets,
    )


def _mutate_json(path: Path, mutation: Any) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    mutation(document)
    path.write_bytes(_json_bytes(document))


def _invalidate_bound_license(inputs: ReleaseInputs) -> None:
    _mutate_json(
        inputs.source_manifest,
        lambda document: document["sources"][0]["license"].__setitem__("kind", "MIT"),
    )
    _mutate_json(
        inputs.report,
        lambda document: document.__setitem__(
            "sourceManifestSha256", _sha256(inputs.source_manifest)
        ),
    )
    _mutate_json(
        inputs.validation_receipt,
        lambda document: document.__setitem__("reportJsonSha256", _sha256(inputs.report)),
    )


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (
            lambda inputs: _mutate_json(
                inputs.report, lambda d: d.__setitem__("holdoutUniquePhotoCount", 199)
            ),
            "200 distinct",
        ),
        (
            lambda inputs: _mutate_json(
                inputs.report, lambda d: d.__setitem__("holdoutDeckGroupCount", 9)
            ),
            "10 unseen",
        ),
        (
            lambda inputs: _mutate_json(
                inputs.report, lambda d: d["metrics"].__setitem__("exactHandTotalAccuracy", 0.949)
            ),
            "exact hand-total",
        ),
        (
            lambda inputs: _mutate_json(
                inputs.report, lambda d: d["metrics"]["perClass"].pop(CARD_LABELS[0])
            ),
            "52-class",
        ),
        (_invalidate_bound_license, "strict license validation"),
        (
            lambda inputs: _mutate_json(
                inputs.report, lambda d: d.__setitem__("modelSha256", "f" * 64)
            ),
            "model hash",
        ),
        (
            lambda inputs: _mutate_json(inputs.report, lambda d: d.pop("thresholdProvenance")),
            "metadata",
        ),
    ],
)
def test_gate_fails_closed_and_preserves_existing_assets(
    tmp_path: Path, mutation: Any, message: str
) -> None:
    """Catch any failed prerequisite replacing the user's currently shipped pair."""

    inputs = _passing_evidence(tmp_path)
    inputs.assets_dir.mkdir()
    model_asset = inputs.assets_dir / "card_detector.tflite"
    release_asset = inputs.assets_dir / "card_detector.release.json"
    model_asset.write_bytes(b"existing-model")
    release_asset.write_bytes(b"existing-release")
    mutation(inputs)

    with pytest.raises(ReleaseGateError, match=message):
        promote_release(inputs)

    assert model_asset.read_bytes() == b"existing-model"
    assert release_asset.read_bytes() == b"existing-release"


def test_gate_promotes_a_fully_bound_candidate_without_clobbering(tmp_path: Path) -> None:
    """Catch a gate that rejects complete evidence or emits an unbound release descriptor."""

    inputs = _passing_evidence(tmp_path)
    release = promote_release(inputs)

    promoted_model = inputs.assets_dir / "card_detector.tflite"
    promoted_release = inputs.assets_dir / "card_detector.release.json"
    assert promoted_model.read_bytes() == inputs.model.read_bytes()
    assert json.loads(promoted_release.read_text(encoding="utf-8")) == release
    assert release["status"] == "APPROVED"
    assert release["modelSha256"] == _sha256(promoted_model)
    assert release["modelSizeBytes"] == len(promoted_model.read_bytes())
    assert release["labels"] == list(CARD_LABELS)
    assert release["exactTotalAccuracy"] == 0.96
    assert release["heldOutPhotoCount"] == 200
    assert release["heldOutDeckCount"] == 10

    with pytest.raises(ReleaseGateError, match="refusing to replace"):
        promote_release(inputs)
    assert _sha256(promoted_model) == release["modelSha256"]


def test_transaction_failure_rolls_back_and_can_recover(tmp_path: Path) -> None:
    """Catch a mid-commit failure leaving a partial release or poisoning the retry."""

    inputs = _passing_evidence(tmp_path)
    calls = 0

    def fail_second_replace(source: Path, target: Path) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("simulated crash boundary")
        os.replace(source, target)

    with pytest.raises(ReleaseGateError, match="promotion transaction failed"):
        promote_release(inputs, replace_fn=fail_second_replace)

    assert not (inputs.assets_dir / "card_detector.tflite").exists()
    assert not (inputs.assets_dir / "card_detector.release.json").exists()

    release = promote_release(inputs)
    assert _sha256(inputs.assets_dir / "card_detector.tflite") == release["modelSha256"]
    assert json.loads((inputs.assets_dir / "card_detector.release.json").read_text()) == release


def test_losing_concurrent_reservation_never_removes_the_winners_transaction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a reservation race where the loser deletes the winner's staged evidence."""

    inputs = _passing_evidence(tmp_path)
    original_mkdir = Path.mkdir
    raced = False

    def lose_transaction_race(path: Path, *args: Any, **kwargs: Any) -> None:
        nonlocal raced
        if path.name == ".card_detector.release.transaction" and not raced:
            raced = True
            original_mkdir(path, *args, **kwargs)
            (path / "winner-owned").write_text("do not remove", encoding="utf-8")
            raise FileExistsError("simulated concurrent reservation")
        original_mkdir(path, *args, **kwargs)

    monkeypatch.setattr(Path, "mkdir", lose_transaction_race)

    with pytest.raises(ReleaseGateError, match="cannot stage release transaction"):
        promote_release(inputs)

    transaction = inputs.assets_dir / ".card_detector.release.transaction"
    assert (transaction / "winner-owned").read_text(encoding="utf-8") == "do not remove"
    assert not (inputs.assets_dir / "card_detector.tflite").exists()
    assert not (inputs.assets_dir / "card_detector.release.json").exists()
