from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from PIL import Image

from pontinho_cards.normalize import NormalizationError, main, normalize_record, normalize_records


def _write_image(root: Path, relative_path: str = "source/table.png") -> tuple[str, str]:
    path = root / relative_path
    path.parent.mkdir(parents=True)
    Image.new("RGB", (100, 80), "green").save(path)
    return relative_path, hashlib.sha256(path.read_bytes()).hexdigest()


def _record(path: str, digest: str) -> dict[str, object]:
    return {
        "imageId": "image-1",
        "path": path,
        "width": 100,
        "height": 80,
        "sourceId": "approved-source",
        "deckGroupId": "deck-a",
        "splitGroupId": "capture-session-a",
        "imageSha256": digest,
        "sourceDerivativeId": "original-image-1",
        "augmentationParentId": None,
        "isRealPhoto": True,
        "boxes": [
            {"label": "AC", "xmin": -5, "ymin": 10, "xmax": 105, "ymax": 70},
            {"label": "AC", "xmin": 20, "ymin": 20, "xmax": 40, "ymax": 60},
            {"label": "2D", "xmin": 50, "ymin": 40, "xmax": 50, "ymax": 60},
        ],
    }


def test_normalize_clamps_bounds_rejects_zero_area_and_keeps_duplicate_cards(
    tmp_path: Path,
) -> None:
    path, digest = _write_image(tmp_path)

    result = normalize_record(_record(path, digest), image_root=tmp_path)

    assert result.record is not None
    assert result.record["boxes"] == [
        {"label": "AC", "xmin": 0.0, "ymin": 10.0, "xmax": 100.0, "ymax": 70.0},
        {"label": "AC", "xmin": 20.0, "ymin": 20.0, "xmax": 40.0, "ymax": 60.0},
    ]
    assert result.rejected_boxes == [
        {"index": 2, "reason": "zero-area box after bounds clamping"}
    ]


def test_image_with_no_usable_standard_cards_is_excluded_with_reason(tmp_path: Path) -> None:
    path, digest = _write_image(tmp_path)
    record = _record(path, digest)
    record["boxes"] = [{"label": "KS", "xmin": 7, "ymin": 9, "xmax": 7, "ymax": 12}]

    result = normalize_record(record, image_root=tmp_path)

    assert result.record is None
    assert result.exclusion_reason == "no usable standard-card boxes"


@pytest.mark.parametrize(
    "mutation, message",
    [
        (lambda record: record.update(path="../escape.png"), "safe relative path"),
        (lambda record: record.update(width=101), "dimensions do not match"),
        (lambda record: record.update(imageSha256="0" * 64), "SHA-256 does not match"),
        (
            lambda record: record["boxes"][0].update(xmin=float("nan")),
            "finite numeric coordinates",
        ),
    ],
)
def test_unsafe_paths_and_false_image_metadata_fail_closed(
    tmp_path: Path, mutation: object, message: str
) -> None:
    path, digest = _write_image(tmp_path)
    record = _record(path, digest)
    mutation(record)  # type: ignore[operator]

    with pytest.raises(NormalizationError, match=message):
        normalize_record(record, image_root=tmp_path)


def test_unsupported_labels_fail_instead_of_being_silently_dropped(tmp_path: Path) -> None:
    path, digest = _write_image(tmp_path)
    record = _record(path, digest)
    record["boxes"] = [{"label": "joker", "xmin": 1, "ymin": 1, "xmax": 9, "ymax": 9}]

    with pytest.raises(NormalizationError, match="Unsupported card label"):
        normalize_record(record, image_root=tmp_path)


def test_normalize_records_writes_deterministic_jsonl_and_rejection_report(
    tmp_path: Path,
) -> None:
    path, digest = _write_image(tmp_path)
    accepted = _record(path, digest)
    excluded = _record(path, digest)
    excluded["imageId"] = "image-2"
    excluded["boxes"] = []
    out = tmp_path / "normalized"

    normalize_records([excluded, accepted], out=out, image_root=tmp_path)

    lines = (out / "records.jsonl").read_text(encoding="utf-8").splitlines()
    assert [json.loads(line)["imageId"] for line in lines] == ["image-1"]
    assert json.loads((out / "rejections.json").read_text(encoding="utf-8")) == {
        "schemaVersion": 1,
        "rejections": [
            {
                "imageId": "image-2",
                "reason": "no usable standard-card boxes",
                "rejectedBoxes": [],
            }
        ],
    }


def test_cli_preserves_truthful_empty_manifest_as_empty_normalized_output(
    tmp_path: Path,
) -> None:
    manifest = tmp_path / "sources.json"
    manifest.write_text('{"schemaVersion": 1, "sources": []}', encoding="utf-8")
    out = tmp_path / "normalized"

    assert main(["--manifest", str(manifest), "--out", str(out)]) == 0
    assert (out / "records.jsonl").read_text(encoding="utf-8") == ""
    assert json.loads((out / "rejections.json").read_text(encoding="utf-8")) == {
        "schemaVersion": 1,
        "rejections": [],
    }
