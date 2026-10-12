"""Exact-hand metrics, validation selection, and one-shot holdout contracts."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from pontinho_yolo import evaluate as evaluate_module
from pontinho_yolo.evaluate import (
    EvaluationError,
    PredictionPartition,
    RawScenePrediction,
    TruthPartition,
    TruthScene,
    evaluate_holdout,
    evaluate_predictions,
    select_thresholds,
)
from pontinho_yolo.postprocess import LetterboxGeometry


def _raw(*cards: tuple[int, float, float, float, float, float]) -> np.ndarray:
    output = np.zeros((1, 56, 8400), dtype=np.float32)
    for anchor, (class_index, score, x, y, width, height) in enumerate(cards):
        output[0, :4, anchor] = (x, y, width, height)
        output[0, 4 + class_index, anchor] = score
    return output


def _partitions(
    partition: str,
    scenes: list[tuple[str, np.ndarray, tuple[str, ...]]],
) -> tuple[PredictionPartition, TruthPartition]:
    digest = "a" * 64
    predictions = PredictionPartition(
        partition=partition,
        dataset_sha256=digest,
        scenes=tuple(
            RawScenePrediction(
                scene_id=scene_id,
                output=output,
                geometry=LetterboxGeometry.for_source(640, 640),
            )
            for scene_id, output, _ in scenes
        ),
    )
    truth = TruthPartition(
        partition=partition,
        dataset_sha256=digest,
        scenes=tuple(TruthScene(scene_id, labels) for scene_id, _, labels in scenes),
    )
    return predictions, truth


def test_metrics_separate_count_multiset_and_pontinho_total() -> None:
    """Score-cancelling class errors must not count as exact physical-card recognition."""
    predictions, truth = _partitions(
        "validation",
        [
            ("exact", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",)),
            ("cancel", _raw((10, 0.9, 100, 100, 40, 60)), ("3D",)),
            (
                "wrong-count",
                _raw((4, 0.9, 100, 100, 40, 60)),
                ("2C", "2D"),
            ),
        ],
    )

    report = evaluate_predictions(predictions, truth, confidence=0.5, iou=0.5)

    assert report.partition == "validation"
    assert report.scene_count == 3
    assert report.exact_count_accuracy == pytest.approx(2 / 3)
    assert report.exact_multiset_accuracy == pytest.approx(1 / 3)
    assert report.exact_total_accuracy == pytest.approx(2 / 3)
    assert report.recall == pytest.approx(2 / 4)
    assert report.precision == pytest.approx(2 / 3)
    assert report.false_positive_share == pytest.approx(1 / 3)
    assert report.buckets[1].supported is True
    assert report.buckets[1].exact_multiset_accuracy == pytest.approx(1 / 2)
    assert report.buckets[2].supported is True
    assert report.buckets[3].supported is False
    assert report.buckets[3].exact_multiset_accuracy is None


def test_threshold_selection_uses_validation_only_and_published_tie_break_order() -> None:
    """Changing tie priorities could select a lower-confidence or test-tuned decoder."""
    predictions, truth = _partitions(
        "validation",
        [("one", _raw((4, 0.91, 100, 100, 40, 60)), ("2C",))],
    )

    selection = select_thresholds(predictions, truth)

    assert selection.confidence == pytest.approx(0.90)
    assert selection.iou == pytest.approx(0.70)
    assert selection.validation_report.exact_multiset_accuracy == 1.0
    assert selection.validation_dataset_sha256 == "a" * 64

    test_predictions, test_truth = _partitions("test", [])
    with pytest.raises(EvaluationError, match="validation"):
        select_thresholds(test_predictions, test_truth)


def test_test_truth_cannot_be_mixed_into_validation_selection() -> None:
    """A partition label mismatch is a direct threshold-selection leakage path."""
    predictions, _ = _partitions("validation", [])
    _, test_truth = _partitions("test", [])

    with pytest.raises(EvaluationError, match="same partition"):
        select_thresholds(predictions, test_truth)


def test_threshold_ranking_prioritizes_metrics_in_the_published_order(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reordering exact total, recall, or FP share would select a different decoder."""
    predictions, truth = _partitions(
        "validation",
        [("one", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    metrics = {
        0.10: (0.90, 0.80, 1.00, 0.00),
        0.20: (0.90, 0.90, 0.80, 0.00),
        0.30: (0.90, 0.90, 0.90, 0.30),
        0.40: (0.90, 0.90, 0.90, 0.20),
        0.50: (0.90, 0.90, 0.90, 0.20),
    }

    def fake_evaluate(
        predictions: PredictionPartition,
        truth: TruthPartition,
        *,
        confidence: float,
        iou: float,
        candidate_sha256: str | None = None,
    ):
        del predictions, truth, candidate_sha256
        exact_multiset, exact_total, recall, false_positive_share = metrics.get(
            confidence, (0.0, 0.0, 0.0, 1.0)
        )
        return evaluate_module.EvaluationReport(
            partition="validation",
            dataset_sha256="a" * 64,
            candidate_sha256=None,
            confidence=confidence,
            iou=iou,
            scene_count=1,
            truth_card_count=1,
            predicted_card_count=1,
            true_positive_count=1,
            false_positive_count=0,
            false_negative_count=0,
            precision=1.0,
            recall=recall,
            false_positive_share=false_positive_share,
            exact_count_accuracy=1.0,
            exact_multiset_accuracy=exact_multiset,
            exact_total_accuracy=exact_total,
            abstention_rate=0.0,
            buckets={
                count: evaluate_module.BucketMetrics.unsupported(count) for count in range(1, 11)
            },
        )

    monkeypatch.setattr(evaluate_module, "evaluate_predictions", fake_evaluate)

    selection = select_thresholds(predictions, truth)

    assert selection.confidence == 0.50
    assert selection.iou == 0.70


def test_holdout_is_claimed_before_read_and_cannot_be_opened_twice(tmp_path: Path) -> None:
    """A second test-set look could influence candidate or threshold choices."""
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(validation_predictions, validation_truth)
    test_predictions, test_truth = _partitions(
        "test",
        [("test", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    opens = 0

    def open_test() -> tuple[PredictionPartition, TruthPartition]:
        nonlocal opens
        opens += 1
        return test_predictions, test_truth

    receipt = tmp_path / "test-evaluation.claim.json"
    report = evaluate_holdout(
        open_test,
        selection=selection,
        claim_path=receipt,
        candidate_sha256="b" * 64,
    )

    assert opens == 1
    assert report.partition == "test"
    assert report.exact_multiset_accuracy == 1.0
    claim = json.loads(receipt.read_text(encoding="utf-8"))
    assert claim["status"] == "completed"
    assert claim["candidateSha256"] == "b" * 64
    assert claim["selectionSha256"] == selection.sha256

    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            open_test,
            selection=selection,
            claim_path=receipt,
            candidate_sha256="b" * 64,
        )
    assert opens == 1


def test_failed_holdout_open_remains_burned_and_cannot_be_retried(tmp_path: Path) -> None:
    """Deleting a failed look would permit iterative tuning against the test partition."""
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(validation_predictions, validation_truth)
    receipt = tmp_path / "burned.claim.json"

    def fail_after_open():
        raise RuntimeError("test reader failed after access")

    with pytest.raises(RuntimeError, match="reader failed"):
        evaluate_holdout(
            fail_after_open,
            selection=selection,
            claim_path=receipt,
            candidate_sha256="c" * 64,
        )

    assert json.loads(receipt.read_text(encoding="utf-8"))["status"] == "started"
    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            fail_after_open,
            selection=selection,
            claim_path=receipt,
            candidate_sha256="c" * 64,
        )
