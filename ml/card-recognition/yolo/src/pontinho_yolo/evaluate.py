"""Validation-only threshold selection and one-shot exact-hand evaluation."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.postprocess import LetterboxGeometry, TooManyDetections, decode_yolo

CONFIDENCE_THRESHOLDS = tuple(round(index * 0.05, 2) for index in range(1, 19))
IOU_THRESHOLDS = tuple(round(index * 0.05, 2) for index in range(6, 15))
_SHA256 = frozenset("0123456789abcdef")


class EvaluationError(ValueError):
    """Raised when evaluation inputs violate split or provenance constraints."""


@dataclass(frozen=True)
class RawScenePrediction:
    scene_id: str
    output: np.ndarray
    geometry: LetterboxGeometry


@dataclass(frozen=True)
class TruthScene:
    scene_id: str
    labels: tuple[str, ...]


@dataclass(frozen=True)
class PredictionPartition:
    partition: str
    dataset_sha256: str
    scenes: tuple[RawScenePrediction, ...]

    @property
    def sha256(self) -> str:
        digest = hashlib.sha256()
        digest.update(self.partition.encode())
        digest.update(bytes.fromhex(self.dataset_sha256))
        for scene in self.scenes:
            digest.update(scene.scene_id.encode())
            digest.update(b"\0")
            digest.update(np.ascontiguousarray(scene.output).tobytes())
            digest.update(repr(scene.geometry).encode())
        return digest.hexdigest()


@dataclass(frozen=True)
class TruthPartition:
    partition: str
    dataset_sha256: str
    scenes: tuple[TruthScene, ...]

    @property
    def sha256(self) -> str:
        return _hash_json(
            {
                "partition": self.partition,
                "datasetSha256": self.dataset_sha256,
                "scenes": [
                    {"sceneId": scene.scene_id, "labels": list(scene.labels)}
                    for scene in self.scenes
                ],
            }
        )


@dataclass(frozen=True)
class BucketMetrics:
    card_count: int
    supported: bool
    scene_count: int
    exact_multiset_accuracy: float | None
    exact_total_accuracy: float | None

    @classmethod
    def unsupported(cls, card_count: int) -> BucketMetrics:
        return cls(card_count, False, 0, None, None)

    def to_dict(self) -> dict[str, object]:
        return {
            "cardCount": self.card_count,
            "supported": self.supported,
            "sceneCount": self.scene_count,
            "exactMultisetAccuracy": self.exact_multiset_accuracy,
            "exactTotalAccuracy": self.exact_total_accuracy,
        }


@dataclass(frozen=True)
class EvaluationReport:
    partition: str
    dataset_sha256: str
    candidate_sha256: str | None
    confidence: float
    iou: float
    scene_count: int
    truth_card_count: int
    predicted_card_count: int
    true_positive_count: int
    false_positive_count: int
    false_negative_count: int
    precision: float
    recall: float
    false_positive_share: float
    exact_count_accuracy: float
    exact_multiset_accuracy: float
    exact_total_accuracy: float
    abstention_rate: float
    buckets: Mapping[int, BucketMetrics]

    def to_dict(self) -> dict[str, object]:
        return {
            "partition": self.partition,
            "datasetSha256": self.dataset_sha256,
            "candidateSha256": self.candidate_sha256,
            "thresholds": {"confidence": self.confidence, "iou": self.iou},
            "sceneCount": self.scene_count,
            "truthCardCount": self.truth_card_count,
            "predictedCardCount": self.predicted_card_count,
            "truePositiveCount": self.true_positive_count,
            "falsePositiveCount": self.false_positive_count,
            "falseNegativeCount": self.false_negative_count,
            "precision": self.precision,
            "recall": self.recall,
            "falsePositiveShare": self.false_positive_share,
            "exactCountAccuracy": self.exact_count_accuracy,
            "exactMultisetAccuracy": self.exact_multiset_accuracy,
            "exactTotalAccuracy": self.exact_total_accuracy,
            "abstentionRate": self.abstention_rate,
            "buckets": {str(count): self.buckets[count].to_dict() for count in range(1, 11)},
        }

    @property
    def sha256(self) -> str:
        return _hash_json(self.to_dict())


@dataclass(frozen=True)
class ThresholdSelection:
    confidence: float
    iou: float
    validation_dataset_sha256: str
    validation_predictions_sha256: str
    validation_truth_sha256: str
    validation_report: EvaluationReport

    def to_dict(self) -> dict[str, object]:
        return {
            "selectedOn": "validation",
            "confidence": self.confidence,
            "iou": self.iou,
            "validationDatasetSha256": self.validation_dataset_sha256,
            "validationPredictionsSha256": self.validation_predictions_sha256,
            "validationTruthSha256": self.validation_truth_sha256,
            "validationReportSha256": self.validation_report.sha256,
        }

    @property
    def sha256(self) -> str:
        return _hash_json(self.to_dict())


def evaluate_predictions(
    predictions: PredictionPartition,
    truth: TruthPartition,
    *,
    confidence: float,
    iou: float,
    candidate_sha256: str | None = None,
) -> EvaluationReport:
    """Evaluate one already-open partition with exact hand-level metrics."""
    pairs = _validated_pairs(predictions, truth)
    scene_results: list[tuple[int, bool, bool, bool, int, int, int, bool]] = []
    for raw, expected in pairs:
        try:
            detections = decode_yolo(raw.output, raw.geometry, confidence, iou, max_cards=10)
        except TooManyDetections as error:
            detections = error.detections
        predicted_labels = tuple(item.label for item in detections)
        expected_counter = Counter(expected.labels)
        predicted_counter = Counter(predicted_labels)
        true_positives = sum((expected_counter & predicted_counter).values())
        false_positives = sum((predicted_counter - expected_counter).values())
        false_negatives = sum((expected_counter - predicted_counter).values())
        scene_results.append(
            (
                len(expected.labels),
                len(predicted_labels) == len(expected.labels),
                predicted_counter == expected_counter,
                _same_pontinho_totals(predicted_labels, expected.labels),
                true_positives,
                false_positives,
                false_negatives,
                not predicted_labels,
            )
        )

    scene_count = len(scene_results)
    truth_count = sum(item[0] for item in scene_results)
    true_positive_count = sum(item[4] for item in scene_results)
    false_positive_count = sum(item[5] for item in scene_results)
    false_negative_count = sum(item[6] for item in scene_results)
    predicted_count = true_positive_count + false_positive_count
    buckets: dict[int, BucketMetrics] = {}
    for card_count in range(1, 11):
        subset = [item for item in scene_results if item[0] == card_count]
        if not subset:
            buckets[card_count] = BucketMetrics.unsupported(card_count)
        else:
            buckets[card_count] = BucketMetrics(
                card_count=card_count,
                supported=True,
                scene_count=len(subset),
                exact_multiset_accuracy=sum(item[2] for item in subset) / len(subset),
                exact_total_accuracy=sum(item[3] for item in subset) / len(subset),
            )
    return EvaluationReport(
        partition=predictions.partition,
        dataset_sha256=predictions.dataset_sha256,
        candidate_sha256=candidate_sha256,
        confidence=confidence,
        iou=iou,
        scene_count=scene_count,
        truth_card_count=truth_count,
        predicted_card_count=predicted_count,
        true_positive_count=true_positive_count,
        false_positive_count=false_positive_count,
        false_negative_count=false_negative_count,
        precision=true_positive_count / predicted_count if predicted_count else 0.0,
        recall=true_positive_count / truth_count if truth_count else 0.0,
        false_positive_share=(false_positive_count / predicted_count if predicted_count else 0.0),
        exact_count_accuracy=(
            sum(item[1] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        exact_multiset_accuracy=(
            sum(item[2] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        exact_total_accuracy=(
            sum(item[3] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        abstention_rate=(
            sum(item[7] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        buckets=buckets,
    )


def select_thresholds(
    validation_predictions: PredictionPartition,
    truth: TruthPartition,
) -> ThresholdSelection:
    """Choose thresholds exclusively from a nonempty validation partition."""
    if validation_predictions.partition != truth.partition:
        raise EvaluationError("predictions and truth must identify the same partition")
    if validation_predictions.partition != "validation":
        raise EvaluationError("threshold selection accepts validation data only")
    if not validation_predictions.scenes:
        raise EvaluationError("threshold selection requires nonempty validation data")
    candidates: list[EvaluationReport] = []
    for confidence in CONFIDENCE_THRESHOLDS:
        for iou in IOU_THRESHOLDS:
            candidates.append(
                evaluate_predictions(
                    validation_predictions,
                    truth,
                    confidence=confidence,
                    iou=iou,
                )
            )
    selected = max(
        candidates,
        key=lambda report: (
            report.exact_multiset_accuracy,
            report.exact_total_accuracy,
            report.recall,
            -report.false_positive_share,
            report.confidence,
            report.iou,
        ),
    )
    return ThresholdSelection(
        confidence=selected.confidence,
        iou=selected.iou,
        validation_dataset_sha256=validation_predictions.dataset_sha256,
        validation_predictions_sha256=validation_predictions.sha256,
        validation_truth_sha256=truth.sha256,
        validation_report=selected,
    )


def evaluate_holdout(
    open_test: Callable[[], tuple[PredictionPartition, TruthPartition]],
    *,
    selection: ThresholdSelection,
    claim_path: Path,
    candidate_sha256: str,
) -> EvaluationReport:
    """Claim and read the untouched test partition exactly once for one selected model."""
    _validate_sha256(candidate_sha256, "candidate SHA-256")
    if selection.validation_report.partition != "validation":
        raise EvaluationError("threshold selection is not validation-derived")
    claim_path = Path(claim_path)
    claim_path.parent.mkdir(parents=True, exist_ok=True)
    started = {
        "schemaVersion": 1,
        "status": "started",
        "candidateSha256": candidate_sha256,
        "selectionSha256": selection.sha256,
        "validationDatasetSha256": selection.validation_dataset_sha256,
    }
    try:
        descriptor = os.open(claim_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError as error:
        raise EvaluationError(f"test partition is already claimed: {claim_path}") from error
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(started, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())

    predictions, truth = open_test()
    if predictions.partition != "test" or truth.partition != "test":
        raise EvaluationError("holdout evaluation requires the test partition")
    if predictions.dataset_sha256 != selection.validation_dataset_sha256:
        raise EvaluationError("test data does not belong to the validation-selected dataset")
    report = evaluate_predictions(
        predictions,
        truth,
        confidence=selection.confidence,
        iou=selection.iou,
        candidate_sha256=candidate_sha256,
    )
    completed = {
        **started,
        "status": "completed",
        "testPredictionsSha256": predictions.sha256,
        "testTruthSha256": truth.sha256,
        "evaluationSha256": report.sha256,
    }
    _atomic_json_replace(claim_path, completed)
    return report


def _validated_pairs(
    predictions: PredictionPartition, truth: TruthPartition
) -> tuple[tuple[RawScenePrediction, TruthScene], ...]:
    if predictions.partition not in {"validation", "test"}:
        raise EvaluationError("partition must be validation or test")
    if predictions.partition != truth.partition:
        raise EvaluationError("predictions and truth must identify the same partition")
    _validate_sha256(predictions.dataset_sha256, "prediction dataset SHA-256")
    _validate_sha256(truth.dataset_sha256, "truth dataset SHA-256")
    if predictions.dataset_sha256 != truth.dataset_sha256:
        raise EvaluationError("predictions and truth identify different datasets")
    predicted_ids = [scene.scene_id for scene in predictions.scenes]
    truth_ids = [scene.scene_id for scene in truth.scenes]
    if len(set(predicted_ids)) != len(predicted_ids) or len(set(truth_ids)) != len(truth_ids):
        raise EvaluationError("scene IDs must be unique")
    if predicted_ids != truth_ids:
        raise EvaluationError("prediction and truth scene order differs")
    for scene in truth.scenes:
        if not scene.labels or not all(label in CARD_LABELS for label in scene.labels):
            raise EvaluationError(f"scene {scene.scene_id!r} has invalid truth labels")
        if len(scene.labels) > 10:
            raise EvaluationError(f"scene {scene.scene_id!r} exceeds ten truth cards")
    return tuple(zip(predictions.scenes, truth.scenes, strict=True))


def _same_pontinho_totals(predicted: tuple[str, ...], truth: tuple[str, ...]) -> bool:
    return all(
        _pontinho_total(predicted, wild_rank, color) == _pontinho_total(truth, wild_rank, color)
        for wild_rank in ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        for color in ("red", "black")
    )


def _pontinho_total(labels: tuple[str, ...], wild_rank: str, color: str) -> int:
    red_suits = frozenset({"D", "H"})
    return sum(
        20
        if label[:-1] == wild_rank and ((label[-1] in red_suits) == (color == "red"))
        else 15
        if label[:-1] == "A"
        else min(int(label[:-1]) if label[:-1].isdigit() else 10, 10)
        for label in labels
    )


def _validate_sha256(value: str, description: str) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(char not in _SHA256 for char in value):
        raise EvaluationError(f"{description} is invalid")


def _hash_json(payload: object) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    ).hexdigest()


def _atomic_json_replace(path: Path, payload: object) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, indent=2, sort_keys=True, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
