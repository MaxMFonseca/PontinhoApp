"""Shared model-version and export-time language for Android release metadata."""

from __future__ import annotations

import re
from datetime import datetime

_SAFE_MODEL_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_UTC_RFC3339 = re.compile(
    r"^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
    r"(?:\.[0-9]{1,9})?Z$"
)


def validate_release_identity(model_version: object, exported_at: object) -> tuple[str, str]:
    if not isinstance(model_version, str) or not _SAFE_MODEL_VERSION.fullmatch(model_version):
        raise ValueError("model version is invalid")
    timestamp = _UTC_RFC3339.fullmatch(exported_at) if isinstance(exported_at, str) else None
    if timestamp is None:
        raise ValueError("export timestamp must be UTC RFC 3339")
    try:
        # Validate calendar/time fields independently of Python's ISO parser, whose
        # supported fractional precision differs by version. The regex accepts
        # 1..9 digits; return the exact input, preserving all nanoseconds.
        datetime(*(int(field) for field in timestamp.groups()))
    except ValueError as error:
        raise ValueError("export timestamp must be UTC RFC 3339") from error
    return model_version, exported_at
