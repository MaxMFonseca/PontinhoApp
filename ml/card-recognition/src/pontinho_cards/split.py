"""Create deterministic, leakage-proof grouped dataset partitions."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from collections import Counter, defaultdict
from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any

from pontinho_cards.normalize import NormalizationError, normalize_record


class SplitError(ValueError):
    """Raised when records cannot be partitioned without ambiguity or leakage."""


class _UnionFind:
    def __init__(self, size: int) -> None:
        self.parent = list(range(size))

    def find(self, item: int) -> int:
        while self.parent[item] != item:
            self.parent[item] = self.parent[self.parent[item]]
            item = self.parent[item]
        return item

    def union(self, left: int, right: int) -> None:
        left_root = self.find(left)
        right_root = self.find(right)
        if left_root != right_root:
            self.parent[right_root] = left_root


_LEAKAGE_KEYS = (
    "imageSha256",
    "sourceDerivativeId",
    "deckGroupId",
    "splitGroupId",
    "augmentationParentId",
)
_PARTITIONS = ("train", "validation", "test")
_RATIOS = {"train": 0.70, "validation": 0.15, "test": 0.15}


def _validate_records(records: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    raw_records = list(records)
    if not all(isinstance(record, Mapping) for record in raw_records):
        raise SplitError("record must be an object")
    normalized: list[dict[str, Any]] = []
    for record in raw_records:
        try:
            result = normalize_record(record)
        except NormalizationError as error:
            raise SplitError(str(error)) from error
        if result.rejected_boxes:
            raise SplitError(result.rejected_boxes[0]["reason"])
        if result.record is None:
            raise SplitError(result.exclusion_reason or "record has no usable boxes")
        for raw_box, canonical_box in zip(record["boxes"], result.record["boxes"]):
            if any(
                raw_box[field] != canonical_box[field] for field in ("xmin", "ymin", "xmax", "ymax")
            ):
                raise SplitError("boxes must use canonical in-bounds geometry")
        normalized.append(result.record)
    normalized.sort(key=lambda record: str(record.get("imageId", "")))
    seen: set[str] = set()
    for record in normalized:
        image_id = record.get("imageId")
        if not isinstance(image_id, str) or not image_id:
            raise SplitError("every record must have a non-empty imageId")
        if image_id in seen:
            raise SplitError(f"duplicate imageId: {image_id}")
        seen.add(image_id)
        for key in _LEAKAGE_KEYS[:-1]:
            if not isinstance(record.get(key), str) or not record[key]:
                raise SplitError(f"{image_id}.{key} must be non-empty text")
        parent = record.get("augmentationParentId")
        if parent is not None and (not isinstance(parent, str) or not parent):
            raise SplitError(f"{image_id}.augmentationParentId must be null or non-empty text")
        if type(record.get("isRealPhoto")) is not bool:
            raise SplitError(f"{image_id}.isRealPhoto must be a boolean")
        boxes = record.get("boxes")
        if not isinstance(boxes, list):
            raise SplitError(f"{image_id}.boxes must be a list")
    return normalized


def _components(records: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    union_find = _UnionFind(len(records))
    owners: dict[tuple[str, str], int] = {}
    for index, record in enumerate(records):
        for key in _LEAKAGE_KEYS:
            value = record.get(key)
            if value is None:
                continue
            token = (key, str(value))
            previous = owners.setdefault(token, index)
            union_find.union(previous, index)
    image_owners = {record["imageId"]: index for index, record in enumerate(records)}
    for index, record in enumerate(records):
        parent_id = record["augmentationParentId"]
        if parent_id is None:
            continue
        parent_index = image_owners.get(parent_id)
        if parent_index is None:
            raise SplitError(f"missing augmentation parent {parent_id!r} for {record['imageId']!r}")
        union_find.union(index, parent_index)
    grouped: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for index, record in enumerate(records):
        grouped[union_find.find(index)].append(record)
    return [sorted(group, key=lambda record: record["imageId"]) for group in grouped.values()]


def _component_key(group: list[dict[str, Any]], seed: int) -> str:
    identity = "\0".join(record["imageId"] for record in group)
    return hashlib.sha256(f"{seed}\0{identity}".encode()).hexdigest()


def _labels(group: list[dict[str, Any]]) -> Counter[str]:
    return Counter(box["label"] for record in group for box in record["boxes"])


def create_splits(
    records: Iterable[Mapping[str, Any]], *, seed: int
) -> dict[str, list[dict[str, Any]]]:
    """Group all leakage aliases, then greedily balance size and class coverage."""

    if type(seed) is not int:
        raise SplitError("seed must be an integer")
    checked = _validate_records(records)
    result: dict[str, list[dict[str, Any]]] = {name: [] for name in _PARTITIONS}
    if not checked:
        return result

    groups = sorted(_components(checked), key=lambda group: _component_key(group, seed))
    total = len(checked)
    target_sizes = {name: total * _RATIOS[name] for name in _PARTITIONS}
    global_labels = _labels(checked)
    target_labels = {
        name: {label: count * _RATIOS[name] for label, count in global_labels.items()}
        for name in _PARTITIONS
    }
    counts = Counter[str]()
    class_counts: dict[str, Counter[str]] = {name: Counter() for name in _PARTITIONS}

    for group_index, group in enumerate(groups):
        eligible = ["train", "validation"]
        if all(record["isRealPhoto"] is True for record in group):
            eligible.append("test")
        remaining = len(groups) - group_index
        empty_eligible = [name for name in eligible if not result[name]]
        if empty_eligible and remaining <= len(empty_eligible):
            candidates = empty_eligible
        else:
            candidates = eligible
        labels = _labels(group)

        def score(partition: str) -> tuple[float, float, int]:
            size_deficit = target_sizes[partition] - counts[partition]
            class_deficit = sum(
                max(target_labels[partition].get(label, 0.0) - class_counts[partition][label], 0.0)
                for label in labels
            )
            # Prefer test on exact ties so a real-only holdout exists when feasible.
            tie_order = {"test": 2, "validation": 1, "train": 0}[partition]
            return (size_deficit, class_deficit, tie_order)

        selected = max(candidates, key=score)
        result[selected].extend(group)
        counts[selected] += len(group)
        class_counts[selected].update(labels)

    for partition in _PARTITIONS:
        result[partition].sort(key=lambda record: record["imageId"])
    return result


def _document(partitions: Mapping[str, list[dict[str, Any]]], seed: int) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "seed": seed,
        "partitions": {
            "test": partitions["test"],
            "train": partitions["train"],
            "validation": partitions["validation"],
        },
    }


def _serialized(document: Mapping[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode(
        "utf-8"
    )


def _atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def write_split_manifests(
    records: Iterable[Mapping[str, Any]], *, out: str | Path, seed: int
) -> dict[str, list[dict[str, Any]]]:
    """Write split data and create-once final holdout before any training can run."""

    partitions = create_splits(records, seed=seed)
    output_path = Path(out)
    holdout_path = output_path.with_name("final-holdout.json")
    split_payload = _serialized(_document(partitions, seed))
    test_payload = (json.dumps(partitions["test"], indent=2, sort_keys=True) + "\n").encode()
    holdout_document = {
        "schemaVersion": 1,
        "kind": "pontinho-frozen-holdout",
        "seed": seed,
        "splitManifest": output_path.name,
        "splitManifestSha256": hashlib.sha256(split_payload).hexdigest(),
        "testPartitionSha256": hashlib.sha256(test_payload).hexdigest(),
        "test": partitions["test"],
    }
    holdout_payload = _serialized(holdout_document)
    if holdout_path.exists() and holdout_path.read_bytes() != holdout_payload:
        raise SplitError(f"frozen final holdout differs from requested split: {holdout_path}")
    if not holdout_path.exists():
        _atomic_write(holdout_path, holdout_payload)
    _atomic_write(output_path, split_payload)
    return partitions


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        records = [json.loads(line) for line in lines if line.strip()]
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SplitError(f"cannot read normalized records from {path}: {error}") from error
    if not all(isinstance(record, dict) for record in records):
        raise SplitError(f"normalized records in {path} must be JSON objects")
    return records


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--seed", type=int, default=20261014)
    args = parser.parse_args(argv)
    input_path = args.input / "records.jsonl" if args.input.is_dir() else args.input
    write_split_manifests(_read_jsonl(input_path), out=args.out, seed=args.seed)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
