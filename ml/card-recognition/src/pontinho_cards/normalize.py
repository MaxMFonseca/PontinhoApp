"""Normalize reviewed dataset annotations into one fail-closed JSONL contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import tempfile
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from PIL import Image

from pontinho_cards.labels import parse_card_label
from pontinho_cards.manifest import load_source_manifest

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_REQUIRED_TEXT_FIELDS = (
    "imageId",
    "sourceId",
    "deckGroupId",
    "splitGroupId",
    "sourceDerivativeId",
)


class NormalizationError(ValueError):
    """Raised when an input record cannot safely enter the normalized dataset."""


@dataclass(frozen=True)
class NormalizationResult:
    record: dict[str, Any] | None
    rejected_boxes: list[dict[str, Any]]
    exclusion_reason: str | None = None


def _required_text(record: Mapping[str, Any], field: str) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value.strip():
        raise NormalizationError(f"{field} must be non-empty text")
    return value


def _safe_relative_path(value: Any) -> PurePosixPath:
    if not isinstance(value, str) or not value or "\\" in value:
        raise NormalizationError("path must be a safe relative path using forward slashes")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise NormalizationError("path must be a safe relative path without traversal")
    return path


def _positive_int(record: Mapping[str, Any], field: str) -> int:
    value = record.get(field)
    if type(value) is not int or value <= 0:
        raise NormalizationError(f"{field} must be a positive integer")
    return value


def _validate_image(
    record: Mapping[str, Any], image_root: Path | None, relative_path: PurePosixPath
) -> None:
    digest = record.get("imageSha256")
    if not isinstance(digest, str) or not _SHA256_RE.fullmatch(digest):
        raise NormalizationError("imageSha256 must be a lowercase 64-character SHA-256")
    if image_root is None:
        return

    root = image_root.resolve()
    image_path = (root / Path(*relative_path.parts)).resolve()
    if image_path != root and root not in image_path.parents:
        raise NormalizationError("path must resolve beneath image_root")
    try:
        data = image_path.read_bytes()
        with Image.open(image_path) as image:
            actual_size = image.size
            image.verify()
    except (OSError, SyntaxError) as error:
        raise NormalizationError(f"image cannot be read safely: {image_path}") from error
    expected_size = (_positive_int(record, "width"), _positive_int(record, "height"))
    if actual_size != expected_size:
        raise NormalizationError(
            f"image dimensions do not match metadata: expected {expected_size}, got {actual_size}"
        )
    actual_digest = hashlib.sha256(data).hexdigest()
    if actual_digest != digest:
        raise NormalizationError("image SHA-256 does not match imageSha256 metadata")


def _coordinate(box: Mapping[str, Any], field: str) -> float:
    value = box.get(field)
    if type(value) not in {int, float} or not math.isfinite(value):
        raise NormalizationError(f"box {field} must use finite numeric coordinates")
    return float(value)


def normalize_record(
    record: Mapping[str, Any], *, image_root: str | Path | None = None
) -> NormalizationResult:
    """Validate and normalize a canonical record without guessing source aliases."""

    if not isinstance(record, Mapping):
        raise NormalizationError("record must be an object")
    normalized: dict[str, Any] = {}
    for field in _REQUIRED_TEXT_FIELDS:
        normalized[field] = _required_text(record, field)
    relative_path = _safe_relative_path(record.get("path"))
    normalized["path"] = relative_path.as_posix()
    width = _positive_int(record, "width")
    height = _positive_int(record, "height")
    normalized["width"] = width
    normalized["height"] = height
    _validate_image(record, Path(image_root) if image_root is not None else None, relative_path)
    normalized["imageSha256"] = record["imageSha256"]

    parent = record.get("augmentationParentId")
    if parent is not None and (not isinstance(parent, str) or not parent.strip()):
        raise NormalizationError("augmentationParentId must be null or non-empty text")
    normalized["augmentationParentId"] = parent
    real = record.get("isRealPhoto")
    if type(real) is not bool:
        raise NormalizationError("isRealPhoto must be a boolean")
    normalized["isRealPhoto"] = real

    raw_boxes = record.get("boxes")
    if not isinstance(raw_boxes, list):
        raise NormalizationError("boxes must be a list")
    boxes: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    for index, raw_box in enumerate(raw_boxes):
        if not isinstance(raw_box, Mapping):
            raise NormalizationError(f"box {index} must be an object")
        label = raw_box.get("label")
        try:
            parse_card_label(label)  # type: ignore[arg-type]
        except ValueError as error:
            raise NormalizationError(str(error)) from error
        xmin = min(max(_coordinate(raw_box, "xmin"), 0.0), float(width))
        ymin = min(max(_coordinate(raw_box, "ymin"), 0.0), float(height))
        xmax = min(max(_coordinate(raw_box, "xmax"), 0.0), float(width))
        ymax = min(max(_coordinate(raw_box, "ymax"), 0.0), float(height))
        if xmax <= xmin or ymax <= ymin:
            rejected.append({"index": index, "reason": "zero-area box after bounds clamping"})
            continue
        boxes.append(
            {
                "label": label,
                "xmin": xmin,
                "ymin": ymin,
                "xmax": xmax,
                "ymax": ymax,
            }
        )

    if not boxes:
        return NormalizationResult(None, rejected, "no usable standard-card boxes")
    normalized["boxes"] = boxes
    return NormalizationResult(normalized, rejected)


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def normalize_records(
    records: Iterable[Mapping[str, Any]],
    *,
    out: str | Path,
    image_root: str | Path | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Normalize records and atomically write deterministic data and rejection files."""

    accepted: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for raw in sorted(records, key=lambda item: str(item.get("imageId", ""))):
        image_id = raw.get("imageId")
        if isinstance(image_id, str) and image_id in seen_ids:
            raise NormalizationError(f"duplicate imageId: {image_id}")
        if isinstance(image_id, str):
            seen_ids.add(image_id)
        result = normalize_record(raw, image_root=image_root)
        if result.record is not None:
            accepted.append(result.record)
        else:
            rejected.append(
                {
                    "imageId": image_id,
                    "reason": result.exclusion_reason,
                    "rejectedBoxes": result.rejected_boxes,
                }
            )

    output = Path(out)
    jsonl = "".join(
        json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        for record in accepted
    )
    rejection_document = {"schemaVersion": 1, "rejections": rejected}
    _atomic_write(output / "records.jsonl", jsonl)
    _atomic_write(
        output / "rejections.json",
        json.dumps(rejection_document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )
    return accepted, rejected


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    manifest = load_source_manifest(args.manifest)
    if manifest.sources:
        raise NormalizationError(
            "manifest schema v1 declares no raw annotation format or local path; "
            "no source adapter can be selected safely"
        )
    normalize_records([], out=args.out)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
