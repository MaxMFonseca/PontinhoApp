"""Offline smoke contract for the card-recognition training environment."""

from __future__ import annotations

import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def test_environment_supports_offline_efficientdet_lite0_smoke_dataset(
    tmp_path: Path,
) -> None:
    """Catch missing, incompatible, or GPU/network-only training dependencies."""
    assert sys.version_info[:2] == (3, 9)

    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"

    import cv2
    import tensorflow as tf
    import yaml
    from PIL import Image
    from tflite_model_maker import object_detector
    from tflite_support import metadata

    model_config = yaml.safe_load(
        (PROJECT_ROOT / "config" / "model.yaml").read_text()
    )
    release_gate = yaml.safe_load(
        (PROJECT_ROOT / "config" / "release-gate.yaml").read_text()
    )
    assert model_config["detector_family"] == "efficientdet_lite0"
    assert release_gate["minimum_real_photo_count"] == 200
    assert release_gate["minimum_unseen_deck_groups"] == 10
    assert release_gate["minimum_exact_total_accuracy"] == 0.95

    spec = object_detector.EfficientDetLite0Spec()
    assert isinstance(spec, object_detector.EfficientDetSpec)
    assert spec.config.model_name == "efficientdet-lite0"
    assert tf.config.list_physical_devices("GPU") == []

    dataset_dir = tmp_path / "dataset"
    images_dir = dataset_dir / "images"
    images_dir.mkdir(parents=True)
    image_path = images_dir / "example.jpg"
    annotation_path = dataset_dir / "annotations.csv"
    assert metadata is not None
    Image.new("RGB", (16, 16), "white").save(image_path)
    assert cv2.imread(str(image_path)) is not None
    annotation_path.write_text(
        "TRAIN,"
        f"{image_path},"
        "ace_clubs,0.0625,0.0625,,,0.5625,0.5625,,\n"
    )

    train_data, validation_data, test_data = object_detector.DataLoader.from_csv(
        str(annotation_path), cache_dir=str(tmp_path / "cache")
    )
    assert train_data is not None
    assert train_data.size == 1
    assert validation_data is None
    assert test_data is None
