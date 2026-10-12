"""Validate and audit the receipt-bound Roboflow YOLOv8 dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
from collections import Counter, defaultdict
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

import yaml

from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.source import DATASET_VERSION, EXPORT_FORMAT, SOURCE_URL, SourceReceipt

SPLITS = ("train", "valid", "test")
IMAGE_SUFFIXES = frozenset({".jpg", ".jpeg", ".png"})
_ROBOFLOW_IDENTITY = re.compile(r"^(?P<source>.+)\.rf\.[^.]+$")


class DatasetValidationError(ValueError):
    """Raised when extracted data is not safe to use for training or evaluation."""


@dataclass(frozen=True)
class SplitAudit:
    """Counts derived from one immutable provider partition."""

    image_count: int
    box_count: int
    per_class_counts: Mapping[str, int]
    card_count_buckets: Mapping[int, int]

    def to_dict(self) -> dict[str, object]:
        return {
            "image_count": self.image_count,
            "box_count": self.box_count,
            "per_class_counts": dict(self.per_class_counts),
            "card_count_buckets": {
                str(count): images for count, images in self.card_count_buckets.items()
            },
        }


@dataclass(frozen=True)
class DatasetAudit:
    """Content-addressed evidence for the exact dataset consumed by training."""

    receipt_sha256: str
    tree_sha256: str
    file_hashes: tuple[tuple[str, str], ...]
    splits: Mapping[str, SplitAudit]
    image_content_duplicate_groups: tuple[tuple[str, ...], ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "receipt_sha256": self.receipt_sha256,
            "tree_sha256": self.tree_sha256,
            "file_count": len(self.file_hashes),
            "splits": {split: audit.to_dict() for split, audit in self.splits.items()},
            "image_content_duplicate_groups": [
                list(group) for group in self.image_content_duplicate_groups
            ],
        }


def audit_dataset(root: Path, receipt: SourceReceipt) -> DatasetAudit:
    """Fail closed on malformed or leaking data and return its deterministic audit."""
    root = Path(root)
    if not root.is_dir():
        raise DatasetValidationError(f"dataset root does not exist: {root}")
    _validate_receipt(receipt)

    file_hashes = _hash_tree(root)
    _validate_inventory(file_hashes, receipt)
    _validate_provider_metadata(root / "data.yaml")

    hashes_by_path = dict(file_hashes)
    image_hash_groups: dict[str, list[tuple[str, str]]] = defaultdict(list)
    identities: dict[str, tuple[str, str]] = {}
    split_audits: dict[str, SplitAudit] = {}
    for split in SPLITS:
        images = _paired_members(root, split)
        class_counts = Counter({label: 0 for label in CARD_LABELS})
        card_count_buckets: Counter[int] = Counter()
        box_count = 0
        for stem, image_path, label_path in images:
            relative_image = image_path.relative_to(root).as_posix()
            image_hash_groups[hashes_by_path[relative_image]].append((split, relative_image))
            _record_source_identity(identities, stem, split, relative_image)
            classes = _read_yolo_rows(label_path)
            card_count_buckets[len(classes)] += 1
            box_count += len(classes)
            class_counts.update(CARD_LABELS[class_index] for class_index in classes)
        split_audits[split] = SplitAudit(
            image_count=len(images),
            box_count=box_count,
            per_class_counts={label: class_counts[label] for label in CARD_LABELS},
            card_count_buckets=dict(sorted(card_count_buckets.items())),
        )

    duplicate_groups: list[tuple[str, ...]] = []
    for grouped in image_hash_groups.values():
        if len(grouped) < 2:
            continue
        grouped.sort(key=lambda item: item[1])
        grouped_splits = {split for split, _ in grouped}
        if len(grouped_splits) > 1:
            paths = ", ".join(relative for _, relative in grouped)
            raise DatasetValidationError(f"identical image content occurs across splits: {paths}")
        duplicate_groups.append(tuple(relative for _, relative in grouped))
    duplicate_groups.sort()

    tree_hasher = hashlib.sha256()
    for relative, digest in file_hashes:
        tree_hasher.update(relative.encode("utf-8"))
        tree_hasher.update(b"\0")
        tree_hasher.update(bytes.fromhex(digest))

    return DatasetAudit(
        receipt_sha256=receipt.sha256,
        tree_sha256=tree_hasher.hexdigest(),
        file_hashes=file_hashes,
        splits=split_audits,
        image_content_duplicate_groups=tuple(duplicate_groups),
    )


def write_dataset_config(root: Path, config: Path) -> Path:
    """Write a portable Ultralytics config that references, rather than copies, images."""
    root = Path(root).resolve()
    config = Path(config)
    config.parent.mkdir(parents=True, exist_ok=True)
    relative_root = Path(os.path.relpath(root, config.parent.resolve())).as_posix()
    payload = {
        "train": f"{relative_root}/train/images",
        "val": f"{relative_root}/valid/images",
        "test": f"{relative_root}/test/images",
        "names": dict(enumerate(CARD_LABELS)),
    }
    config.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    return config


def _validate_receipt(receipt: SourceReceipt) -> None:
    if (
        receipt.source_url != SOURCE_URL
        or receipt.version != DATASET_VERSION
        or receipt.export_format != EXPORT_FORMAT
        or not re.fullmatch(r"[0-9a-f]{64}", receipt.sha256)
    ):
        raise DatasetValidationError("receipt does not identify the approved Roboflow v4 export")


def _hash_tree(root: Path) -> tuple[tuple[str, str], ...]:
    files = sorted(
        (path for path in root.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    hashed: list[tuple[str, str]] = []
    for path in files:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        hashed.append((path.relative_to(root).as_posix(), digest.hexdigest()))
    return tuple(hashed)


def _validate_inventory(
    file_hashes: tuple[tuple[str, str], ...], receipt: SourceReceipt
) -> None:
    actual = tuple(relative for relative, _ in file_hashes)
    recorded = tuple(sorted(member for member in receipt.members if not member.endswith("/")))
    if actual != recorded:
        raise DatasetValidationError("extracted file tree differs from receipt inventory")


def _validate_provider_metadata(path: Path) -> None:
    try:
        payload = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise DatasetValidationError(f"invalid provider data.yaml: {error}") from error
    if not isinstance(payload, dict):
        raise DatasetValidationError("invalid provider data.yaml")
    names = payload.get("names")
    if names != list(CARD_LABELS) or payload.get("nc") != len(CARD_LABELS):
        raise DatasetValidationError("provider class order differs from the canonical contract")
    expected_splits = {
        "train": "../train/images",
        "val": "../valid/images",
        "test": "../test/images",
    }
    if any(payload.get(key) != value for key, value in expected_splits.items()):
        raise DatasetValidationError("provider split paths differ from the approved partitions")


def _paired_members(root: Path, split: str) -> list[tuple[str, Path, Path]]:
    image_directory = root / split / "images"
    label_directory = root / split / "labels"
    if not image_directory.is_dir() or not label_directory.is_dir():
        raise DatasetValidationError(f"missing {split} image/label directories")

    images: dict[str, Path] = {}
    for path in image_directory.iterdir():
        if not path.is_file() or path.suffix.casefold() not in IMAGE_SUFFIXES:
            raise DatasetValidationError(f"unsupported image member: {path}")
        if path.stem in images:
            raise DatasetValidationError(f"duplicate image stem: {path.stem}")
        images[path.stem] = path
    labels: dict[str, Path] = {}
    for path in label_directory.iterdir():
        if not path.is_file() or path.suffix.casefold() != ".txt":
            raise DatasetValidationError(f"unsupported label member: {path}")
        if path.stem in labels:
            raise DatasetValidationError(f"duplicate label stem: {path.stem}")
        labels[path.stem] = path
    if images.keys() != labels.keys():
        raise DatasetValidationError(f"{split} image/label pairs do not match")
    return [(stem, images[stem], labels[stem]) for stem in sorted(images)]


def _record_source_identity(
    identities: dict[str, tuple[str, str]], stem: str, split: str, relative: str
) -> None:
    match = _ROBOFLOW_IDENTITY.fullmatch(stem)
    identity = (match.group("source") if match else stem).casefold()
    previous = identities.get(identity)
    if previous is not None and previous[0] != split:
        raise DatasetValidationError(
            "source identity occurs across splits: "
            f"{previous[1]} ({previous[0]}) and {relative} ({split})"
        )
    identities.setdefault(identity, (split, relative))


def _read_yolo_rows(path: Path) -> list[int]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise DatasetValidationError(f"cannot read label file {path}: {error}") from error
    if not lines or all(not line.strip() for line in lines):
        raise DatasetValidationError(f"empty label file: {path}")

    classes: list[int] = []
    for line_number, line in enumerate(lines, start=1):
        fields = line.split()
        if len(fields) != 5:
            raise DatasetValidationError(f"malformed YOLO row at {path}:{line_number}")
        try:
            class_index = int(fields[0])
            coordinates = tuple(float(value) for value in fields[1:])
        except ValueError as error:
            raise DatasetValidationError(
                f"malformed YOLO row at {path}:{line_number}"
            ) from error
        if class_index not in range(len(CARD_LABELS)):
            raise DatasetValidationError(f"class index outside 0..51 at {path}:{line_number}")
        if not all(math.isfinite(value) for value in coordinates):
            raise DatasetValidationError(f"non-finite coordinate at {path}:{line_number}")
        if not all(0.0 <= value <= 1.0 for value in coordinates):
            raise DatasetValidationError(f"coordinate outside range 0..1 at {path}:{line_number}")
        if coordinates[2] == 0.0 or coordinates[3] == 0.0:
            raise DatasetValidationError(f"empty box at {path}:{line_number}")
        classes.append(class_index)
    return classes


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    audit = commands.add_parser("audit")
    audit.add_argument("--receipt", required=True, type=Path)
    audit.add_argument("--root", required=True, type=Path)
    audit.add_argument("--config", required=True, type=Path)
    audit.add_argument("--report", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    """Audit one verified extraction and write deterministic training inputs."""
    args = _parse_args()
    receipt = SourceReceipt.from_path(args.receipt)
    audit = audit_dataset(args.root, receipt)
    for split in ("train", "valid"):
        missing = [
            label for label, count in audit.splits[split].per_class_counts.items() if count == 0
        ]
        if missing:
            raise DatasetValidationError(f"{split} does not represent all 52 classes: {missing}")
    write_dataset_config(args.root, args.config)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(audit.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(json.dumps(audit.to_dict(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
