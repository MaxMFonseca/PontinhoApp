"""Normalize reviewed dataset annotations into one fail-closed JSONL contract."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import stat
import tarfile
import zipfile
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

import yaml
from PIL import Image

from pontinho_cards.labels import parse_card_label
from pontinho_cards.manifest import DatasetSource, load_source_manifest
from pontinho_cards.secure_io import (
    SecureReadError,
    safe_name,
    safe_relative_posix,
    secure_ensure_directory,
    secure_read_bytes,
    secure_write_bytes,
)

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_TASK_ROLES = {"DETECTOR_SCENE", "CLASSIFIER_CROP", "FINAL_SCENE_HOLDOUT"}
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
    try:
        return safe_relative_posix(value, field="path")
    except ValueError as error:
        raise NormalizationError(str(error)) from error


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

    root = Path(os.path.abspath(image_root))
    image_path = root.joinpath(*relative_path.parts)
    try:
        data = secure_read_bytes(image_path, root=root)
        with Image.open(io.BytesIO(data)) as image:
            actual_size = image.size
            image.verify()
    except (SecureReadError, OSError, SyntaxError) as error:
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
    for field in ("sourceId", "deckGroupId", "splitGroupId"):
        try:
            normalized[field] = safe_name(normalized[field], field=field)
        except ValueError as error:
            raise NormalizationError(str(error)) from error
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
    if "taskRoles" in record:
        task_roles = record["taskRoles"]
        if not isinstance(task_roles, list) or any(
            not isinstance(role, str) or role not in _TASK_ROLES for role in task_roles
        ):
            raise NormalizationError(
                f"taskRoles entries must be one of {sorted(_TASK_ROLES)}"
            )
        if len(task_roles) != len(set(task_roles)):
            raise NormalizationError("taskRoles must not contain duplicates")
        normalized["taskRoles"] = list(task_roles)
    canonical_member = record.get("sourceArchiveMember")
    canonical_member_hash = record.get("sourceArchiveMemberSha256")
    if canonical_member is not None or canonical_member_hash is not None:
        safe_member = _safe_relative_path(canonical_member)
        if not isinstance(canonical_member_hash, str) or not _SHA256_RE.fullmatch(
            canonical_member_hash
        ):
            raise NormalizationError(
                "sourceArchiveMemberSha256 must be a lowercase 64-character SHA-256"
            )
        normalized["sourceArchiveMember"] = safe_member.as_posix()
        normalized["sourceArchiveMemberSha256"] = canonical_member_hash
    lighting = record.get("lighting", "unspecified")
    if not isinstance(lighting, str) or not lighting.strip():
        raise NormalizationError("lighting must be non-empty text when provided")
    normalized["lighting"] = lighting.strip()

    raw_boxes = record.get("boxes")
    if not isinstance(raw_boxes, list):
        raise NormalizationError("boxes must be a list")
    indexed_boxes = any(
        isinstance(raw_box, Mapping) and "boxIndex" in raw_box for raw_box in raw_boxes
    )
    if indexed_boxes and any(
        not isinstance(raw_box, Mapping) or "boxIndex" not in raw_box for raw_box in raw_boxes
    ):
        raise NormalizationError("boxIndex must be present on every box when provided")
    boxes: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    seen_box_indexes: set[int] = set()
    for index, raw_box in enumerate(raw_boxes):
        if not isinstance(raw_box, Mapping):
            raise NormalizationError(f"box {index} must be an object")
        box_index = raw_box.get("boxIndex", index)
        if type(box_index) is not int or box_index < 0:
            raise NormalizationError("boxIndex must be a non-negative integer")
        if box_index in seen_box_indexes:
            raise NormalizationError(f"duplicate boxIndex: {box_index}")
        seen_box_indexes.add(box_index)
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
        normalized_box = {
            "label": label,
            "xmin": xmin,
            "ymin": ymin,
            "xmax": xmax,
            "ymax": ymax,
        }
        if indexed_boxes:
            normalized_box["boxIndex"] = box_index
        boxes.append(normalized_box)

    audited_negative = (
        not raw_boxes
        and normalized["isRealPhoto"] is True
        and bool(
            {"DETECTOR_SCENE", "FINAL_SCENE_HOLDOUT"}.intersection(
                normalized.get("taskRoles", [])
            )
        )
    )
    if not boxes and not audited_negative:
        return NormalizationResult(None, rejected, "no usable standard-card boxes")
    normalized["boxes"] = boxes
    return NormalizationResult(normalized, rejected)


def normalize_records(
    records: Iterable[Mapping[str, Any]],
    *,
    out: str | Path,
    image_root: str | Path | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Normalize records and atomically write deterministic data and rejection files."""

    raw_records = list(records)
    if not all(isinstance(record, Mapping) for record in raw_records):
        raise NormalizationError("record must be an object")
    accepted: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for raw in sorted(raw_records, key=lambda item: str(item.get("imageId", ""))):
        image_id = raw.get("imageId")
        if isinstance(image_id, str) and image_id in seen_ids:
            raise NormalizationError(f"duplicate imageId: {image_id}")
        if isinstance(image_id, str):
            seen_ids.add(image_id)
        result = normalize_record(raw, image_root=image_root)
        if result.record is not None:
            accepted.append(result.record)
            if result.rejected_boxes:
                rejected.append(
                    {
                        "imageId": image_id,
                        "reason": "one or more boxes rejected",
                        "rejectedBoxes": result.rejected_boxes,
                    }
                )
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
    try:
        secure_ensure_directory(output)
        secure_write_bytes(output, "records.jsonl", jsonl.encode("utf-8"))
        secure_write_bytes(
            output,
            "rejections.json",
            (
                json.dumps(rejection_document, ensure_ascii=False, indent=2, sort_keys=True)
                + "\n"
            ).encode("utf-8"),
        )
    except SecureReadError as error:
        raise NormalizationError(str(error)) from error
    return accepted, rejected


def _archive_members(payload: bytes) -> dict[str, bytes]:
    members: dict[str, bytes] = {}
    stream = io.BytesIO(payload)
    try:
        if zipfile.is_zipfile(stream):
            with zipfile.ZipFile(stream) as archive:
                entries = []
                for item in archive.infolist():
                    if item.is_dir():
                        continue
                    if (
                        item.create_system == 3
                        and stat.S_IFMT(item.external_attr >> 16) == stat.S_IFLNK
                    ):
                        raise NormalizationError("unsafe archive member link")
                    entries.append((item.filename, False, archive.read(item)))
        else:
            stream.seek(0)
            with tarfile.open(fileobj=stream, mode="r:*") as archive:
                entries = []
                for item in archive.getmembers():
                    if item.issym() or item.islnk():
                        raise NormalizationError("unsafe archive member link")
                    if item.isfile():
                        handle = archive.extractfile(item)
                        if handle is None:
                            raise NormalizationError("archive member cannot be read")
                        entries.append((item.name, False, handle.read()))
    except (tarfile.TarError, zipfile.BadZipFile, KeyError, OSError) as error:
        raise NormalizationError(f"archive cannot be read: {error}") from error
    for name, _is_dir, data in entries:
        try:
            safe = _safe_relative_path(name).as_posix()
        except NormalizationError as error:
            raise NormalizationError(f"unsafe archive member: {name}") from error
        if safe in members:
            raise NormalizationError(f"duplicate archive member: {safe}")
        members[safe] = data
    return members


def _image_metadata(payload: bytes, member: str) -> tuple[int, int, str]:
    try:
        with Image.open(io.BytesIO(payload)) as image:
            image.load()
            width, height = image.size
    except (OSError, SyntaxError) as error:
        raise NormalizationError(f"archive image cannot be decoded: {member}") from error
    return width, height, hashlib.sha256(payload).hexdigest()


def _base_archive_record(
    source: DatasetSource,
    member: str,
    payload: bytes,
    output: Path,
    index: int,
) -> dict[str, Any]:
    width, height, digest = _image_metadata(payload, member)
    suffix = PurePosixPath(member).suffix.lower() or ".img"
    relative = PurePosixPath("images", source.source_id, f"{index:06d}{suffix}")
    try:
        secure_write_bytes(output, relative, payload)
    except SecureReadError as error:
        raise NormalizationError(str(error)) from error
    return {
        "imageId": f"{source.source_id}:{index:06d}",
        "path": relative.as_posix(),
        "width": width,
        "height": height,
        "sourceId": source.source_id,
        "deckGroupId": source.deck_group_id,
        "splitGroupId": source.source_id,
        "imageSha256": digest,
        "sourceDerivativeId": f"{source.source_id}:{member}",
        "augmentationParentId": None,
        "isRealPhoto": True,
        "taskRoles": list(source.task_roles),
        "sourceArchiveMember": member,
        "sourceArchiveMemberSha256": digest,
        "lighting": "unspecified",
    }


def _coco_id(value: Any, name: str) -> int | str:
    """Accept only stable scalar COCO identifiers, never bool/null/coercions."""

    if isinstance(value, bool) or not isinstance(value, (int, str)) or value == "":
        raise NormalizationError(f"COCO {name} ID must be a non-null integer or string")
    return value


def _records_from_archive(
    source: DatasetSource, archive: Path, output: Path, archive_root: Path
) -> list[dict[str, Any]]:
    try:
        archive_payload = secure_read_bytes(archive, root=archive_root)
    except SecureReadError as error:
        raise NormalizationError(str(error)) from error
    if hashlib.sha256(archive_payload).hexdigest() != source.archive_sha256:
        raise NormalizationError(f"Archive checksum mismatch for {source.source_id}")
    members = _archive_members(archive_payload)
    prefix = PurePosixPath(source.adapter.image_prefix).as_posix().rstrip("/") + "/"
    records: list[dict[str, Any]] = []
    if source.adapter.kind == "COCO_JSON_SCENES":
        annotation_member = str(source.adapter.annotation_member)
        try:
            coco = json.loads(members[annotation_member])
        except (KeyError, UnicodeError, json.JSONDecodeError) as error:
            raise NormalizationError("COCO annotation member is missing or invalid") from error
        if not isinstance(coco, Mapping):
            raise NormalizationError("COCO annotation must be an object")
        images = coco.get("images")
        categories = coco.get("categories")
        annotations = coco.get("annotations")
        if not all(isinstance(value, list) for value in (images, categories, annotations)):
            raise NormalizationError("COCO images/categories/annotations must be lists")
        if not all(isinstance(value, Mapping) for value in categories):
            raise NormalizationError("COCO categories must contain only objects")
        if not all(isinstance(value, Mapping) for value in images):
            raise NormalizationError("COCO images must contain only objects")
        if not all(isinstance(value, Mapping) for value in annotations):
            raise NormalizationError("COCO annotations must contain only objects")
        category_labels: dict[int | str, str] = {}
        for category in categories:
            category_id = _coco_id(category.get("id"), "category")
            if category_id in category_labels:
                raise NormalizationError("COCO category IDs must be unique")
            if category.get("name") not in source.adapter.label_map:
                raise NormalizationError("COCO category lacks an explicit canonical label mapping")
            category_labels[category_id] = source.adapter.label_map[str(category["name"])]
        image_ids: set[int | str] = set()
        member_by_image: dict[int | str, str] = {}
        members_seen: set[str] = set()
        for image in images:
            image_id = _coco_id(image.get("id"), "image")
            if image_id in image_ids:
                raise NormalizationError("COCO image IDs must be unique")
            image_ids.add(image_id)
            if not isinstance(image.get("file_name"), str):
                raise NormalizationError("COCO image metadata is invalid")
            file_name = _safe_relative_path(image["file_name"]).as_posix()
            member = prefix + file_name
            if member in members_seen:
                raise NormalizationError("COCO image archive member mapping must be unique")
            members_seen.add(member)
            member_by_image[image_id] = member
        boxes_by_image: dict[int | str, list[dict[str, Any]]] = {}
        annotation_ids: set[int | str] = set()
        for annotation in annotations:
            if "id" in annotation:
                annotation_id = _coco_id(annotation.get("id"), "annotation")
                if annotation_id in annotation_ids:
                    raise NormalizationError("COCO annotation IDs must be unique")
                annotation_ids.add(annotation_id)
            image_id = _coco_id(annotation.get("image_id"), "annotation image_id")
            category_id = _coco_id(annotation.get("category_id"), "annotation category_id")
            if image_id not in image_ids:
                raise NormalizationError("COCO annotation references an unknown image")
            if category_id not in category_labels:
                raise NormalizationError("COCO annotation references an unknown category")
            bbox = annotation.get("bbox")
            if not isinstance(bbox, list) or len(bbox) != 4:
                raise NormalizationError("COCO bbox must be [x,y,width,height]")
            x, y, width, height = bbox
            if any(type(value) not in {int, float} or not math.isfinite(value) for value in bbox):
                raise NormalizationError("COCO bbox must be finite")
            boxes_by_image.setdefault(image_id, []).append(
                {
                    "label": category_labels[category_id],
                    "xmin": float(x),
                    "ymin": float(y),
                    "xmax": float(x + width),
                    "ymax": float(y + height),
                }
            )
        for index, image in enumerate(sorted(images, key=lambda item: str(item.get("id")))):
            image_id = _coco_id(image.get("id"), "image")
            member = member_by_image[image_id]
            if member not in members:
                raise NormalizationError(f"COCO image member is missing: {member}")
            record = _base_archive_record(source, member, members[member], output, index)
            if image.get("width") != record["width"] or image.get("height") != record["height"]:
                raise NormalizationError("COCO image dimensions differ from archive bytes")
            record["boxes"] = boxes_by_image.get(image_id, [])
            records.append(record)
    elif source.adapter.kind == "YOLO_SCENES":
        annotation_member = str(source.adapter.annotation_member)
        try:
            dataset = yaml.safe_load(members[annotation_member])
        except (KeyError, UnicodeError, yaml.YAMLError) as error:
            raise NormalizationError("YOLO data member is missing or invalid") from error
        if not isinstance(dataset, Mapping) or not isinstance(dataset.get("names"), list):
            raise NormalizationError("YOLO data must declare an ordered names list")
        names = dataset["names"]
        if not all(isinstance(name, str) and name for name in names):
            raise NormalizationError("YOLO class names must be non-empty text")
        image_members = sorted(
            member
            for member in members
            if member.startswith(prefix)
            and PurePosixPath(member).suffix.casefold() in {".jpg", ".jpeg", ".png", ".webp"}
        )
        for index, member in enumerate(image_members):
            record = _base_archive_record(source, member, members[member], output, index)
            relative = PurePosixPath(member).relative_to(PurePosixPath(source.adapter.image_prefix))
            label_member = (
                PurePosixPath(source.adapter.image_prefix).parent
                / "labels"
                / relative.with_suffix(".txt")
            ).as_posix()
            try:
                lines = members[label_member].decode("utf-8").splitlines()
            except (KeyError, UnicodeError) as error:
                raise NormalizationError(f"YOLO label member is missing: {label_member}") from error
            boxes: list[dict[str, Any]] = []
            for line in lines:
                fields = line.split()
                if len(fields) != 5:
                    raise NormalizationError("YOLO row must contain class and four coordinates")
                try:
                    class_id = int(fields[0])
                    center_x, center_y, box_width, box_height = map(float, fields[1:])
                except ValueError as error:
                    raise NormalizationError("YOLO row contains invalid numeric data") from error
                if not 0 <= class_id < len(names):
                    raise NormalizationError("YOLO class index is outside the names list")
                if any(
                    not math.isfinite(value) or not 0 <= value <= 1
                    for value in (center_x, center_y, box_width, box_height)
                ):
                    raise NormalizationError("YOLO coordinates must be finite and normalized")
                source_label = names[class_id]
                if source_label not in source.adapter.label_map:
                    raise NormalizationError("YOLO class lacks an explicit canonical label mapping")
                xmin = (center_x - box_width / 2) * record["width"]
                ymin = (center_y - box_height / 2) * record["height"]
                xmax = (center_x + box_width / 2) * record["width"]
                ymax = (center_y + box_height / 2) * record["height"]
                boxes.append(
                    {
                        "label": source.adapter.label_map[source_label],
                        "xmin": xmin,
                        "ymin": ymin,
                        "xmax": xmax,
                        "ymax": ymax,
                    }
                )
            record["boxes"] = boxes
            records.append(record)
    else:
        image_members = sorted(member for member in members if member.startswith(prefix))
        for index, member in enumerate(image_members):
            relative = PurePosixPath(member).relative_to(PurePosixPath(source.adapter.image_prefix))
            if len(relative.parts) < 2:
                raise NormalizationError("isolated-card image must be inside a mapped label folder")
            source_label = relative.parts[0]
            if source_label not in source.adapter.label_map:
                raise NormalizationError("isolated-card folder lacks an explicit label mapping")
            record = _base_archive_record(source, member, members[member], output, index)
            record["boxes"] = [
                {
                    "label": source.adapter.label_map[source_label],
                    "xmin": 0,
                    "ymin": 0,
                    "xmax": record["width"],
                    "ymax": record["height"],
                }
            ]
            records.append(record)
    return records


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--archive-root", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    manifest = load_source_manifest(args.manifest)
    records: list[dict[str, Any]] = []
    if manifest.sources and args.archive_root is None:
        raise NormalizationError("--archive-root is required for approved sources")
    for source in manifest.sources:
        archive = args.archive_root / source.archive_local_path
        records.extend(_records_from_archive(source, archive, args.out, args.archive_root))
    normalize_records(records, out=args.out, image_root=args.out)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
