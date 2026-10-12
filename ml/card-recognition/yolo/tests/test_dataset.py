"""Contracts for the receipt-bound YOLO dataset and card class mapping."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest
import yaml
from ultralytics.data.utils import check_det_dataset

from pontinho_yolo.dataset import (
    DatasetValidationError,
    audit_dataset,
    write_dataset_config,
)
from pontinho_yolo.labels import CARD_LABELS, card_label_to_rank_suit
from pontinho_yolo.source import SourceReceipt

EXPECTED_PROVIDER_LABELS = (
    "10C",
    "10D",
    "10H",
    "10S",
    "2C",
    "2D",
    "2H",
    "2S",
    "3C",
    "3D",
    "3H",
    "3S",
    "4C",
    "4D",
    "4H",
    "4S",
    "5C",
    "5D",
    "5H",
    "5S",
    "6C",
    "6D",
    "6H",
    "6S",
    "7C",
    "7D",
    "7H",
    "7S",
    "8C",
    "8D",
    "8H",
    "8S",
    "9C",
    "9D",
    "9H",
    "9S",
    "AC",
    "AD",
    "AH",
    "AS",
    "JC",
    "JD",
    "JH",
    "JS",
    "KC",
    "KD",
    "KH",
    "KS",
    "QC",
    "QD",
    "QH",
    "QS",
)
REPOSITORY_ROOT = Path(__file__).resolve().parents[4]


def _write_metadata(root: Path, names: tuple[str, ...] = EXPECTED_PROVIDER_LABELS) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "data.yaml").write_text(
        yaml.safe_dump(
            {
                "train": "../train/images",
                "val": "../valid/images",
                "test": "../test/images",
                "nc": 52,
                "names": list(names),
                "roboflow": {
                    "workspace": "augmented-startups",
                    "project": "playing-cards-ow27d",
                    "version": 4,
                    "license": "Public Domain",
                },
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )


def _add_pair(
    root: Path,
    split: str,
    source: str,
    *,
    label: str = "0 0.5 0.5 0.25 0.5\n",
    image: bytes | None = None,
    augmentation: str = "0123456789abcdef0123456789abcdef",
) -> None:
    stem = f"{source}_jpg.rf.{augmentation}"
    image_path = root / split / "images" / f"{stem}.jpg"
    label_path = root / split / "labels" / f"{stem}.txt"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    label_path.parent.mkdir(parents=True, exist_ok=True)
    image_path.write_bytes(image if image is not None else f"{split}:{source}".encode())
    label_path.write_text(label, encoding="utf-8")


def _receipt(root: Path) -> SourceReceipt:
    members = tuple(
        path.relative_to(root).as_posix()
        for path in sorted(path for path in root.rglob("*") if path.is_file())
    )
    return SourceReceipt(
        source_url="https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4",
        version="4",
        format="yolov8",
        byte_length=123,
        sha256="a" * 64,
        members=members,
        retrieved_at="2026-10-16T00:00:00Z",
    )


def _valid_dataset(tmp_path: Path) -> tuple[Path, SourceReceipt]:
    root = tmp_path / "playing-cards-ow27d-v4"
    _write_metadata(root)
    _add_pair(root, "train", "train-one", label="0 0.5 0.5 0.25 0.5\n4 0.25 0.5 0.1 0.2\n")
    _add_pair(root, "valid", "valid-one", label="39 0.5 0.5 0.2 0.2\n")
    _add_pair(root, "test", "test-one", label="50 0.5 0.5 0.2 0.2\n")
    return root, _receipt(root)


def test_card_labels_bind_the_exact_provider_output_order_to_pontinho_cards() -> None:
    """A changed label order or ambiguous parser would silently relabel detector output."""
    assert CARD_LABELS == EXPECTED_PROVIDER_LABELS
    assert len(CARD_LABELS) == 52
    assert len(set(CARD_LABELS)) == 52
    assert card_label_to_rank_suit("10C") == ("TEN", "CLUBS")
    assert card_label_to_rank_suit("AS") == ("ACE", "SPADES")
    assert card_label_to_rank_suit("QH") == ("QUEEN", "HEARTS")


@pytest.mark.parametrize("label", ["joker", "JOKER", "1H", "ace_spades", "AH ", ""])
def test_unknown_or_noncanonical_card_labels_are_rejected(label: str) -> None:
    """Accepting aliases would make the model-to-domain mapping non-deterministic."""
    with pytest.raises(ValueError, match="unsupported card label"):
        card_label_to_rank_suit(label)


def test_audit_records_counts_hashes_buckets_and_same_split_duplicate_images(
    tmp_path: Path,
) -> None:
    """Dropping audit evidence would make a later training run impossible to reproduce."""
    root, _ = _valid_dataset(tmp_path)
    duplicate = b"same-image-content"
    _add_pair(root, "train", "duplicate-a", image=duplicate, augmentation="a" * 32)
    _add_pair(root, "train", "duplicate-b", image=duplicate, augmentation="b" * 32)

    audit = audit_dataset(root, _receipt(root))

    assert audit.receipt_sha256 == "a" * 64
    assert audit.tree_sha256 == hashlib.sha256(
        b"".join(
            relative.encode("utf-8") + b"\0" + bytes.fromhex(digest)
            for relative, digest in audit.file_hashes
        )
    ).hexdigest()
    assert audit.splits["train"].image_count == 3
    assert audit.splits["train"].box_count == 4
    assert audit.splits["train"].per_class_counts["10C"] == 3
    assert audit.splits["train"].per_class_counts["2C"] == 1
    assert audit.splits["train"].card_count_buckets == {1: 2, 2: 1}
    assert audit.splits["valid"].per_class_counts["AS"] == 1
    assert audit.splits["test"].per_class_counts["QH"] == 1
    assert audit.image_content_duplicate_groups == (
        (
            "train/images/duplicate-a_jpg.rf.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg",
            "train/images/duplicate-b_jpg.rf.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.jpg",
        ),
    )
    serialized = audit.to_dict()
    assert serialized["file_count"] == len(audit.file_hashes)
    assert "file_hashes" not in serialized


def test_normalized_config_points_at_verified_root_and_preserves_provider_splits(
    tmp_path: Path,
) -> None:
    """Changing split paths or copying images would break provenance and partition isolation."""
    root, receipt = _valid_dataset(tmp_path)
    audit_dataset(root, receipt)
    config = tmp_path / "config" / "dataset.yaml"

    write_dataset_config(root, config)

    payload = yaml.safe_load(config.read_text(encoding="utf-8"))
    assert payload == {
        "train": "../playing-cards-ow27d-v4/train/images",
        "val": "../playing-cards-ow27d-v4/valid/images",
        "test": "../playing-cards-ow27d-v4/test/images",
        "names": {index: label for index, label in enumerate(EXPECTED_PROVIDER_LABELS)},
    }
    assert not (config.parent / "train").exists()


def test_ultralytics_resolves_generated_config_from_repository_root(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A YAML accepted only by our parser but not pinned Ultralytics cannot start training."""
    root, receipt = _valid_dataset(tmp_path)
    audit_dataset(root, receipt)
    config = tmp_path / "config" / "dataset.yaml"
    write_dataset_config(root, config)

    monkeypatch.chdir(REPOSITORY_ROOT)
    invocation_path = Path(os.path.relpath(config, REPOSITORY_ROOT))
    resolved = check_det_dataset(invocation_path, autodownload=False)

    assert Path(resolved["train"]) == (root / "train" / "images").resolve()
    assert Path(resolved["val"]) == (root / "valid" / "images").resolve()
    assert Path(resolved["test"]) == (root / "test" / "images").resolve()
    assert tuple(resolved["names"].values()) == EXPECTED_PROVIDER_LABELS


def test_audit_rejects_reordered_provider_class_list(tmp_path: Path) -> None:
    """Trusting a reordered source list would attach the wrong card to every affected index."""
    root, _ = _valid_dataset(tmp_path)
    reordered = list(EXPECTED_PROVIDER_LABELS)
    reordered[0], reordered[1] = reordered[1], reordered[0]
    _write_metadata(root, tuple(reordered))

    with pytest.raises(DatasetValidationError, match="class order"):
        audit_dataset(root, _receipt(root))


@pytest.mark.parametrize(
    ("label", "message"),
    [
        ("52 0.5 0.5 0.2 0.2\n", "class index"),
        ("-1 0.5 0.5 0.2 0.2\n", "class index"),
        ("0 nan 0.5 0.2 0.2\n", "finite"),
        ("0 inf 0.5 0.2 0.2\n", "finite"),
        ("0 -0.1 0.5 0.2 0.2\n", "range"),
        ("0 0.5 1.1 0.2 0.2\n", "range"),
        ("0 0.5 0.5 0 0.2\n", "empty box"),
    ],
)
def test_audit_rejects_invalid_yolo_rows(tmp_path: Path, label: str, message: str) -> None:
    """Relaxing row validation would feed corrupt targets into training."""
    root, _ = _valid_dataset(tmp_path)
    target = next((root / "train" / "labels").iterdir())
    target.write_text(label, encoding="utf-8")

    with pytest.raises(DatasetValidationError, match=message):
        audit_dataset(root, _receipt(root))


def test_audit_rejects_missing_image_or_label_pair(tmp_path: Path) -> None:
    """Ignoring an unpaired member would silently change split membership."""
    root, _ = _valid_dataset(tmp_path)
    next((root / "valid" / "labels").iterdir()).unlink()

    with pytest.raises(DatasetValidationError, match="image/label pairs"):
        audit_dataset(root, _receipt(root))


def test_audit_rejects_empty_label_file(tmp_path: Path) -> None:
    """Accepting a zero-box label would violate the card-detection dataset contract."""
    root, _ = _valid_dataset(tmp_path)
    next((root / "test" / "labels").iterdir()).write_text("\n", encoding="utf-8")

    with pytest.raises(DatasetValidationError, match="empty label"):
        audit_dataset(root, _receipt(root))


def test_audit_rejects_source_identity_shared_across_splits(tmp_path: Path) -> None:
    """Allowing augmented siblings across splits would leak one physical source into evaluation."""
    root, _ = _valid_dataset(tmp_path)
    _add_pair(root, "valid", "train-one", augmentation="f" * 32)

    with pytest.raises(DatasetValidationError, match="source identity.*across splits"):
        audit_dataset(root, _receipt(root))


def test_audit_rejects_identical_image_content_across_splits(tmp_path: Path) -> None:
    """Renaming an exact duplicate must not bypass cross-split leakage checks."""
    root, _ = _valid_dataset(tmp_path)
    train_image = next((root / "train" / "images").iterdir()).read_bytes()
    _add_pair(root, "test", "renamed-copy", image=train_image, augmentation="e" * 32)

    with pytest.raises(DatasetValidationError, match="image content.*across splits"):
        audit_dataset(root, _receipt(root))


def test_audit_rejects_tree_that_does_not_match_receipt_inventory(tmp_path: Path) -> None:
    """An unreceipted file would mean the audit no longer describes the verified extraction."""
    root, receipt = _valid_dataset(tmp_path)
    (root / "unexpected.txt").write_text("not in receipt", encoding="utf-8")

    with pytest.raises(DatasetValidationError, match="receipt inventory"):
        audit_dataset(root, receipt)
