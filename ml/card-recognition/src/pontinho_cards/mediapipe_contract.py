"""Contracts executable by MediaPipe CPU image tasks with Python-equivalent inputs.

ImagePreprocessingGraph feeds UINT8 raw pixels and only normalizes FLOAT32.
BuildInputImageTensorSpecs rejects INT8. This is a release-runtime eligibility
gate, not a restriction on experimental training/export or an accuracy claim.
"""

import numpy as np

NATIVE_BOX_POLICY = "mediapipe-integer-interior-1px-v1"
DETECTOR_SIZE = 320


def native_detector_box(box):
    """Mirror DetectionTransformationCalculator float32 -> bounded integer pixels.

    Native clamps origins/extents separately, not the final right/bottom. This
    loses a negative origin; an apparently valid rectangle is not proof of a
    complete card. Return the same normalized grid consumed by Android.
    """
    x0, y0, x1, y1 = np.asarray(box, dtype=np.float32)

    def pixel(value):
        return int(np.clip(np.trunc(value * np.float32(DETECTOR_SIZE)), 0, DETECTOR_SIZE))

    x, y = pixel(x0), pixel(y0)
    return tuple(
        float(np.float32(v) / np.float32(DETECTOR_SIZE))
        for v in (x, y, x + pixel(x1 - x0), y + pixel(y1 - y0))
    )


def complete_native_box(box):
    """Guard one analysis pixel: two truncated terms can hide a raw edge >=320.

    Bounds at pixel 1/319 abstain; pixels 2..318 are interior. No float epsilon
    and no dependence on original capture dimensions. Matches Android exactly.
    """
    x0, y0, x1, y1 = box
    lower = float(np.float32(1) / np.float32(DETECTOR_SIZE))
    upper = float(np.float32(DETECTOR_SIZE - 1) / np.float32(DETECTOR_SIZE))
    return lower < x0 < x1 < upper and lower < y0 < y1 < upper


def require_mediapipe_input(contract, kind):
    try:
        tensor = contract["input"]
        dtype = tensor["dtype"]
        mean = np.asarray(contract["normalization"]["mean"], dtype=np.float32)
        std = np.asarray(contract["normalization"]["std"], dtype=np.float32)
        if (
            mean.shape != (3,)
            or std.shape != (3,)
            or not np.all(np.isfinite(mean))
            or not np.all(np.isfinite(std))
            or not np.all(std >= np.finfo(np.float32).eps)
        ):
            raise ValueError("invalid normalization")
        if dtype == "float32":
            if not np.all(mean == mean[0]) or not np.all(std == std[0]):
                raise ValueError("per-channel float normalization is unsupported")
        elif dtype == "uint8":
            if kind == "classifier":
                scale, zero = tensor["quantization"]
            else:
                q = contract["quantization"]["input"]
                (scale,), (zero,) = q["scales"], q["zeroPoints"]
            if (
                not np.isfinite(scale)
                or scale <= 0
                or type(zero) is not int
                or not 0 <= zero <= 255
            ):
                raise ValueError("invalid quantization")
            pixels = np.arange(256, dtype=np.float32)[:, None]
            # Same float32 operations/rint as prepare_model_input and _infer_classifier.
            prepared = np.clip(np.rint(((pixels - mean) / std) / scale + zero), 0, 255)
            if not np.all(prepared == pixels):
                raise ValueError("quantized tensor differs from raw RGB pixels")
        else:
            raise ValueError("input type must be FLOAT32 or UINT8")
        if kind == "classifier" and any(
            output["dtype"] not in {"float32", "uint8"} for output in contract["outputs"]
        ):
            raise ValueError("classifier output type must be FLOAT32 or UINT8")
    except (KeyError, TypeError, ValueError, OverflowError) as error:
        raise ValueError(f"MediaPipe runtime input/output incompatibility: {error}") from error


def mediapipe_compatible(contract, kind):
    try:
        require_mediapipe_input(contract, kind)
        return True
    except ValueError:
        return False
