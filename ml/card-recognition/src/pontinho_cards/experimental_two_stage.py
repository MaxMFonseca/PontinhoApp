"""Explicit one-deck upstream experiment. Never a production holdout or release."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from pontinho_cards.manifest import load_source_manifest
from pontinho_cards.secure_io import (
    safe_relative_posix,
    secure_read_bytes,
    secure_write_bytes_immutable,
)
from pontinho_cards.split import validate_split_document
from pontinho_cards.two_stage_data import build_two_stage_inputs

EVIDENCE_MODE = "EXPERIMENTAL_ONE_DECK"


def prepare_experimental_document(document, sources):
    """Restore audited deck identities without inventing independent deck designs."""
    result = copy.deepcopy(document)
    result.update(evidenceMode=EVIDENCE_MODE, deckDisjoint=False, productionHoldout=False)
    source_by_id = {source.source_id: source for source in sources.sources}
    decks = set()
    visual_decks = set()
    for partition, records in result["partitions"].items():
        for record in records:
            source = source_by_id.get(record["sourceId"])
            if source is None or not source.distribution_approved:
                raise ValueError("experimental record source is not admitted")
            if (
                "FINAL_SCENE_HOLDOUT" in record.get("taskRoles", ())
                or "FINAL_SCENE_HOLDOUT" in source.task_roles
            ):
                raise ValueError("experimental data cannot consume production holdout")
            if not {"DETECTOR_SCENE", "CLASSIFIER_CROP"}.issubset(source.task_roles):
                raise ValueError("experimental source needs both audited task roles")
            member = safe_relative_posix(record.get("sourceArchiveMember"), field="source member")
            prefix = safe_relative_posix(source.adapter.image_prefix, field="source prefix")
            if member.parts[: len(prefix.parts)] != prefix.parts:
                raise ValueError("source archive member differs from admitted partition")
            expected_prefix = {"train": "train", "validation": "valid", "test": "test"}[partition]
            if prefix.parts[0] != expected_prefix:
                raise ValueError("source partition was reassigned")
            if record.get("sourceArchiveMemberSha256") != record["imageSha256"]:
                raise ValueError("experimental source bytes differ from admitted archive member")
            record["upstreamDeckGroupId"] = record["deckGroupId"]
            record["deckGroupId"] = source.deck_group_id
            record["visualDeckId"] = source.visual_deck_id
            record["taskRoles"] = list(source.task_roles)
            decks.add(source.deck_group_id)
            visual_decks.add(source.visual_deck_id)
    if len(decks) != 1 or len(visual_decks) != 1:
        raise ValueError("one-deck experiment must have exactly one audited visual deck")
    validate_split_document(result, evidence_mode=EVIDENCE_MODE)
    return result


def load_experimental_inputs(split_path, source_path, *, include_test=False):
    split_bytes = secure_read_bytes(split_path, root=split_path.parent)
    source_bytes = secure_read_bytes(source_path, root=source_path.parent)
    document = prepare_experimental_document(
        json.loads(split_bytes), load_source_manifest(source_path)
    )
    document["upstreamSplitSha256"] = hashlib.sha256(split_bytes).hexdigest()
    document["sourceManifestSha256"] = hashlib.sha256(source_bytes).hexdigest()
    # The missing two-stage manifest is materialized next to its actual relative image root.
    secure_write_bytes_immutable(
        split_path.parent,
        "two-stage-inputs.experimental.json",
        (json.dumps(document, indent=2, sort_keys=True) + "\n").encode(),
    )
    return document, build_two_stage_inputs(
        document,
        split_path.parent,
        evidence_mode=EVIDENCE_MODE,
        include_experimental_test=include_test,
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("prepare", "detector", "classifier"))
    parser.add_argument("--splits", type=Path, default=Path("datasets/normalized/splits.json"))
    parser.add_argument("--sources", type=Path, default=Path("datasets/sources.json"))
    parser.add_argument("--config", type=Path)
    parser.add_argument("--run-id", default="one-deck-20261015")
    args = parser.parse_args(argv)
    document, inputs = load_experimental_inputs(args.splits, args.sources)
    print(
        json.dumps(
            {
                "evidenceMode": EVIDENCE_MODE,
                "scenes": {k: len(v) for k, v in document["partitions"].items()},
                "crops": {k: len(v) for k, v in inputs.classifier_partitions.items()},
            }
        ),
        flush=True,
    )
    if args.stage == "detector":
        from pontinho_cards.train_detector import load_detector_config, train_detector

        candidate = train_detector(inputs, load_detector_config(args.config), args.run_id)
        print(candidate.contract_path, flush=True)
    elif args.stage == "classifier":
        from pontinho_cards.train_classifier import load_classifier_config, train_classifier

        candidate = train_classifier(inputs, load_classifier_config(args.config), args.run_id)
        print(candidate.contract_path, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
