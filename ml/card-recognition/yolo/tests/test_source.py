"""Tests for immutable, safely extracted Roboflow source archives."""

from __future__ import annotations

import hashlib
import json
import stat
import warnings
import zipfile
from datetime import datetime
from pathlib import Path

import pytest

from pontinho_yolo import source
from pontinho_yolo.source import (
    SourceReceipt,
    SourceVerificationError,
    register_archive,
    verify_and_extract,
)


def _archive_path(tmp_path: Path, name: str = "fixture.zip") -> Path:
    path = tmp_path / "datasets" / "source-archives" / name
    path.parent.mkdir(parents=True)
    return path


def _output_path(tmp_path: Path) -> Path:
    return tmp_path / "datasets" / "extracted" / "playing-cards-ow27d-v4"


def _write_zip(path: Path, members: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in members.items():
            archive.writestr(name, content)


def _receipt_for(archive: Path, tmp_path: Path) -> SourceReceipt:
    receipt_path = tmp_path / "receipt.json"
    return register_archive(archive, receipt_path)


def test_register_archive_records_immutable_receipt(tmp_path: Path) -> None:
    """A missing content hash, member inventory, or provenance must fail this contract."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"names: [ace]", "train/images/card.jpg": b"image"})
    receipt_path = tmp_path / "receipt.json"

    receipt = register_archive(archive, receipt_path)

    assert receipt.source_url == source.SOURCE_URL
    assert receipt.version == "4"
    assert receipt.export_format == "yolov8"
    assert receipt.byte_length == archive.stat().st_size
    assert receipt.sha256 == hashlib.sha256(archive.read_bytes()).hexdigest()
    assert receipt.members == ("data.yaml", "train/images/card.jpg")
    assert datetime.fromisoformat(receipt.retrieved_at.replace("Z", "+00:00")).tzinfo is not None
    assert json.loads(receipt_path.read_text(encoding="utf-8"))["sha256"] == receipt.sha256


def test_verify_and_extract_rejects_changed_archive_bytes(tmp_path: Path) -> None:
    """Removing archive digest verification would publish bytes other than the receipt binds."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"names: [ace]"})
    receipt = _receipt_for(archive, tmp_path)
    archive.write_bytes(archive.read_bytes() + b"changed")

    with pytest.raises(SourceVerificationError, match="differs from source receipt"):
        verify_and_extract(archive, receipt, _output_path(tmp_path))


def test_verify_and_extract_rejects_path_traversal(tmp_path: Path) -> None:
    """Removing member path checks would allow a ZIP to write outside the dataset root."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"../outside.txt": b"bad"})

    with pytest.raises(SourceVerificationError, match="unsafe member path"):
        verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))


def test_verify_and_extract_rejects_duplicate_member(tmp_path: Path) -> None:
    """Removing duplicate checks would let later ZIP entries silently replace earlier ones."""
    archive = _archive_path(tmp_path)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(archive, "w") as zip_file:
            zip_file.writestr("data.yaml", b"first")
            zip_file.writestr("data.yaml", b"second")

    with pytest.raises(SourceVerificationError, match="duplicate member"):
        verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))


def test_verify_and_extract_rejects_symlink_member(tmp_path: Path) -> None:
    """Removing file-type checks would permit links that escape the extraction root."""
    archive = _archive_path(tmp_path)
    info = zipfile.ZipInfo("linked-data")
    info.create_system = 3
    info.external_attr = (stat.S_IFLNK | 0o777) << 16
    with zipfile.ZipFile(archive, "w") as zip_file:
        zip_file.writestr(info, b"../outside.txt")

    with pytest.raises(SourceVerificationError, match="non-regular"):
        verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))


def test_verify_and_extract_rejects_archive_exceeding_expanded_byte_ceiling(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Removing expanded-size accounting would permit ZIP-bomb expansion."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"large.bin": b"x" * 11})
    monkeypatch.setattr(source, "MAX_EXPANDED_BYTES", 10)

    with pytest.raises(SourceVerificationError, match="expanded-byte ceiling"):
        verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))


def test_verify_and_extract_stages_then_publishes_only_verified_files(tmp_path: Path) -> None:
    """Removing staged publication would expose a partial extraction after an interrupted write."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"names: [ace]", "valid/images/card.jpg": b"image"})

    output = verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))

    assert output == _output_path(tmp_path)
    assert (output / "data.yaml").read_bytes() == b"names: [ace]"
    assert (output / "valid/images/card.jpg").read_bytes() == b"image"


def test_verify_and_extract_ignores_safe_directory_records(tmp_path: Path) -> None:
    """Rejecting safe ZIP directory records would reject the real v4 export before extraction."""
    archive = _archive_path(tmp_path)
    with zipfile.ZipFile(archive, "w") as zip_file:
        zip_file.writestr("train/", b"")
        zip_file.writestr("train/images/", b"")
        zip_file.writestr("train/images/card.jpg", b"image")

    output = verify_and_extract(archive, _receipt_for(archive, tmp_path), _output_path(tmp_path))

    assert (output / "train/images/card.jpg").read_bytes() == b"image"


def test_member_ceiling_accepts_the_expected_v4_image_and_label_inventory() -> None:
    """A ceiling below the 24,240 images plus labels would reject the real v4 export."""
    image_count = 24_240
    infos = [
        *(zipfile.ZipInfo(f"train/images/{index:05d}.jpg") for index in range(image_count)),
        *(zipfile.ZipInfo(f"train/labels/{index:05d}.txt") for index in range(image_count)),
        zipfile.ZipInfo("data.yaml"),
    ]

    source._validate_members(infos)


def test_expanded_byte_ceiling_accepts_measured_v4_export_without_large_fixture() -> None:
    """A ceiling below v4's measured 2,158,709,054 bytes would reject its real export."""
    info = zipfile.ZipInfo("train/images/representative.jpg")
    info.file_size = 2_158_709_054

    source._validate_members([info])


def test_verify_and_extract_uses_verified_open_archive_after_path_replacement(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Reopening a verified path would extract replacement bytes with the original receipt."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"trusted"})
    receipt = _receipt_for(archive, tmp_path)
    replacement = tmp_path / "replacement.zip"
    _write_zip(replacement, {"data.yaml": b"attacker-controlled"})
    original_zip_file = source.zipfile.ZipFile

    def replacement_on_reopened_path(
        value: object, *args: object, **kwargs: object
    ) -> zipfile.ZipFile:
        if value == archive:
            return original_zip_file(replacement, *args, **kwargs)
        return original_zip_file(value, *args, **kwargs)

    monkeypatch.setattr(source.zipfile, "ZipFile", replacement_on_reopened_path)

    output = verify_and_extract(archive, receipt, _output_path(tmp_path))

    assert (output / "data.yaml").read_bytes() == b"trusted"


def test_verify_and_extract_restores_existing_output_after_interrupted_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Failing to roll back an interrupted replacement would leave readers without a root."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"new"})
    receipt = _receipt_for(archive, tmp_path)
    output = _output_path(tmp_path)
    output.mkdir(parents=True)
    (output / "data.yaml").write_bytes(b"old")
    original_replace = source.os.replace

    def interrupt_staging_publish(old: str | Path, new: str | Path) -> None:
        old_path = Path(old)
        if (
            Path(new) == output
            and old_path.name.startswith(f".{source.DATASET_DIRECTORY}-")
            and "-previous-" not in old_path.name
        ):
            raise KeyboardInterrupt
        original_replace(old, new)

    monkeypatch.setattr(source.os, "replace", interrupt_staging_publish)

    with pytest.raises(KeyboardInterrupt):
        verify_and_extract(archive, receipt, output)

    assert output.is_dir()
    assert (output / "data.yaml").read_bytes() == b"old"


def test_verify_and_extract_recovers_journaled_output_before_replacement(tmp_path: Path) -> None:
    """Ignoring a crashed publication journal would orphan the only prior output tree."""
    archive = _archive_path(tmp_path)
    _write_zip(archive, {"data.yaml": b"new"})
    receipt = _receipt_for(archive, tmp_path)
    output = _output_path(tmp_path)
    output.mkdir(parents=True)
    (output / "data.yaml").write_bytes(b"old")
    backup = output.parent / f".{source.DATASET_DIRECTORY}-previous-crashed"
    output.replace(backup)
    transaction = output.parent / f".{source.DATASET_DIRECTORY}-transaction.json"
    transaction.write_text(json.dumps({"backup": backup.name}), encoding="utf-8")

    verify_and_extract(archive, receipt, output)

    assert (output / "data.yaml").read_bytes() == b"new"
    assert not backup.exists()
    assert not transaction.exists()
