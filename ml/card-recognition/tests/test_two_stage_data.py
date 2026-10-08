from __future__ import annotations

import hashlib
import os
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest
from PIL import Image

from pontinho_cards import secure_io
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.two_stage_data import (
    TwoStageDataError,
    build_two_stage_inputs,
)


def _write_scene(root: Path, partition: str, *, color: str) -> tuple[str, str]:
    relative = f"source/{partition}.png"
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (520, 120), color).save(path)
    return relative, hashlib.sha256(path.read_bytes()).hexdigest()


def _boxes(*, duplicate_ace: bool = False) -> list[dict[str, object]]:
    boxes: list[dict[str, object]] = []
    for index, label in enumerate(CARD_LABELS):
        xmin = float(index * 10)
        boxes.append(
            {
                "boxIndex": index * 2,
                "label": label,
                "xmin": xmin,
                "ymin": 0.0,
                "xmax": xmin + 5.0,
                "ymax": 10.0,
            }
        )
    if duplicate_ace:
        boxes.append(
            {
                "boxIndex": 999,
                "label": "AC",
                "xmin": 500.0,
                "ymin": 50.0,
                "xmax": 515.0,
                "ymax": 100.0,
            }
        )
    return boxes


def _record(
    partition: str,
    path: str,
    digest: str,
    *,
    deck: str,
    duplicate_ace: bool = False,
) -> dict[str, object]:
    task_roles = (
        ["FINAL_SCENE_HOLDOUT"]
        if partition == "test"
        else ["DETECTOR_SCENE", "CLASSIFIER_CROP"]
    )
    return {
        "imageId": f"{partition}-scene",
        "path": path,
        "width": 520,
        "height": 120,
        "sourceId": "approved-scenes",
        "deckGroupId": deck,
        "splitGroupId": f"{partition}-capture",
        "imageSha256": digest,
        "sourceDerivativeId": f"{partition}-original",
        "augmentationParentId": None,
        "isRealPhoto": True,
        "taskRoles": task_roles,
        "boxes": _boxes(duplicate_ace=duplicate_ace),
    }


def _split_document(root: Path, *, duplicate_ace: bool = False) -> dict[str, object]:
    train_path, train_digest = _write_scene(root, "train", color="green")
    validation_path, validation_digest = _write_scene(root, "validation", color="blue")
    return {
        "schemaVersion": 1,
        "seed": 20261014,
        "partitions": {
            "train": [
                _record(
                    "train",
                    train_path,
                    train_digest,
                    deck="deck-train",
                    duplicate_ace=duplicate_ace,
                )
            ],
            "validation": [
                _record(
                    "validation",
                    validation_path,
                    validation_digest,
                    deck="deck-validation",
                )
            ],
            "test": [
                _record(
                    "test",
                    "source/frozen-holdout-must-not-be-opened.png",
                    "f" * 64,
                    deck="deck-holdout",
                )
            ],
        },
    }


def test_build_projects_detector_labels_and_classifier_crops_without_merging_equal_cards(
    tmp_path: Path,
) -> None:
    manifest = build_two_stage_inputs(_split_document(tmp_path, duplicate_ace=True), tmp_path)

    detector = manifest.detector_partitions["train"][0]
    assert len(detector.boxes) == 53
    assert {box.label for box in detector.boxes} == {"card"}
    assert detector.boxes[0].bounds == (0.0, 0.0, 5.0, 10.0)
    assert detector.path.read_bytes() == (tmp_path / "source/train.png").read_bytes()

    crops = manifest.classifier_partitions["train"]
    assert {crop.label for crop in crops} == set(CARD_LABELS)
    ace_crops = [crop for crop in crops if crop.label == "AC"]
    assert len(ace_crops) == 2
    assert {"-b0000-" in crop.path.name for crop in ace_crops} == {False, True}
    assert {"-b0999-" in crop.path.name for crop in ace_crops} == {False, True}
    assert len({crop.path for crop in ace_crops}) == 2
    assert crops[0].source_bounds == (0, 0, 6, 11)


def test_build_preserves_partition_deck_group_source_hash_and_content_binding(
    tmp_path: Path,
) -> None:
    split = _split_document(tmp_path)
    manifest = build_two_stage_inputs(split, tmp_path)

    assert tuple(manifest.detector_partitions) == ("train", "validation")
    assert tuple(manifest.classifier_partitions) == ("train", "validation")
    for partition, expected_deck in (
        ("train", "deck-train"),
        ("validation", "deck-validation"),
    ):
        source = split["partitions"][partition][0]  # type: ignore[index]
        detector = manifest.detector_partitions[partition][0]
        assert detector.deck_group_id == expected_deck
        assert detector.split_group_id == f"{partition}-capture"
        assert detector.source_image_sha256 == source["imageSha256"]
        assert source["imageSha256"] in detector.path.name
        assert {crop.label for crop in manifest.classifier_partitions[partition]} == set(
            CARD_LABELS
        )
        for crop in manifest.classifier_partitions[partition]:
            assert crop.deck_group_id == expected_deck
            assert crop.source_image_sha256 == source["imageSha256"]
            assert crop.source_image_sha256 in crop.path.name
            assert crop.label in crop.path.name
            crop_digest = hashlib.sha256(crop.path.read_bytes()).hexdigest()
            assert crop_digest in crop.path.name

    with pytest.raises(TypeError):
        manifest.detector_partitions["train"] = ()  # type: ignore[index]
    with pytest.raises(FrozenInstanceError):
        manifest.output_root = tmp_path / "elsewhere"  # type: ignore[misc]


def test_build_requires_all_52_classes_in_train_and_validation_before_writing(
    tmp_path: Path,
) -> None:
    split = _split_document(tmp_path)
    split["partitions"]["validation"][0]["boxes"].pop()  # type: ignore[index,union-attr]

    with pytest.raises(TwoStageDataError, match="validation.*52 canonical classes"):
        build_two_stage_inputs(split, tmp_path)

    assert not (tmp_path / "detector").exists()
    assert not (tmp_path / "classifier").exists()


def test_build_never_opens_or_materializes_the_final_holdout(tmp_path: Path) -> None:
    manifest = build_two_stage_inputs(_split_document(tmp_path), tmp_path)

    assert "test" not in manifest.detector_partitions
    assert "test" not in manifest.classifier_partitions
    assert not any("test" in path.parts for path in (tmp_path / "detector").rglob("*"))
    assert not any("test" in path.parts for path in (tmp_path / "classifier").rglob("*"))


def test_build_rejects_a_final_holdout_record_in_a_training_partition(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    split["partitions"]["train"][0]["taskRoles"] = ["FINAL_SCENE_HOLDOUT"]  # type: ignore[index]

    with pytest.raises(TwoStageDataError, match="FINAL_SCENE_HOLDOUT.*train"):
        build_two_stage_inputs(split, tmp_path)

    assert not (tmp_path / "detector").exists()


def test_build_keeps_audited_detector_negative_without_classifier_crop(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    negative_path, negative_digest = _write_scene(tmp_path, "negative", color="black")
    negative = _record(
        "negative",
        negative_path,
        negative_digest,
        deck="deck-train",
    )
    negative["imageId"] = "train-negative"
    negative["splitGroupId"] = "train-negative-capture"
    negative["sourceDerivativeId"] = "train-negative-original"
    negative["taskRoles"] = ["DETECTOR_SCENE"]
    negative["boxes"] = []
    split["partitions"]["train"].append(negative)  # type: ignore[index,union-attr]

    manifest = build_two_stage_inputs(split, tmp_path)

    negative_detector = next(
        record
        for record in manifest.detector_partitions["train"]
        if record.image_id == "train-negative"
    )
    assert negative_detector.boxes == ()
    assert all(
        negative_digest not in crop.path.name
        for crop in manifest.classifier_partitions["train"]
    )


def test_build_rejects_deck_leakage_between_training_partitions(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    split["partitions"]["validation"][0]["deckGroupId"] = "deck-train"  # type: ignore[index]

    with pytest.raises(TwoStageDataError, match="deckGroupId.*train.*validation"):
        build_two_stage_inputs(split, tmp_path)


def test_build_rejects_source_hash_mismatch_without_publishing_outputs(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    split["partitions"]["train"][0]["imageSha256"] = "0" * 64  # type: ignore[index]

    with pytest.raises(TwoStageDataError, match="SHA-256"):
        build_two_stage_inputs(split, tmp_path)

    assert not (tmp_path / "classifier").exists()


def test_build_rejects_symlinked_source_and_output_components(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    source = tmp_path / "source/train.png"
    real_source = tmp_path / "real-train.png"
    source.replace(real_source)
    source.symlink_to(real_source)

    with pytest.raises(TwoStageDataError, match="securely read"):
        build_two_stage_inputs(split, tmp_path)

    source.unlink()
    real_source.replace(source)
    outside = tmp_path / "outside"
    outside.mkdir()
    (tmp_path / "classifier").symlink_to(outside, target_is_directory=True)

    with pytest.raises(TwoStageDataError, match="secure output"):
        build_two_stage_inputs(split, tmp_path)


def test_rebuild_rejects_a_mutated_content_addressed_crop(tmp_path: Path) -> None:
    split = _split_document(tmp_path)
    first = build_two_stage_inputs(split, tmp_path)
    crop = first.classifier_partitions["train"][0]
    crop.path.write_bytes(b"tampered")

    with pytest.raises(TwoStageDataError, match="immutable derived file"):
        build_two_stage_inputs(split, tmp_path)


def test_atomic_immutable_publication_never_replaces_a_racing_destination(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    split = _split_document(tmp_path)
    source_digest = split["partitions"]["train"][0]["imageSha256"]  # type: ignore[index]
    destination = tmp_path / "detector" / "train" / f"{source_digest}.png"
    original_link = secure_io.os.link
    raced = False

    def racing_link(
        source: str,
        target: str,
        *,
        src_dir_fd: int | None = None,
        dst_dir_fd: int | None = None,
        follow_symlinks: bool = True,
    ) -> None:
        nonlocal raced
        if not raced:
            raced = True
            assert dst_dir_fd is not None
            descriptor = os.open(
                target,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=dst_dir_fd,
            )
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(b"raced")
        original_link(
            source,
            target,
            src_dir_fd=src_dir_fd,
            dst_dir_fd=dst_dir_fd,
            follow_symlinks=follow_symlinks,
        )

    monkeypatch.setattr(secure_io.os, "link", racing_link)

    with pytest.raises(TwoStageDataError, match="immutable derived file"):
        build_two_stage_inputs(split, tmp_path)

    assert raced is True
    assert destination.read_bytes() == b"raced"
