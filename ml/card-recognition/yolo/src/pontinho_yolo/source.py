"""Immutable registration and safe extraction for the Roboflow YOLOv8 export."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from typing import BinaryIO

SOURCE_URL = "https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4"
DATASET_VERSION = "4"
EXPORT_FORMAT = "yolov8"
DATASET_DIRECTORY = "playing-cards-ow27d-v4"
# v4 advertises 24,240 images. A YOLOv8 export has one label per image plus
# data.yaml (48,481 entries before optional metadata); 65,536 retains headroom.
MAX_MEMBERS = 65_536
# The downloaded v4 export expands to 2,158,709,054 bytes. Keep the ceiling
# bounded at 2.5 GB, allowing 341,290,946 bytes (15.8%) of format headroom.
MAX_EXPANDED_BYTES = 2_500_000_000


class SourceVerificationError(ValueError):
    """Raised when an archive differs from its receipt or is unsafe to extract."""


@dataclass(frozen=True)
class SourceReceipt:
    """The content-addressed provenance record for one downloaded source archive."""

    source_url: str
    version: str
    format: str
    byte_length: int
    sha256: str
    members: tuple[str, ...]
    retrieved_at: str

    @property
    def export_format(self) -> str:
        """Name the export format without shadowing Python's built-in in callers."""
        return self.format

    def to_dict(self) -> dict[str, object]:
        """Serialize a stable, human-reviewable JSON receipt."""
        payload = asdict(self)
        payload["members"] = list(self.members)
        return payload

    @classmethod
    def from_path(cls, path: Path) -> SourceReceipt:
        """Load and minimally validate a receipt written by :func:`register_archive`."""
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            members = payload["members"]
            if not isinstance(members, list) or not all(
                isinstance(member, str) for member in members
            ):
                raise TypeError("members must be a string list")
            return cls(
                source_url=_required_text(payload, "source_url"),
                version=_required_text(payload, "version"),
                format=_required_text(payload, "format"),
                byte_length=_required_int(payload, "byte_length"),
                sha256=_required_text(payload, "sha256"),
                members=tuple(members),
                retrieved_at=_required_text(payload, "retrieved_at"),
            )
        except (OSError, TypeError, ValueError, KeyError, json.JSONDecodeError) as error:
            raise SourceVerificationError(f"invalid source receipt {path}: {error}") from error


def register_archive(archive: Path, receipt: Path) -> SourceReceipt:
    """Hash and inventory a manually downloaded archive without extracting it."""
    archive = Path(archive)
    if not archive.is_file():
        raise SourceVerificationError(f"source archive does not exist: {archive}")

    try:
        with archive.open("rb") as archive_stream:
            byte_length = os.fstat(archive_stream.fileno()).st_size
            with zipfile.ZipFile(archive_stream) as zip_file:
                members = tuple(info.filename for info in zip_file.infolist())
            sha256 = _sha256(archive_stream)
    except (OSError, zipfile.BadZipFile) as error:
        raise SourceVerificationError(f"source archive is not a ZIP file: {archive}") from error

    receipt_value = SourceReceipt(
        source_url=SOURCE_URL,
        version=DATASET_VERSION,
        format=EXPORT_FORMAT,
        byte_length=byte_length,
        sha256=sha256,
        members=members,
        retrieved_at=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    )
    receipt = Path(receipt)
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(
        json.dumps(receipt_value.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return receipt_value


def verify_and_extract(archive: Path, receipt: SourceReceipt, output: Path) -> Path:
    """Verify one open archive and publish its staged regular files.

    Same-volume directory renames are atomic individually, but Windows exposes
    no portable atomic exchange for nonempty directories. An existing output is
    therefore journaled and rolled back for every caught interruption; a power
    loss can leave a brief missing-name interval, which the next invocation
    repairs from the durable journal before it verifies a new archive.
    """
    archive = Path(archive)
    output = Path(output)
    _validate_output_path(archive, output)
    _recover_interrupted_publication(output)

    try:
        with archive.open("rb") as archive_stream:
            _verify_archive_identity(archive_stream, receipt)
            with zipfile.ZipFile(archive_stream) as zip_file:
                infos = zip_file.infolist()
                _validate_members(infos)
                if tuple(info.filename for info in infos) != receipt.members:
                    raise SourceVerificationError(
                        "archive member inventory differs from source receipt"
                    )
                return _extract_staged(zip_file, infos, output)
    except (OSError, zipfile.BadZipFile) as error:
        raise SourceVerificationError(f"source archive is not a ZIP file: {archive}") from error


def _required_text(payload: object, key: str) -> str:
    if not isinstance(payload, dict) or not isinstance(payload.get(key), str) or not payload[key]:
        raise TypeError(f"{key} must be a nonempty string")
    return payload[key]


def _required_int(payload: object, key: str) -> int:
    if not isinstance(payload, dict) or isinstance(payload.get(key), bool):
        raise TypeError(f"{key} must be an integer")
    value = payload.get(key)
    if not isinstance(value, int) or value < 0:
        raise TypeError(f"{key} must be a nonnegative integer")
    return value


def _sha256(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    stream.seek(0)
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    stream.seek(0)
    return digest.hexdigest()


def _validate_output_path(archive: Path, output: Path) -> None:
    expected = archive.parent.parent / "extracted" / DATASET_DIRECTORY
    if archive.parent.name != "source-archives" or output.resolve() != expected.resolve():
        raise SourceVerificationError(
            "output must be datasets/extracted/playing-cards-ow27d-v4 next to source-archives"
        )


def _verify_archive_identity(archive_stream: BinaryIO, receipt: SourceReceipt) -> None:
    if os.fstat(archive_stream.fileno()).st_size != receipt.byte_length:
        raise SourceVerificationError("archive byte length differs from source receipt")
    if _sha256(archive_stream) != receipt.sha256:
        raise SourceVerificationError("archive SHA-256 differs from source receipt")


def _validate_members(infos: list[zipfile.ZipInfo]) -> None:
    if len(infos) > MAX_MEMBERS:
        raise SourceVerificationError("archive exceeds member-count ceiling")

    expanded_bytes = 0
    normalized_names: set[str] = set()
    for info in infos:
        normalized = _safe_member_name(info)
        if normalized in normalized_names:
            raise SourceVerificationError(f"duplicate member: {info.filename}")
        normalized_names.add(normalized)
        if _is_safe_directory(info):
            continue
        if not _is_regular_file(info):
            raise SourceVerificationError(f"non-regular archive member: {info.filename}")
        if info.file_size < 0 or info.file_size > MAX_EXPANDED_BYTES - expanded_bytes:
            raise SourceVerificationError("archive exceeds expanded-byte ceiling")
        expanded_bytes += info.file_size


def _safe_member_name(info: zipfile.ZipInfo) -> str:
    name = info.filename
    path = PurePosixPath(name)
    if (
        not name
        or "\\" in name
        or name.startswith("/")
        or path.is_absolute()
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
    ):
        raise SourceVerificationError(f"unsafe member path: {name}")
    return "/".join(path.parts).casefold()


def _is_regular_file(info: zipfile.ZipInfo) -> bool:
    if info.is_dir() or info.filename.endswith("/"):
        return False
    unix_type = (info.external_attr >> 16) & 0o170000
    return unix_type in {0, stat.S_IFREG}


def _is_safe_directory(info: zipfile.ZipInfo) -> bool:
    """Allow empty directory records but materialize data from regular files only."""
    unix_type = (info.external_attr >> 16) & 0o170000
    return info.is_dir() and info.file_size == 0 and unix_type in {0, stat.S_IFDIR}


def _extract_staged(zip_file: zipfile.ZipFile, infos: list[zipfile.ZipInfo], output: Path) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{DATASET_DIRECTORY}-", dir=output.parent))
    backup = Path(tempfile.mkdtemp(prefix=f".{DATASET_DIRECTORY}-previous-", dir=output.parent))
    backup.rmdir()
    try:
        for info in infos:
            if _is_safe_directory(info):
                continue
            member = PurePosixPath(info.filename)
            destination = staging.joinpath(*member.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with zip_file.open(info, "r") as source, destination.open("xb") as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
            if destination.stat().st_size != info.file_size:
                raise SourceVerificationError(f"extracted member size mismatch: {info.filename}")

        if output.exists():
            _write_transaction(output, backup)
            os.replace(output, backup)
        os.replace(staging, output)
        _remove_owned_path(backup)
        _remove_owned_path(_transaction_path(output))
        return output
    except BaseException:
        _recover_interrupted_publication(output)
        raise
    finally:
        _remove_owned_path(staging)


def _transaction_path(output: Path) -> Path:
    return output.parent / f".{DATASET_DIRECTORY}-transaction.json"


def _write_transaction(output: Path, backup: Path) -> None:
    transaction = _transaction_path(output)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{DATASET_DIRECTORY}-transaction-", suffix=".json", dir=output.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(file_descriptor, "w", encoding="utf-8") as stream:
            json.dump({"backup": backup.name}, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, transaction)
    finally:
        _remove_owned_path(temporary)


def _recover_interrupted_publication(output: Path) -> None:
    transaction = _transaction_path(output)
    if not transaction.exists():
        return
    try:
        payload = json.loads(transaction.read_text(encoding="utf-8"))
        backup_name = payload["backup"]
        if (
            not isinstance(backup_name, str)
            or Path(backup_name).name != backup_name
            or not backup_name.startswith(f".{DATASET_DIRECTORY}-previous-")
        ):
            raise ValueError("unsafe backup path")
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SourceVerificationError(
            f"invalid extraction transaction {transaction}: {error}"
        ) from error

    backup = output.parent / backup_name
    if not output.exists() and backup.exists():
        os.replace(backup, output)
    elif output.exists() and backup.exists():
        _remove_owned_path(backup)
    elif not output.exists():
        raise SourceVerificationError(
            "interrupted extraction lost both published and backup output"
        )
    _remove_owned_path(transaction)


def _remove_owned_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.exists():
        shutil.rmtree(path)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("register", "verify"):
        subparser = commands.add_parser(command)
        subparser.add_argument("--archive", required=True, type=Path)
        subparser.add_argument("--receipt", required=True, type=Path)
        if command == "verify":
            subparser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    """Run receipt registration or secure verification from the command line."""
    args = _parse_args()
    if args.command == "register":
        receipt = register_archive(args.archive, args.receipt)
        print(json.dumps(receipt.to_dict(), indent=2, sort_keys=True))
        return
    receipt = SourceReceipt.from_path(args.receipt)
    print(verify_and_extract(args.archive, receipt, args.output))


if __name__ == "__main__":
    main()
