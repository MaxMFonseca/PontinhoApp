"""Opt-in, real Model Maker one-batch integration smoke."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest
from PIL import Image

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.train import prepare_training_run, run_training

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _scene(path: Path, image_id: str) -> dict[str, object]:
    Image.new("RGB", (320, 320), "white").save(path)
    payload = path.read_bytes()
    boxes = []
    for index, label in enumerate(CARD_LABELS):
        column, row = index % 13, index // 13
        xmin, ymin = column * 24 + 2, row * 72 + 2
        boxes.append(
            {
                "label": label,
                "xmin": xmin,
                "ymin": ymin,
                "xmax": xmin + 18,
                "ymax": ymin + 60,
            }
        )
    return {
        "imageId": image_id,
        "path": str(path),
        "width": 320,
        "height": 320,
        "sourceId": "generated-smoke-fixture",
        "deckGroupId": f"deck-{image_id}",
        "splitGroupId": f"split-{image_id}",
        "sourceDerivativeId": f"derivative-{image_id}",
        "augmentationParentId": None,
        "imageSha256": hashlib.sha256(payload).hexdigest(),
        "isRealPhoto": False,
        "boxes": boxes,
    }


@pytest.mark.skipif(
    os.environ.get("PONTINHO_RUN_MODEL_MAKER_SMOKE") != "1",
    reason="set PONTINHO_RUN_MODEL_MAKER_SMOKE=1 for the real CPU batch",
)
def test_model_maker_completes_exactly_one_generated_cpu_batch(tmp_path: Path) -> None:
    """Catch a training adapter that only builds data but never executes a batch."""

    train_image = tmp_path / "train.png"
    validation_image = tmp_path / "validation.png"
    split_path = tmp_path / "splits.json"
    split_path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "seed": 20261014,
                "partitions": {
                    "train": [_scene(train_image, "train")],
                    "validation": [_scene(validation_image, "validation")],
                    "test": [],
                },
            }
        ),
        encoding="utf-8",
    )
    prepared = prepare_training_run(
        config_path=PROJECT_ROOT / "config" / "model.yaml",
        splits_path=split_path,
        run_id="one-batch-smoke",
        checkpoint_root=tmp_path / "checkpoints",
        requirements_lock=PROJECT_ROOT / "requirements.lock",
        git_commit="172a705d",
    )

    metrics = run_training(prepared, one_batch_smoke=True)

    assert metrics["status"] == "SMOKE_ONLY"
    assert len(metrics["history"]["loss"]) == 1
    selected = prepared.output_dir / metrics["selectedCheckpoint"]
    assert selected.name == "selected.weights.h5"
    assert hashlib.sha256(selected.read_bytes()).hexdigest() == metrics[
        "selectedCheckpointSha256"
    ]
    assert "AP" in metrics["validation"]
    assert {key for key in metrics["validation"] if key.startswith("AP_/")} == {
        f"AP_/{label}" for label in CARD_LABELS
    }
    assert not prepared.staging_dir.exists()
