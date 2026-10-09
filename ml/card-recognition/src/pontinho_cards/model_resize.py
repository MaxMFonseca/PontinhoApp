"""Exact shared pre-model RGB resize; independent of Pillow/Skia resamplers."""

import json

import numpy as np

RESIZE_POLICY = {
    "version": 1,
    "algorithm": "point-bilinear",
    "coordinateTransform": "half-pixel",
    "edgeMode": "clamp",
    "rounding": "half-up-once",
    "channels": "RGB8",
    "alpha": "opaque-only",
    "antialias": False,
    "detectorSize": [320, 320],
    "classifierSize": [224, 224],
}


def require_resize_policy(policy):
    # Serialization also rejects bool-for-int, floats-for-version and extra keys.
    if json.dumps(policy, sort_keys=True, allow_nan=False) != json.dumps(
        RESIZE_POLICY, sort_keys=True
    ):
        raise ValueError("resize policy differs from runtime")


def resize_rgb(image, size):
    """Half-pixel centers, rational weights, clamp, one final half-up rounding.

    Coordinates on an axis are clamp(((2*d+1)*S-D)/(2*D), 0, S-1).
    Four uint8 RGB samples are interpolated in their encoded channel domain.
    No intermediate rounding, antialias, color transform or alpha composition.
    Transparent pixels anywhere fail closed (Android storage is premultiplied).
    No-op still returns independently owned RGB bytes.
    """
    width, height = size
    if any(type(v) is not int or not 1 <= v <= 320 for v in size):
        raise ValueError("invalid model resize target")
    if not 1 <= image.width <= 8192 or not 1 <= image.height <= 8192:
        raise ValueError("invalid model resize source")
    pixels = np.asarray(image if image.mode == "RGB" else image.convert("RGBA"))
    if pixels.shape[2] == 4 and not np.all(pixels[:, :, 3] == 255):
        raise ValueError("model input must be opaque")
    dx, dy = 2 * width, 2 * height
    nx = np.clip(
        (2 * np.arange(width, dtype=np.int64) + 1) * image.width - width, 0, (image.width - 1) * dx
    )
    x0, wx = nx // dx, (nx % dx)[:, None]
    x1 = np.minimum(x0 + 1, image.width - 1)
    output = np.empty((height, width, 3), dtype=np.uint8)
    denominator = dx * dy
    for y in range(height):
        ny = min(max((2 * y + 1) * image.height - height, 0), (image.height - 1) * dy)
        y0, wy = divmod(ny, dy)
        y1 = min(y0 + 1, image.height - 1)
        total = (
            pixels[y0, x0, :3].astype(np.int64) * (dx - wx) * (dy - wy)
            + pixels[y0, x1, :3].astype(np.int64) * wx * (dy - wy)
            + pixels[y1, x0, :3].astype(np.int64) * (dx - wx) * wy
            + pixels[y1, x1, :3].astype(np.int64) * wx * wy
        )
        output[y] = ((total + denominator // 2) // denominator).astype(np.uint8)
    return output
