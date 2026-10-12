"""Contract tests for the self-contained YOLO training environment."""

from pathlib import Path

import torch
import ultralytics
from ultralytics import YOLO

REPOSITORY_ROOT = Path(__file__).resolve().parents[4]


def test_pinned_ultralytics_runtime_exposes_yolo_and_reports_cuda() -> None:
    """Fail if the reproducible training runtime is replaced or unavailable."""
    assert ultralytics.__version__ == "8.4.177"
    assert YOLO is not None
    assert isinstance(torch.cuda.is_available(), bool)


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
