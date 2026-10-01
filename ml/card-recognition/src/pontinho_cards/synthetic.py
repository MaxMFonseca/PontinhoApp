"""Create deterministic, non-overlapping tabletop training scenes."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import random
import shutil
import tempfile
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from pontinho_cards.labels import parse_card_label


class SyntheticDataError(ValueError):
    """Raised when synthetic data cannot be produced without violating its contract."""


@dataclass(frozen=True)
class CardCrop:
    """One reviewed training crop and the provenance required to reproduce it."""

    label: str
    image: Image.Image
    image_id: str
    parent_image_sha256: str
    source_id: str
    source_path: str
    crop_box: tuple[int, int, int, int]
    box_index: int
    partition: str = "train"


@dataclass(frozen=True)
class PlacedCard:
    """A transformed card layer placed at one scene coordinate."""

    image: Image.Image
    x: int
    y: int


_LEAKAGE_KEYS = ("imageSha256", "sourceDerivativeId", "deckGroupId", "splitGroupId")


def _load_document(value: Mapping[str, Any] | str | Path) -> Mapping[str, Any]:
    if isinstance(value, Mapping):
        return value
    path = Path(value)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SyntheticDataError(f"cannot read split document {path}: {error}") from error
    if not isinstance(document, Mapping):
        raise SyntheticDataError("split document must be an object")
    return document


def _safe_image_path(root: Path, relative: Any) -> Path:
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise SyntheticDataError("card path must be non-empty and use forward slashes")
    candidate = (root / relative).resolve()
    resolved_root = root.resolve()
    if candidate != resolved_root and resolved_root not in candidate.parents:
        raise SyntheticDataError("card path must remain beneath image_root")
    return candidate


def _required_text(record: Mapping[str, Any], field: str) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value.strip():
        raise SyntheticDataError(f"training record {field} must be non-empty text")
    return value


def _validate_partition_isolation(partitions: Mapping[str, Any]) -> None:
    records: list[tuple[str, Mapping[str, Any]]] = []
    for partition in ("train", "validation", "test"):
        for record in partitions[partition]:
            if not isinstance(record, Mapping):
                raise SyntheticDataError(f"{partition} record must be an object")
            records.append((partition, record))

    for key in _LEAKAGE_KEYS:
        owners: dict[str, str] = {}
        for partition, record in records:
            value = _required_text(record, key)
            previous = owners.setdefault(value, partition)
            if previous != partition:
                raise SyntheticDataError(f"{key} crosses dataset partitions")

    image_owners: dict[str, str] = {}
    parent: dict[str, str] = {}

    def find(item: str) -> str:
        parent.setdefault(item, item)
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    def union(left: str, right: str) -> None:
        left_root = find(left)
        right_root = find(right)
        if left_root != right_root:
            parent[right_root] = left_root

    for partition, record in records:
        image_id = _required_text(record, "imageId")
        previous = image_owners.setdefault(image_id, partition)
        if previous != partition:
            raise SyntheticDataError("imageId crosses dataset partitions")
        find(image_id)
        augmentation_parent = record.get("augmentationParentId")
        if augmentation_parent is not None:
            if not isinstance(augmentation_parent, str) or not augmentation_parent.strip():
                raise SyntheticDataError("augmentationParentId must be null or non-empty text")
            union(image_id, augmentation_parent)

    component_partitions: dict[str, set[str]] = {}
    for partition, record in records:
        root = find(str(record["imageId"]))
        component_partitions.setdefault(root, set()).add(partition)
    if any(len(names) > 1 for names in component_partitions.values()):
        raise SyntheticDataError("augmentation parent/ancestor relation crosses dataset partitions")


def _crop_coordinate(value: Any, *, lower: int, upper: int, edge: str) -> int:
    if type(value) not in {int, float} or not math.isfinite(value):
        raise SyntheticDataError(f"crop {edge} must be finite")
    rounded = math.floor(value) if edge in {"xmin", "ymin"} else math.ceil(value)
    if rounded < lower or rounded > upper:
        raise SyntheticDataError(f"crop {edge} lies outside its source image")
    return rounded


def load_training_crops(
    split: Mapping[str, Any] | str | Path, *, image_root: str | Path
) -> list[CardCrop]:
    """Load only the train partition and verify every source byte before cropping."""

    document = _load_document(split)
    partitions = document.get("partitions")
    if not isinstance(partitions, Mapping):
        raise SyntheticDataError("split document must contain partitions")
    for name in ("train", "validation", "test"):
        if not isinstance(partitions.get(name), list):
            raise SyntheticDataError(f"partition {name!r} must be a list")
    _validate_partition_isolation(partitions)
    root = Path(image_root)
    crops: list[CardCrop] = []
    for record in partitions["train"]:
        if not isinstance(record, Mapping):
            raise SyntheticDataError("training record must be an object")
        digest = record.get("imageSha256")
        if not isinstance(digest, str) or len(digest) != 64:
            raise SyntheticDataError("training record requires an image SHA-256")
        image_id = _required_text(record, "imageId")
        source_id = _required_text(record, "sourceId")
        source_path = _required_text(record, "path")
        image_path = _safe_image_path(root, source_path)
        try:
            payload = image_path.read_bytes()
            image = Image.open(io.BytesIO(payload)).convert("RGB")
        except (OSError, SyntaxError) as error:
            raise SyntheticDataError(f"cannot read training image {image_path}") from error
        if hashlib.sha256(payload).hexdigest() != digest:
            raise SyntheticDataError(f"training image hash mismatch: {image_path}")
        if image.size != (record.get("width"), record.get("height")):
            raise SyntheticDataError(f"training image dimensions mismatch: {image_path}")
        boxes = record.get("boxes")
        if not isinstance(boxes, list):
            raise SyntheticDataError("training record boxes must be a list")
        for box_index, box in enumerate(boxes):
            if not isinstance(box, Mapping):
                raise SyntheticDataError("training box must be an object")
            label = box.get("label")
            try:
                parse_card_label(label)  # type: ignore[arg-type]
            except ValueError as error:
                raise SyntheticDataError(str(error)) from error
            left = _crop_coordinate(box.get("xmin"), lower=0, upper=image.width, edge="xmin")
            top = _crop_coordinate(box.get("ymin"), lower=0, upper=image.height, edge="ymin")
            right = _crop_coordinate(box.get("xmax"), lower=0, upper=image.width, edge="xmax")
            bottom = _crop_coordinate(box.get("ymax"), lower=0, upper=image.height, edge="ymax")
            if right <= left or bottom <= top:
                raise SyntheticDataError("training crop must have positive area")
            crops.append(
                CardCrop(
                    label=label,
                    image=image.crop((left, top, right, bottom)).convert("RGBA"),
                    image_id=image_id,
                    parent_image_sha256=digest,
                    source_id=source_id,
                    source_path=source_path,
                    crop_box=(left, top, right, bottom),
                    box_index=box_index,
                )
            )
    return crops


def _procedural_background(size: tuple[int, int], seed: int) -> Image.Image:
    rng = random.Random(seed)
    width, height = size
    base = (rng.randint(96, 150), rng.randint(66, 105), rng.randint(40, 72))
    image = Image.new("RGB", size, base)
    draw = ImageDraw.Draw(image, "RGBA")
    for y in range(height):
        wave = int(8 * math.sin((y + seed % 31) / 16.0))
        noise = rng.randint(-5, 5)
        color = tuple(max(0, min(255, channel + wave + noise)) for channel in base)
        draw.line((0, y, width, y), fill=(*color, 150))
    for _ in range(max(4, height // 40)):
        y = rng.randrange(height)
        draw.line((0, y, width, y), fill=(45, 28, 15, rng.randint(15, 35)), width=1)
    return image


def _adjust_white_balance(image: Image.Image, red: float, blue: float) -> Image.Image:
    r, g, b, a = image.split()
    r = r.point(lambda value: min(255, round(value * red)))
    b = b.point(lambda value: min(255, round(value * blue)))
    return Image.merge("RGBA", (r, g, b, a))


def _visible_alpha_box(image: Image.Image, *, threshold: int = 8) -> tuple[int, int, int, int]:
    alpha = np.asarray(image.getchannel("A"))
    locations = np.argwhere(alpha > threshold)
    if len(locations) == 0:
        raise SyntheticDataError("a transformed card unexpectedly has no visible pixels")
    ymin, xmin = locations.min(axis=0)
    ymax, xmax = locations.max(axis=0)
    return int(xmin), int(ymin), int(xmax) + 1, int(ymax) + 1


def render_card(
    image: Image.Image,
    *,
    target_size: tuple[int, int],
    rotation_degrees: float,
    perspective_offsets: Sequence[tuple[float, float]],
    brightness: float,
    white_balance_red: float,
    white_balance_blue: float,
    blur_radius: float,
) -> Image.Image:
    """Forward-warp a card onto padding while preserving all four source corners."""

    target_width, target_height = target_size
    if target_width <= 0 or target_height <= 0:
        raise SyntheticDataError("target card dimensions must be positive")
    if len(perspective_offsets) != 4 or any(len(offset) != 2 for offset in perspective_offsets):
        raise SyntheticDataError("perspective_offsets must contain four (x, y) pairs")
    if not -12.0 <= rotation_degrees <= 12.0:
        raise SyntheticDataError("rotation must stay between -12 and 12 degrees")
    if not 0.82 <= brightness <= 1.18:
        raise SyntheticDataError("brightness is outside the realistic bound")
    if not 0.92 <= white_balance_red <= 1.08 or not 0.92 <= white_balance_blue <= 1.08:
        raise SyntheticDataError("white balance is outside the realistic bound")
    if not 0.0 <= blur_radius <= 0.6:
        raise SyntheticDataError("blur radius is outside the realistic bound")
    if any(abs(component) > 0.04 for offset in perspective_offsets for component in offset):
        raise SyntheticDataError("perspective offset is outside the realistic bound")

    resized = image.convert("RGBA").resize(
        (target_width, target_height), Image.Resampling.LANCZOS
    )
    resized = ImageEnhance.Brightness(resized).enhance(brightness)
    resized = _adjust_white_balance(resized, white_balance_red, white_balance_blue)

    padding = max(4, math.ceil(max(target_width, target_height) * 0.25))
    output_width = target_width + 2 * padding
    output_height = target_height + 2 * padding
    source = np.float32(
        [
            [0, 0],
            [target_width - 1, 0],
            [target_width - 1, target_height - 1],
            [0, target_height - 1],
        ]
    )
    unit = float(min(target_width, target_height))
    base_destination = (
        (padding, padding),
        (padding + target_width - 1, padding),
        (padding + target_width - 1, padding + target_height - 1),
        (padding, padding + target_height - 1),
    )
    destination = np.float32(
        [
            [
                base_x + x_fraction * unit,
                base_y + y_fraction * unit,
            ]
            for (base_x, base_y), (x_fraction, y_fraction) in zip(
                base_destination, perspective_offsets
            )
        ]
    )
    matrix = cv2.getPerspectiveTransform(source, destination)
    warped_array = cv2.warpPerspective(
        np.asarray(resized),
        matrix,
        (output_width, output_height),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(0, 0, 0, 0),
    )
    warped = Image.fromarray(warped_array, mode="RGBA")
    transformed = warped.rotate(
        rotation_degrees,
        resample=Image.Resampling.BICUBIC,
        expand=True,
        fillcolor=(0, 0, 0, 0),
    )
    if blur_radius > 0.01:
        transformed = transformed.filter(ImageFilter.GaussianBlur(blur_radius))
    return transformed.crop(_visible_alpha_box(transformed))


def _transform_crop(
    crop: CardCrop,
    *,
    rng: random.Random,
    scene_size: tuple[int, int],
    card_count: int,
    jpeg_quality: int,
) -> tuple[Image.Image, dict[str, Any]]:
    width, height = scene_size
    if card_count <= 4:
        base_height = height * 0.27
    elif card_count <= 9:
        base_height = height * 0.19
    else:
        base_height = height * 0.135
    scale = rng.uniform(0.75, 1.15)
    target_height = max(18, round(base_height * scale))
    target_width = max(12, round(target_height * crop.image.width / crop.image.height))
    brightness = rng.uniform(0.82, 1.18)
    red = rng.uniform(0.92, 1.08)
    blue = rng.uniform(0.92, 1.08)
    blur = rng.uniform(0.0, 0.6)
    perspective = rng.uniform(0.0, 0.04)
    rotation = rng.uniform(-12.0, 12.0)
    perspective_offsets = tuple(
        (rng.uniform(-perspective, perspective), rng.uniform(-perspective, perspective))
        for _ in range(4)
    )
    transformed = render_card(
        crop.image,
        target_size=(target_width, target_height),
        rotation_degrees=rotation,
        perspective_offsets=perspective_offsets,
        brightness=brightness,
        white_balance_red=red,
        white_balance_blue=blue,
        blur_radius=blur,
    )
    if transformed.width > width or transformed.height > height:
        raise SyntheticDataError("transformed card cannot fit fully inside the scene")
    return transformed, {
        "scale": round(scale, 6),
        "rotationDegrees": round(rotation, 6),
        "perspectiveFraction": round(perspective, 6),
        "perspectiveOffsets": [
            [round(x_offset, 6), round(y_offset, 6)]
            for x_offset, y_offset in perspective_offsets
        ],
        "brightness": round(brightness, 6),
        "whiteBalanceRed": round(red, 6),
        "whiteBalanceBlue": round(blue, 6),
        "blurRadius": round(blur, 6),
        "jpegQuality": jpeg_quality,
    }


def _intersection_fraction(left: Mapping[str, int], right: Mapping[str, int]) -> float:
    overlap_width = max(0, min(left["xmax"], right["xmax"]) - max(left["xmin"], right["xmin"]))
    overlap_height = max(0, min(left["ymax"], right["ymax"]) - max(left["ymin"], right["ymin"]))
    intersection = overlap_width * overlap_height
    if intersection == 0:
        return 0.0
    left_area = (left["xmax"] - left["xmin"]) * (left["ymax"] - left["ymin"])
    right_area = (right["xmax"] - right["xmin"]) * (right["ymax"] - right["ymin"])
    return intersection / min(left_area, right_area)


def validate_annotations(
    metadata: Mapping[str, Any],
    *,
    width: int,
    height: int,
    maximum_overlap_fraction: float = 0.0,
) -> None:
    """Fail closed when a final annotation is clipped, degenerate, or overlapping."""

    cards = metadata.get("cards")
    if not isinstance(cards, list):
        raise SyntheticDataError("scene metadata cards must be a list")
    boxes: list[Mapping[str, int]] = []
    for card in cards:
        if not isinstance(card, Mapping) or not isinstance(card.get("finalBox"), Mapping):
            raise SyntheticDataError("card annotation requires a finalBox")
        box = card["finalBox"]
        try:
            xmin, ymin, xmax, ymax = (box[key] for key in ("xmin", "ymin", "xmax", "ymax"))
        except KeyError as error:
            raise SyntheticDataError("card annotation has an incomplete finalBox") from error
        if not all(type(value) is int for value in (xmin, ymin, xmax, ymax)):
            raise SyntheticDataError("card annotation coordinates must be integers")
        if xmin < 0 or ymin < 0 or xmax > width or ymax > height:
            raise SyntheticDataError("card annotation is clipped by the scene")
        if xmax <= xmin or ymax <= ymin:
            raise SyntheticDataError("card annotation has zero area")
        boxes.append(box)
    for index, left in enumerate(boxes):
        for right in boxes[index + 1 :]:
            if _intersection_fraction(left, right) > maximum_overlap_fraction:
                raise SyntheticDataError("card annotations overlap above the configured tolerance")


def _place_without_overlap(
    transformed: Image.Image,
    *,
    rng: random.Random,
    scene_size: tuple[int, int],
    existing: list[Mapping[str, int]],
) -> dict[str, int]:
    width, height = scene_size
    for _ in range(4000):
        x = rng.randint(0, width - transformed.width)
        y = rng.randint(0, height - transformed.height)
        candidate = {
            "xmin": x,
            "ymin": y,
            "xmax": x + transformed.width,
            "ymax": y + transformed.height,
        }
        if all(_intersection_fraction(candidate, box) == 0.0 for box in existing):
            return candidate
    raise SyntheticDataError("could not place every card without clipping or overlap")


def composite_placed_cards(
    background: Image.Image,
    placements: Sequence[PlacedCard],
    *,
    shadow_blur_radius: float = 2.0,
    shadow_offset: tuple[int, int] = (2, 3),
    shadow_opacity: float = 0.28,
) -> Image.Image:
    """Render every shadow first, then cards, so no shadow can occlude a card."""

    if shadow_blur_radius < 0 or not 0.0 <= shadow_opacity <= 1.0:
        raise SyntheticDataError("shadow parameters are outside their valid bounds")
    width, height = background.size
    for placement in placements:
        if (
            placement.x < 0
            or placement.y < 0
            or placement.x + placement.image.width > width
            or placement.y + placement.image.height > height
        ):
            raise SyntheticDataError("placed card is clipped by the scene")

    scene = background.convert("RGBA")
    shadow_layer = Image.new("RGBA", background.size, (0, 0, 0, 0))
    for placement in placements:
        shadow_alpha = placement.image.getchannel("A").filter(
            ImageFilter.GaussianBlur(shadow_blur_radius)
        )
        shadow = Image.new("RGBA", placement.image.size, (0, 0, 0, 0))
        shadow.putalpha(shadow_alpha.point(lambda value: round(value * shadow_opacity)))
        shadow_layer.alpha_composite(
            shadow,
            (placement.x + shadow_offset[0], placement.y + shadow_offset[1]),
        )
    scene.alpha_composite(shadow_layer)
    for placement in placements:
        scene.alpha_composite(placement.image, (placement.x, placement.y))
    return scene


def compose_scene(
    crops: Sequence[CardCrop],
    *,
    seed: int,
    card_count: int,
    size: tuple[int, int] = (640, 640),
) -> tuple[Image.Image, dict[str, Any]]:
    """Compose one deterministic scene with independently annotated physical instances."""

    if type(seed) is not int:
        raise SyntheticDataError("seed must be an integer")
    if type(card_count) is not int or not 0 <= card_count <= 15:
        raise SyntheticDataError("card_count must be between 0 and 15")
    if card_count and not crops:
        raise SyntheticDataError("card-containing scenes require reviewed training crops")
    if any(crop.partition != "train" for crop in crops):
        raise SyntheticDataError("validation/test crops cannot enter synthetic training scenes")
    width, height = size
    if width <= 0 or height <= 0:
        raise SyntheticDataError("scene dimensions must be positive")

    rng = random.Random(seed)
    background_seed = rng.randrange(2**31)
    background = _procedural_background(size, background_seed)
    background_provenance: dict[str, Any] = {
        "kind": "procedural",
        "source": "procedural:tabletop-v1",
        "license": "CC0-1.0",
        "generator": "pontinho_cards.synthetic.tabletop-v1",
        "seed": background_seed,
    }
    annotations: list[dict[str, Any]] = []
    boxes: list[Mapping[str, int]] = []
    placements: list[PlacedCard] = []
    jpeg_quality = rng.randint(82, 96)
    for instance_index in range(card_count):
        crop = crops[rng.randrange(len(crops))]
        transformed, transform = _transform_crop(
            crop,
            rng=rng,
            scene_size=size,
            card_count=card_count,
            jpeg_quality=jpeg_quality,
        )
        box = _place_without_overlap(transformed, rng=rng, scene_size=size, existing=boxes)
        x, y = box["xmin"], box["ymin"]
        placements.append(PlacedCard(transformed, x=x, y=y))
        instance_id = f"card-{instance_index:02d}"
        pixel_source = {
            "kind": "cardCrop",
            "instanceId": instance_id,
            "parentImageSha256": crop.parent_image_sha256,
            "sourceId": crop.source_id,
            "sourcePath": crop.source_path,
            "cropBox": list(crop.crop_box),
            "boxIndex": crop.box_index,
        }
        annotations.append(
            {
                "instanceId": instance_id,
                "label": crop.label,
                "partition": crop.partition,
                "parentImageSha256": crop.parent_image_sha256,
                "pixelSource": pixel_source,
                "transform": transform,
                "finalBox": box,
            }
        )
        boxes.append(box)

    metadata: dict[str, Any] = {
        "schemaVersion": 1,
        "seed": seed,
        "width": width,
        "height": height,
        "background": background_provenance,
        "cards": annotations,
        "pixelSources": [background_provenance, *[card["pixelSource"] for card in annotations]],
    }
    validate_annotations(metadata, width=width, height=height)
    scene = composite_placed_cards(background, placements)
    buffer = io.BytesIO()
    scene.convert("RGB").save(
        buffer,
        format="JPEG",
        quality=jpeg_quality,
        optimize=False,
        progressive=False,
    )
    compressed = Image.open(io.BytesIO(buffer.getvalue())).convert("RGB")
    return compressed, metadata


def _write_json(path: Path, document: Mapping[str, Any]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def _contact_sheet(images: Sequence[Image.Image], *, tile_size: int = 160) -> Image.Image:
    columns = min(10, max(1, math.ceil(math.sqrt(len(images)))))
    rows = math.ceil(len(images) / columns)
    sheet = Image.new("RGB", (columns * tile_size, rows * tile_size), "#202020")
    for index, image in enumerate(images):
        thumbnail = image.copy()
        thumbnail.thumbnail((tile_size, tile_size), Image.Resampling.LANCZOS)
        x = (index % columns) * tile_size + (tile_size - thumbnail.width) // 2
        y = (index // columns) * tile_size + (tile_size - thumbnail.height) // 2
        sheet.paste(thumbnail, (x, y))
    return sheet


def _distribution(
    metadata: Sequence[Mapping[str, Any]], *, seed: int, requested_scenes: int
) -> dict[str, Any]:
    card_counts = Counter(str(len(scene["cards"])) for scene in metadata)
    classes = Counter(card["label"] for scene in metadata for card in scene["cards"])
    backgrounds = Counter(scene["background"]["source"] for scene in metadata)
    transform_names = (
        "scale",
        "rotationDegrees",
        "perspectiveFraction",
        "brightness",
        "whiteBalanceRed",
        "whiteBalanceBlue",
        "blurRadius",
        "jpegQuality",
    )
    transform_ranges: dict[str, dict[str, float | int]] = {}
    for name in transform_names:
        values = [card["transform"][name] for scene in metadata for card in scene["cards"]]
        if values:
            transform_ranges[name] = {"min": min(values), "max": max(values)}
    actual = len(metadata)
    if actual == 0:
        audit_status = "BLOCKED_NO_TRAINING_CROPS"
    elif actual < 100:
        audit_status = "INSUFFICIENT_SCENES"
    else:
        audit_status = "PENDING_HUMAN_REVIEW"
    return {
        "schemaVersion": 1,
        "seed": seed,
        "requestedSceneCount": requested_scenes,
        "sceneCount": actual,
        "cardCountDistribution": dict(sorted(card_counts.items(), key=lambda item: int(item[0]))),
        "classFrequency": dict(sorted(classes.items())),
        "transformRanges": transform_ranges,
        "backgrounds": dict(sorted(backgrounds.items())),
        "audit": {
            "minimumScenes": 100,
            "reviewedScenes": 0,
            "status": audit_status,
            "issues": [],
            "note": "Record human review issues without changing the generation seed.",
        },
        "trainingBlocked": True,
    }


def generate_dataset(
    split: Mapping[str, Any] | str | Path,
    *,
    output: str | Path,
    image_root: str | Path,
    seed: int,
    scene_count: int = 100,
    size: tuple[int, int] = (640, 640),
) -> dict[str, Any]:
    """Generate a complete dataset in a staging directory, then publish it atomically."""

    if type(scene_count) is not int or scene_count < 0:
        raise SyntheticDataError("scene_count must be a non-negative integer")
    output_path = Path(output)
    if output_path.exists():
        raise SyntheticDataError(f"output already exists: {output_path}")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output_path.name}.", dir=output_path.parent))
    try:
        crops = load_training_crops(split, image_root=image_root)
        scenes: list[Image.Image] = []
        metadata: list[dict[str, Any]] = []
        if crops:
            count_rng = random.Random(seed)
            for index in range(scene_count):
                scene_seed = count_rng.randrange(2**31)
                # Reserve one deterministic empty negative whenever a batch exists.
                card_count = 0 if index == 0 else count_rng.randint(1, 15)
                image, scene_metadata = compose_scene(
                    crops, seed=scene_seed, card_count=card_count, size=size
                )
                filename = f"scene-{index:05d}.png"
                image.save(staging / filename, format="PNG", optimize=False)
                scene_metadata["image"] = filename
                _write_json(staging / f"scene-{index:05d}.json", scene_metadata)
                scenes.append(image)
                metadata.append(scene_metadata)
        report = _distribution(metadata, seed=seed, requested_scenes=scene_count)
        _write_json(staging / "distribution.json", report)
        if scenes:
            _contact_sheet(scenes).save(
                staging / "contact-sheet.jpg",
                format="JPEG",
                quality=90,
                optimize=False,
                progressive=False,
            )
        os.replace(staging, output_path)
        return report
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--image-root", type=Path)
    parser.add_argument("--scene-count", type=int, default=100)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=640)
    args = parser.parse_args(argv)
    document = _load_document(args.split)
    seed = document.get("seed")
    if type(seed) is not int:
        raise SyntheticDataError("split document seed must be an integer")
    image_root = args.image_root if args.image_root is not None else args.split.parent
    report = generate_dataset(
        document,
        output=args.output,
        image_root=image_root,
        seed=seed,
        scene_count=args.scene_count,
        size=(args.width, args.height),
    )
    return 2 if report["audit"]["status"] == "BLOCKED_NO_TRAINING_CROPS" else 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
