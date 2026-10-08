from __future__ import annotations

import json
from pathlib import Path

import pytest

from pontinho_cards.split import SplitError, create_splits, main, write_split_manifests


def _record(
    index: int,
    *,
    deck: str,
    label: str,
    real: bool = True,
    image_hash: str | None = None,
    derivative: str | None = None,
    parent: str | None = None,
) -> dict[str, object]:
    return {
        "imageId": f"image-{index}",
        "path": f"images/{index}.jpg",
        "width": 100,
        "height": 80,
        "sourceId": "source-a",
        "deckGroupId": deck,
        "splitGroupId": f"session-{index}",
        "imageSha256": image_hash or f"{index:064x}",
        "sourceDerivativeId": derivative or f"derivative-{index}",
        "augmentationParentId": parent,
        "isRealPhoto": real,
        "boxes": [{"label": label, "xmin": 1.0, "ymin": 2.0, "xmax": 9.0, "ymax": 12.0}],
    }


def _fixture_records() -> list[dict[str, object]]:
    records = [
        _record(1, deck="deck-a", label="AC"),
        _record(2, deck="deck-a", label="2D"),
        _record(3, deck="deck-b", label="AC"),
        _record(4, deck="deck-c", label="2D"),
        _record(5, deck="deck-d", label="3H"),
        _record(6, deck="deck-e", label="3H"),
        _record(7, deck="deck-f", label="4S"),
        _record(8, deck="deck-g", label="4S"),
        _record(9, deck="deck-h", label="5C"),
    ]
    # These three records are transitively related and therefore must stay together.
    records.append(_record(10, deck="deck-i", label="6D", derivative="shared-derivative"))
    records.append(
        _record(
            11,
            deck="deck-j",
            label="7H",
            derivative="shared-derivative",
            parent="image-10",
        )
    )
    records.append(_record(12, deck="deck-k", label="8S", real=False, parent="image-11"))
    return records


def test_grouped_split_is_deterministic_and_leakage_identifiers_are_disjoint() -> None:
    records = _fixture_records()

    first = create_splits(records, seed=20261014)
    second = create_splits(list(reversed(records)), seed=20261014)

    assert first == second
    locations = {
        record["imageId"]: partition
        for partition, partition_records in first.items()
        for record in partition_records
    }
    assert locations["image-10"] == locations["image-11"] == locations["image-12"]
    for key in (
        "imageSha256",
        "sourceDerivativeId",
        "deckGroupId",
        "splitGroupId",
        "augmentationParentId",
    ):
        values = [
            {record[key] for record in first[partition] if record[key] is not None}
            for partition in ("train", "validation", "test")
        ]
        assert values[0].isdisjoint(values[1])
        assert values[0].isdisjoint(values[2])
        assert values[1].isdisjoint(values[2])


def test_augmentation_parent_chain_and_siblings_remain_in_one_partition() -> None:
    records = [
        _record(1, deck="deck-a", label="AC"),
        _record(2, deck="deck-b", label="2D", parent="image-1"),
        _record(3, deck="deck-c", label="3H", parent="image-2"),
        _record(4, deck="deck-d", label="4S", parent="image-1"),
    ]
    split = create_splits(records, seed=20261014)

    locations = {
        record["imageId"]: partition
        for partition, partition_records in split.items()
        for record in partition_records
    }
    assert len({locations[f"image-{index}"] for index in range(1, 5)}) == 1


def test_missing_augmentation_parent_fails_closed() -> None:
    child = _record(1, deck="deck-a", label="AC", parent="absent-parent")

    with pytest.raises(SplitError, match="missing augmentation parent"):
        create_splits([child], seed=20261014)


def test_test_partition_is_real_photo_only() -> None:
    split = create_splits(_fixture_records(), seed=20261014)

    assert split["test"]
    assert all(record["isRealPhoto"] is True for record in split["test"])


def test_final_holdout_role_is_reserved_for_test_and_training_roles_never_enter_it() -> None:
    training = _record(1, deck="deck-training", label="AC")
    training["taskRoles"] = ["DETECTOR_SCENE", "CLASSIFIER_CROP"]
    holdout = _record(2, deck="deck-holdout", label="2D")
    holdout["taskRoles"] = ["FINAL_SCENE_HOLDOUT"]

    split = create_splits([training, holdout], seed=20261014)

    assert [record["imageId"] for record in split["test"]] == ["image-2"]
    assert all(
        record["imageId"] != "image-2"
        for partition in ("train", "validation")
        for record in split[partition]
    )
    assert all(record["imageId"] != "image-1" for record in split["test"])


def test_split_rejects_a_duplicate_image_id_with_different_content() -> None:
    records = [_record(1, deck="deck-a", label="AC"), _record(2, deck="deck-b", label="2D")]
    records[1]["imageId"] = "image-1"

    with pytest.raises(SplitError, match="duplicate imageId"):
        create_splits(records, seed=1)


@pytest.mark.parametrize(
    "mutation, message",
    [
        (lambda record: record.update(path="../escape.jpg"), "safe relative path"),
        (lambda record: record.update(width=True), "positive integer"),
        (lambda record: record.update(imageSha256="not-a-hash"), "SHA-256"),
        (lambda record: record.update(isRealPhoto="yes"), "boolean"),
        (lambda record: record.update(boxes=[]), "no usable standard-card boxes"),
        (
            lambda record: record["boxes"][0].update(label="joker"),
            "Unsupported card label",
        ),
        (
            lambda record: record["boxes"][0].update(xmin=float("nan")),
            "finite numeric coordinates",
        ),
        (
            lambda record: record["boxes"][0].update(xmin=-1),
            "canonical in-bounds geometry",
        ),
        (
            lambda record: record["boxes"][0].update(xmax=1),
            "zero-area box",
        ),
    ],
)
def test_split_rejects_noncanonical_normalized_records(mutation: object, message: str) -> None:
    record = _record(1, deck="deck-a", label="AC")
    mutation(record)  # type: ignore[operator]

    with pytest.raises(SplitError, match=message):
        create_splits([record], seed=20261014)


def test_split_rejects_non_mapping_jsonl_entry() -> None:
    with pytest.raises(SplitError, match="record must be an object"):
        create_splits([None], seed=20261014)  # type: ignore[list-item]


def test_final_holdout_is_frozen_and_empty_audit_stays_truthfully_empty(tmp_path: Path) -> None:
    split_path = tmp_path / "splits.json"

    write_split_manifests([], out=split_path, seed=20261014)

    assert json.loads(split_path.read_text(encoding="utf-8")) == {
        "schemaVersion": 1,
        "seed": 20261014,
        "partitions": {"test": [], "train": [], "validation": []},
    }
    holdout = tmp_path / "final-holdout.json"
    holdout_document = json.loads(holdout.read_text(encoding="utf-8"))
    assert holdout_document == {
        "schemaVersion": 1,
        "kind": "pontinho-frozen-holdout",
        "seed": 20261014,
        "splitManifest": "splits.json",
        "splitManifestSha256": __import__("hashlib").sha256(split_path.read_bytes()).hexdigest(),
        "testPartitionSha256": __import__("hashlib").sha256(b"[]\n").hexdigest(),
        "test": [],
    }
    original = holdout.read_bytes()
    write_split_manifests([], out=split_path, seed=20261014)
    assert holdout.read_bytes() == original

    changed_records = [_record(index, deck=f"deck-{index}", label="AC") for index in range(1, 9)]
    assert create_splits(changed_records, seed=20261014)["test"]
    with pytest.raises(SplitError, match="frozen final holdout"):
        write_split_manifests(changed_records, out=split_path, seed=20261014)


def test_cli_reads_normalized_jsonl_and_writes_configured_seed(tmp_path: Path) -> None:
    normalized = tmp_path / "normalized"
    normalized.mkdir()
    (normalized / "records.jsonl").write_text("", encoding="utf-8")
    out = tmp_path / "splits.json"

    assert main(["--input", str(normalized), "--out", str(out), "--seed", "17"]) == 0
    assert json.loads(out.read_text(encoding="utf-8"))["seed"] == 17
