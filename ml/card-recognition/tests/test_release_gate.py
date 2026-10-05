from __future__ import annotations

import hashlib
import io
import json
import os
import zipfile
from collections import Counter
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from pontinho_cards import release as release_module
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.release import (
    _PINNED_GATE_PATH,
    ReleaseGateError,
    ReleaseInputs,
    _archive_member,
    _load_gate,
    _publish,
    _recompute_threshold_sweep,
    _validate_release_identity,
    promote_release,
)
from pontinho_cards.release import (
    main as release_main,
)


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _write_json(path: Path, value: Any) -> str:
    payload = _json_bytes(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize(
    ("model_version", "exported_at"),
    [
        ("cards.release_candidate-1", "2026-10-15T12:00:00Z"),
        ("2026.10.15-int8", "2026-10-15T12:00:00.123456789Z"),
    ],
)
def test_release_identity_accepts_exact_android_contract_language(
    model_version: str, exported_at: str
) -> None:
    assert _validate_release_identity(model_version, exported_at) == (
        model_version,
        exported_at,
    )


@pytest.mark.parametrize(
    ("model_version", "exported_at"),
    [
        ("unsafe/version", "2026-10-15T12:00:00Z"),
        ("safe", "2026-10-15T12:00:00+00:00"),
        ("safe", "20261015T120000Z"),
        ("safe", "2026-10-15T12:00:00.1234567890Z"),
    ],
)
def test_release_identity_rejects_values_android_cannot_consume(
    model_version: str, exported_at: str
) -> None:
    with pytest.raises(ReleaseGateError):
        _validate_release_identity(model_version, exported_at)


@pytest.mark.parametrize(
    "fixture_name",
    ["card_detector.float16.release.json", "card_detector.int8.release.json"],
)
def test_android_release_fixtures_use_producer_identity_and_quantization_language(
    fixture_name: str,
) -> None:
    fixture_path = Path(__file__).parent / "fixtures" / "android-release" / fixture_name
    document = json.loads(fixture_path.read_text(encoding="utf-8"))

    _validate_release_identity(document["modelVersion"], document["exportedAt"])
    quantization = document["quantization"]
    assert quantization["nonemptyConstantTensorTypes"] == sorted(
        set(quantization["nonemptyConstantTensorTypes"])
    )
    assert set(quantization["nonemptyConstantTensorTypes"]) <= {
        "FLOAT16",
        "FLOAT32",
        "INT8",
        "UINT8",
        "INT32",
    }


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
        "archiveInput": {"localPath": f"source-{index:02d}.archive"},
        "adapter": {
            "kind": "COCO_JSON_SCENES",
            "annotationMember": "annotations/instances.json",
            "imagePrefix": "photos",
            "labelMap": {label: label for label in CARD_LABELS},
        },
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


def _contract(
    model_hash: str,
    split_hash: str,
    validation_hash: str,
    candidate_hash: str,
    comparison_hash: str,
) -> dict:
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
        "splitManifestSha256": split_hash,
        "trainingInputSha256": split_hash,
        "syntheticManifestSha256": None,
        "syntheticReviewSha256": None,
        "syntheticGenerationConfigSha256": None,
        "requirementsLockSha256": "3" * 64,
        "validationManifestSha256": validation_hash,
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
            "splitManifestSha256": split_hash,
            "trainingInputSha256": split_hash,
            "syntheticManifestSha256": None,
            "syntheticReviewSha256": None,
            "syntheticGenerationConfigSha256": None,
            "requirementsLockSha256": "3" * 64,
            "scoreThreshold": 0.5,
            "iouThreshold": 0.5,
            "thresholdProvenance": "configured-not-validation-selected",
            "labels": list(CARD_LABELS),
            "variants": {
                "float16": {"modelSha256": "4" * 64},
                "int8": {"modelSha256": model_hash},
            },
        },
    )
    validation_manifest = root / "validation-manifest.json"
    validation_hash = _write_json(
        validation_manifest,
        {
            "schemaVersion": 1,
            "kind": "pontinho-validation-threshold-selection",
            "seed": 20261014,
            "splitManifestSha256": split_hash,
            "validationPartitionSha256": hashlib.sha256(_json_bytes([])).hexdigest(),
            "records": [],
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
            "splitManifestSha256": split_hash,
            "trainingInputSha256": split_hash,
            "syntheticManifestSha256": None,
            "syntheticReviewSha256": None,
            "syntheticGenerationConfigSha256": None,
            "validationManifestSha256": validation_hash,
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
    contract_document = _contract(
        model_hash,
        split_hash,
        validation_hash,
        candidate_hash,
        comparison_hash,
    )
    contract_hash = _write_json(contract, contract_document)

    support = Counter(box["label"] for record in records for box in record["boxes"])
    per_class = {
        label: {"precision": 1.0, "recall": 1.0, "support": support[label]} for label in CARD_LABELS
    }
    scenes = [
        {
            "sceneId": record["imageId"],
            "status": "ABSTAINED" if index < 4 else "EVALUATED",
            "exactCardMultiset": index < 191,
            "exactHandTotalAccuracy": float(index < 192),
        }
        for index, record in enumerate(records)
    ]
    slices: dict[str, dict[str, dict[str, float | int]]] = {}
    for dimension, key_for in {
        "deckGroup": lambda record: str(record["deckGroupId"]),
        "cardCount": lambda record: str(len(record["boxes"])),
        "lighting": lambda record: str(record["lighting"]),
    }.items():
        grouped: dict[str, list[int]] = {}
        for index, record in enumerate(records):
            grouped.setdefault(key_for(record), []).append(index)
        slices[dimension] = {
            key: {
                "sceneCount": len(indices),
                "exactHandTotalAccuracy": sum(index < 192 for index in indices) / len(indices),
                "exactCardMultisetAccuracy": sum(index < 191 for index in indices) / len(indices),
            }
            for key, indices in grouped.items()
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
            "slices": slices,
            "scenes": scenes,
        },
    }
    report_hash = _write_json(report, report_document)
    report_markdown = report.with_suffix(".md")
    report_markdown.write_text("# Frozen validation\n", encoding="utf-8")
    receipt = root / ".evaluation-receipts" / f"{model_hash}.{identity}.json"
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
        validation_manifest=validation_manifest,
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
        (
            lambda inputs: _mutate_json(
                inputs.report,
                lambda d: d["metrics"]["scenes"][0].__setitem__("exactHandTotalAccuracy", 0.0),
            ),
            "scene-derived",
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


def test_publish_commits_a_prevalidated_pair_without_clobbering(tmp_path: Path) -> None:
    """Catch the pair transaction corrupting bytes already authorized by the gate."""

    inputs = _passing_evidence(tmp_path)
    model_payload = b"prevalidated-genuine-model-snapshot"
    release = {"schemaVersion": 1, "status": "APPROVED"}
    _publish(inputs, model_payload, release, os.link)

    promoted_model = inputs.assets_dir / "card_detector.tflite"
    promoted_release = inputs.assets_dir / "card_detector.release.json"
    assert promoted_model.read_bytes() == model_payload
    assert json.loads(promoted_release.read_text(encoding="utf-8")) == release

    with pytest.raises(ReleaseGateError, match="refusing to replace"):
        _publish(inputs, model_payload, release, os.link)
    assert promoted_model.read_bytes() == model_payload


def test_transaction_failure_rolls_back_and_can_recover(tmp_path: Path) -> None:
    """Catch a hard crash leaving a transaction that cannot finish on retry."""

    inputs = _passing_evidence(tmp_path)
    calls = 0

    def crash_on_second_link(source: Path, target: Path) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise SystemExit("simulated hard crash boundary")
        os.link(source, target)

    with pytest.raises(SystemExit, match="simulated hard crash boundary"):
        _publish(inputs, b"candidate-model", {"schemaVersion": 1}, crash_on_second_link)

    assert (inputs.assets_dir / "card_detector.tflite").exists()
    assert not (inputs.assets_dir / "card_detector.release.json").exists()

    release = {"schemaVersion": 1}
    _publish(inputs, b"candidate-model", release, os.link)
    assert (inputs.assets_dir / "card_detector.tflite").read_bytes() == b"candidate-model"
    assert json.loads((inputs.assets_dir / "card_detector.release.json").read_text()) == release


def test_losing_concurrent_reservation_never_removes_the_winners_transaction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a reservation race where the loser deletes the winner's staged evidence."""

    inputs = _passing_evidence(tmp_path)
    original_replace = os.replace
    raced = False

    def lose_transaction_race(source: Path, target: Path) -> None:
        nonlocal raced
        if target.name == ".card_detector.release.transaction" and not raced:
            raced = True
            original_replace(source, target)
            (target / "winner-owned").write_text("do not remove", encoding="utf-8")
            raise FileExistsError("simulated concurrent reservation")
        original_replace(source, target)

    monkeypatch.setattr(release_module.os, "replace", lose_transaction_race)

    with pytest.raises(ReleaseGateError, match="cannot reserve release transaction"):
        _publish(inputs, b"candidate-model", {"schemaVersion": 1}, os.link)

    transaction = inputs.assets_dir / ".card_detector.release.transaction"
    assert (transaction / "winner-owned").read_text(encoding="utf-8") == "do not remove"
    assert not (inputs.assets_dir / "card_detector.tflite").exists()
    assert not (inputs.assets_dir / "card_detector.release.json").exists()


def test_arbitrary_json_fake_model_and_nonexistent_images_never_authorize_assets(
    tmp_path: Path,
) -> None:
    """Catch invented JSON authorizing bytes never inspected or photographed."""

    inputs = _passing_evidence(tmp_path)

    with pytest.raises(
        ReleaseGateError, match="inspected TFLite|canonical image|training and validation"
    ):
        promote_release(inputs)

    assert not inputs.assets_dir.exists()


def test_production_cli_has_no_caller_controlled_release_gate() -> None:
    with pytest.raises(SystemExit, match="2"):
        release_main(
            [
                "--model",
                "candidate.tflite",
                "--report",
                "validation.json",
                "--contract",
                "model-contract.json",
                "--release-config",
                "attacker.yaml",
            ]
        )


def test_repository_gate_pins_the_exact_committed_human_audit_receipt() -> None:
    *_, schema, receipt_hash = _load_gate(_PINNED_GATE_PATH)
    receipt = _PINNED_GATE_PATH.parent.parent / "datasets" / "source-audit-receipt.json"

    assert schema == 2
    assert receipt_hash == _sha256(receipt)
    assert json.loads(receipt.read_text(encoding="utf-8"))["status"] == "NOT_APPROVED"


def test_archive_member_binding_rejects_unsafe_or_unrelated_bytes() -> None:
    archive_bytes = io.BytesIO()
    with zipfile.ZipFile(archive_bytes, "w") as archive:
        archive.writestr("photos/card.png", b"audited-image")

    assert _archive_member(archive_bytes.getvalue(), "photos/card.png") == b"audited-image"
    with pytest.raises(ReleaseGateError, match="unsafe"):
        _archive_member(archive_bytes.getvalue(), "../card.png")


def test_fabricated_threshold_metrics_reject_after_replayed_inference(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from pontinho_cards import evaluate as evaluate_module
    from pontinho_cards.evaluate import Detection

    scene = {
        "sceneId": "validation-1",
        "truth": [Detection("AC", (0.0, 0.0, 10.0, 10.0))],
        "predictions": [Detection("AC", (0.0, 0.0, 10.0, 10.0), 0.9)],
        "deckGroupId": "deck-validation",
        "lighting": "daylight",
    }
    monkeypatch.setattr(evaluate_module, "_infer_scenes", lambda *args, **kwargs: [scene])
    contract = {
        "input": {"shape": [1, 320, 320, 3], "dtype": "uint8"},
        "normalization": {"mean": [0.0] * 3, "std": [255.0] * 3},
    }
    artifact = {
        "labels": list(CARD_LABELS),
        "preprocessing": {
            "input": contract["input"],
            "normalization": contract["normalization"],
        },
        "grid": {"scoreThresholds": [0.5], "iouThresholds": [0.5]},
        "scenes": [
            {
                "sceneId": "validation-1",
                "predictions": [{"label": "AC", "box": [0.0, 0.0, 10.0, 10.0], "confidence": 0.9}],
            }
        ],
        "rows": [
            {
                "scoreThreshold": 0.5,
                "iouThreshold": 0.5,
                "exactHandTotalAccuracy": 0.0,
                "exactCardMultisetAccuracy": 0.0,
                "abstentionRate": 0.0,
            }
        ],
    }

    with pytest.raises(ReleaseGateError, match="replayed inference"):
        _recompute_threshold_sweep(
            artifact,
            model_payload=b"model",
            contract=contract,
            validation={"records": [{}]},
            validation_path=tmp_path / "validation-manifest.json",
            seed=17,
        )


def test_fabricated_release_report_and_receipt_cannot_replace_holdout_replay(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Catch a perfectly self-consistent report/receipt that no inference produced."""

    from PIL import Image

    from pontinho_cards import evaluate as evaluate_module
    from pontinho_cards.evaluate import Detection, evaluate_scenes
    from pontinho_cards.release import _replay_holdout_metrics

    image = tmp_path / "scene.png"
    Image.new("RGB", (20, 20), "white").save(image)
    payload = image.read_bytes()
    record = {
        "imageId": "scene-1",
        "path": "scene.png",
        "width": 20,
        "height": 20,
        "imageSha256": hashlib.sha256(payload).hexdigest(),
        "deckGroupId": "deck-1",
        "lighting": "daylight",
        "boxes": [{"label": "AC", "xmin": 1, "ymin": 1, "xmax": 10, "ymax": 10}],
    }
    scene = {
        **record,
        "sceneId": "scene-1",
        "truth": [Detection("AC", (1.0, 1.0, 10.0, 10.0))],
        "predictions": [Detection("AC", (1.0, 1.0, 10.0, 10.0), 0.99)],
    }
    monkeypatch.setattr(evaluate_module, "_infer_scenes", lambda *_args, **_kwargs: [scene])
    genuine = evaluate_scenes([scene], confidence_threshold=0.5, iou_threshold=0.5, seed=17)
    fabricated = json.loads(json.dumps(genuine))
    fabricated["exactHandTotalAccuracy"] = 0.0

    with pytest.raises(ReleaseGateError, match="holdout replay"):
        _replay_holdout_metrics(
            model_payload=b"snapshotted-tflite",
            contract={"scoreThreshold": 0.5, "iouThreshold": 0.5},
            holdout={"seed": 17, "test": [record]},
            image_payloads={"scene-1": payload},
            reported_metrics=fabricated,
        )


def test_canonical_image_bytes_must_exist_and_match_the_record(tmp_path: Path) -> None:
    """Catch a signed record referring to absent rather than snapshotted image bytes."""

    from pontinho_cards.release import _validate_canonical_image

    record = {
        "path": "photos/missing.jpg",
        "imageSha256": "0" * 64,
        "width": 640,
        "height": 480,
    }

    with pytest.raises(ReleaseGateError, match="canonical image"):
        _validate_canonical_image(tmp_path, record)


def test_publish_never_replaces_a_target_created_after_reservation(tmp_path: Path) -> None:
    """Catch a late competing writer being overwritten by rename semantics."""

    inputs = _passing_evidence(tmp_path)
    competitor = b"competitor-model"
    calls = 0

    def late_competitor(source: Path, target: Path) -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            target.write_bytes(competitor)
        os.link(source, target)

    with pytest.raises(ReleaseGateError, match="already exists|no-clobber"):
        _publish(
            inputs,
            b"candidate-model",
            {"schemaVersion": 1, "status": "APPROVED"},
            late_competitor,
        )

    assert (inputs.assets_dir / "card_detector.tflite").read_bytes() == competitor


@pytest.mark.parametrize("link_target", ["parent", "assets", "model", "metadata"])
def test_publish_rejects_symlinked_asset_paths(tmp_path: Path, link_target: str) -> None:
    """Catch publication escaping through a parent or dangling target symlink."""

    inputs = _passing_evidence(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    if link_target == "parent":
        linked_parent = tmp_path / "linked-parent"
        linked_parent.symlink_to(outside, target_is_directory=True)
        inputs = replace(inputs, assets_dir=linked_parent / "assets")
    elif link_target == "assets":
        inputs.assets_dir.symlink_to(outside, target_is_directory=True)
    else:
        inputs.assets_dir.mkdir()
        name = "card_detector.tflite" if link_target == "model" else "card_detector.release.json"
        (inputs.assets_dir / name).symlink_to(outside / "missing")

    with pytest.raises(ReleaseGateError, match="symbolic link|reparse"):
        _publish(inputs, b"candidate-model", {"schemaVersion": 1}, os.replace)

    assert not any(outside.iterdir())


def test_threshold_selection_is_recomputed_not_trusted() -> None:
    """Catch a declared threshold selection that is not the deterministic best row."""

    from pontinho_cards.release import _validate_threshold_selection

    artifact = {
        "schemaVersion": 1,
        "modelSha256": "a" * 64,
        "checkpointSha256": "b" * 64,
        "configSha256": "c" * 64,
        "splitManifestSha256": "e" * 64,
        "validationManifestSha256": "d" * 64,
        "rows": [
            {
                "scoreThreshold": 0.4,
                "iouThreshold": 0.5,
                "exactHandTotalAccuracy": 0.96,
                "exactCardMultisetAccuracy": 0.95,
                "abstentionRate": 0.02,
            },
            {
                "scoreThreshold": 0.5,
                "iouThreshold": 0.5,
                "exactHandTotalAccuracy": 0.97,
                "exactCardMultisetAccuracy": 0.94,
                "abstentionRate": 0.01,
            },
        ],
        "selected": {
            "scoreThreshold": 0.4,
            "iouThreshold": 0.5,
            "exactHandTotalAccuracy": 0.96,
            "exactCardMultisetAccuracy": 0.95,
            "abstentionRate": 0.02,
        },
    }

    with pytest.raises(ReleaseGateError, match="deterministic best"):
        _validate_threshold_selection(
            artifact,
            model_hash="a" * 64,
            checkpoint_hash="b" * 64,
            config_hash="c" * 64,
            split_hash="e" * 64,
            validation_hash="d" * 64,
            score_threshold=0.4,
            iou_threshold=0.5,
        )


@pytest.mark.skipif(
    not os.environ.get("PONTINHO_GENUINE_RELEASE_FIXTURE"),
    reason="set PONTINHO_GENUINE_RELEASE_FIXTURE to an immutable real release fixture",
)
def test_genuine_fixture_cannot_override_pinned_not_approved_trust_root(
    tmp_path: Path,
) -> None:
    """Exercise the public gate only with a real TFLite and canonical evidence tree."""

    fixture = Path(os.environ["PONTINHO_GENUINE_RELEASE_FIXTURE"]).resolve(strict=True)
    report = fixture / "validation.json"
    report_document = json.loads(report.read_text(encoding="utf-8"))
    receipt = (
        fixture
        / ".evaluation-receipts"
        / (f"{report_document['modelSha256']}.{report_document['holdoutIdentity']}.json")
    )
    inputs = ReleaseInputs(
        model=fixture / "card_detector.tflite",
        report=report,
        contract=fixture / "model-contract.json",
        source_manifest=fixture / "sources.json",
        split_manifest=fixture / "splits.json",
        holdout_manifest=fixture / "final-holdout.json",
        validation_receipt=receipt,
        candidate_contract=fixture / "candidate-contract.json",
        comparison=fixture / "export-comparison.json",
        validation_manifest=fixture / "validation-manifest.json",
        assets_dir=tmp_path / "assets",
        threshold_selection=fixture / "threshold-selection.json",
        source_audit_receipt=fixture / "source-audit-receipt.json",
        source_archives=fixture / "source-archives",
    )

    with pytest.raises(ReleaseGateError, match="source-audit|trust root|NOT_APPROVED"):
        promote_release(inputs)

    assert not inputs.assets_dir.exists()


@pytest.mark.skipif(
    not os.environ.get("PONTINHO_GENUINE_RELEASE_FIXTURE"),
    reason="set PONTINHO_GENUINE_RELEASE_FIXTURE to an immutable real release fixture",
)
def test_genuine_fixture_replays_every_holdout_image_and_complete_report() -> None:
    """Opt-in proof that release replay runs real TFLite inference, not transaction mocks."""

    from pontinho_cards.release import _replay_holdout_metrics

    fixture = Path(os.environ["PONTINHO_GENUINE_RELEASE_FIXTURE"]).resolve(strict=True)
    model_payload = (fixture / "card_detector.tflite").read_bytes()
    contract = json.loads((fixture / "model-contract.json").read_text(encoding="utf-8"))
    holdout = json.loads((fixture / "final-holdout.json").read_text(encoding="utf-8"))
    report = json.loads((fixture / "validation.json").read_text(encoding="utf-8"))
    image_payloads = {
        record["imageId"]: (fixture / record["path"]).read_bytes() for record in holdout["test"]
    }

    _replay_holdout_metrics(
        model_payload=model_payload,
        contract=contract,
        holdout=holdout,
        image_payloads=image_payloads,
        reported_metrics=report["metrics"],
    )
