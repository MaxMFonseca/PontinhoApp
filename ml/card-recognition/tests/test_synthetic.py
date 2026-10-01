from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageDraw

import pontinho_cards.synthetic as synthetic
from pontinho_cards.synthetic import (
    SyntheticDataError,
    compose_scene,
    generate_dataset,
    load_training_crops,
    main,
    validate_annotations,
)


def _write_card(path: Path, *, accent: str) -> str:
    image = Image.new("RGB", (48, 72), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle((1, 1, 46, 70), outline="black", width=2)
    draw.ellipse((14, 24, 34, 44), fill=accent)
    image.save(path)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _record(index: int, path: str, digest: str, *, label: str = "AC") -> dict[str, object]:
    return {
        "imageId": f"card-{index}",
        "path": path,
        "width": 48,
        "height": 72,
        "sourceId": "reviewed-source",
        "deckGroupId": f"deck-{index}",
        "splitGroupId": f"group-{index}",
        "imageSha256": digest,
        "sourceDerivativeId": f"derivative-{index}",
        "augmentationParentId": None,
        "isRealPhoto": True,
        "boxes": [{"label": label, "xmin": 0.0, "ymin": 0.0, "xmax": 48.0, "ymax": 72.0}],
    }


def _split_fixture(tmp_path: Path) -> tuple[dict[str, object], Path]:
    image_root = tmp_path / "images"
    image_root.mkdir()
    first = image_root / "first.png"
    second = image_root / "second.png"
    validation = image_root / "validation.png"
    first_hash = _write_card(first, accent="#bb2233")
    second_hash = _write_card(second, accent="#2244bb")
    validation_hash = _write_card(validation, accent="#22aa66")
    document: dict[str, object] = {
        "schemaVersion": 1,
        "seed": 20261014,
        "partitions": {
            "train": [
                _record(1, "first.png", first_hash),
                _record(2, "second.png", second_hash),
            ],
            "validation": [_record(3, "validation.png", validation_hash)],
            "test": [],
        },
    }
    return document, image_root


def _tree_hashes(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _corner_card() -> Image.Image:
    image = Image.new("RGBA", (80, 120), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 13, 13), fill=(255, 0, 0, 255))
    draw.rectangle((66, 0, 79, 13), fill=(0, 255, 0, 255))
    draw.rectangle((66, 106, 79, 119), fill=(0, 0, 255, 255))
    draw.rectangle((0, 106, 13, 119), fill=(255, 255, 0, 255))
    draw.rectangle((0, 0, 79, 119), outline=(0, 0, 0, 255), width=2)
    return image


def test_compositor_is_deterministic_and_preserves_duplicate_instances(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    crops = load_training_crops(split, image_root=image_root)

    first_image, first_metadata = compose_scene(crops, seed=41, card_count=2, size=(320, 320))
    second_image, second_metadata = compose_scene(crops, seed=41, card_count=2, size=(320, 320))

    assert first_image.tobytes() == second_image.tobytes()
    assert first_metadata == second_metadata
    assert [card["label"] for card in first_metadata["cards"]] == ["AC", "AC"]
    assert len({card["instanceId"] for card in first_metadata["cards"]}) == 2


def test_boxes_enclose_cards_and_layout_has_no_overlap(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    crops = load_training_crops(split, image_root=image_root)

    _, metadata = compose_scene(crops, seed=8, card_count=8, size=(640, 640))

    validate_annotations(metadata, width=640, height=640, maximum_overlap_fraction=0.0)
    for card in metadata["cards"]:
        box = card["finalBox"]
        assert 0 <= box["xmin"] < box["xmax"] <= 640
        assert 0 <= box["ymin"] < box["ymax"] <= 640


def test_forward_perspective_preserves_orientation_corners_edges_and_alpha_bounds() -> None:
    rendered = synthetic.render_card(
        _corner_card(),
        target_size=(80, 120),
        rotation_degrees=0.0,
        perspective_offsets=(
            (-0.04, 0.03),
            (0.03, -0.04),
            (0.04, 0.02),
            (-0.03, -0.02),
        ),
        brightness=1.0,
        white_balance_red=1.0,
        white_balance_blue=1.0,
        blur_radius=0.0,
    )

    pixels = np.asarray(rendered)
    alpha_locations = np.argwhere(pixels[:, :, 3] > 8)
    assert tuple(alpha_locations.min(axis=0)) == (0, 0)
    assert tuple(alpha_locations.max(axis=0)) == (rendered.height - 1, rendered.width - 1)

    masks = (
        (pixels[:, :, 0] > 210) & (pixels[:, :, 1] < 60) & (pixels[:, :, 2] < 60),
        (pixels[:, :, 0] < 60) & (pixels[:, :, 1] > 210) & (pixels[:, :, 2] < 60),
        (pixels[:, :, 0] < 60) & (pixels[:, :, 1] < 60) & (pixels[:, :, 2] > 210),
        (pixels[:, :, 0] > 210) & (pixels[:, :, 1] > 210) & (pixels[:, :, 2] < 60),
    )
    centroids = []
    for mask in masks:
        locations = np.argwhere(mask)
        assert len(locations) >= 25
        centroids.append(locations.mean(axis=0))
    black_edge = (
        (pixels[:, :, 0] < 45) & (pixels[:, :, 1] < 45) & (pixels[:, :, 2] < 45)
    )
    edge_locations = np.argwhere(black_edge & (pixels[:, :, 3] > 8))
    assert int(edge_locations[:, 0].max() - edge_locations[:, 0].min()) > rendered.height * 0.85
    assert int(edge_locations[:, 1].max() - edge_locations[:, 1].min()) > rendered.width * 0.85
    top_left, top_right, bottom_right, bottom_left = centroids
    assert top_left[0] < rendered.height / 2 and top_left[1] < rendered.width / 2
    assert top_right[0] < rendered.height / 2 and top_right[1] > rendered.width / 2
    assert bottom_right[0] > rendered.height / 2 and bottom_right[1] > rendered.width / 2
    assert bottom_left[0] > rendered.height / 2 and bottom_left[1] < rendered.width / 2


def test_all_shadows_render_before_cards_so_adjacent_card_pixels_are_not_occluded() -> None:
    background = Image.new("RGB", (140, 80), "white")
    red = Image.new("RGBA", (40, 50), (240, 20, 20, 255))
    blue = Image.new("RGBA", (40, 50), (20, 20, 240, 255))
    placements = [
        synthetic.PlacedCard(red, x=70, y=15),
        synthetic.PlacedCard(blue, x=20, y=15),
    ]

    rendered = synthetic.composite_placed_cards(
        background,
        placements,
        shadow_blur_radius=18.0,
        shadow_offset=(45, 0),
        shadow_opacity=0.95,
    )

    pixels = np.asarray(rendered)
    assert tuple(pixels[40, 90, :3]) == (240, 20, 20)
    assert tuple(pixels[40, 40, :3]) == (20, 20, 240)


def test_only_training_crops_are_loaded_and_every_pixel_source_is_recorded(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)

    crops = load_training_crops(split, image_root=image_root)
    _, metadata = compose_scene(crops, seed=19, card_count=2, size=(320, 320))

    validation_hash = split["partitions"]["validation"][0]["imageSha256"]  # type: ignore[index]
    parent_hashes = {card["parentImageSha256"] for card in metadata["cards"]}
    assert validation_hash not in parent_hashes
    assert all(card["partition"] == "train" for card in metadata["cards"])
    assert metadata["background"]["kind"] == "procedural"
    assert metadata["background"]["license"] == "CC0-1.0"
    assert metadata["pixelSources"] == [
        metadata["background"],
        *[card["pixelSource"] for card in metadata["cards"]],
    ]


def test_missing_card_provenance_fails_closed(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    split["partitions"]["train"][0]["sourceId"] = None  # type: ignore[index]

    with pytest.raises(SyntheticDataError, match="sourceId"):
        load_training_crops(split, image_root=image_root)


@pytest.mark.parametrize(
    "identity_key",
    ["imageSha256", "sourceDerivativeId", "deckGroupId", "splitGroupId"],
)
def test_training_crop_identity_cannot_collide_with_validation_or_test(
    tmp_path: Path, identity_key: str
) -> None:
    split, image_root = _split_fixture(tmp_path)
    train = split["partitions"]["train"][0]  # type: ignore[index]
    validation = split["partitions"]["validation"][0]  # type: ignore[index]
    validation[identity_key] = train[identity_key]

    with pytest.raises(SyntheticDataError, match=identity_key):
        load_training_crops(split, image_root=image_root)


def test_training_crop_cannot_share_an_augmentation_ancestor_with_validation(
    tmp_path: Path,
) -> None:
    split, image_root = _split_fixture(tmp_path)
    validation = split["partitions"]["validation"]  # type: ignore[index]
    middle = dict(validation[0])
    middle["imageId"] = "validation-middle"
    middle["augmentationParentId"] = "card-1"
    validation[0]["augmentationParentId"] = "validation-middle"
    validation.append(middle)

    with pytest.raises(SyntheticDataError, match="augmentation"):
        load_training_crops(split, image_root=image_root)


def test_realistic_transform_values_remain_inside_declared_bounds(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    crops = load_training_crops(split, image_root=image_root)

    _, metadata = compose_scene(crops, seed=77, card_count=4, size=(480, 480))

    for card in metadata["cards"]:
        transform = card["transform"]
        assert 0.75 <= transform["scale"] <= 1.15
        assert -12.0 <= transform["rotationDegrees"] <= 12.0
        assert 0.0 <= transform["perspectiveFraction"] <= 0.04
        assert 0.82 <= transform["brightness"] <= 1.18
        assert 0.92 <= transform["whiteBalanceRed"] <= 1.08
        assert 0.92 <= transform["whiteBalanceBlue"] <= 1.08
        assert 0.0 <= transform["blurRadius"] <= 0.6
        assert 82 <= transform["jpegQuality"] <= 96


def test_compositor_supports_empty_and_maximum_card_boundaries(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    crops = load_training_crops(split, image_root=image_root)

    _, empty = compose_scene([], seed=1, card_count=0, size=(320, 320))
    _, maximum = compose_scene(crops, seed=2, card_count=15, size=(640, 640))

    assert empty["cards"] == []
    assert len(maximum["cards"]) == 15
    validate_annotations(maximum, width=640, height=640, maximum_overlap_fraction=0.0)
    with pytest.raises(SyntheticDataError, match="between 0 and 15"):
        compose_scene(crops, seed=3, card_count=-1)
    with pytest.raises(SyntheticDataError, match="between 0 and 15"):
        compose_scene(crops, seed=3, card_count=16)


def test_annotation_validation_rejects_clipping_and_overlap() -> None:
    metadata = {
        "cards": [
            {"finalBox": {"xmin": -1, "ymin": 2, "xmax": 20, "ymax": 30}},
            {"finalBox": {"xmin": 10, "ymin": 10, "xmax": 30, "ymax": 40}},
        ]
    }
    with pytest.raises(SyntheticDataError, match="clipped"):
        validate_annotations(metadata, width=100, height=100, maximum_overlap_fraction=0.0)

    metadata["cards"][0]["finalBox"]["xmin"] = 1
    with pytest.raises(SyntheticDataError, match="overlap"):
        validate_annotations(metadata, width=100, height=100, maximum_overlap_fraction=0.0)


def test_dataset_generation_is_atomic_and_writes_audit_artifacts(tmp_path: Path) -> None:
    split, image_root = _split_fixture(tmp_path)
    first = tmp_path / "generated-a"
    second = tmp_path / "generated-b"

    first_report = generate_dataset(
        split,
        output=first,
        image_root=image_root,
        seed=123,
        scene_count=6,
        size=(320, 320),
    )
    second_report = generate_dataset(
        split,
        output=second,
        image_root=image_root,
        seed=123,
        scene_count=6,
        size=(320, 320),
    )

    assert first_report == second_report
    assert _tree_hashes(first) == _tree_hashes(second)
    assert (first / "contact-sheet.jpg").is_file()
    report = json.loads((first / "distribution.json").read_text(encoding="utf-8"))
    assert report["sceneCount"] == 6
    assert report["audit"]["minimumScenes"] == 100
    assert report["audit"]["status"] == "INSUFFICIENT_SCENES"
    assert report["trainingBlocked"] is True
    assert sum(report["cardCountDistribution"].values()) == 6
    assert int(report["cardCountDistribution"]["0"]) >= 1
    assert report["backgrounds"] == {"procedural:tabletop-v1": 6}

    sentinel = first / "sentinel.txt"
    sentinel.write_text("unchanged", encoding="utf-8")
    with pytest.raises(SyntheticDataError, match="already exists"):
        generate_dataset(split, output=first, image_root=image_root, seed=1, scene_count=1)
    assert sentinel.read_text(encoding="utf-8") == "unchanged"


def test_empty_split_cli_reports_blocked_audit_without_inventing_sources(tmp_path: Path) -> None:
    split_path = tmp_path / "splits.json"
    split_path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "seed": 20261014,
                "partitions": {"train": [], "validation": [], "test": []},
            }
        ),
        encoding="utf-8",
    )
    output = tmp_path / "generated"

    assert main(["--split", str(split_path), "--output", str(output)]) == 2
    report = json.loads((output / "distribution.json").read_text(encoding="utf-8"))
    assert report["sceneCount"] == 0
    assert report["audit"]["status"] == "BLOCKED_NO_TRAINING_CROPS"
    assert report["audit"]["reviewedScenes"] == 0
    assert report["trainingBlocked"] is True
    assert report["classFrequency"] == {}
    assert not (output / "contact-sheet.jpg").exists()
