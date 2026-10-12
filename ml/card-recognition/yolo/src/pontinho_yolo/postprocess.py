"""Deterministic decoding of the raw YOLOv8 card-detector tensor."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from pontinho_yolo.labels import CARD_LABELS

INPUT_SIZE = 640
OUTPUT_SHAPE = (1, 56, 8400)


class DecodeError(ValueError):
    """Raised when model output or letterbox geometry violates the contract."""


class TooManyDetections(DecodeError):
    """Raised without truncation when a capture contains more than the configured maximum."""

    def __init__(self, detections: tuple[Detection, ...], maximum: int) -> None:
        self.detections = detections
        self.count = len(detections)
        self.maximum = maximum
        super().__init__(f"detected {self.count} cards; maximum is {maximum}")


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
    scale: float
    pad_x: float
    pad_y: float

    @classmethod
    def for_source(
        cls, source_width: int, source_height: int, input_size: int = INPUT_SIZE
    ) -> LetterboxGeometry:
        if source_width <= 0 or source_height <= 0 or input_size <= 0:
            raise DecodeError("letterbox dimensions must be positive")
        scale = min(input_size / source_width, input_size / source_height)
        scaled_width = source_width * scale
        scaled_height = source_height * scale
        return cls(
            source_width=source_width,
            source_height=source_height,
            input_width=input_size,
            input_height=input_size,
            scale=scale,
            pad_x=(input_size - scaled_width) / 2.0,
            pad_y=(input_size - scaled_height) / 2.0,
        )

    def validate(self) -> None:
        values = (self.scale, self.pad_x, self.pad_y)
        if (
            self.source_width <= 0
            or self.source_height <= 0
            or self.input_width != INPUT_SIZE
            or self.input_height != INPUT_SIZE
            or not all(math.isfinite(value) for value in values)
            or self.scale <= 0
            or self.pad_x < 0
            or self.pad_y < 0
        ):
            raise DecodeError("invalid letterbox geometry")
        scaled_width = self.source_width * self.scale
        scaled_height = self.source_height * self.scale
        if not math.isclose(scaled_width + 2 * self.pad_x, self.input_width, abs_tol=1e-5):
            raise DecodeError("letterbox horizontal geometry is inconsistent")
        if not math.isclose(scaled_height + 2 * self.pad_y, self.input_height, abs_tol=1e-5):
            raise DecodeError("letterbox vertical geometry is inconsistent")


def decode_yolo(
    output: np.ndarray,
    geometry: LetterboxGeometry,
    confidence: float,
    iou: float,
    max_cards: int,
) -> tuple[Detection, ...]:
    """Decode raw BCN output using class-aware NMS then physical duplicate suppression."""
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
        model_box = Box(
            left=cx - width / 2.0,
            top=cy - height / 2.0,
            right=cx + width / 2.0,
            bottom=cy + height / 2.0,
        )
        source_box = _invert_complete_box(model_box, geometry)
        if source_box is None:
            continue
        candidates.append(
            Detection(
                class_index=class_index,
                label=CARD_LABELS[class_index],
                confidence=score,
                box=source_box,
                anchor_index=anchor_index,
            )
        )

    priority = sorted(candidates, key=_priority_key)
    class_aware = _nms(priority, iou, class_aware=True)
    physical = _nms(class_aware, iou, class_aware=False)
    ordered = tuple(sorted(physical, key=_spatial_key))
    if len(ordered) > max_cards:
        raise TooManyDetections(ordered, max_cards)
    return ordered


def _invert_complete_box(box: Box, geometry: LetterboxGeometry) -> Box | None:
    content_left = geometry.pad_x
    content_top = geometry.pad_y
    content_right = geometry.input_width - geometry.pad_x
    content_bottom = geometry.input_height - geometry.pad_y
    epsilon = 1e-5
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
    return source


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


def _intersection_over_union(first: Box, second: Box) -> float:
    left = max(first.left, second.left)
    top = max(first.top, second.top)
    right = min(first.right, second.right)
    bottom = min(first.bottom, second.bottom)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = first.area + second.area - intersection
    return intersection / union if union > 0.0 else 0.0
