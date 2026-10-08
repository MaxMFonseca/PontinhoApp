"""Descriptor-rooted immutable input snapshots for release evidence."""

from __future__ import annotations

import os
import re
import secrets
import stat
from pathlib import Path, PurePosixPath

_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


class SecureReadError(OSError):
    """Raised when a path cannot be snapshotted without following links."""


def safe_name(value: object, *, field: str) -> str:
    """Return one bounded portable ASCII name, never a path or device spelling."""

    if (
        not isinstance(value, str)
        or not _SAFE_NAME.fullmatch(value)
        or value in {".", ".."}
        or value.endswith((".", " "))
    ):
        raise ValueError(f"{field} must be a 1-64 character ASCII safe name")
    return value


def safe_relative_posix(value: object, *, field: str) -> PurePosixPath:
    """Validate an archive/canonical member as a portable relative POSIX path."""

    if (
        not isinstance(value, str)
        or not value
        or "\\" in value
        or ":" in value
        or value.startswith("/")
        or value.endswith("/")
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise ValueError(f"{field} must be a safe relative path using POSIX separators")
    raw_parts = value.split("/")
    if any(part in {"", ".", ".."} or part.endswith((".", " ")) for part in raw_parts):
        raise ValueError(f"{field} must be a safe relative path using POSIX separators")
    return PurePosixPath(*raw_parts)


def _require_posix() -> None:
    if (
        os.name != "posix"
        or not all(value in os.supports_dir_fd for value in (os.open, os.mkdir))
        or not hasattr(os, "O_NOFOLLOW")
    ):
        raise SecureReadError("descriptor-rooted secure I/O requires POSIX openat/O_NOFOLLOW")


def _open_absolute_directory(path: Path, *, create: bool) -> tuple[int, list[int]]:
    _require_posix()
    absolute = Path(os.path.abspath(path))
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptors: list[int] = []
    current = os.open(Path(absolute.anchor), flags)
    descriptors.append(current)
    try:
        for component in absolute.parts[1:]:
            if create:
                try:
                    os.mkdir(component, mode=0o700, dir_fd=current)
                except FileExistsError:
                    pass
            current = os.open(component, flags, dir_fd=current)
            descriptors.append(current)
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise SecureReadError(f"secure directory component is not a directory: {path}")
        return current, descriptors
    except BaseException:
        for descriptor in reversed(descriptors):
            os.close(descriptor)
        raise


def secure_ensure_directory(path: Path) -> None:
    """Create/open an absolute directory without following any parent links."""

    try:
        _, descriptors = _open_absolute_directory(path, create=True)
    except OSError as error:
        raise SecureReadError(f"cannot create secure output root {path}: {error}") from error
    finally:
        for descriptor in reversed(locals().get("descriptors", [])):
            os.close(descriptor)


def secure_write_bytes(root: Path, relative: str | PurePosixPath, payload: bytes) -> None:
    """Atomically write bytes beneath a descriptor-held root without link traversal."""

    member = safe_relative_posix(str(relative), field="secure output path")
    descriptors: list[int] = []
    temporary = f".pontinho-{secrets.token_hex(12)}.tmp"
    created = False
    try:
        current, descriptors = _open_absolute_directory(root, create=True)
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        for component in member.parts[:-1]:
            try:
                os.mkdir(component, mode=0o700, dir_fd=current)
            except FileExistsError:
                pass
            current = os.open(component, flags, dir_fd=current)
            descriptors.append(current)
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=current,
        )
        created = True
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, member.name, src_dir_fd=current, dst_dir_fd=current)
        created = False
        os.fsync(current)
    except (OSError, ValueError) as error:
        raise SecureReadError(f"cannot write secure output beneath {root}: {error}") from error
    finally:
        if created and descriptors:
            try:
                os.unlink(temporary, dir_fd=descriptors[-1])
            except OSError:
                pass
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def secure_write_bytes_immutable(
    root: Path, relative: str | PurePosixPath, payload: bytes
) -> None:
    """Publish bytes once beneath a held root; never replace an existing name."""

    member = safe_relative_posix(str(relative), field="secure output path")
    descriptors: list[int] = []
    temporary = f".pontinho-{secrets.token_hex(12)}.tmp"
    created = False
    publication_directory: int | None = None
    try:
        current, descriptors = _open_absolute_directory(root, create=True)
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        for component in member.parts[:-1]:
            try:
                os.mkdir(component, mode=0o700, dir_fd=current)
            except FileExistsError:
                pass
            current = os.open(component, flags, dir_fd=current)
            descriptors.append(current)
        publication_directory = current
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=current,
        )
        created = True
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(
                temporary,
                member.name,
                src_dir_fd=current,
                dst_dir_fd=current,
                follow_symlinks=False,
            )
        except FileExistsError:
            existing_descriptor = os.open(
                member.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=current
            )
            descriptors.append(existing_descriptor)
            if not stat.S_ISREG(os.fstat(existing_descriptor).st_mode):
                raise SecureReadError("immutable destination is not a regular file")
            with os.fdopen(existing_descriptor, "rb", closefd=False) as handle:
                existing = handle.read()
            if existing != payload:
                raise SecureReadError("immutable destination content differs")
        else:
            os.fsync(current)
    except (OSError, ValueError) as error:
        raise SecureReadError(
            f"cannot publish secure immutable output beneath {root}: {error}"
        ) from error
    finally:
        if created and publication_directory is not None:
            try:
                os.unlink(temporary, dir_fd=publication_directory)
            except OSError:
                pass
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def secure_read_bytes(path: Path, *, root: Path | None = None) -> bytes:
    """Read one regular file through openat-held directories on POSIX only."""

    _require_posix()
    absolute = Path(os.path.abspath(path))
    trusted = Path(os.path.abspath(root if root is not None else absolute.parent))
    try:
        relative = absolute.relative_to(trusted)
    except ValueError as error:
        raise SecureReadError(f"path escapes trusted root: {path}") from error
    if not relative.parts or any(part in {"", ".", ".."} for part in relative.parts):
        raise SecureReadError(f"invalid secure input path: {path}")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptors: list[int] = []
    try:
        current = os.open(Path(trusted.anchor), directory_flags)
        descriptors.append(current)
        for component in trusted.parts[1:]:
            current = os.open(component, directory_flags, dir_fd=current)
            descriptors.append(current)
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise SecureReadError(f"trusted input parent is not a directory: {path}")
        for component in relative.parts[:-1]:
            current = os.open(component, directory_flags, dir_fd=current)
            descriptors.append(current)
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise SecureReadError(f"input parent is not a directory: {path}")
        descriptor = os.open(relative.parts[-1], os.O_RDONLY | os.O_NOFOLLOW, dir_fd=current)
        descriptors.append(descriptor)
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise SecureReadError(f"input is not a regular file: {path}")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    except OSError as error:
        raise SecureReadError(
            f"cannot securely snapshot {path}; symbolic links or replaced components "
            f"are rejected: {error}"
        ) from error
    finally:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass
