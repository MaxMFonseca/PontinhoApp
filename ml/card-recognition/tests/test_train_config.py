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
    capture_synthetic_evidence,
    load_training_config,
    main,
    merge_approved_synthetic_training,
    prepare_training_run,
    run_training,
    validate_validation_metrics,
    write_model_maker_csv,
    write_model_maker_pascal,
)

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _canonical_json(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


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


def _reviewed_synthetic_bundle(
    root: Path,
    split_path: Path,
    records: list[dict[str, object]],
) -> tuple[Path, Path]:
    root.mkdir(exist_ok=True)
    split_hash = hashlib.sha256(split_path.read_bytes()).hexdigest()
    generation_config = {
        "generator": "pontinho_cards.synthetic.tabletop-v1",
        "seed": 123,
        "requestedSceneCount": len(records),
        "sceneSize": [32, 32],
        "baseSplitManifestSha256": split_hash,
    }
    generation_hash = hashlib.sha256(
        (json.dumps(generation_config, sort_keys=True, separators=(",", ":")) + "\n").encode()
    ).hexdigest()
    train_parent = json.loads(split_path.read_text())["partitions"]["train"][0]
    for index, record in enumerate(records):
        metadata = {
            "schemaVersion": 1,
            "seed": 1000 + index,
            "width": 32,
            "height": 32,
            "background": {
                "kind": "procedural",
                "source": "procedural-tabletop-v1",
                "license": "CC0-1.0",
                "generator": "pontinho_cards.synthetic.tabletop-v1",
                "seed": 2000 + index,
            },
            "cards": [],
            "pixelSources": [],
            "image": str(record["path"]),
        }
        parents: list[dict[str, object]] = []
        if record["boxes"]:
            parents = [
                {
                    "imageId": train_parent["imageId"],
                    "imageSha256": train_parent["imageSha256"],
                    "sourceId": train_parent["sourceId"],
                }
            ]
            metadata["cards"] = [
                {
                    "instanceId": f"card-{index:02d}",
                    "label": box["label"],
                    "partition": "train",
                    "parentImageSha256": train_parent["imageSha256"],
                    "pixelSource": {
                        "kind": "cardCrop",
                        "instanceId": f"card-{index:02d}",
                        "parentImageSha256": train_parent["imageSha256"],
                        "parentImageId": train_parent["imageId"],
                        "sourceId": train_parent["sourceId"],
                        "sourcePath": train_parent["path"],
                        "cropBox": [2, 3, 22, 25],
                        "boxIndex": 0,
                    },
                    "transform": {},
                    "finalBox": {
                        key: box[key] for key in ("xmin", "ymin", "xmax", "ymax")
                    },
                }
                for box in record["boxes"]
            ]
        metadata_path = root / f"scene-{index:05d}.json"
        metadata_path.write_bytes(_canonical_json(metadata))
        record.update(
            {
                "sourceId": "synthetic-tabletop-v1",
                "deckGroupId": "synthetic-mixed-training-decks",
                "splitGroupId": "synthetic-run-123",
                "sourceDerivativeId": record["imageSha256"],
                "augmentationParentId": parents[0]["imageId"] if len(parents) == 1 else None,
                "augmentationParentIds": [parent["imageId"] for parent in parents],
                "isRealPhoto": False,
                "syntheticProvenance": {
                    "generator": "pontinho_cards.synthetic.tabletop-v1",
                    "seed": metadata["seed"],
                    "metadata": metadata_path.name,
                    "metadataSha256": hashlib.sha256(metadata_path.read_bytes()).hexdigest(),
                    "parentImageIds": [parent["imageId"] for parent in parents],
                    "parents": parents,
                    "background": metadata["background"],
                },
            }
        )
    has_cards = any(record["boxes"] for record in records)
    scene_count = max(100, len(records)) if has_cards else len(records)
    distribution_document = {
        "schemaVersion": 1,
        "seed": 123,
        "requestedSceneCount": scene_count,
        "sceneCount": scene_count,
        "audit": {
            "minimumScenes": 100,
            "reviewedScenes": 0,
            "status": "PENDING_HUMAN_REVIEW",
            "issues": [],
        },
        "trainingBlocked": True,
    }
    distribution = root / "distribution.json"
    distribution.write_bytes(_canonical_json(distribution_document))
    contact = root / "contact-sheet.jpg"
    Image.new("RGB", (32, 32), "black").save(contact)
    manifest_document = {
        "schemaVersion": 1,
        "kind": "pontinho-synthetic-training",
        "partition": "train",
        "seed": 123,
        "generation": {**generation_config, "configSha256": generation_hash},
        "records": records,
        "audit": {
            "status": "PENDING_HUMAN_REVIEW",
            "minimumScenes": 100,
            "sceneCount": scene_count,
            "distributionSha256": hashlib.sha256(distribution.read_bytes()).hexdigest(),
            "contactSheetSha256": hashlib.sha256(contact.read_bytes()).hexdigest(),
        },
    }
    manifest = root / "training-manifest.json"
    manifest.write_bytes(_canonical_json(manifest_document))
    review_document = {
        "schemaVersion": 1,
        "status": "HUMAN_APPROVED",
        "syntheticManifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        "generationConfigSha256": generation_hash,
        "distributionSha256": manifest_document["audit"]["distributionSha256"],
        "contactSheetSha256": manifest_document["audit"]["contactSheetSha256"],
    }
    review = root / "human-review.json"
    review.write_bytes(_canonical_json(review_document))
    return manifest, review


def _merge_bundle(split: Path, manifest: Path, review: Path) -> dict[str, object]:
    evidence = capture_synthetic_evidence(
        split_payload=split.read_bytes(),
        synthetic_manifest=manifest,
        human_review=review,
    )
    return merge_approved_synthetic_training(evidence)


@pytest.mark.parametrize(
    "linked_name",
    ["training-manifest.json", "human-review.json", "distribution.json", "contact-sheet.jpg"],
)
def test_synthetic_authorization_rejects_symlinked_evidence(
    tmp_path: Path, linked_name: str
) -> None:
    split = _split_document(tmp_path)
    root = tmp_path / "synthetic-symlink"
    image = root / "scene.png"
    root.mkdir()
    Image.new("RGB", (32, 32), "black").save(image)
    record = _record(image)
    record["path"] = image.name
    record["boxes"] = []
    manifest, review = _reviewed_synthetic_bundle(root, split, [record])
    selected = root / linked_name
    outside = tmp_path / f"outside-{linked_name}"
    selected.replace(outside)
    selected.symlink_to(outside)

    with pytest.raises(TrainingConfigError, match="cannot snapshot"):
        capture_synthetic_evidence(
            split_payload=split.read_bytes(),
            synthetic_manifest=manifest,
            human_review=review,
        )


def test_merge_uses_only_captured_bytes_after_snapshot(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    split = _split_document(tmp_path)
    root = tmp_path / "synthetic-immutable"
    root.mkdir()
    image = root / "scene.png"
    Image.new("RGB", (32, 32), "black").save(image)
    record = _record(image)
    record["path"] = image.name
    record["boxes"] = []
    manifest, review = _reviewed_synthetic_bundle(root, split, [record])
    evidence = capture_synthetic_evidence(
        split_payload=split.read_bytes(),
        synthetic_manifest=manifest,
        human_review=review,
    )

    def forbidden(*args: object, **kwargs: object) -> object:
        raise AssertionError("authorization reopened live evidence")

    monkeypatch.setattr(Path, "read_bytes", forbidden)
    monkeypatch.setattr(Path, "is_file", forbidden)
    merged = merge_approved_synthetic_training(evidence)

    assert merged["partitions"]["train"][-1]["path"] == "synthetic-evidence/scene.png"


def test_split_swap_after_initial_capture_cannot_change_synthetic_reconstruction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    split = _split_document(tmp_path)
    original_split = split.read_bytes()
    root = tmp_path / "synthetic-swap"
    root.mkdir()
    image = root / "scene.png"
    Image.new("RGB", (32, 32), "black").save(image)
    record = _record(image)
    record["path"] = image.name
    record["boxes"] = []
    manifest, review = _reviewed_synthetic_bundle(root, split, [record])
    original_reader = train_module.secure_read_bytes
    swapped = False

    def swapping_reader(path: Path, *, root: Path | None = None) -> bytes:
        nonlocal swapped
        payload = original_reader(path, root=root)
        if not swapped:
            split.write_text('{"schemaVersion":1,"seed":0,"partitions":{}}')
            swapped = True
        return payload

    monkeypatch.setattr(train_module, "secure_read_bytes", swapping_reader)
    evidence = capture_synthetic_evidence(
        split_payload=original_split,
        synthetic_manifest=manifest,
        human_review=review,
    )
    merged = merge_approved_synthetic_training(evidence)

    assert merged["seed"] == 20261014
    assert merged["syntheticManifestSha256"] == hashlib.sha256(
        evidence.manifest_payload
    ).hexdigest()


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


def test_approved_synthetic_manifest_merges_only_into_train_and_keeps_negative(
    tmp_path: Path,
) -> None:
    split_path = _split_document(tmp_path, full_vocabulary=True)
    synthetic_root = tmp_path / "synthetic"
    negative = synthetic_root / "negative.png"
    second_negative = synthetic_root / "negative-2.png"
    synthetic_root.mkdir()
    Image.new("RGB", (32, 32), "black").save(negative)
    Image.new("RGB", (32, 32), "navy").save(second_negative)
    record = _record(negative)
    record["imageId"] = "synthetic-negative"
    record["isRealPhoto"] = False
    record["boxes"] = []
    record["path"] = negative.name
    second_record = _record(second_negative)
    second_record.update(
        {
            "imageId": "synthetic-negative-2",
            "path": second_negative.name,
            "sourceDerivativeId": "synthetic-negative-2",
            "isRealPhoto": False,
            "boxes": [],
        }
    )
    manifest, review = _reviewed_synthetic_bundle(
        synthetic_root, split_path, [record, second_record]
    )

    merged = _merge_bundle(split_path, manifest, review)

    original = json.loads(split_path.read_text(encoding="utf-8"))
    assert merged["partitions"]["validation"] == original["partitions"]["validation"]
    assert merged["partitions"]["test"] == original["partitions"]["test"]
    assert [record["imageId"] for record in merged["partitions"]["train"][-2:]] == [
        "synthetic-negative",
        "synthetic-negative-2",
    ]
    assert merged["syntheticManifestSha256"] == hashlib.sha256(manifest.read_bytes()).hexdigest()
    assert merged["syntheticReviewSha256"] == hashlib.sha256(review.read_bytes()).hexdigest()

    merged_path = tmp_path / "merged.json"
    merged_path.write_text(json.dumps(merged), encoding="utf-8")
    evidence_root = tmp_path / "synthetic-evidence"
    evidence_root.mkdir()
    (evidence_root / negative.name).write_bytes(negative.read_bytes())
    (evidence_root / second_negative.name).write_bytes(second_negative.read_bytes())
    annotations = tmp_path / "pascal"
    counts = write_model_maker_pascal(
        merged_path,
        annotations,
        train_partition="train",
        validation_partition="validation",
    )
    assert counts == {"train": 3, "validation": 1, "negativeTrain": 2}
    empty_xml = (annotations / "train" / "synthetic-negative.xml").read_text()
    assert "<object>" not in empty_xml
    assert ".jpg" in empty_xml


def test_reviewed_positive_and_empty_negative_bind_separate_training_hashes(
    tmp_path: Path,
) -> None:
    split_path = _split_document(tmp_path, full_vocabulary=True)
    synthetic_root = tmp_path / "synthetic-reviewed"
    synthetic_root.mkdir()
    records: list[dict[str, object]] = []
    for index in range(101):
        image = synthetic_root / f"scene-{index:05d}.png"
        Image.new(
            "RGB",
            (32, 32),
            (index % 256, (index * 3) % 256, (index * 7) % 256),
        ).save(image)
        record = _record(image)
        record["imageId"] = f"synthetic-123-{index:05d}"
        record["path"] = image.name
        if index == 0:
            record["boxes"] = []
        records.append(record)
    manifest, review = _reviewed_synthetic_bundle(synthetic_root, split_path, records)

    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=split_path,
        run_id="synthetic-reviewed",
        checkpoint_root=tmp_path / "checkpoints",
        requirements_lock=PROJECT_ROOT / "requirements.lock",
        git_commit="856f3f9",
        synthetic_manifest=manifest,
        synthetic_review=review,
    )

    metadata = json.loads((prepared.staging_dir / "run-metadata.json").read_text())
    assert metadata["splitManifestSha256"] == hashlib.sha256(split_path.read_bytes()).hexdigest()
    assert metadata["trainingInputSha256"] == hashlib.sha256(
        prepared.splits_path.read_bytes()
    ).hexdigest()
    assert metadata["trainingInputSha256"] != metadata["splitManifestSha256"]
    assert metadata["syntheticManifestSha256"] == hashlib.sha256(manifest.read_bytes()).hexdigest()
    assert metadata["syntheticReviewSha256"] == hashlib.sha256(review.read_bytes()).hexdigest()
    merged = json.loads(prepared.splits_path.read_text())
    assert merged["partitions"]["train"][-101]["boxes"] == []
    assert merged["partitions"]["validation"] == json.loads(split_path.read_text())[
        "partitions"
    ]["validation"]

    from pontinho_cards.release import (
        ReleaseInputs,
        _validate_threshold_selection,
        _validate_training_input,
    )
    from pontinho_cards.thresholds import produce_validation_manifest

    validation_path = tmp_path / "validation-manifest.json"
    validation = produce_validation_manifest(split_path, validation_path)
    selected = {
        "scoreThreshold": 0.5,
        "iouThreshold": 0.5,
        "exactHandTotalAccuracy": 0.9,
        "exactCardMultisetAccuracy": 0.9,
        "abstentionRate": 0.0,
    }
    _validate_threshold_selection(
        {
            "schemaVersion": 1,
            "modelSha256": "a" * 64,
            "checkpointSha256": "b" * 64,
            "configSha256": "c" * 64,
            "splitManifestSha256": validation["splitManifestSha256"],
            "validationManifestSha256": hashlib.sha256(validation_path.read_bytes()).hexdigest(),
            "rows": [selected],
            "selected": selected,
        },
        model_hash="a" * 64,
        checkpoint_hash="b" * 64,
        config_hash="c" * 64,
        split_hash=validation["splitManifestSha256"],
        validation_hash=hashlib.sha256(validation_path.read_bytes()).hexdigest(),
        score_threshold=0.5,
        iou_threshold=0.5,
    )
    contract = {
        key: metadata[key]
        for key in (
            "splitManifestSha256",
            "trainingInputSha256",
            "syntheticManifestSha256",
            "syntheticReviewSha256",
            "syntheticGenerationConfigSha256",
        )
    }
    dummy = tmp_path / "unused"
    release_inputs = ReleaseInputs(
        model=dummy,
        report=dummy,
        contract=dummy,
        source_manifest=dummy,
        split_manifest=split_path,
        holdout_manifest=dummy,
        validation_receipt=dummy,
        candidate_contract=dummy,
        comparison=dummy,
        validation_manifest=validation_path,
        assets_dir=tmp_path / "assets",
        training_input=prepared.splits_path,
        synthetic_manifest=manifest,
        synthetic_review=review,
    )
    reconstructed, payload, *_ = _validate_training_input(
        release_inputs,
        split_payload=split_path.read_bytes(),
        contract=contract,
    )
    assert hashlib.sha256(payload).hexdigest() == metadata["trainingInputSha256"]
    assert reconstructed["partitions"]["train"][-101]["boxes"] == []


def test_synthetic_parent_cannot_be_rebound_to_validation_even_with_rehashed_receipt(
    tmp_path: Path,
) -> None:
    split_path = _split_document(tmp_path, full_vocabulary=True)
    synthetic_root = tmp_path / "synthetic-leak"
    synthetic_root.mkdir()
    records: list[dict[str, object]] = []
    for index in range(100):
        image = synthetic_root / f"scene-{index:05d}.png"
        Image.new("RGB", (32, 32), (index, 10, 20)).save(image)
        record = _record(image)
        record["imageId"] = f"synthetic-leak-{index:05d}"
        record["path"] = image.name
        records.append(record)
    manifest, review = _reviewed_synthetic_bundle(synthetic_root, split_path, records)
    document = json.loads(manifest.read_text())
    validation_id = json.loads(split_path.read_text())["partitions"]["validation"][0]["imageId"]
    document["records"][0]["syntheticProvenance"]["parents"][0]["imageId"] = validation_id
    manifest.write_bytes(_canonical_json(document))
    receipt = json.loads(review.read_text())
    receipt["syntheticManifestSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
    review.write_bytes(_canonical_json(receipt))

    with pytest.raises(TrainingConfigError, match="held-out"):
        _merge_bundle(split_path, manifest, review)


def test_synthetic_metadata_card_ancestry_cannot_hide_validation_pixels(
    tmp_path: Path,
) -> None:
    split_path = _split_document(tmp_path, full_vocabulary=True)
    synthetic_root = tmp_path / "synthetic-metadata-leak"
    synthetic_root.mkdir()
    records: list[dict[str, object]] = []
    for index in range(100):
        image = synthetic_root / f"scene-{index:05d}.png"
        Image.new("RGB", (32, 32), (index, 30, 40)).save(image)
        record = _record(image)
        record["imageId"] = f"synthetic-metadata-leak-{index:05d}"
        record["path"] = image.name
        records.append(record)
    manifest, review = _reviewed_synthetic_bundle(synthetic_root, split_path, records)
    split = json.loads(split_path.read_text())
    validation = split["partitions"]["validation"][0]
    metadata_path = synthetic_root / "scene-00000.json"
    metadata = json.loads(metadata_path.read_text())
    pixel_source = metadata["cards"][0]["pixelSource"]
    pixel_source["parentImageId"] = validation["imageId"]
    pixel_source["parentImageSha256"] = validation["imageSha256"]
    pixel_source["sourceId"] = validation["sourceId"]
    metadata["cards"][0]["parentImageSha256"] = validation["imageSha256"]
    metadata_path.write_bytes(_canonical_json(metadata))
    manifest_document = json.loads(manifest.read_text())
    manifest_document["records"][0]["syntheticProvenance"]["metadataSha256"] = hashlib.sha256(
        metadata_path.read_bytes()
    ).hexdigest()
    manifest.write_bytes(_canonical_json(manifest_document))
    receipt = json.loads(review.read_text())
    receipt["syntheticManifestSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
    review.write_bytes(_canonical_json(receipt))

    with pytest.raises(TrainingConfigError, match="metadata card ancestry"):
        _merge_bundle(split_path, manifest, review)


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
        "splitManifestSha256": hashlib.sha256(split_path.read_bytes()).hexdigest(),
        "trainingInputSha256": hashlib.sha256(split_path.read_bytes()).hexdigest(),
        "syntheticManifestSha256": None,
        "syntheticReviewSha256": None,
        "syntheticGenerationConfigSha256": None,
        "requirementsLockSha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
        "schemaVersion": 2,
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
        validate_validation_metrics({key: value for key, value in valid.items() if key != "AP_/KS"})
    with pytest.raises(TrainingConfigError, match="finite"):
        validate_validation_metrics({**valid, "AP_/KS": math.nan})
