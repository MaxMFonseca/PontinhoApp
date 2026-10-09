"""Versioned native-grid NMS with exact integer/rational comparisons."""

import json
import math

import numpy as np

NMS_POLICY = {
    "version": 1,
    "grid": [320, 320],
    "thresholdRepresentation": "binary64-to-binary32-exact-rational",
    "scoreRepresentation": "binary32",
    "comparison": "strict-greater",
    "priority": "score-desc-left-top-right-bottom-index",
    "spatialOrder": "top-left-bottom-right-index",
    "invalidBox": "reject",
}


def require_nms_policy(policy):
    if json.dumps(policy, sort_keys=True, allow_nan=False) != json.dumps(
        NMS_POLICY, sort_keys=True
    ):
        raise ValueError("NMS policy differs from runtime")


def native_nms(boxes, threshold):
    """Return original indices in spatial order. Coordinates never leave 320 grid.

    Binary32 threshold is an exact ratio; Python integers and Android BigInteger
    avoid overflow even for 2^-149 (smallest positive binary32). Area/union are
    bounded by 102400/204800. Ties use native score, four integer bounds, index.
    """
    threshold = float(np.float32(float(threshold)))
    if not math.isfinite(threshold) or not 0 < threshold <= 1:
        raise ValueError("invalid NMS threshold")
    numerator, denominator = threshold.as_integer_ratio()
    normalized = []
    for score, bounds in boxes:
        score = float(np.float32(score))
        if (
            not math.isfinite(score)
            or not 0 <= score <= 1
            or len(bounds) != 4
            or any(type(v) is not int or not 0 <= v <= 320 for v in bounds)
            or not (bounds[0] < bounds[2] and bounds[1] < bounds[3])
        ):
            raise ValueError("invalid native NMS box")
        normalized.append((score, bounds))

    def suppress(a, b):
        intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(
            0, min(a[3], b[3]) - max(a[1], b[1])
        )
        union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
        return intersection * denominator > union * numerator

    kept = []
    for i in sorted(range(len(boxes)), key=lambda i: (-normalized[i][0], *normalized[i][1], i)):
        if all(not suppress(normalized[i][1], normalized[j][1]) for j in kept):
            kept.append(i)
    return sorted(
        kept,
        key=lambda i: (
            normalized[i][1][1],
            normalized[i][1][0],
            normalized[i][1][3],
            normalized[i][1][2],
            i,
        ),
    )
