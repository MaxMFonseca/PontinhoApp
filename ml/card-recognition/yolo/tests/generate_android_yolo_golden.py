"""Generate Android parity fixtures without importing production decoder code.

Expected detections below are hand-derived from the pinned raw BCN tensor,
letterbox, confidence, NMS, and spatial-order contracts.  Keeping this generator
free of ``pontinho_yolo`` imports prevents the implementation under test from
manufacturing its own expected answers.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
PYTHON_FIXTURE = Path(__file__).parent / "fixtures" / "android-yolo-golden.json"
ANDROID_FIXTURE = (
    ROOT / "app" / "src" / "test" / "resources" / "recognition" / "android-yolo-golden.json"
)

LABELS = [
    "10C",
    "10D",
    "10H",
    "10S",
    "2C",
    "2D",
    "2H",
    "2S",
    "3C",
    "3D",
    "3H",
    "3S",
    "4C",
    "4D",
    "4H",
    "4S",
    "5C",
    "5D",
    "5H",
    "5S",
    "6C",
    "6D",
    "6H",
    "6S",
    "7C",
    "7D",
    "7H",
    "7S",
    "8C",
    "8D",
    "8H",
    "8S",
    "9C",
    "9D",
    "9H",
    "9S",
    "AC",
    "AD",
    "AH",
    "AS",
    "JC",
    "JD",
    "JH",
    "JS",
    "KC",
    "KD",
    "KH",
    "KS",
    "QC",
    "QD",
    "QH",
    "QS",
]


def anchor(index: int, xywh: list[float], class_index: int, score: float, **extra: float) -> dict:
    scores = {str(class_index): score, **{str(key): value for key, value in extra.items()}}
    return {"anchor": index, "xywh": xywh, "scores": scores}


def detection(
    anchor_index: int,
    class_index: int,
    confidence: float,
    box: list[float],
) -> dict:
    return {
        "anchorIndex": anchor_index,
        "classIndex": class_index,
        "label": LABELS[class_index],
        "confidence": confidence,
        "normalizedBox": box,
    }


def geometry(width: int, height: int) -> dict:
    scale = min(640 / width, 640 / height)
    resized_width = round(width * scale)
    resized_height = round(height * scale)
    horizontal_padding = 640 - resized_width
    vertical_padding = 640 - resized_height
    pad_x = round(horizontal_padding / 2 - 0.1)
    pad_right = round(horizontal_padding / 2 + 0.1)
    pad_y = round(vertical_padding / 2 - 0.1)
    pad_bottom = round(vertical_padding / 2 + 0.1)
    return {
        "sourceWidth": width,
        "sourceHeight": height,
        "inputWidth": 640,
        "inputHeight": 640,
        "resizedWidth": resized_width,
        "resizedHeight": resized_height,
        "scale": scale,
        "padX": float(pad_x),
        "padY": float(pad_y),
        "padRight": pad_right,
        "padBottom": pad_bottom,
    }


def preprocess_case(case_id: str, width: int, height: int, source_rgb: list[int]) -> dict:
    source = np.asarray(source_rgb, dtype=np.uint8).reshape(height, width, 3)
    transform = geometry(width, height)
    resized = cv2.resize(
        source,
        (transform["resizedWidth"], transform["resizedHeight"]),
        interpolation=cv2.INTER_LINEAR,
    )
    output = cv2.copyMakeBorder(
        resized,
        int(transform["padY"]),
        transform["padBottom"],
        int(transform["padX"]),
        transform["padRight"],
        cv2.BORDER_CONSTANT,
        value=(114, 114, 114),
    )
    normalized = (output.astype(np.float32) / np.float32(255.0)).astype("<f4", copy=False)
    left = int(transform["padX"])
    top = int(transform["padY"])
    points = {
        (0, 0),
        (left, top),
        (min(left + 1, 639), top),
        (left + transform["resizedWidth"] // 2, top + transform["resizedHeight"] // 2),
        (left + transform["resizedWidth"] - 1, top + transform["resizedHeight"] - 1),
        (639, 639),
    }
    return {
        "id": case_id,
        "sourceWidth": width,
        "sourceHeight": height,
        "sourceRgb": source_rgb,
        "geometry": transform,
        "expectedNormalizedFloat32Sha256": hashlib.sha256(
            normalized.tobytes(order="C")
        ).hexdigest(),
        "samples": [
            {"x": x, "y": y, "rgb": output[y, x].tolist()}
            for x, y in sorted(points, key=lambda p: (p[1], p[0]))
        ],
    }


def build_fixture() -> dict:
    eleven_anchors = [
        anchor(i, [(25 + i * 55) / 640, 100 / 640, 20 / 640, 40 / 640], i, 0.9) for i in range(11)
    ]
    eleven_detections = [
        detection(i, i, 0.9, [(15 + i * 55) / 640, 80 / 640, (35 + i * 55) / 640, 120 / 640])
        for i in range(11)
    ]
    landscape_rgb = [
        255,
        0,
        0,
        0,
        255,
        0,
        0,
        0,
        255,
        12,
        34,
        56,
        78,
        90,
        123,
        250,
        128,
        5,
    ]
    portrait_rgb = [
        1,
        101,
        201,
        252,
        152,
        52,
        31,
        63,
        95,
        127,
        159,
        191,
        250,
        10,
        90,
        40,
        220,
        140,
    ]
    nonintegral_rgb = [
        (37 * x + 19 * y + 11 * channel) % 256
        for y in range(5)
        for x in range(7)
        for channel in range(3)
    ]
    return {
        "schemaVersion": 1,
        "generator": "ml/card-recognition/yolo/tests/generate_android_yolo_golden.py",
        "tensorLayout": "BCN",
        "tensorShape": [1, 56, 8400],
        "rawTensorEncoding": "zero-default-sparse-anchors",
        "tensorDefault": 0.0,
        "boxEncoding": "normalized-input-xywh",
        "sourceBoxEncoding": "normalized-source-ltrb",
        "normalizedBoxTolerance": 1e-5,
        "thresholdRepresentation": {
            "metadata": "IEEE-754 binary64 parsed directly from JSON",
            "confidenceComparison": (
                "threshold normalized to IEEE-754 binary32; raw score > threshold"
            ),
            "iouAndGeometry": "IEEE-754 binary64",
        },
        "labels": LABELS,
        "settings": {"confidence": 0.5, "iou": 0.5, "maxCards": 10, "physicalDuplicateIou": 0.95},
        "preprocessContract": {
            "implementation": "ultralytics-8.4.177-LetterBox-cv2-4.11.0-INTER_LINEAR",
            "layout": "NHWC-RGB-float32",
            "paddingRgb": [114, 114, 114],
            "normalizationDivisor": 255.0,
        },
        "preprocessCases": [
            preprocess_case("odd-landscape-3x2", 3, 2, landscape_rgb),
            preprocess_case("odd-portrait-2x3", 2, 3, portrait_rgb),
            preprocess_case("nonintegral-landscape-7x5", 7, 5, nonintegral_rgb),
        ],
        "cases": [
            {
                "id": "square-max-class-strict-confidence",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(7, [0.5, 0.5, 0.25, 0.3125], 3, 0.8, **{"8": 0.79}),
                    anchor(1, [0.5, 0.5, 0.125, 0.125], 4, 0.5),
                ],
                "expected": [detection(7, 3, 0.8, [0.375, 0.34375, 0.625, 0.65625])],
            },
            {
                "id": "confidence-float32-strict-boundary",
                "geometry": geometry(640, 640),
                "settings": {"confidence": 0.3},
                "anchors": [
                    anchor(0, [0.25, 0.25, 0.1, 0.1], 0, float(np.float32(0.3))),
                    anchor(
                        1,
                        [0.75, 0.25, 0.1, 0.1],
                        1,
                        float(np.nextafter(np.float32(0.3), np.float32(np.inf))),
                    ),
                ],
                "expected": [
                    detection(
                        1,
                        1,
                        float(np.nextafter(np.float32(0.3), np.float32(np.inf))),
                        [0.7, 0.2, 0.8, 0.3],
                    )
                ],
            },
            {
                "id": "complete-box-suppresses-weaker-clipped-duplicate",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(0, [0.1, 0.5, 0.2, 0.25], 4, 0.9),
                    anchor(1, [0.09, 0.5, 0.2, 0.25], 4, 0.8),
                ],
                "expected": [detection(0, 4, 0.9, [0.0, 0.375, 0.2, 0.625])],
            },
            {
                "id": "surviving-clipped-duplicate-is-incomplete",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(0, [0.1, 0.5, 0.2, 0.25], 4, 0.8),
                    anchor(1, [0.09, 0.5, 0.2, 0.25], 4, 0.9),
                ],
                "expected": [],
                "expectedError": "INCOMPLETE_DETECTIONS",
                "expectedIncompleteAnchors": [1],
            },
            {
                "id": "portrait-padding-boundary",
                "geometry": geometry(320, 640),
                "anchors": [
                    anchor(0, [0.5, 0.5, 0.5, 1.0], 0, 0.91),
                    anchor(1, [175 / 640, 100 / 640, 60 / 640, 80 / 640], 1, 0.89),
                    anchor(2, [240 / 640, 200 / 640, 80 / 640, 200 / 640], 2, 0.88),
                ],
                "expected": [
                    detection(0, 0, 0.91, [0.0, 0.0, 1.0, 1.0]),
                ],
                "expectedError": "INCOMPLETE_DETECTIONS",
                "expectedIncompleteAnchors": [1],
            },
            {
                "id": "landscape-padding-boundary",
                "geometry": geometry(640, 320),
                "anchors": [
                    anchor(0, [0.5, 0.5, 1.0, 0.5], 36, 0.93),
                    anchor(1, [100 / 640, 150 / 640, 60 / 640, 40 / 640], 37, 0.87),
                    anchor(2, [200 / 640, 240 / 640, 200 / 640, 80 / 640], 38, 0.86),
                ],
                "expected": [
                    detection(0, 36, 0.93, [0.0, 0.0, 1.0, 1.0]),
                ],
                "expectedError": "INCOMPLETE_DETECTIONS",
                "expectedIncompleteAnchors": [1],
            },
            {
                "id": "duplicates-and-adjacent-cards",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(0, [100 / 640, 100 / 640, 80 / 640, 120 / 640], 2, 0.95),
                    anchor(1, [101 / 640, 101 / 640, 80 / 640, 120 / 640], 2, 0.90),
                    anchor(2, [100 / 640, 100 / 640, 80 / 640, 120 / 640], 8, 0.92),
                    anchor(3, [210 / 640, 100 / 640, 80 / 640, 120 / 640], 8, 0.88),
                    anchor(4, [230 / 640, 100 / 640, 80 / 640, 120 / 640], 9, 0.87),
                ],
                "expected": [
                    detection(0, 2, 0.95, [0.09375, 0.0625, 0.21875, 0.25]),
                    detection(3, 8, 0.88, [0.265625, 0.0625, 0.390625, 0.25]),
                    detection(4, 9, 0.87, [0.296875, 0.0625, 0.421875, 0.25]),
                ],
            },
            {
                "id": "class-nms-binary64-threshold-boundary",
                "geometry": geometry(640, 640),
                "settings": {"iou": 0.3},
                "anchors": [
                    anchor(0, [0.5772691965103149, 0.25, 0.35965079069137573, 0.1], 0, 0.9),
                    anchor(1, [0.7709273099899292, 0.25, 0.35965079069137573, 0.1], 0, 0.8),
                ],
                "expected": [
                    detection(
                        0,
                        0,
                        0.9,
                        [0.3974438011646271, 0.2, 0.7570945918560028, 0.3],
                    )
                ],
            },
            {
                "id": "physical-nms-binary64-threshold-boundary",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(0, [0.41983315348625183, 0.5, 0.34758538007736206, 0.1], 0, 0.9),
                    anchor(1, [0.4287455976009369, 0.5, 0.34758538007736206, 0.1], 1, 0.8),
                ],
                "expected": [
                    detection(
                        0,
                        0,
                        0.9,
                        [0.2460404634475708, 0.45, 0.5936258435249329, 0.55],
                    )
                ],
            },
            {
                "id": "deterministic-priority-and-spatial-order",
                "geometry": geometry(640, 640),
                "anchors": [
                    anchor(7, [500 / 640, 100 / 640, 40 / 640, 60 / 640], 6, 0.8),
                    anchor(2, [100 / 640, 100 / 640, 40 / 640, 60 / 640], 5, 0.8),
                    anchor(3, [100 / 640, 100 / 640, 40 / 640, 60 / 640], 4, 0.8),
                ],
                "expected": [
                    detection(3, 4, 0.8, [0.125, 0.109375, 0.1875, 0.203125]),
                    detection(7, 6, 0.8, [0.75, 0.109375, 0.8125, 0.203125]),
                ],
            },
            {
                "id": "zero-candidates",
                "geometry": geometry(640, 640),
                "anchors": [],
                "expected": [],
            },
            {
                "id": "invalid-selected-box",
                "geometry": geometry(640, 640),
                "anchors": [anchor(0, [0.5, 0.5, 0.0, 0.25], 0, 0.9)],
                "expectedError": "DECODE_ERROR",
            },
            {
                "id": "eleven-candidates",
                "geometry": geometry(640, 640),
                "anchors": eleven_anchors,
                "expected": eleven_detections,
                "expectedError": "TOO_MANY_DETECTIONS",
                "expectedCount": 11,
            },
        ],
    }


def main() -> None:
    text = json.dumps(build_fixture(), indent=2, sort_keys=False) + "\n"
    for path in (PYTHON_FIXTURE, ANDROID_FIXTURE):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
