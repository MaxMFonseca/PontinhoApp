from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from pontinho_cards.thresholds import main, produce_validation_manifest


def _canonical(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def test_validation_manifest_is_deterministically_bound_to_split_validation(
    tmp_path: Path,
) -> None:
    validation = [{"imageId": "validation-1", "boxes": []}]
    split = {
        "schemaVersion": 1,
        "seed": 17,
        "partitions": {"train": [], "validation": validation, "test": []},
    }
    split_path = tmp_path / "splits.json"
    split_path.write_bytes(_canonical(split))
    output = tmp_path / "validation-manifest.json"

    artifact = produce_validation_manifest(split_path, output)

    assert artifact == {
        "schemaVersion": 1,
        "kind": "pontinho-validation-threshold-selection",
        "seed": 17,
        "splitManifestSha256": hashlib.sha256(split_path.read_bytes()).hexdigest(),
        "validationPartitionSha256": hashlib.sha256(_canonical(validation)).hexdigest(),
        "records": validation,
    }
    assert output.read_bytes() == _canonical(artifact)


def test_validation_manifest_refuses_replacement_and_invalid_split(tmp_path: Path) -> None:
    split_path = tmp_path / "splits.json"
    split_path.write_text('{"schemaVersion":1,"partitions":{}}', encoding="utf-8")
    output = tmp_path / "validation.json"

    with pytest.raises(ValueError, match="validation partition"):
        produce_validation_manifest(split_path, output)

    split_path.write_bytes(
        _canonical(
            {
                "schemaVersion": 1,
                "seed": 1,
                "partitions": {"train": [], "validation": [], "test": []},
            }
        )
    )
    output.write_text("preserve", encoding="utf-8")
    with pytest.raises(FileExistsError):
        produce_validation_manifest(split_path, output)


def test_validation_manifest_cli_subcommand_runs_the_producer(tmp_path: Path) -> None:
    split = tmp_path / "splits.json"
    split.write_bytes(
        _canonical(
            {
                "schemaVersion": 1,
                "seed": 9,
                "partitions": {"train": [], "validation": [], "test": []},
            }
        )
    )
    output = tmp_path / "validation.json"

    assert main(["validation-manifest", "--split", str(split), "--output", str(output)]) == 0
    assert (
        json.loads(output.read_text())["splitManifestSha256"]
        == hashlib.sha256(split.read_bytes()).hexdigest()
    )
