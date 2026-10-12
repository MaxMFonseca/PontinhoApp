"""Behavioral contract for raw YOLOv8 card-detector postprocessing."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from pontinho_yolo.labels import CARD_LABELS
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
        (0, 0.5, 0.5, 0.25, 0.3125, 3, 0.80),
        (1, 0.5, 0.5, 0.125, 0.125, 4, 0.50),
    )
    output[0, 4 + 8, 0] = 0.79

    detections = decode_yolo(output, geometry, confidence=0.50, iou=0.50, max_cards=10)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.class_index == 3
    assert detection.label == "10S"
    assert detection.confidence == pytest.approx(0.80)
    assert detection.box.as_tuple() == pytest.approx((80.0, 220.0, 240.0, 420.0))


def test_decode_rescales_normalized_boxes_for_square_source() -> None:
    """Pinned Ultralytics TFLite emits xywh normalized by the 640 input dimensions."""
    output = _output((0, 0.5, 0.5, 0.25, 0.3125, 3, 0.8))

    detection = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.1,
        iou=0.5,
        max_cards=10,
    )[0]

    assert detection.box.as_tuple() == pytest.approx((240.0, 220.0, 400.0, 420.0))


def test_letterbox_geometry_matches_pinned_ultralytics_rounding_and_odd_padding() -> None:
    """Continuous symmetric padding would disagree with the actual resized raster."""
    landscape = LetterboxGeometry.for_source(3, 2)
    assert landscape.scale == 640 / 3
    assert (landscape.resized_width, landscape.resized_height) == (640, 427)
    assert (landscape.pad_x, landscape.pad_right, landscape.pad_y, landscape.pad_bottom) == (
        0.0,
        0,
        106.0,
        107,
    )

    portrait = LetterboxGeometry.for_source(2, 3)
    assert portrait.scale == 640 / 3
    assert (portrait.resized_width, portrait.resized_height) == (427, 640)
    assert (portrait.pad_x, portrait.pad_right, portrait.pad_y, portrait.pad_bottom) == (
        106.0,
        107,
        0.0,
        0,
    )

    with pytest.raises(DecodeError, match="padding split"):
        landscape.__class__(**{**landscape.__dict__, "pad_y": 0.0, "pad_bottom": 213}).validate()
    with pytest.raises(DecodeError, match="padding split"):
        portrait.__class__(**{**portrait.__dict__, "pad_x": 0.0, "pad_right": 213}).validate()


def test_decode_suppresses_same_class_and_competing_class_physical_duplicates() -> None:
    """Skipping either NMS phase would report one physical card more than once."""
    output = _output(
        (0, 100 / 640, 100 / 640, 80 / 640, 120 / 640, 2, 0.95),
        (1, 101 / 640, 101 / 640, 80 / 640, 120 / 640, 2, 0.90),
        (2, 100 / 640, 100 / 640, 80 / 640, 120 / 640, 8, 0.92),
        (3, 210 / 640, 100 / 640, 80 / 640, 120 / 640, 8, 0.88),
    )

    detections = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.10,
        iou=0.50,
        max_cards=10,
    )

    assert [(item.class_index, item.anchor_index) for item in detections] == [(2, 0), (8, 3)]


def test_physical_duplicate_suppression_keeps_adjacent_different_cards() -> None:
    """The physical pass is stricter than class NMS so overlapping neighbors survive."""
    output = _output(
        (0, 100 / 640, 100 / 640, 100 / 640, 120 / 640, 2, 0.95),
        (1, 120 / 640, 100 / 640, 100 / 640, 120 / 640, 8, 0.92),
    )

    detections = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.1,
        iou=0.5,
        max_cards=10,
    )

    assert [(item.class_index, item.anchor_index) for item in detections] == [(2, 0), (8, 1)]


def test_decode_uses_deterministic_priority_then_returns_spatial_order() -> None:
    """Tensor traversal order must not decide a confidence/box tie or final card order."""
    output = _output(
        (7, 500 / 640, 100 / 640, 40 / 640, 60 / 640, 4, 0.80),
        (2, 100 / 640, 100 / 640, 40 / 640, 60 / 640, 5, 0.80),
        (3, 100 / 640, 100 / 640, 40 / 640, 60 / 640, 4, 0.80),
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
        (0, 100 / 640, 100 / 640, 60 / 640, 60 / 640, 0, 0.9),
        (1, 100 / 640, 200 / 640, 60 / 640, 60 / 640, 1, 0.8),
    )

    detections = decode_yolo(output, geometry, confidence=0.1, iou=0.5, max_cards=10)

    assert [(item.class_index, item.anchor_index) for item in detections] == [(1, 1)]
    assert detections[0].box.as_tuple() == pytest.approx((70.0, 10.0, 130.0, 70.0))


def test_decode_clamps_tolerated_boundary_roundoff_to_source_bounds() -> None:
    """Tolerance may accept float noise, but it must never leak out-of-source coordinates."""
    output = _output((0, 0.5, 0.5, 1.0 + 1e-7, 1.0 + 1e-7, 0, 0.9))

    detection = decode_yolo(
        output,
        LetterboxGeometry.for_source(640, 640),
        confidence=0.1,
        iou=0.5,
        max_cards=10,
    )[0]

    assert detection.box.as_tuple() == (0.0, 0.0, 640.0, 640.0)


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
    output = _output(
        *(
            (index, (30 + index * 55) / 640, 100 / 640, 30 / 640, 40 / 640, index, 0.9)
            for index in range(11)
        )
    )

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


def test_android_golden_fixture_matches_python_decoder_contract() -> None:
    """Android parity data must remain an independent, literal oracle for Python."""
    fixture_path = Path(__file__).parent / "fixtures" / "android-yolo-golden.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    android_fixture_path = (
        Path(__file__).parents[4]
        / "app"
        / "src"
        / "test"
        / "resources"
        / "recognition"
        / "android-yolo-golden.json"
    )
    assert fixture_path.read_bytes() == android_fixture_path.read_bytes()
    assert fixture["generator"].endswith("generate_android_yolo_golden.py")
    assert fixture["rawTensorEncoding"] == "zero-default-sparse-anchors"
    assert fixture["tensorDefault"] == 0.0
    assert tuple(fixture["tensorShape"]) == (1, 56, 8400)
    assert tuple(fixture["labels"]) == CARD_LABELS
    assert fixture["thresholdRepresentation"] == {
        "metadata": "IEEE-754 binary64 parsed directly from JSON",
        "confidenceComparison": "threshold normalized to IEEE-754 binary32; raw score > threshold",
        "iouAndGeometry": "IEEE-754 binary64",
    }

    settings = fixture["settings"]
    for case in fixture["cases"]:
        case_settings = settings | case.get("settings", {})
        output = np.zeros(tuple(fixture["tensorShape"]), dtype=np.float32)
        for raw in case["anchors"]:
            anchor_index = raw["anchor"]
            output[0, :4, anchor_index] = raw["xywh"]
            for class_index, score in raw["scores"].items():
                output[0, 4 + int(class_index), anchor_index] = score
        raw_geometry = case["geometry"]
        geometry = LetterboxGeometry(
            source_width=raw_geometry["sourceWidth"],
            source_height=raw_geometry["sourceHeight"],
            input_width=raw_geometry["inputWidth"],
            input_height=raw_geometry["inputHeight"],
            resized_width=raw_geometry["resizedWidth"],
            resized_height=raw_geometry["resizedHeight"],
            scale=raw_geometry["scale"],
            pad_x=raw_geometry["padX"],
            pad_y=raw_geometry["padY"],
            pad_right=raw_geometry["padRight"],
            pad_bottom=raw_geometry["padBottom"],
        )
        if case.get("expectedError") == "DECODE_ERROR":
            with pytest.raises(DecodeError):
                decode_yolo(
                    output,
                    geometry,
                    case_settings["confidence"],
                    case_settings["iou"],
                    case_settings["maxCards"],
                )
            continue
        if case.get("expectedError") == "TOO_MANY_DETECTIONS":
            with pytest.raises(TooManyDetections) as caught:
                decode_yolo(
                    output,
                    geometry,
                    case_settings["confidence"],
                    case_settings["iou"],
                    case_settings["maxCards"],
                )
            actual = caught.value.detections
            assert caught.value.count == case["expectedCount"]
        else:
            actual = decode_yolo(
                output,
                geometry,
                case_settings["confidence"],
                case_settings["iou"],
                case_settings["maxCards"],
            )
        expected = case["expected"]
        assert len(actual) == len(expected), case["id"]
        for got, want in zip(actual, expected, strict=True):
            assert got.anchor_index == want["anchorIndex"], case["id"]
            assert got.class_index == want["classIndex"], case["id"]
            assert got.label == want["label"], case["id"]
            assert got.confidence == pytest.approx(want["confidence"], abs=1e-6), case["id"]
            normalized = (
                got.box.left / geometry.source_width,
                got.box.top / geometry.source_height,
                got.box.right / geometry.source_width,
                got.box.bottom / geometry.source_height,
            )
            assert normalized == pytest.approx(
                want["normalizedBox"], abs=fixture["normalizedBoxTolerance"]
            ), case["id"]
