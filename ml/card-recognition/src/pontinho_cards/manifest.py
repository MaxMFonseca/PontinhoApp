"""Fail-closed parsing for license-reviewed card dataset sources."""

from __future__ import annotations

import hashlib
import hmac
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from pontinho_cards.labels import CARD_LABELS, parse_card_label
from pontinho_cards.secure_io import safe_name, safe_relative_posix

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_GIT_COMMIT_RE = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
_PLATFORM_VERSION_RE = re.compile(r"^[1-9][0-9]*$")
_PLATFORM_SLUG_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_PUBLIC_DOMAIN_LICENSES = frozenset({"CC0-1.0", "PUBLIC-DOMAIN-MARK-1.0"})
_ANNOTATION_STATUSES = frozenset({"BOUNDING_BOXES", "CLASS_LABELS", "UNANNOTATED"})
_GENERIC_LICENSE_HOSTS = frozenset(
    {"creativecommons.org", "spdx.org", "choosealicense.com", "opensource.org"}
)
_LEGAL_TOOL_URLS = {
    "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
    "PUBLIC-DOMAIN-MARK-1.0": "https://creativecommons.org/publicdomain/mark/1.0/",
}


class ManifestError(ValueError):
    """Raised when source evidence does not satisfy the approved policy."""


@dataclass(frozen=True)
class LicenseEvidence:
    kind: str
    evidence_url: str
    evidence_text: str
    legal_tool_url: str
    reviewed_evidence_host: str | None


@dataclass(frozen=True)
class SourceRevision:
    kind: str
    repository_url: str | None = None
    commit_sha: str | None = None
    provider: str | None = None
    dataset_id: str | None = None
    version_id: str | None = None


@dataclass(frozen=True)
class SourceCapabilities:
    real_scenes: bool
    isolated_cards: bool
    annotations: bool
    distinct_physical_deck_designs: int


@dataclass(frozen=True)
class SourceAdapter:
    kind: str
    annotation_member: str | None
    image_prefix: str
    label_map: Mapping[str, str]


@dataclass(frozen=True)
class DatasetSource:
    source_id: str
    title: str
    publisher: str
    source_url: str
    archive_url: str
    retrieved_at: str
    source_revision: SourceRevision
    archive_sha256: str
    archive_local_path: str
    adapter: SourceAdapter
    license: LicenseEvidence
    annotation_status: str
    attribution: str
    deck_group_id: str
    labels: tuple[str, ...]
    provides: SourceCapabilities
    usable_photo_count: int
    holdout_eligible_photo_count: int
    public_domain_approved: bool
    approval_note: str


@dataclass(frozen=True)
class SourceManifest:
    schema_version: int
    sources: tuple[DatasetSource, ...]

    @property
    def holdout_photo_count(self) -> int:
        return sum(source.holdout_eligible_photo_count for source in self.sources)

    @property
    def holdout_deck_group_count(self) -> int:
        return len(
            {
                source.deck_group_id
                for source in self.sources
                if source.holdout_eligible_photo_count > 0
            }
        )


def _object(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise ManifestError(f"{path} is required and must be an object")
    return value


def _required(mapping: Mapping[str, Any], key: str, path: str) -> Any:
    if key not in mapping:
        raise ManifestError(f"{path}.{key} is required")
    return mapping[key]


def _text(mapping: Mapping[str, Any], key: str, path: str) -> str:
    value = _required(mapping, key, path)
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{path}.{key} is required and must be non-empty text")
    return value


def _safe_manifest_name(mapping: Mapping[str, Any], key: str, path: str) -> str:
    try:
        return safe_name(_required(mapping, key, path), field=f"{path}.{key}")
    except ValueError as error:
        raise ManifestError(str(error)) from error


def _url(mapping: Mapping[str, Any], key: str, path: str) -> str:
    value = _text(mapping, key, path)
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ManifestError(f"{path}.{key} is required to be an HTTP(S) URL")
    return value


def _bool(mapping: Mapping[str, Any], key: str, path: str) -> bool:
    value = _required(mapping, key, path)
    if type(value) is not bool:
        raise ManifestError(f"{path}.{key} is required and must be a boolean")
    return value


def _nonnegative_int(mapping: Mapping[str, Any], key: str, path: str) -> int:
    value = _required(mapping, key, path)
    if type(value) is not int or value < 0:
        raise ManifestError(f"{path}.{key} is required and must be a non-negative integer")
    return value


def _normalize_hostname(hostname: str, path: str) -> str:
    candidate = hostname.strip().rstrip(".")
    if not candidate or any(character in candidate for character in "/:@"):
        raise ManifestError(f"{path} must be a hostname without a scheme, path, or port")
    try:
        return candidate.encode("idna").decode("ascii").casefold()
    except UnicodeError as error:
        raise ManifestError(f"{path} is not a valid IDNA hostname") from error


def _url_hostname(url: str, path: str) -> str:
    hostname = urlparse(url).hostname
    if hostname is None:
        raise ManifestError(f"{path} must contain a hostname")
    return _normalize_hostname(hostname, path)


def _canonical_platform_dataset_id(provider: str, source_url: str, path: str) -> str:
    source = urlparse(source_url)
    source_host = _url_hostname(source_url, f"{path}.sourceUrl")
    raw_path = source.path.strip("/")
    segments = raw_path.split("/") if raw_path else []

    if provider == "kaggle":
        valid = (
            source_host in {"kaggle.com", "www.kaggle.com"}
            and len(segments) == 3
            and segments[0] == "datasets"
            and all(_PLATFORM_SLUG_RE.fullmatch(segment) for segment in segments[1:])
        )
        if not valid:
            raise ManifestError(
                f"{path} must use the source-specific canonical Kaggle "
                "/datasets/{owner}/{slug} route"
            )
        return f"{segments[1]}/{segments[2]}"

    if provider == "roboflow":
        valid = (
            source_host == "universe.roboflow.com"
            and len(segments) == 2
            and all(_PLATFORM_SLUG_RE.fullmatch(segment) for segment in segments)
        )
        if not valid:
            raise ManifestError(
                f"{path} must use the source-specific canonical Roboflow "
                "/{workspace}/{project} route"
            )
        return f"{segments[0]}/{segments[1]}"

    raise ManifestError(f"{path}.provider must be the supported source-specific platform")


def _parse_revision(raw: Any, path: str, source_url: str) -> SourceRevision:
    if not isinstance(raw, dict):
        raise ManifestError(f"{path} must be a typed immutable revision object")
    kind = _text(raw, "kind", path)

    if kind == "GIT_COMMIT":
        repository_url = _url(raw, "repositoryUrl", path)
        commit_sha = _text(raw, "commitSha", path)
        if repository_url.rstrip("/") != source_url.rstrip("/"):
            raise ManifestError(f"{path}.repositoryUrl must be source-specific and match sourceUrl")
        if not _GIT_COMMIT_RE.fullmatch(commit_sha):
            raise ManifestError(f"{path}.commitSha must be a full lowercase Git commit SHA")
        return SourceRevision(
            kind=kind,
            repository_url=repository_url,
            commit_sha=commit_sha,
        )

    if kind == "PLATFORM_DATASET_VERSION":
        provider = _text(raw, "provider", path).casefold()
        dataset_id = _text(raw, "datasetId", path)
        version_id = _text(raw, "versionId", path)
        canonical_dataset_id = _canonical_platform_dataset_id(provider, source_url, path)
        if dataset_id != canonical_dataset_id:
            platform_name = "Kaggle" if provider == "kaggle" else "Roboflow"
            raise ManifestError(
                f"{path}.datasetId must exactly match the canonical {platform_name} "
                f"identifier {canonical_dataset_id!r}"
            )
        if not _PLATFORM_VERSION_RE.fullmatch(version_id):
            raise ManifestError(f"{path}.versionId must be an immutable versionId")
        return SourceRevision(
            kind=kind,
            provider=provider,
            dataset_id=dataset_id,
            version_id=version_id,
        )

    raise ManifestError(f"{path}.kind must be GIT_COMMIT or PLATFORM_DATASET_VERSION, not {kind!r}")


def _parse_source(raw: Any, index: int) -> DatasetSource:
    path = f"sources[{index}]"
    source = _object(raw, path)

    try:
        source_id = safe_name(_required(source, "id", path), field=f"{path}.id")
    except ValueError as error:
        raise ManifestError(str(error)) from error
    title = _text(source, "title", path)
    publisher = _text(source, "publisher", path)
    source_url = _url(source, "sourceUrl", path)
    revision = _parse_revision(
        _required(source, "sourceRevision", path), f"{path}.sourceRevision", source_url
    )

    archive_sha256 = _required(source, "archiveSha256", path)
    if not isinstance(archive_sha256, str) or not _SHA256_RE.fullmatch(archive_sha256):
        raise ManifestError(f"{path}.archiveSha256 must be a lowercase 64-character SHA-256")
    archive_input = _object(_required(source, "archiveInput", path), f"{path}.archiveInput")
    try:
        archive_local_path = safe_name(
            _required(archive_input, "localPath", f"{path}.archiveInput"),
            field=f"{path}.archiveInput.localPath safe filename",
        )
    except ValueError as error:
        raise ManifestError(str(error)) from error

    adapter_raw = _object(_required(source, "adapter", path), f"{path}.adapter")
    adapter_kind = _text(adapter_raw, "kind", f"{path}.adapter")
    if adapter_kind not in {"COCO_JSON_SCENES", "ISOLATED_CARD_FOLDERS"}:
        raise ManifestError(f"{path}.adapter.kind is unsupported")
    try:
        image_prefix = safe_relative_posix(
            _required(adapter_raw, "imagePrefix", f"{path}.adapter"),
            field=f"{path}.adapter.imagePrefix",
        ).as_posix()
    except ValueError as error:
        raise ManifestError(str(error)) from error
    annotation_member_raw = adapter_raw.get("annotationMember")
    if adapter_kind == "COCO_JSON_SCENES":
        if not isinstance(annotation_member_raw, str) or not annotation_member_raw:
            raise ManifestError(f"{path}.adapter.annotationMember is required")
        try:
            annotation_member = safe_relative_posix(
                annotation_member_raw, field=f"{path}.adapter.annotationMember"
            ).as_posix()
        except ValueError as error:
            raise ManifestError(str(error)) from error
    else:
        if annotation_member_raw is not None:
            raise ManifestError(f"{path}.adapter.annotationMember is unsupported")
        annotation_member = None
    label_map_raw = _object(
        _required(adapter_raw, "labelMap", f"{path}.adapter"), f"{path}.adapter.labelMap"
    )
    label_map: dict[str, str] = {}
    for source_label, canonical in label_map_raw.items():
        if (
            not isinstance(source_label, str)
            or not source_label.strip()
            or not isinstance(canonical, str)
        ):
            raise ManifestError(f"{path}.adapter.labelMap entries must be non-empty text")
        if canonical not in CARD_LABELS:
            raise ManifestError(f"{path}.adapter.labelMap contains unsupported canonical label")
        label_map[source_label] = canonical
    if not label_map:
        raise ManifestError(f"{path}.adapter.labelMap must not be empty")

    license_path = f"{path}.license"
    license_raw = _object(_required(source, "license", path), license_path)
    license_kind = _text(license_raw, "kind", license_path)
    if license_kind not in _PUBLIC_DOMAIN_LICENSES:
        raise ManifestError(
            f"{license_path}.kind must be CC0 or public-domain; got {license_kind!r}"
        )
    evidence_url = _url(license_raw, "evidenceUrl", license_path)
    evidence_text = _text(license_raw, "evidenceText", license_path)
    legal_tool_url = _url(license_raw, "legalToolUrl", license_path)
    evidence_host = _url_hostname(evidence_url, f"{license_path}.evidenceUrl")
    source_host = _url_hostname(source_url, f"{path}.sourceUrl")
    if any(
        evidence_host == host or evidence_host.endswith(f".{host}")
        for host in _GENERIC_LICENSE_HOSTS
    ):
        raise ManifestError(
            f"{license_path}.evidenceUrl must be source-specific, not a generic license deed"
        )
    reviewed_host_raw = license_raw.get("reviewedEvidenceHost")
    reviewed_host = None
    if evidence_host != source_host:
        if reviewed_host_raw is None:
            raise ManifestError(
                f"{license_path}.reviewedEvidenceHost is required when evidenceUrl uses "
                "a host other than sourceUrl"
            )
        if not isinstance(reviewed_host_raw, str):
            raise ManifestError(f"{license_path}.reviewedEvidenceHost must be a hostname")
        reviewed_host = _normalize_hostname(
            reviewed_host_raw, f"{license_path}.reviewedEvidenceHost"
        )
        if reviewed_host != evidence_host:
            raise ManifestError(
                f"{license_path}.reviewedEvidenceHost must exactly match the evidenceUrl host"
            )
    elif reviewed_host_raw is not None:
        if not isinstance(reviewed_host_raw, str):
            raise ManifestError(f"{license_path}.reviewedEvidenceHost must be a hostname")
        reviewed_host = _normalize_hostname(
            reviewed_host_raw, f"{license_path}.reviewedEvidenceHost"
        )
        if reviewed_host != evidence_host:
            raise ManifestError(
                f"{license_path}.reviewedEvidenceHost must exactly match the evidenceUrl host"
            )
    if title.casefold() not in evidence_text.casefold() and publisher.casefold() not in (
        evidence_text.casefold()
    ):
        raise ManifestError(f"{license_path}.evidenceText must identify the source or publisher")
    if legal_tool_url != _LEGAL_TOOL_URLS[license_kind]:
        raise ManifestError(
            f"{license_path}.legalToolUrl must equal {_LEGAL_TOOL_URLS[license_kind]!r}"
        )
    if license_kind == "CC0-1.0" and "cc0" not in evidence_text.casefold():
        raise ManifestError(f"{license_path}.evidenceText must state the CC0 dedication")
    if license_kind == "PUBLIC-DOMAIN-MARK-1.0" and "public domain" not in evidence_text.casefold():
        raise ManifestError(f"{license_path}.evidenceText must state the public-domain mark")

    approved = _required(source, "publicDomainApproved", path)
    if approved is not True:
        raise ManifestError(f"{path}.publicDomainApproved is required and must be literal true")
    approval_note = _text(source, "approvalNote", path)
    if evidence_url not in approval_note:
        raise ManifestError(f"{path}.approvalNote must point to the reviewed license evidence URL")

    annotation_status = _text(source, "annotationStatus", path)
    if annotation_status not in _ANNOTATION_STATUSES:
        raise ManifestError(
            f"{path}.annotationStatus must be one of {sorted(_ANNOTATION_STATUSES)}"
        )

    labels_raw = _required(source, "labels", path)
    if not isinstance(labels_raw, list):
        raise ManifestError(f"{path}.labels is required and must be a list")
    labels = []
    for label in labels_raw:
        try:
            parse_card_label(label)
        except ValueError as error:
            raise ManifestError(str(error)) from error
        labels.append(label)
    if len(labels) != len(set(labels)):
        raise ManifestError(f"{path}.labels must not contain duplicates")

    provides_path = f"{path}.provides"
    provides_raw = _object(_required(source, "provides", path), provides_path)
    deck_design_count = _nonnegative_int(provides_raw, "distinctPhysicalDeckDesigns", provides_path)
    if deck_design_count < 1:
        raise ManifestError(f"{provides_path}.distinctPhysicalDeckDesigns must be at least one")
    provides = SourceCapabilities(
        real_scenes=_bool(provides_raw, "realScenes", provides_path),
        isolated_cards=_bool(provides_raw, "isolatedCards", provides_path),
        annotations=_bool(provides_raw, "annotations", provides_path),
        distinct_physical_deck_designs=deck_design_count,
    )

    usable_count = _nonnegative_int(source, "usablePhotoCount", path)
    holdout_count = _nonnegative_int(source, "holdoutEligiblePhotoCount", path)
    annotated = annotation_status != "UNANNOTATED"
    if provides.annotations != annotated:
        raise ManifestError(
            f"{provides_path}.annotations contradicts annotationStatus={annotation_status}"
        )
    if annotated and not labels:
        raise ManifestError(f"{path}.labels must not be empty for annotated content")
    if not annotated and labels:
        raise ManifestError(f"{path}.labels must be empty for UNANNOTATED content")
    if usable_count and not (provides.real_scenes or provides.isolated_cards):
        raise ManifestError(
            f"{path}.usablePhotoCount requires realScenes=true or isolatedCards=true"
        )
    if holdout_count > usable_count:
        raise ManifestError(f"{path}.holdoutEligiblePhotoCount cannot exceed usablePhotoCount")
    if holdout_count and not provides.real_scenes:
        raise ManifestError(f"{path}.holdoutEligiblePhotoCount requires realScenes=true")
    if holdout_count and annotation_status != "BOUNDING_BOXES":
        raise ManifestError(
            f"{path}.holdoutEligiblePhotoCount requires evaluation-ready bounding boxes"
        )
    if set(label_map.values()) != set(labels):
        raise ManifestError(f"{path}.adapter.labelMap must map exactly the declared labels")
    if adapter_kind == "COCO_JSON_SCENES" and annotation_status != "BOUNDING_BOXES":
        raise ManifestError(f"{path}.adapter COCO scenes require BOUNDING_BOXES")
    if adapter_kind == "ISOLATED_CARD_FOLDERS" and annotation_status != "CLASS_LABELS":
        raise ManifestError(f"{path}.adapter isolated folders require CLASS_LABELS")

    return DatasetSource(
        source_id=source_id,
        title=title,
        publisher=publisher,
        source_url=source_url,
        archive_url=_url(source, "archiveUrl", path),
        retrieved_at=_text(source, "retrievedAt", path),
        source_revision=revision,
        archive_sha256=archive_sha256,
        archive_local_path=archive_local_path,
        adapter=SourceAdapter(adapter_kind, annotation_member, image_prefix, label_map),
        license=LicenseEvidence(
            license_kind,
            evidence_url,
            evidence_text,
            legal_tool_url,
            reviewed_host,
        ),
        annotation_status=annotation_status,
        attribution=_text(source, "attribution", path),
        deck_group_id=_safe_manifest_name(source, "deckGroupId", path),
        labels=tuple(labels),
        provides=provides,
        usable_photo_count=usable_count,
        holdout_eligible_photo_count=holdout_count,
        public_domain_approved=True,
        approval_note=approval_note,
    )


def parse_source_manifest_bytes(
    payload: bytes, *, source_name: str = "source manifest"
) -> SourceManifest:
    """Parse one immutable manifest snapshot without reopening its source path."""

    try:
        raw = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Cannot parse {source_name}: {error}") from error

    root = _object(raw, "manifest")
    schema_version = _required(root, "schemaVersion", "manifest")
    if schema_version != 1:
        raise ManifestError("manifest.schemaVersion must equal 1")
    sources_raw = _required(root, "sources", "manifest")
    if not isinstance(sources_raw, list):
        raise ManifestError("manifest.sources is required and must be a list")

    sources = tuple(_parse_source(source, index) for index, source in enumerate(sources_raw))
    source_ids = [source.source_id for source in sources]
    if len(source_ids) != len(set(source_ids)):
        raise ManifestError("manifest.sources must use unique ids")
    return SourceManifest(schema_version=schema_version, sources=sources)


def load_source_manifest(path: str | Path) -> SourceManifest:
    """Load one reviewed source-manifest snapshot and reject incomplete evidence."""

    manifest_path = Path(path)
    try:
        payload = manifest_path.read_bytes()
    except OSError as error:
        raise ManifestError(f"Cannot load source manifest {manifest_path}: {error}") from error
    return parse_source_manifest_bytes(payload, source_name=str(manifest_path))


def verify_archive_sha256(archive: str | Path, source: DatasetSource) -> str:
    """Hash an archive before extraction and fail on any byte mismatch."""

    digest = hashlib.sha256()
    archive_path = Path(archive)
    try:
        with archive_path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise ManifestError(f"Cannot read archive {archive_path}: {error}") from error

    actual = digest.hexdigest()
    if not hmac.compare_digest(actual, source.archive_sha256):
        raise ManifestError(
            f"Archive checksum mismatch for {source.source_id}: "
            f"expected {source.archive_sha256}, got {actual}"
        )
    return actual
