"""Behavioral contract for raw YOLOv8 card-detector postprocessing."""

from __future__ import annotations

import numpy as np
import pytest

from pontinho_yolo.postprocess import (
    DecodeError,
    LetterboxGeometry,
    TooManyDetections,
    decode_yolo,
)


def _output(*rows: tuple[int, float, float, float, float, int, float]) -> np.ndarray:
    output = np.zeros((1, 56, 8400), dtype=np.float32)
    for anchor, cx, cy, width, height, class_index, score in rows:
        output[0, :4, anchor] = (cx, cy, width, height)
        output[0, 4 + class_index, anchor] = score
    return output


def test_decode_selects_maximum_class_uses_strict_confidence_and_inverts_letterbox() -> None:
    """Using >= or failing to undo portrait padding would emit the wrong source box."""
    geometry = LetterboxGeometry.for_source(320, 640)
    output = _output(
        (0, 320, 320, 160, 200, 3, 0.80),
        (1, 320, 320, 80, 80, 4, 0.50),
    )
    output[0, 4 + 8, 0] = 0.79

    detections = decode_yolo(output, geometry, confidence=0.50, iou=0.50, max_cards=10)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.class_index == 3
    assert detection.label == "10S"
    assert detection.confidence == pytest.approx(0.80)
    assert detection.box.as_tuple() == pytest.approx((80.0, 220.0, 240.0, 420.0))


def test_decode_suppresses_same_class_and_competing_class_physical_duplicates() -> None:
    """Skipping either NMS phase would report one physical card more than once."""
    output = _output(
        (0, 100, 100, 80, 120, 2, 0.95),
        (1, 101, 101, 80, 120, 2, 0.90),
        (2, 100, 100, 80, 120, 8, 0.92),
        (3, 210, 100, 80, 120, 8, 0.88),
    )

    detections = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.10,
        iou=0.50,
        max_cards=10,
    )

    assert [(item.class_index, item.anchor_index) for item in detections] == [(2, 0), (8, 3)]


def test_decode_uses_deterministic_priority_then_returns_spatial_order() -> None:
    """Tensor traversal order must not decide a confidence/box tie or final card order."""
    output = _output(
        (7, 500, 100, 40, 60, 4, 0.80),
        (2, 100, 100, 40, 60, 5, 0.80),
        (3, 100, 100, 40, 60, 4, 0.80),
    )

    detections = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.10,
        iou=0.50,
        max_cards=10,
    )

    assert [(item.class_index, item.anchor_index) for item in detections] == [(4, 3), (4, 7)]


def test_decode_rejects_boxes_touching_letterbox_padding() -> None:
    """Clipping a partial box to source pixels would turn padding into a plausible card."""
    geometry = LetterboxGeometry.for_source(640, 320)
    output = _output(
        (0, 100, 100, 60, 60, 0, 0.9),
        (1, 100, 200, 60, 60, 1, 0.8),
    )

    detections = decode_yolo(output, geometry, confidence=0.1, iou=0.5, max_cards=10)

    assert [(item.class_index, item.anchor_index) for item in detections] == [(1, 1)]


@pytest.mark.parametrize(
    "mutation",
    [
        lambda value: np.zeros((56, 8400), dtype=np.float32),
        lambda value: np.zeros((1, 57, 8400), dtype=np.float32),
        lambda value: np.zeros((1, 56, 8399), dtype=np.float32),
        lambda value: value.astype(np.int8),
        lambda value: np.put(value, 0, np.nan) or value,
        lambda value: np.put(value, 0, np.inf) or value,
        lambda value: np.put(value, 2 * 8400, -1.0) or value,
    ],
)
def test_decode_fails_closed_for_malformed_or_nonfinite_raw_output(mutation) -> None:
    """Malformed output must fail inference instead of yielding a partial plausible hand."""
    with pytest.raises(DecodeError):
        decode_yolo(
            mutation(_output()),
            LetterboxGeometry.for_source(640, 640),
            confidence=0.1,
            iou=0.5,
            max_cards=10,
        )


def test_decode_reports_all_eleven_valid_detections_without_truncating() -> None:
    """Silently taking ten would prevent the UI from reporting TooManyCards(11)."""
    output = _output(*((index, 30 + index * 55, 100, 30, 40, index, 0.9) for index in range(11)))

    with pytest.raises(TooManyDetections) as caught:
        decode_yolo(
            output,
            LetterboxGeometry.for_source(640, 640),
            confidence=0.1,
            iou=0.5,
            max_cards=10,
        )

    assert caught.value.count == 11
    assert len(caught.value.detections) == 11


@pytest.mark.parametrize("score", [-0.01, 1.01])
def test_decode_rejects_class_scores_outside_probability_range(score: float) -> None:
    """Corrupt logits presented as post-sigmoid scores must fail the raw-output contract."""
    output = _output()
    output[0, 4, 0] = score

    with pytest.raises(DecodeError, match="class score"):
        decode_yolo(
            output,
            LetterboxGeometry.for_source(640, 640),
            confidence=0.1,
            iou=0.5,
            max_cards=10,
        )
