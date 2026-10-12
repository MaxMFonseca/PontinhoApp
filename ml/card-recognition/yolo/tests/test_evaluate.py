"""Exact-hand metrics, validation selection, and one-shot holdout contracts."""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from pontinho_yolo import evaluate as evaluate_module
from pontinho_yolo.evaluate import (
    EvaluationError,
    PredictionPartition,
    RawScenePrediction,
    RepositoryIdentity,
    TruthDetection,
    TruthPartition,
    TruthScene,
    canonical_holdout_active_path,
    canonical_holdout_receipt_path,
    evaluate_holdout,
    evaluate_predictions,
    select_thresholds,
    verify_committed_holdout_receipt,
)
from pontinho_yolo.postprocess import Box, LetterboxGeometry

CHECKPOINT_BYTES = b"checkpoint"
CHECKPOINT_SHA256 = hashlib.sha256(CHECKPOINT_BYTES).hexdigest()
RECEIPT_SHA256 = "e" * 64
AUDIT_SHA256 = "f" * 64
INVOCATION_SHA256 = "1" * 64


def _raw(*cards: tuple[int, float, float, float, float, float]) -> np.ndarray:
    output = np.zeros((1, 56, 8400), dtype=np.float32)
    for anchor, (class_index, score, x, y, width, height) in enumerate(cards):
        output[0, :4, anchor] = (x / 640, y / 640, width / 640, height / 640)
        output[0, 4 + class_index, anchor] = score
    return output


def _partitions(
    partition: str,
    scenes: list[tuple[str, np.ndarray, tuple[str, ...]]],
    *,
    source_model_sha256: str = CHECKPOINT_SHA256,
) -> tuple[PredictionPartition, TruthPartition]:
    digest = "a" * 64
    predictions = PredictionPartition(
        partition=partition,
        dataset_sha256=digest,
        source_model_sha256=source_model_sha256,
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
        scenes=tuple(
            TruthScene(
                scene_id,
                tuple(
                    TruthDetection(label, Box(80 + index * 100, 70, 120 + index * 100, 130))
                    for index, label in enumerate(labels)
                ),
            )
            for scene_id, _, labels in scenes
        ),
    )
    return predictions, truth


def _checkpoint(tmp_path: Path) -> Path:
    checkpoint = tmp_path / "best.pt"
    checkpoint.write_bytes(CHECKPOINT_BYTES)
    return checkpoint


def _install_repository(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, *, name: str = "repo"
) -> RepositoryIdentity:
    root = tmp_path / name
    common_git_dir = tmp_path / "common-git"
    (root / "ml" / "card-recognition" / "yolo" / "reports").mkdir(parents=True)
    common_git_dir.mkdir(exist_ok=True)
    identity = RepositoryIdentity(root, common_git_dir)
    monkeypatch.setattr(evaluate_module, "_discover_repository_identity", lambda: identity)
    return identity


def test_repository_identity_is_derived_from_module_not_caller_cwd(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)

    identity = evaluate_module._discover_repository_identity()

    assert identity.yolo_root == Path(__file__).resolve().parents[1]
    assert identity.common_git_dir.is_absolute()
    assert identity.common_git_dir.is_dir()


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
    assert report.exact_detection_accuracy == pytest.approx(1 / 3)
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
    assert report.confusion == {
        "2C": {"2C": 2},
        "2D": {"__background__": 1},
        "3D": {"3H": 1},
    }
    assert report.per_class["3D"].false_negative_count == 1
    assert report.per_class["3H"].false_positive_count == 1


def test_label_only_match_does_not_pass_localization_metric() -> None:
    """A right label at the wrong image location is not a correct detection."""
    predictions, truth = _partitions(
        "validation",
        [("mislocalized", _raw((4, 0.9, 500, 500, 40, 60)), ("2C",))],
    )

    report = evaluate_predictions(predictions, truth, confidence=0.5, iou=0.5)

    assert report.exact_multiset_accuracy == 1.0
    assert report.exact_total_accuracy == 1.0
    assert report.exact_detection_accuracy == 0.0
    assert report.precision == 0.0
    assert report.recall == 0.0
    assert report.per_class["2C"].false_positive_count == 1
    assert report.per_class["2C"].false_negative_count == 1
    assert report.confusion == {
        "2C": {"__background__": 1},
        "__background__": {"2C": 1},
    }


def test_class_correct_match_wins_before_wrong_class_confusion_association() -> None:
    """A higher-IoU wrong class must not steal GT from a valid same-class detection."""
    predictions, truth = _partitions(
        "validation",
        [
            (
                "competing",
                _raw(
                    (5, 0.95, 100, 100, 42, 62),
                    (4, 0.90, 105, 100, 40, 60),
                ),
                ("2C",),
            )
        ],
    )

    report = evaluate_predictions(predictions, truth, confidence=0.5, iou=0.5)

    assert report.true_positive_count == 1
    assert report.false_positive_count == 1
    assert report.false_negative_count == 0
    assert report.recall == 1.0
    assert report.confusion == {
        "2C": {"2C": 1},
        "__background__": {"2D": 1},
    }


def test_threshold_selection_uses_validation_only_and_published_tie_break_order(
    tmp_path: Path,
) -> None:
    """Changing tie priorities could select a lower-confidence or test-tuned decoder."""
    predictions, truth = _partitions(
        "validation",
        [("one", _raw((4, 0.91, 100, 100, 40, 60)), ("2C",))],
    )

    selection = select_thresholds(predictions, truth, checkpoint=_checkpoint(tmp_path))

    assert selection.confidence == pytest.approx(0.90)
    assert selection.iou == pytest.approx(0.30)
    assert selection.validation_report.exact_multiset_accuracy == 1.0
    assert selection.validation_dataset_sha256 == "a" * 64
    assert selection.source_checkpoint_sha256 == CHECKPOINT_SHA256

    test_predictions, test_truth = _partitions("test", [])
    with pytest.raises(EvaluationError, match="validation"):
        select_thresholds(test_predictions, test_truth, checkpoint=_checkpoint(tmp_path))


def test_test_truth_cannot_be_mixed_into_validation_selection(tmp_path: Path) -> None:
    """A partition label mismatch is a direct threshold-selection leakage path."""
    predictions, _ = _partitions("validation", [])
    _, test_truth = _partitions("test", [])

    with pytest.raises(EvaluationError, match="same partition"):
        select_thresholds(predictions, test_truth, checkpoint=_checkpoint(tmp_path))


def test_threshold_selection_verifies_predictions_against_checkpoint_bytes(tmp_path: Path) -> None:
    predictions, truth = _partitions(
        "validation",
        [("one", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
        source_model_sha256="0" * 64,
    )

    with pytest.raises(EvaluationError, match="source model"):
        select_thresholds(predictions, truth, checkpoint=_checkpoint(tmp_path))


def test_prediction_partition_factory_hashes_the_inference_model(tmp_path: Path) -> None:
    checkpoint = _checkpoint(tmp_path)

    partition = PredictionPartition.from_model(
        partition="validation",
        dataset_sha256="a" * 64,
        source_model=checkpoint,
        scenes=(),
    )

    assert partition.source_model_sha256 == CHECKPOINT_SHA256


def test_threshold_ranking_prioritizes_metrics_in_the_published_order(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Reordering exact total, recall, or FP share would select a different decoder."""
    predictions, truth = _partitions(
        "validation",
        [("one", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    metrics = {
        0.10: (1.00, 0.90, 0.80, 1.00, 0.00),
        0.20: (0.90, 0.90, 0.90, 0.80, 0.00),
        0.30: (0.90, 0.90, 0.90, 0.90, 0.30),
        0.40: (0.90, 0.90, 0.90, 0.90, 0.20),
        0.50: (0.10, 0.90, 0.90, 0.90, 0.20),
    }
    original_evaluate = evaluate_module.evaluate_predictions

    def fake_evaluate(
        predictions: PredictionPartition,
        truth: TruthPartition,
        *,
        confidence: float,
        iou: float,
        candidate_sha256: str | None = None,
    ):
        del candidate_sha256
        exact_detection, exact_multiset, exact_total, recall, false_positive_share = metrics.get(
            confidence, (0.0, 0.0, 0.0, 0.0, 1.0)
        )
        template = original_evaluate(predictions, truth, confidence=0.05, iou=0.30)
        return replace(
            template,
            confidence=confidence,
            iou=iou,
            exact_detection_accuracy=exact_detection,
            recall=recall,
            false_positive_share=false_positive_share,
            exact_multiset_accuracy=exact_multiset,
            exact_total_accuracy=exact_total,
        )

    monkeypatch.setattr(evaluate_module, "evaluate_predictions", fake_evaluate)

    selection = select_thresholds(predictions, truth, checkpoint=_checkpoint(tmp_path))

    assert selection.confidence == 0.50
    assert selection.iou == 0.30


def test_holdout_is_claimed_before_read_and_cannot_be_opened_twice(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A second test-set look could influence candidate or threshold choices."""
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(
        validation_predictions, validation_truth, checkpoint=_checkpoint(tmp_path)
    )
    test_predictions, test_truth = _partitions(
        "test",
        [("test", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
        source_model_sha256="b" * 64,
    )
    opens = 0

    def open_test() -> tuple[PredictionPartition, TruthPartition]:
        nonlocal opens
        opens += 1
        return test_predictions, test_truth

    _install_repository(monkeypatch, tmp_path)
    receipt = canonical_holdout_receipt_path()
    active = canonical_holdout_active_path()
    report = evaluate_holdout(
        open_test,
        selection=selection,
        candidate_sha256="b" * 64,
        dataset_receipt_sha256=RECEIPT_SHA256,
        dataset_audit_sha256=AUDIT_SHA256,
        invocation_sha256=INVOCATION_SHA256,
    )

    assert opens == 1
    assert report.partition == "test"
    assert report.exact_multiset_accuracy == 1.0
    claim = json.loads(receipt.read_text(encoding="utf-8"))
    assert claim["status"] == "completed"
    assert claim["candidateSha256"] == "b" * 64
    assert claim["selectionSha256"] == selection.sha256
    assert "recordChecksumSha256" in claim
    assert "recordSha256" not in claim
    assert json.loads(active.read_text(encoding="utf-8"))["status"] == "completed"

    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            open_test,
            selection=selection,
            candidate_sha256="b" * 64,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )
    assert opens == 1

    active.unlink()
    evaluate_module._CLAIMED_HOLDOUT_PATHS.clear()
    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            open_test,
            selection=selection,
            candidate_sha256="b" * 64,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )
    assert opens == 1


def test_failed_holdout_open_remains_burned_and_cannot_be_retried(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Deleting a failed look would permit iterative tuning against the test partition."""
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(
        validation_predictions, validation_truth, checkpoint=_checkpoint(tmp_path)
    )
    _install_repository(monkeypatch, tmp_path)
    active = canonical_holdout_active_path()

    def fail_after_open():
        raise RuntimeError("test reader failed after access")

    with pytest.raises(RuntimeError, match="reader failed"):
        evaluate_holdout(
            fail_after_open,
            selection=selection,
            candidate_sha256="c" * 64,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )

    assert json.loads(active.read_text(encoding="utf-8"))["status"] == "started"
    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            fail_after_open,
            selection=selection,
            candidate_sha256="c" * 64,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )


def test_holdout_detects_active_claim_replacement_during_the_only_open(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(
        validation_predictions, validation_truth, checkpoint=_checkpoint(tmp_path)
    )
    test_partitions = _partitions(
        "test",
        [("test", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
        source_model_sha256="9" * 64,
    )
    _install_repository(monkeypatch, tmp_path)
    active = canonical_holdout_active_path()

    def replace_active_claim():
        replacement = active.with_name("replacement.json")
        replacement.write_bytes(active.read_bytes())
        replacement.replace(active)
        return test_partitions

    with pytest.raises(EvaluationError, match="deleted or replaced"):
        evaluate_holdout(
            replace_active_claim,
            selection=selection,
            candidate_sha256="9" * 64,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )


def test_shared_git_common_claim_and_durable_receipt_block_other_worktrees_and_clones(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(
        validation_predictions, validation_truth, checkpoint=_checkpoint(tmp_path)
    )
    candidate_sha256 = "8" * 64
    test_partitions = _partitions(
        "test",
        [("test", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
        source_model_sha256=candidate_sha256,
    )
    first = _install_repository(monkeypatch, tmp_path, name="worktree-one")
    opens = 0

    def open_test():
        nonlocal opens
        opens += 1
        return test_partitions

    evaluate_holdout(
        open_test,
        selection=selection,
        candidate_sha256=candidate_sha256,
        dataset_receipt_sha256=RECEIPT_SHA256,
        dataset_audit_sha256=AUDIT_SHA256,
        invocation_sha256=INVOCATION_SHA256,
    )
    first_receipt = canonical_holdout_receipt_path()
    shared_active = canonical_holdout_active_path()

    second_root = tmp_path / "worktree-two"
    (second_root / "ml" / "card-recognition" / "yolo" / "reports").mkdir(parents=True)
    second = RepositoryIdentity(second_root, first.common_git_dir)
    monkeypatch.setattr(evaluate_module, "_discover_repository_identity", lambda: second)
    assert canonical_holdout_active_path() == shared_active
    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            open_test,
            selection=selection,
            candidate_sha256=candidate_sha256,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )

    clone_root = tmp_path / "clean-clone"
    clone_common = tmp_path / "clone-git"
    clone_receipt = (
        clone_root / "ml" / "card-recognition" / "yolo" / "reports" / "test-evaluation-receipt.json"
    )
    clone_receipt.parent.mkdir(parents=True)
    clone_common.mkdir()
    clone_receipt.write_bytes(first_receipt.read_bytes())
    clone = RepositoryIdentity(clone_root, clone_common)
    monkeypatch.setattr(evaluate_module, "_discover_repository_identity", lambda: clone)
    evaluate_module._CLAIMED_HOLDOUT_PATHS.clear()
    with pytest.raises(EvaluationError, match="already claimed"):
        evaluate_holdout(
            open_test,
            selection=selection,
            candidate_sha256=candidate_sha256,
            dataset_receipt_sha256=RECEIPT_SHA256,
            dataset_audit_sha256=AUDIT_SHA256,
            invocation_sha256=INVOCATION_SHA256,
        )
    assert opens == 1


def test_durable_receipt_verifier_requires_exact_bytes_committed_to_head(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "repo"
    reports = root / "ml" / "card-recognition" / "yolo" / "reports"
    reports.mkdir(parents=True)
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    identity = RepositoryIdentity(root, root / ".git")
    monkeypatch.setattr(evaluate_module, "_discover_repository_identity", lambda: identity)
    validation_predictions, validation_truth = _partitions(
        "validation",
        [("validation", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
    )
    selection = select_thresholds(
        validation_predictions, validation_truth, checkpoint=_checkpoint(tmp_path)
    )
    candidate_sha256 = "7" * 64
    test_partitions = _partitions(
        "test",
        [("test", _raw((4, 0.9, 100, 100, 40, 60)), ("2C",))],
        source_model_sha256=candidate_sha256,
    )
    evaluate_holdout(
        lambda: test_partitions,
        selection=selection,
        candidate_sha256=candidate_sha256,
        dataset_receipt_sha256=RECEIPT_SHA256,
        dataset_audit_sha256=AUDIT_SHA256,
        invocation_sha256=INVOCATION_SHA256,
    )

    with pytest.raises(EvaluationError, match="committed"):
        verify_committed_holdout_receipt()

    relative = "ml/card-recognition/yolo/reports/test-evaluation-receipt.json"
    subprocess.run(["git", "-C", str(root), "add", "--", relative], check=True)
    subprocess.run(
        [
            "git",
            "-C",
            str(root),
            "-c",
            "user.name=Task5 Test",
            "-c",
            "user.email=task5@example.invalid",
            "commit",
            "-q",
            "-m",
            "record holdout receipt",
        ],
        check=True,
    )

    record, digest = verify_committed_holdout_receipt()

    assert record["status"] == "completed"
    assert digest == hashlib.sha256(canonical_holdout_receipt_path().read_bytes()).hexdigest()
