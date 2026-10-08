import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

from pontinho_cards.manifest import (
    ManifestError,
    load_source_manifest,
    parse_source_manifest_bytes,
    verify_archive_sha256,
)


def test_manifest_bytes_parser_uses_the_single_snapshotted_payload(tmp_path: Path) -> None:
    """Catch release hashing one manifest payload and parsing swapped path bytes."""

    path = _write_manifest(tmp_path, _valid_source())
    snapshot = path.read_bytes()
    path.write_text('{"schemaVersion": 1, "sources": []}', encoding="utf-8")

    parsed = parse_source_manifest_bytes(snapshot, source_name="snapshotted sources")

    assert len(parsed.sources) == 1


def _valid_source() -> dict[str, Any]:
    evidence_url = "https://example.test/cards/license"
    return {
        "id": "fixture-cards-v1",
        "title": "Fixture cards",
        "publisher": "Fixture publisher",
        "sourceUrl": "https://example.test/cards",
        "archiveUrl": "https://example.test/cards-v1.zip",
        "archiveInput": {"localPath": "fixture-cards-v1.archive"},
        "adapter": {
            "kind": "COCO_JSON_SCENES",
            "annotationMember": "annotations/instances.json",
            "imagePrefix": "images",
            "labelMap": {label: label for label in ("AC", "10D", "QH", "KS")},
        },
        "retrievedAt": "2026-10-15",
        "sourceRevision": {
            "kind": "GIT_COMMIT",
            "repositoryUrl": "https://example.test/cards",
            "commitSha": "a" * 40,
        },
        "archiveSha256": hashlib.sha256(b"fixture archive").hexdigest(),
        "license": {
            "kind": "CC0-1.0",
            "evidenceUrl": evidence_url,
            "evidenceText": "Fixture publisher dedicates Fixture cards under CC0 1.0.",
            "legalToolUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        },
        "annotationStatus": "BOUNDING_BOXES",
        "attribution": "Fixture publisher, Fixture cards release 2026-10-01",
        "deckGroupId": "fixture-physical-deck-a",
        "labels": ["AC", "10D", "QH", "KS"],
        "provides": {
            "realScenes": True,
            "isolatedCards": False,
            "annotations": True,
            "distinctPhysicalDeckDesigns": 1,
        },
        "usablePhotoCount": 200,
        "holdoutEligiblePhotoCount": 200,
        "publicDomainApproved": True,
        "approvalNote": f"Human-reviewed license statement: {evidence_url}",
    }


def _write_manifest(tmp_path: Path, source: dict[str, Any]) -> Path:
    path = tmp_path / "sources.json"
    path.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}), encoding="utf-8")
    return path


@pytest.mark.parametrize(
    "missing_path",
    [
        ("sourceRevision",),
        ("archiveSha256",),
        ("archiveInput",),
        ("adapter",),
        ("adapter", "imagePrefix"),
        ("adapter", "labelMap"),
        ("annotationStatus",),
        ("attribution",),
        ("deckGroupId",),
        ("license", "evidenceUrl"),
        ("license", "evidenceText"),
        ("license", "legalToolUrl"),
        ("publicDomainApproved",),
        ("approvalNote",),
    ],
)
def test_manifest_fails_closed_when_audit_evidence_is_missing(
    tmp_path: Path, missing_path: tuple[str, ...]
) -> None:
    source = _valid_source()
    target: dict[str, Any] = source
    for key in missing_path[:-1]:
        target = target[key]
    del target[missing_path[-1]]

    with pytest.raises(ManifestError, match="required"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize("approval", [False, "true", 1])
def test_public_domain_approval_must_be_literal_true(tmp_path: Path, approval: object) -> None:
    source = _valid_source()
    source["publicDomainApproved"] = approval

    with pytest.raises(ManifestError, match="publicDomainApproved"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_approval_note_must_point_to_the_reviewed_evidence(tmp_path: Path) -> None:
    source = _valid_source()
    source["approvalNote"] = "Reviewed by a human, looks okay."

    with pytest.raises(ManifestError, match="approvalNote"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    "generic_url",
    [
        "https://creativecommons.org/publicdomain/zero/1.0/",
        "https://creativecommons.org/publicdomain/zero/1.0/legalcode",
        "https://spdx.org/licenses/CC0-1.0.html",
        "https://www.spdx.org/licenses/CC0-1.0.html",
        "https://creativecommons.org./publicdomain/zero/1.0/",
    ],
)
def test_generic_license_or_deed_url_is_not_source_evidence(
    tmp_path: Path, generic_url: str
) -> None:
    source = _valid_source()
    source["license"]["evidenceUrl"] = generic_url
    source["approvalNote"] = f"Human-reviewed license statement: {generic_url}"

    with pytest.raises(ManifestError, match="source-specific"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_unrelated_evidence_host_requires_explicit_reviewed_host(tmp_path: Path) -> None:
    source = _valid_source()
    source["license"]["evidenceUrl"] = "https://license.publisher.test/cards"
    source["approvalNote"] = (
        "Human-reviewed license statement: https://license.publisher.test/cards"
    )

    with pytest.raises(ManifestError, match="reviewedEvidenceHost"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_explicit_reviewed_source_evidence_host_is_accepted(tmp_path: Path) -> None:
    source = _valid_source()
    source["license"]["evidenceUrl"] = "https://license.publisher.test/cards"
    source["license"]["reviewedEvidenceHost"] = "LICENSE.PUBLISHER.TEST."
    source["approvalNote"] = (
        "Human-reviewed license statement: https://license.publisher.test/cards"
    )

    manifest = load_source_manifest(_write_manifest(tmp_path, source))

    assert manifest.sources[0].license.reviewed_evidence_host == "license.publisher.test"


def test_reviewed_evidence_host_must_match_evidence_url(tmp_path: Path) -> None:
    source = _valid_source()
    source["license"]["evidenceUrl"] = "https://license.publisher.test/cards"
    source["license"]["reviewedEvidenceHost"] = "other.publisher.test"
    source["approvalNote"] = (
        "Human-reviewed license statement: https://license.publisher.test/cards"
    )

    with pytest.raises(ManifestError, match="exactly match"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_source_and_evidence_hosts_are_normalized_before_comparison(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceUrl"] = "https://EXAMPLE.test./cards"
    source["sourceRevision"]["repositoryUrl"] = "https://EXAMPLE.test./cards"

    assert load_source_manifest(_write_manifest(tmp_path, source)).sources


def test_idna_equivalent_source_and_evidence_hosts_are_accepted(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceUrl"] = "https://bücher.example/cards"
    source["sourceRevision"]["repositoryUrl"] = "https://bücher.example/cards"
    source["license"]["evidenceUrl"] = "https://xn--bcher-kva.example/cards/license"
    source["approvalNote"] = (
        "Human-reviewed license statement: " "https://xn--bcher-kva.example/cards/license"
    )

    assert load_source_manifest(_write_manifest(tmp_path, source)).sources


def test_evidence_text_must_identify_the_source_or_publisher(tmp_path: Path) -> None:
    source = _valid_source()
    source["license"]["evidenceText"] = "Everything is dedicated under CC0 1.0."

    with pytest.raises(ManifestError, match="source or publisher"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_cc_by_4_is_accepted_with_explicit_distribution_approval(tmp_path: Path) -> None:
    source = _valid_source()
    source["license"] = {
        "kind": "CC-BY-4.0",
        "evidenceUrl": "https://example.test/cards/license",
        "evidenceText": "Fixture publisher releases Fixture cards under CC BY 4.0.",
        "legalToolUrl": "https://creativecommons.org/licenses/by/4.0/",
    }
    source.pop("publicDomainApproved")
    source["licenseApproved"] = True

    parsed = load_source_manifest(_write_manifest(tmp_path, source)).sources[0]

    assert parsed.license.kind == "CC-BY-4.0"
    assert parsed.distribution_approved is True
    assert parsed.public_domain_approved is False


@pytest.mark.parametrize("license_kind", ["MIT", "Apache-2.0", "CC-BY-SA-4.0"])
def test_unreviewed_license_kind_is_not_admitted(
    tmp_path: Path, license_kind: str
) -> None:
    source = _valid_source()
    source["license"]["kind"] = license_kind

    with pytest.raises(ManifestError, match="permit commercial distribution"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    "source_revision",
    ["latest", "main", "master", "HEAD", "current", "develop", "trunk", "stable"],
)
def test_untyped_moving_source_revision_is_rejected(tmp_path: Path, source_revision: str) -> None:
    source = _valid_source()
    source["sourceRevision"] = source_revision

    with pytest.raises(ManifestError, match="typed immutable"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    "mutable_ref",
    ["develop", "trunk", "refs/heads/main", "origin/main", "stable", "v1.2.3", "a" * 39],
)
def test_git_revision_requires_a_full_commit_sha(tmp_path: Path, mutable_ref: str) -> None:
    source = _valid_source()
    source["sourceRevision"]["commitSha"] = mutable_ref

    with pytest.raises(ManifestError, match="full lowercase Git commit SHA"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_git_revision_repository_must_match_the_source(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceRevision"]["repositoryUrl"] = "https://elsewhere.test/cards"

    with pytest.raises(ManifestError, match="source-specific"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_typed_platform_dataset_version_is_accepted(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceUrl"] = "https://www.kaggle.com/datasets/example/cards"
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "kaggle",
        "datasetId": "example/cards",
        "versionId": "2",
    }
    source["license"]["reviewedEvidenceHost"] = "example.test"

    manifest = load_source_manifest(_write_manifest(tmp_path, source))

    assert manifest.sources[0].source_revision.kind == "PLATFORM_DATASET_VERSION"


@pytest.mark.parametrize(
    ("source_url", "dataset_id"),
    [
        ("https://www.kaggle.com/datasets/example/cards", "cards"),
        ("https://www.kaggle.com/example/cards", "example/cards"),
        ("https://www.kaggle.com/datasets/example/cards/versions/2", "example/cards"),
        ("https://www.kaggle.com/datasets/example", "example/cards"),
    ],
)
def test_kaggle_revision_requires_exact_canonical_dataset_path(
    tmp_path: Path, source_url: str, dataset_id: str
) -> None:
    source = _valid_source()
    source["sourceUrl"] = source_url
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "kaggle",
        "datasetId": dataset_id,
        "versionId": "2",
    }

    with pytest.raises(ManifestError, match="canonical Kaggle"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_typed_roboflow_dataset_version_is_accepted(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceUrl"] = "https://universe.roboflow.com/skypea/playing-cards"
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "roboflow",
        "datasetId": "skypea/playing-cards",
        "versionId": "1",
    }
    source["license"]["reviewedEvidenceHost"] = "example.test"

    manifest = load_source_manifest(_write_manifest(tmp_path, source))

    assert manifest.sources[0].source_revision.dataset_id == "skypea/playing-cards"


@pytest.mark.parametrize(
    ("source_url", "dataset_id"),
    [
        ("https://universe.roboflow.com/skypea/playing-cards", "playing-cards"),
        ("https://universe.roboflow.com/datasets/skypea/playing-cards", "skypea/playing-cards"),
        (
            "https://universe.roboflow.com/skypea/playing-cards/dataset/1",
            "skypea/playing-cards",
        ),
        ("https://app.roboflow.com/skypea/playing-cards", "skypea/playing-cards"),
    ],
)
def test_roboflow_revision_requires_exact_canonical_project_path(
    tmp_path: Path, source_url: str, dataset_id: str
) -> None:
    source = _valid_source()
    source["sourceUrl"] = source_url
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "roboflow",
        "datasetId": dataset_id,
        "versionId": "1",
    }

    with pytest.raises(ManifestError, match="canonical Roboflow"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize("version_id", ["latest", "current", "stable", "main", "v2/latest"])
def test_platform_dataset_version_rejects_moving_identifiers(
    tmp_path: Path, version_id: str
) -> None:
    source = _valid_source()
    source["sourceUrl"] = "https://www.kaggle.com/datasets/example/cards"
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "kaggle",
        "datasetId": "example/cards",
        "versionId": version_id,
    }

    with pytest.raises(ManifestError, match="immutable versionId"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_platform_provider_must_match_source_host(tmp_path: Path) -> None:
    source = _valid_source()
    source["sourceRevision"] = {
        "kind": "PLATFORM_DATASET_VERSION",
        "provider": "kaggle",
        "datasetId": "example/cards",
        "versionId": "2",
    }

    with pytest.raises(ManifestError, match="source-specific"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize("checksum", ["", "abc", "g" * 64, "A" * 64])
def test_archive_checksum_must_be_lowercase_sha256(tmp_path: Path, checksum: str) -> None:
    source = _valid_source()
    source["archiveSha256"] = checksum

    with pytest.raises(ManifestError, match="SHA-256"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    "unsafe",
    [
        "",
        ".",
        "..",
        "../outside",
        "a/b",
        "a\\b",
        "C:cards",
        "C:/cards",
        "//server/share",
        "bad:name",
        "trail.",
        " space",
        "a" * 65,
    ],
)
@pytest.mark.parametrize("field", ["id", "deckGroupId"])
def test_source_and_deck_ids_are_bounded_ascii_safe_names(
    tmp_path: Path, field: str, unsafe: str
) -> None:
    source = _valid_source()
    source[field] = unsafe

    with pytest.raises(ManifestError, match="safe name"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    "unsafe",
    [
        "",
        ".",
        "..",
        "../outside.zip",
        "a/b.zip",
        "a\\b.zip",
        "C:cards.zip",
        "C:/cards.zip",
        "//server/share.zip",
        "trail.",
        "a" * 65,
    ],
)
def test_archive_local_path_is_one_bounded_ascii_safe_filename(
    tmp_path: Path, unsafe: str
) -> None:
    source = _valid_source()
    source["archiveInput"]["localPath"] = unsafe

    with pytest.raises(ManifestError, match="safe filename"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    ("field", "unsafe"),
    [
        ("imagePrefix", "../images"),
        ("imagePrefix", "C:/images"),
        ("imagePrefix", "//server/images"),
        ("imagePrefix", "images\\nested"),
        ("annotationMember", "../instances.json"),
        ("annotationMember", "C:/instances.json"),
        ("annotationMember", "//server/instances.json"),
        ("annotationMember", "annotations\\instances.json"),
    ],
)
def test_adapter_paths_are_safe_relative_posix_paths(
    tmp_path: Path, field: str, unsafe: str
) -> None:
    source = _valid_source()
    source["adapter"][field] = unsafe

    with pytest.raises(ManifestError, match="safe"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize("label", ["joker", "BACK", "AH ", "ace_hearts"])
def test_manifest_rejects_unsupported_labels(tmp_path: Path, label: str) -> None:
    source = _valid_source()
    source["labels"].append(label)

    with pytest.raises(ManifestError, match="Unsupported card label"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_valid_manifest_is_loaded_as_immutable_records(tmp_path: Path) -> None:
    source = _valid_source()

    manifest = load_source_manifest(_write_manifest(tmp_path, source))

    assert manifest.schema_version == 1
    assert len(manifest.sources) == 1
    assert manifest.sources[0].source_id == "fixture-cards-v1"
    assert manifest.sources[0].source_revision.commit_sha == "a" * 40
    assert manifest.sources[0].labels == ("AC", "10D", "QH", "KS")
    assert manifest.sources[0].provides.real_scenes is True


@pytest.mark.parametrize(
    ("annotation_status", "provides_annotations", "labels", "error"),
    [
        ("UNANNOTATED", True, [], "provides.annotations"),
        ("BOUNDING_BOXES", False, ["AC"], "provides.annotations"),
        ("CLASS_LABELS", False, ["AC"], "provides.annotations"),
        ("UNANNOTATED", False, ["AC"], "labels must be empty"),
        ("BOUNDING_BOXES", True, [], "labels must not be empty"),
        ("CLASS_LABELS", True, [], "labels must not be empty"),
    ],
)
def test_annotation_metadata_contradictions_are_rejected(
    tmp_path: Path,
    annotation_status: str,
    provides_annotations: bool,
    labels: list[str],
    error: str,
) -> None:
    source = _valid_source()
    source["annotationStatus"] = annotation_status
    source["provides"]["annotations"] = provides_annotations
    source["labels"] = labels
    source["holdoutEligiblePhotoCount"] = 0

    with pytest.raises(ManifestError, match=error):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_unannotated_real_photos_are_not_evaluation_ready(tmp_path: Path) -> None:
    source = _valid_source()
    source["annotationStatus"] = "UNANNOTATED"
    source["provides"]["annotations"] = False
    source["labels"] = []
    source["holdoutEligiblePhotoCount"] = 1

    with pytest.raises(ManifestError, match="evaluation-ready bounding boxes"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_classification_labels_are_not_evaluation_ready_holdout(tmp_path: Path) -> None:
    source = _valid_source()
    source["annotationStatus"] = "CLASS_LABELS"
    source["holdoutEligiblePhotoCount"] = 1

    with pytest.raises(ManifestError, match="evaluation-ready bounding boxes"):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_holdout_count_cannot_exceed_usable_photo_count(tmp_path: Path) -> None:
    source = _valid_source()
    source["usablePhotoCount"] = 199
    source["holdoutEligiblePhotoCount"] = 200

    with pytest.raises(ManifestError, match="cannot exceed usablePhotoCount"):
        load_source_manifest(_write_manifest(tmp_path, source))


@pytest.mark.parametrize(
    ("real_scenes", "isolated_cards", "usable_count", "error"),
    [
        (False, False, 200, "usablePhotoCount"),
        (False, True, 200, "realScenes=true"),
    ],
)
def test_photo_counts_must_match_declared_capabilities(
    tmp_path: Path,
    real_scenes: bool,
    isolated_cards: bool,
    usable_count: int,
    error: str,
) -> None:
    source = _valid_source()
    source["provides"]["realScenes"] = real_scenes
    source["provides"]["isolatedCards"] = isolated_cards
    source["usablePhotoCount"] = usable_count

    with pytest.raises(ManifestError, match=error):
        load_source_manifest(_write_manifest(tmp_path, source))


def test_empty_audited_manifest_is_valid_but_has_no_holdout_capacity(tmp_path: Path) -> None:
    path = tmp_path / "sources.json"
    path.write_text('{"schemaVersion":1,"sources":[]}', encoding="utf-8")

    manifest = load_source_manifest(path)

    assert manifest.sources == ()
    assert manifest.holdout_photo_count == 0
    assert manifest.holdout_deck_group_count == 0


def test_repository_manifest_truthfully_blocks_the_release() -> None:
    path = Path(__file__).parents[1] / "datasets" / "sources.json"

    manifest = load_source_manifest(path)

    assert manifest.holdout_photo_count < 200
    assert manifest.holdout_deck_group_count < 10


def test_archive_hash_is_verified_before_use(tmp_path: Path) -> None:
    archive = tmp_path / "cards.zip"
    archive.write_bytes(b"fixture archive")
    source = load_source_manifest(_write_manifest(tmp_path, _valid_source())).sources[0]

    assert verify_archive_sha256(archive, source) == source.archive_sha256

    archive.write_bytes(b"tampered")
    with pytest.raises(ManifestError, match="checksum mismatch"):
        verify_archive_sha256(archive, source)
