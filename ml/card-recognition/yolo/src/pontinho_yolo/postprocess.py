"""Deterministic decoding of the raw YOLOv8 card-detector tensor."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from pontinho_yolo.labels import CARD_LABELS

INPUT_SIZE = 640
OUTPUT_SHAPE = (1, 56, 8400)
OUTPUT_BOX_COORDINATE_UNITS = "normalized-input-dimensions"
PHYSICAL_DUPLICATE_IOU = 0.95
CONTAINED_DUPLICATE_MIN_COVERAGE = 0.80
CONTAINED_DUPLICATE_MAX_AREA_RATIO = 0.50
BOUNDARY_EPSILON_PIXELS = 1e-4


class DecodeError(ValueError):
    """Raised when model output or letterbox geometry violates the contract."""


class TooManyDetections(DecodeError):
    """Raised without truncation when a capture contains more than the configured maximum."""

    def __init__(self, detections: tuple[Detection, ...], maximum: int) -> None:
        self.detections = detections
        self.count = len(detections)
        self.maximum = maximum
        super().__init__(f"detected {self.count} cards; maximum is {maximum}")


class IncompleteDetections(RuntimeError):
    """Raised when an otherwise viable card crosses the captured source bounds."""

    def __init__(
        self,
        anchor_indexes: tuple[int, ...],
        detections: tuple[Detection, ...],
    ) -> None:
        self.anchor_indexes = anchor_indexes
        self.detections = detections
        super().__init__(f"detected {len(anchor_indexes)} incomplete card candidates")


@dataclass(frozen=True)
class Box:
    """One source-pixel axis-aligned box."""

    left: float
    top: float
    right: float
    bottom: float

    def as_tuple(self) -> tuple[float, float, float, float]:
        return self.left, self.top, self.right, self.bottom

    @property
    def area(self) -> float:
        return max(0.0, self.right - self.left) * max(0.0, self.bottom - self.top)


@dataclass(frozen=True)
class Detection:
    """One decoded card in source-image coordinates."""

    class_index: int
    label: str
    confidence: float
    box: Box
    anchor_index: int


@dataclass(frozen=True)
class LetterboxGeometry:
    """Exact geometry used to place a source image inside the square model input."""

    source_width: int
    source_height: int
    input_width: int
    input_height: int
    resized_width: int
    resized_height: int
    scale: float
    pad_x: float
    pad_y: float
    pad_right: int
    pad_bottom: int

    @classmethod
    def for_source(
        cls, source_width: int, source_height: int, input_size: int = INPUT_SIZE
    ) -> LetterboxGeometry:
        if source_width <= 0 or source_height <= 0 or input_size <= 0:
            raise DecodeError("letterbox dimensions must be positive")
        scale = min(input_size / source_width, input_size / source_height)
        resized_width = round(source_width * scale)
        resized_height = round(source_height * scale)
        horizontal_padding = input_size - resized_width
        vertical_padding = input_size - resized_height
        pad_x = round(horizontal_padding / 2 - 0.1)
        pad_y = round(vertical_padding / 2 - 0.1)
        return cls(
            source_width=source_width,
            source_height=source_height,
            input_width=input_size,
            input_height=input_size,
            resized_width=resized_width,
            resized_height=resized_height,
            scale=scale,
            pad_x=float(pad_x),
            pad_y=float(pad_y),
            pad_right=round(horizontal_padding / 2 + 0.1),
            pad_bottom=round(vertical_padding / 2 + 0.1),
        )

    def validate(self) -> None:
        values = (self.scale, self.pad_x, self.pad_y)
        if (
            self.source_width <= 0
            or self.source_height <= 0
            or self.input_width != INPUT_SIZE
            or self.input_height != INPUT_SIZE
            or self.resized_width <= 0
            or self.resized_height <= 0
            or not all(math.isfinite(value) for value in values)
            or self.scale <= 0
            or self.pad_x < 0
            or self.pad_y < 0
            or self.pad_right < 0
            or self.pad_bottom < 0
        ):
            raise DecodeError("invalid letterbox geometry")
        expected_scale = min(
            self.input_width / self.source_width, self.input_height / self.source_height
        )
        if not math.isclose(self.scale, expected_scale, rel_tol=0.0, abs_tol=1e-12):
            raise DecodeError("letterbox scale is inconsistent")
        if self.resized_width != round(
            self.source_width * self.scale
        ) or self.resized_height != round(self.source_height * self.scale):
            raise DecodeError("letterbox resized raster is inconsistent")
        horizontal_padding = self.input_width - self.resized_width
        vertical_padding = self.input_height - self.resized_height
        if (
            self.pad_x != float(round(horizontal_padding / 2 - 0.1))
            or self.pad_right != round(horizontal_padding / 2 + 0.1)
            or self.pad_y != float(round(vertical_padding / 2 - 0.1))
            or self.pad_bottom != round(vertical_padding / 2 + 0.1)
        ):
            raise DecodeError("letterbox padding split is inconsistent")


def decode_yolo(
    output: np.ndarray,
    geometry: LetterboxGeometry,
    confidence: float,
    iou: float,
    max_cards: int,
) -> tuple[Detection, ...]:
    """Decode normalized-input BCN xywh using class NMS then duplicate suppression."""
    geometry.validate()
    if not isinstance(output, np.ndarray) or output.shape != OUTPUT_SHAPE:
        raise DecodeError(f"raw YOLO output must have shape {OUTPUT_SHAPE}")
    if not np.issubdtype(output.dtype, np.floating):
        raise DecodeError("raw YOLO output must use a floating dtype")
    if not np.isfinite(output).all():
        raise DecodeError("raw YOLO output contains a non-finite value")
    if not math.isfinite(confidence) or not 0.0 < confidence < 1.0:
        raise DecodeError("confidence threshold must be finite and inside (0, 1)")
    if not math.isfinite(iou) or not 0.0 < iou < 1.0:
        raise DecodeError("IoU threshold must be finite and inside (0, 1)")
    if not isinstance(max_cards, int) or isinstance(max_cards, bool) or max_cards <= 0:
        raise DecodeError("max_cards must be a positive integer")
    if np.any(output[0, 2:4, :] < 0):
        raise DecodeError("raw YOLO output contains a negative box extent")

    candidates: list[Detection] = []
    class_scores = output[0, 4:, :]
    if np.any(class_scores < 0.0) or np.any(class_scores > 1.0):
        raise DecodeError("raw YOLO class score is outside [0, 1]")
    class_indices = np.argmax(class_scores, axis=0)
    scores = class_scores[class_indices, np.arange(class_scores.shape[1])]
    for anchor_index in np.flatnonzero(scores > confidence).tolist():
        class_index = int(class_indices[anchor_index])
        score = float(scores[anchor_index])
        cx, cy, width, height = (float(value) for value in output[0, :4, anchor_index].tolist())
        if width <= 0.0 or height <= 0.0:
            raise DecodeError("selected raw YOLO box has a non-positive extent")
        cx *= geometry.input_width
        width *= geometry.input_width
        cy *= geometry.input_height
        height *= geometry.input_height
        model_box = Box(
            left=cx - width / 2.0,
            top=cy - height / 2.0,
            right=cx + width / 2.0,
            bottom=cy + height / 2.0,
        )
        candidates.append(
            Detection(
                class_index=class_index,
                label=CARD_LABELS[class_index],
                confidence=score,
                box=model_box,
                anchor_index=anchor_index,
            )
        )

    priority = sorted(candidates, key=_priority_key)
    class_aware = _nms(priority, iou, class_aware=True)
    physical = _suppress_contained_duplicates(
        _nms(class_aware, PHYSICAL_DUPLICATE_IOU, class_aware=False)
    )
    complete: list[Detection] = []
    incomplete_anchor_indexes: list[int] = []
    for candidate in physical:
        source_box = _invert_complete_box(candidate.box, geometry)
        if source_box is None:
            incomplete_anchor_indexes.append(candidate.anchor_index)
        else:
            complete.append(
                Detection(
                    class_index=candidate.class_index,
                    label=candidate.label,
                    confidence=candidate.confidence,
                    box=source_box,
                    anchor_index=candidate.anchor_index,
                )
            )
    consolidated = _consolidate_corner_indexes(complete)
    ordered = tuple(sorted(consolidated, key=_spatial_key))
    if incomplete_anchor_indexes:
        raise IncompleteDetections(tuple(sorted(incomplete_anchor_indexes)), ordered)
    if len(ordered) > max_cards:
        raise TooManyDetections(ordered, max_cards)
    return ordered


def _invert_complete_box(box: Box, geometry: LetterboxGeometry) -> Box | None:
    content_left = geometry.pad_x
    content_top = geometry.pad_y
    content_right = geometry.pad_x + geometry.source_width * geometry.scale
    content_bottom = geometry.pad_y + geometry.source_height * geometry.scale
    epsilon = BOUNDARY_EPSILON_PIXELS
    if (
        box.left < content_left - epsilon
        or box.top < content_top - epsilon
        or box.right > content_right + epsilon
        or box.bottom > content_bottom + epsilon
        or box.right <= box.left
        or box.bottom <= box.top
    ):
        return None
    source = Box(
        left=(box.left - geometry.pad_x) / geometry.scale,
        top=(box.top - geometry.pad_y) / geometry.scale,
        right=(box.right - geometry.pad_x) / geometry.scale,
        bottom=(box.bottom - geometry.pad_y) / geometry.scale,
    )
    if (
        source.left < -epsilon
        or source.top < -epsilon
        or source.right > geometry.source_width + epsilon
        or source.bottom > geometry.source_height + epsilon
    ):
        return None
    return Box(
        left=min(max(source.left, 0.0), float(geometry.source_width)),
        top=min(max(source.top, 0.0), float(geometry.source_height)),
        right=min(max(source.right, 0.0), float(geometry.source_width)),
        bottom=min(max(source.bottom, 0.0), float(geometry.source_height)),
    )


def _priority_key(detection: Detection) -> tuple[float | int, ...]:
    return (
        -detection.confidence,
        detection.box.left,
        detection.box.top,
        detection.box.right,
        detection.box.bottom,
        detection.class_index,
        detection.anchor_index,
    )


def _spatial_key(detection: Detection) -> tuple[float | int, ...]:
    return (
        detection.box.left,
        detection.box.top,
        detection.box.right,
        detection.box.bottom,
        detection.class_index,
        -detection.confidence,
        detection.anchor_index,
    )


def _nms(candidates: list[Detection], threshold: float, *, class_aware: bool) -> list[Detection]:
    kept: list[Detection] = []
    for candidate in candidates:
        duplicate = any(
            (not class_aware or previous.class_index == candidate.class_index)
            and _intersection_over_union(previous.box, candidate.box) > threshold
            for previous in kept
        )
        if not duplicate:
            kept.append(candidate)
    return kept


def _suppress_contained_duplicates(candidates: list[Detection]) -> list[Detection]:
    """Remove small detections of artwork inside a detected physical card."""
    return [
        candidate
        for candidate in candidates
        if not any(
            other is not candidate and _is_contained_duplicate(candidate.box, other.box)
            for other in candidates
        )
    ]


def _consolidate_corner_indexes(candidates: list[Detection]) -> list[Detection]:
    """Convert the model's two printed-corner observations into physical cards."""
    by_class: dict[int, list[Detection]] = {}
    for candidate in candidates:
        by_class.setdefault(candidate.class_index, []).append(candidate)
    return [
        candidate
        for observations in by_class.values()
        for candidate in observations[: (len(observations) + 1) // 2]
    ]


def _is_contained_duplicate(inner: Box, outer: Box) -> bool:
    if inner.area <= 0.0 or inner.area > outer.area * CONTAINED_DUPLICATE_MAX_AREA_RATIO:
        return False
    intersection = _intersection_area(inner, outer)
    return intersection / inner.area >= CONTAINED_DUPLICATE_MIN_COVERAGE


def _intersection_over_union(first: Box, second: Box) -> float:
    intersection = _intersection_area(first, second)
    union = first.area + second.area - intersection
    return intersection / union if union > 0.0 else 0.0


def _intersection_area(first: Box, second: Box) -> float:
    left = max(first.left, second.left)
    top = max(first.top, second.top)
    right = min(first.right, second.right)
    bottom = min(first.bottom, second.bottom)
    return max(0.0, right - left) * max(0.0, bottom - top)
