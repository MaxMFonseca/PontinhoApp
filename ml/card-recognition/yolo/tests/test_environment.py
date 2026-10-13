"""Contract tests for the self-contained YOLO training environment."""

import os
from pathlib import Path

import pytest
import torch
import torchvision
import ultralytics
from ultralytics import YOLO

REPOSITORY_ROOT = Path(__file__).resolve().parents[4]


def test_pinned_runtime_uses_the_official_cuda_13_builds() -> None:
    """Fail if a clean sync can silently resolve PyTorch's CPU-only wheels."""
    assert ultralytics.__version__ == "8.4.177"
    assert YOLO is not None
    assert torch.__version__ == "2.14.1+cu130"
    assert torchvision.__version__ == "0.29.1+cu130"
    assert torch.version.cuda == "13.0"


def test_required_training_host_is_the_verified_ada_gpu() -> None:
    """Fail closed on the training host without burdening portable unit-test runners."""
    if os.environ.get("PONTINHO_REQUIRE_CUDA_HOST") != "1":
        pytest.skip("set PONTINHO_REQUIRE_CUDA_HOST=1 on the designated training host")

    assert torch.cuda.is_available()
    assert torch.cuda.device_count() >= 1
    assert torch.cuda.get_device_name(0) == "NVIDIA GeForce RTX 4070 Ti SUPER"
    assert torch.cuda.get_device_capability(0) == (8, 9)


def test_isolated_yolo_package_is_importable() -> None:
    """Fail if the project's installable YOLO package is absent from the environment."""
    import pontinho_yolo

    assert pontinho_yolo is not None


def test_repository_license_and_notices_cover_the_yolo_distribution() -> None:
    """Fail if distributed source loses its AGPL or upstream attributions."""
    license_text = (REPOSITORY_ROOT / "LICENSE").read_text(encoding="utf-8")
    notice_text = (REPOSITORY_ROOT / "NOTICE.md").read_text(encoding="utf-8")

    assert "GNU AFFERO GENERAL PUBLIC LICENSE" in license_text
    assert "Version 3" in license_text
    assert "Ultralytics" in notice_text
    assert "Roboflow" in notice_text
    assert "playing-cards-ow27d" in notice_text
