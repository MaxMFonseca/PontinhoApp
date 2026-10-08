"""Shared classifier-only boundary for floating and quantized softmax outputs."""

from __future__ import annotations

import math

import numpy as np

from pontinho_cards.labels import CARD_LABELS


def normalize_classifier_output(raw, *, quantization=None):
    """Validate native scores, then normalize each row before strict calibration.

    Nearest rounding of C probabilities with step s contributes at most C*s/2
    error to their sum. For C=52 and the TFLite softmax step 1/256 this is
    0.1015625. This also covers its saturated maximum (255/256): saturation
    affects at most one probability and its total deficit is at most s.

    Floating outputs get only the standard gamma_C bound for C float32
    rounding errors. They never inherit the integer quantization allowance.
    """
    raw = np.asarray(raw)
    classes = len(CARD_LABELS)
    if raw.ndim != 2 or raw.shape[0] == 0 or raw.shape[1] != classes:
        raise ValueError("classifier requires nonempty rows of 52 scores")
    if raw.dtype.kind not in {"f", "i", "u"}:
        raise ValueError("classifier scores must be numeric")
    values = raw.astype(np.float64)
    epsilon = np.finfo(np.float32).eps
    tolerance = classes * epsilon / (1 - classes * epsilon)
    if quantization is not None:
        if raw.dtype not in {np.dtype("int8"), np.dtype("uint8")}:
            raise ValueError("quantized classifier scores must be int8 or uint8")
        if not isinstance(quantization, (tuple, list)) or len(quantization) != 2:
            raise ValueError("classifier output quantization must contain one scale and zero point")
        scale, zero = quantization
        limits = np.iinfo(raw.dtype)
        if (
            isinstance(scale, (bool, np.bool_))
            or not isinstance(scale, (int, float, np.integer, np.floating))
            or not math.isfinite(scale)
            or scale <= 0
            or isinstance(zero, (bool, np.bool_))
            or not isinstance(zero, (int, np.integer))
            or not limits.min <= zero <= limits.max
        ):
            raise ValueError("classifier output quantization is invalid")
        values = (values - int(zero)) * float(scale)
        tolerance += classes * float(scale) / 2
    mass = values.sum(axis=1, keepdims=True)
    if (
        not np.all(np.isfinite(values))
        or np.any(values < 0)
        or np.any(values > 1)
        or np.any(mass <= 0)
        or np.any(np.abs(mass - 1) > tolerance)
    ):
        raise ValueError("classifier probability mass is invalid or exceeds its rounding bound")
    return values / mass
