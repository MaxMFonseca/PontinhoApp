"""Real-candidate dataset and inference adapter contracts."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

from pontinho_yolo import run_candidate as run_candidate_module
from pontinho_yolo.candidate import (
    _litert_thread_count,
    checkpoint_output_to_contract,
    load_truth_partition,
    prepare_image,
)
from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.run_candidate import _execution_dataset, _experimental_gates


def test_load_truth_partition_binds_sorted_images_and_pixel_boxes(tmp_path: Path) -> None:
    root = tmp_path / "dataset"
    images = root / "valid" / "images"
    labels = root / "valid" / "labels"
    images.mkdir(parents=True)
    labels.mkdir(parents=True)
    for stem in ("b", "a"):
        Image.new("RGB", (200, 100), (10, 20, 30)).save(images / f"{stem}.jpg")
        (labels / f"{stem}.txt").write_text("37 0.5 0.5 0.2 0.4\n", encoding="utf-8")

    dataset_sha256 = hashlib.sha256(b"dataset").hexdigest()
    partition, paths = load_truth_partition(root, "validation", dataset_sha256)

    assert [scene.scene_id for scene in partition.scenes] == ["a.jpg", "b.jpg"]
    assert [path.name for path in paths] == ["a.jpg", "b.jpg"]
    detection = partition.scenes[0].detections[0]
    assert detection.label == CARD_LABELS[37] == "AD"
    assert detection.box.as_tuple() == (80.0, 30.0, 120.0, 70.0)


def test_prepare_image_uses_centered_letterbox_and_rgb_normalization(tmp_path: Path) -> None:
    path = tmp_path / "wide.png"
    Image.new("RGB", (4, 2), (255, 0, 0)).save(path)

    tensor, geometry = prepare_image(path)

    assert tensor.shape == (3, 640, 640)
    assert tensor.dtype == np.float32
    assert geometry.resized_width == 640
    assert geometry.resized_height == 320
    assert geometry.pad_y == 160.0
    assert np.allclose(tensor[:, 0, 0], np.array([114, 114, 114]) / 255.0)
    assert np.allclose(tensor[:, 320, 320], np.array([1.0, 0.0, 0.0]))


def test_prepare_image_matches_pinned_opencv_full_tensor_oracle(tmp_path: Path) -> None:
    fixture = json.loads(
        (Path(__file__).parent / "fixtures" / "android-yolo-golden.json").read_text()
    )
    case = fixture["preprocessCases"][0]
    source = np.asarray(case["sourceRgb"], dtype=np.uint8).reshape(
        case["sourceHeight"], case["sourceWidth"], 3
    )
    path = tmp_path / "source.png"
    Image.fromarray(source, mode="RGB").save(path)

    tensor, _ = prepare_image(path)
    normalized_hwc = np.ascontiguousarray(tensor.transpose(1, 2, 0)).astype("<f4")

    assert hashlib.sha256(normalized_hwc.tobytes()).hexdigest() == case[
        "expectedNormalizedFloat32Sha256"
    ]


def test_checkpoint_output_is_normalized_to_pinned_raw_contract() -> None:
    output = np.zeros((1, 56, 8400), dtype=np.float32)
    output[:, :4, :] = 320.0
    output[:, 4:, :] = 0.25

    normalized = checkpoint_output_to_contract(output)

    assert normalized.shape == (1, 56, 8400)
    assert normalized.dtype == np.float32
    assert np.all(normalized[:, :4, :] == 0.5)
    assert np.all(normalized[:, 4:, :] == 0.25)
    assert np.all(output[:, :4, :] == 320.0)


def test_litert_uses_bounded_parallel_cpu_threads(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("pontinho_yolo.candidate.os.cpu_count", lambda: 32)
    assert _litert_thread_count() == 8

    monkeypatch.setattr("pontinho_yolo.candidate.os.cpu_count", lambda: None)
    assert _litert_thread_count() == 1


def test_public_dataset_gates_never_claim_physical_or_production_approval() -> None:
    report = SimpleNamespace(
        recall=0.99,
        false_positive_share=0.005,
        exact_multiset_accuracy=0.95,
        exact_total_accuracy=0.98,
    )

    gates = _experimental_gates(report)  # type: ignore[arg-type]

    assert gates["publicDatasetMetricGatesPassed"] is True
    assert gates["physicalDeckGateExecuted"] is False
    assert gates["productionGatePassed"] is False


def test_execution_dataset_accepts_only_a_fully_matching_absolute_mirror(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    yolo_root = tmp_path / "yolo"
    canonical = yolo_root / "dataset"
    mirror = tmp_path / "mirror"
    canonical.mkdir(parents=True)
    mirror.mkdir()
    audit_path = yolo_root / "audit.json"
    audit_path.write_text('{"tree_sha256":"a"}\n', encoding="utf-8")
    receipt_path = yolo_root / "source.json"
    receipt_path.write_text("{}\n", encoding="utf-8")
    monkeypatch.setenv("PONTINHO_TASK10_DATASET_MIRROR", str(mirror.resolve()))
    monkeypatch.setattr(
        run_candidate_module.SourceReceipt,
        "from_path",
        lambda _path: object(),
    )
    monkeypatch.setattr(
        run_candidate_module,
        "audit_dataset",
        lambda _root, _receipt: SimpleNamespace(to_dict=lambda: {"tree_sha256": "a"}),
    )

    root, config = _execution_dataset(yolo_root, canonical, audit_path, receipt_path)

    assert root == mirror.resolve()
    payload = config.read_text(encoding="utf-8")
    assert (mirror / "train" / "images").as_posix() in payload
    assert "test:" in payload
    assert "51: QS" in payload
