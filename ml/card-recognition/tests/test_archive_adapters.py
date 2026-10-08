from __future__ import annotations

import hashlib
import io
import json
import tarfile
import zipfile
from pathlib import Path

import pytest
from PIL import Image

from pontinho_cards.normalize import main


def _png(color: str = "white") -> bytes:
    stream = io.BytesIO()
    Image.new("RGB", (20, 30), color).save(stream, format="PNG")
    return stream.getvalue()


def _source(archive: Path, *, adapter: dict[str, object]) -> dict[str, object]:
    evidence = "https://example.test/cards/license"
    return {
        "id": "cards-v1",
        "title": "Cards",
        "publisher": "Publisher",
        "sourceUrl": "https://example.test/cards",
        "archiveUrl": "https://example.test/cards.zip",
        "archiveInput": {"localPath": archive.name},
        "adapter": adapter,
        "retrievedAt": "2026-10-15",
        "sourceRevision": {
            "kind": "GIT_COMMIT",
            "repositoryUrl": "https://example.test/cards",
            "commitSha": "a" * 40,
        },
        "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "license": {
            "kind": "CC0-1.0",
            "evidenceUrl": evidence,
            "evidenceText": "Publisher dedicates Cards under CC0 1.0.",
            "legalToolUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        },
        "annotationStatus": "BOUNDING_BOXES"
        if adapter["kind"] in {"COCO_JSON_SCENES", "YOLO_SCENES"}
        else "CLASS_LABELS",
        "attribution": "Publisher",
        "deckGroupId": "deck-a",
        "labels": sorted(set(adapter["labelMap"].values())),
        "provides": {
            "realScenes": adapter["kind"] in {"COCO_JSON_SCENES", "YOLO_SCENES"},
            "isolatedCards": adapter["kind"] == "ISOLATED_CARD_FOLDERS",
            "annotations": True,
            "distinctPhysicalDeckDesigns": 1,
        },
        "usablePhotoCount": 1,
        "holdoutEligiblePhotoCount": 1 if adapter["kind"] == "COCO_JSON_SCENES" else 0,
        "publicDomainApproved": True,
        "approvalNote": f"Human-reviewed license statement: {evidence}",
    }


def _run(tmp_path: Path, source: dict[str, object]) -> dict[str, object]:
    manifest = tmp_path / "sources.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}), encoding="utf-8")
    output = tmp_path / "normalized"
    assert (
        main(
            [
                "--manifest",
                str(manifest),
                "--archive-root",
                str(tmp_path),
                "--out",
                str(output),
            ]
        )
        == 0
    )
    return json.loads((output / "records.jsonl").read_text(encoding="utf-8"))


def test_coco_zip_adapter_reads_verified_snapshot_and_maps_labels(tmp_path: Path) -> None:
    archive = tmp_path / "cards.zip"
    image = _png()
    annotation = {
        "images": [{"id": 1, "file_name": "one.png", "width": 20, "height": 30}],
        "categories": [{"id": 7, "name": "ace hearts"}],
        "annotations": [{"id": 1, "image_id": 1, "category_id": 7, "bbox": [1, 2, 10, 20]}],
    }
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("annotations/instances.json", json.dumps(annotation))
        bundle.writestr("images/one.png", image)
    source = _source(
        archive,
        adapter={
            "kind": "COCO_JSON_SCENES",
            "annotationMember": "annotations/instances.json",
            "imagePrefix": "images",
            "labelMap": {"ace hearts": "AH"},
        },
    )

    record = _run(tmp_path, source)

    assert record["boxes"] == [
        {"label": "AH", "xmin": 1.0, "ymin": 2.0, "xmax": 11.0, "ymax": 22.0}
    ]
    assert record["sourceArchiveMember"] == "images/one.png"
    assert record["sourceArchiveMemberSha256"] == hashlib.sha256(image).hexdigest()


def test_yolo_adapter_reads_normalized_boxes_and_merges_source_alias(tmp_path: Path) -> None:
    archive = tmp_path / "cards.tar"
    image = _png()
    with tarfile.open(archive, "w") as bundle:
        for name, payload in (
            ("data.yaml", b"names: ['10 Diamonds', '59']\n"),
            ("train/images/one.png", image),
            ("train/labels/one.txt", b"1 0.5 0.5 0.5 0.5\n"),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            bundle.addfile(info, io.BytesIO(payload))
    source = _source(
        archive,
        adapter={
            "kind": "YOLO_SCENES",
            "annotationMember": "data.yaml",
            "imagePrefix": "train/images",
            "labelMap": {"10 Diamonds": "10D", "59": "10D"},
        },
    )

    record = _run(tmp_path, source)

    assert record["boxes"] == [
        {"label": "10D", "xmin": 5.0, "ymin": 7.5, "xmax": 15.0, "ymax": 22.5}
    ]
    assert record["sourceArchiveMember"] == "train/images/one.png"


def _run_coco_document(tmp_path: Path, document: object) -> None:
    archive = tmp_path / "cards.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("annotations/instances.json", json.dumps(document))
        bundle.writestr("images/one.png", _png())
        bundle.writestr("images/two.png", _png("red"))
    source = _source(
        archive,
        adapter={
            "kind": "COCO_JSON_SCENES",
            "annotationMember": "annotations/instances.json",
            "imagePrefix": "images",
            "labelMap": {"ace hearts": "AH"},
        },
    )
    _run(tmp_path, source)


@pytest.mark.parametrize(
    ("section", "replacement", "message"),
    [
        (
            "categories",
            [{"id": 7, "name": "ace hearts"}, {"id": 7, "name": "ace hearts"}],
            "category IDs must be unique",
        ),
        (
            "images",
            [
                {"id": 1, "file_name": "one.png", "width": 20, "height": 30},
                {"id": 1, "file_name": "two.png", "width": 20, "height": 30},
            ],
            "image IDs must be unique",
        ),
        (
            "annotations",
            [
                {"id": 9, "image_id": 1, "category_id": 7, "bbox": [1, 2, 3, 4]},
                {"id": 9, "image_id": 1, "category_id": 7, "bbox": [2, 3, 4, 5]},
            ],
            "annotation IDs must be unique",
        ),
    ],
)
def test_coco_adapter_rejects_duplicate_typed_ids(
    tmp_path: Path, section: str, replacement: object, message: str
) -> None:
    document = {
        "images": [{"id": 1, "file_name": "one.png", "width": 20, "height": 30}],
        "categories": [{"id": 7, "name": "ace hearts"}],
        "annotations": [
            {"id": 9, "image_id": 1, "category_id": 7, "bbox": [1, 2, 10, 20]}
        ],
    }
    document[section] = replacement

    with pytest.raises(ValueError, match=message):
        _run_coco_document(tmp_path, document)


@pytest.mark.parametrize("bad_id", [None, True, 1.5, [], {}])
@pytest.mark.parametrize("field", ["category", "image", "annotation"])
def test_coco_adapter_rejects_null_bool_and_wrong_type_ids(
    tmp_path: Path, field: str, bad_id: object
) -> None:
    document = {
        "images": [{"id": 1, "file_name": "one.png", "width": 20, "height": 30}],
        "categories": [{"id": 7, "name": "ace hearts"}],
        "annotations": [
            {"id": 9, "image_id": 1, "category_id": 7, "bbox": [1, 2, 10, 20]}
        ],
    }
    target = {"category": "categories", "image": "images", "annotation": "annotations"}[field]
    document[target][0]["id"] = bad_id

    with pytest.raises(ValueError, match=rf"COCO {field} ID"):
        _run_coco_document(tmp_path, document)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("image_id", 404, "unknown image"),
        ("category_id", 404, "unknown category"),
        ("image_id", True, "annotation image_id"),
        ("category_id", None, "annotation category_id"),
    ],
)
def test_coco_adapter_rejects_orphan_or_untyped_annotation_references(
    tmp_path: Path, field: str, value: object, message: str
) -> None:
    annotation = {"id": 9, "image_id": 1, "category_id": 7, "bbox": [1, 2, 10, 20]}
    annotation[field] = value
    document = {
        "images": [{"id": 1, "file_name": "one.png", "width": 20, "height": 30}],
        "categories": [{"id": 7, "name": "ace hearts"}],
        "annotations": [annotation],
    }

    with pytest.raises(ValueError, match=message):
        _run_coco_document(tmp_path, document)


def test_coco_adapter_rejects_two_images_mapped_to_one_archive_member(tmp_path: Path) -> None:
    document = {
        "images": [
            {"id": 1, "file_name": "one.png", "width": 20, "height": 30},
            {"id": 2, "file_name": "one.png", "width": 20, "height": 30},
        ],
        "categories": [{"id": 7, "name": "ace hearts"}],
        "annotations": [],
    }

    with pytest.raises(ValueError, match="archive member mapping must be unique"):
        _run_coco_document(tmp_path, document)


def test_isolated_folder_tar_adapter_creates_one_full_card_box(tmp_path: Path) -> None:
    archive = tmp_path / "cards.tar"
    image = _png("red")
    with tarfile.open(archive, "w") as bundle:
        info = tarfile.TarInfo("cards/ace-hearts/one.png")
        info.size = len(image)
        bundle.addfile(info, io.BytesIO(image))
    source = _source(
        archive,
        adapter={
            "kind": "ISOLATED_CARD_FOLDERS",
            "imagePrefix": "cards",
            "labelMap": {"ace-hearts": "AH"},
        },
    )

    record = _run(tmp_path, source)

    assert record["isRealPhoto"] is True
    assert record["boxes"] == [
        {"label": "AH", "xmin": 0.0, "ymin": 0.0, "xmax": 20.0, "ymax": 30.0}
    ]


def test_adapter_rejects_unsafe_archive_member(tmp_path: Path) -> None:
    archive = tmp_path / "unsafe.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("../escape.png", _png())
    source = _source(
        archive,
        adapter={
            "kind": "ISOLATED_CARD_FOLDERS",
            "imagePrefix": "cards",
            "labelMap": {"ace-hearts": "AH"},
        },
    )
    manifest = tmp_path / "sources.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}))

    with pytest.raises(ValueError, match="unsafe archive member"):
        main(
            [
                "--manifest",
                str(manifest),
                "--archive-root",
                str(tmp_path),
                "--out",
                str(tmp_path / "out"),
            ]
        )


def test_adapter_rejects_zip_symlink_member(tmp_path: Path) -> None:
    archive = tmp_path / "unsafe.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        link = zipfile.ZipInfo("cards/ace-hearts/one.png")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        bundle.writestr(link, "../../outside.png")
    source = _source(
        archive,
        adapter={
            "kind": "ISOLATED_CARD_FOLDERS",
            "imagePrefix": "cards",
            "labelMap": {"ace-hearts": "AH"},
        },
    )
    manifest = tmp_path / "sources.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}))

    with pytest.raises(ValueError, match="unsafe archive member link"):
        main(
            [
                "--manifest",
                str(manifest),
                "--archive-root",
                str(tmp_path),
                "--out",
                str(tmp_path / "out"),
            ]
        )


def test_adapter_rejects_symlinked_archive_without_reading_outside(
    tmp_path: Path,
) -> None:
    outside = tmp_path / "outside.zip"
    with zipfile.ZipFile(outside, "w") as bundle:
        bundle.writestr("cards/ace-hearts/one.png", _png())
    linked = tmp_path / "cards.zip"
    linked.symlink_to(outside)
    source = _source(
        outside,
        adapter={
            "kind": "ISOLATED_CARD_FOLDERS",
            "imagePrefix": "cards",
            "labelMap": {"ace-hearts": "AH"},
        },
    )
    source["archiveInput"]["localPath"] = linked.name
    manifest = tmp_path / "sources.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}))

    with pytest.raises(ValueError, match="securely snapshot|symbolic links"):
        main(
            [
                "--manifest",
                str(manifest),
                "--archive-root",
                str(tmp_path),
                "--out",
                str(tmp_path / "out"),
            ]
        )

    assert not (tmp_path / "out").exists()


def test_adapter_rejects_symlinked_output_parent_without_writing_outside(
    tmp_path: Path,
) -> None:
    archive = tmp_path / "cards.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("cards/ace-hearts/one.png", _png())
    source = _source(
        archive,
        adapter={
            "kind": "ISOLATED_CARD_FOLDERS",
            "imagePrefix": "cards",
            "labelMap": {"ace-hearts": "AH"},
        },
    )
    manifest = tmp_path / "sources.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "sources": [source]}))
    outside = tmp_path / "outside"
    outside.mkdir()
    output = tmp_path / "normalized"
    output.symlink_to(outside, target_is_directory=True)

    with pytest.raises(ValueError, match="symbolic link|secure output|trusted root"):
        main(
            [
                "--manifest",
                str(manifest),
                "--archive-root",
                str(tmp_path),
                "--out",
                str(output),
            ]
        )

    assert list(outside.iterdir()) == []
