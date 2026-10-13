"""Validation-only threshold selection and one-shot exact-hand evaluation."""

from __future__ import annotations

import hashlib
import json
import math
import os
import subprocess
import tempfile
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.postprocess import (
    Box,
    Detection,
    IncompleteDetections,
    LetterboxGeometry,
    TooManyDetections,
    decode_yolo,
)

CONFIDENCE_THRESHOLDS = tuple(round(index * 0.05, 2) for index in range(1, 19))
IOU_THRESHOLDS = tuple(round(index * 0.05, 2) for index in range(6, 15))
_SHA256 = frozenset("0123456789abcdef")
LOCALIZATION_IOU = 0.50
BACKGROUND_LABEL = "__background__"
INCOMPLETE_CARDS_ABSTENTION = "INCOMPLETE_CARDS"
NO_CARDS_ABSTENTION = "NO_CARDS"
_CLAIMED_HOLDOUT_PATHS: set[Path] = set()


class EvaluationError(ValueError):
    """Raised when evaluation inputs violate split or provenance constraints."""


@dataclass(frozen=True)
class RepositoryIdentity:
    """Internally discovered worktree root and Git common state shared by worktrees."""

    worktree_root: Path
    common_git_dir: Path

    @property
    def yolo_root(self) -> Path:
        return self.worktree_root / "ml" / "card-recognition" / "yolo"

    @property
    def common_dir_sha256(self) -> str:
        canonical = os.path.normcase(str(self.common_git_dir.resolve()))
        return hashlib.sha256(canonical.encode()).hexdigest()


@dataclass(frozen=True)
class RawScenePrediction:
    scene_id: str
    output: np.ndarray
    geometry: LetterboxGeometry


@dataclass(frozen=True)
class TruthDetection:
    label: str
    box: Box


@dataclass(frozen=True)
class TruthScene:
    scene_id: str
    detections: tuple[TruthDetection, ...]

    @property
    def labels(self) -> tuple[str, ...]:
        return tuple(item.label for item in self.detections)


@dataclass(frozen=True)
class PredictionPartition:
    partition: str
    dataset_sha256: str
    source_model_sha256: str
    scenes: tuple[RawScenePrediction, ...]

    @classmethod
    def from_model(
        cls,
        *,
        partition: str,
        dataset_sha256: str,
        source_model: Path,
        scenes: tuple[RawScenePrediction, ...],
    ) -> PredictionPartition:
        """Bind inference outputs to the exact regular model file that produced them."""
        source_model = Path(source_model)
        if source_model.is_symlink() or not source_model.is_file():
            raise EvaluationError("prediction source model must be a regular file")
        return cls(partition, dataset_sha256, _hash_file(source_model), scenes)

    @property
    def sha256(self) -> str:
        digest = hashlib.sha256()
        digest.update(self.partition.encode())
        digest.update(bytes.fromhex(self.dataset_sha256))
        digest.update(bytes.fromhex(self.source_model_sha256))
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
                    {
                        "sceneId": scene.scene_id,
                        "detections": [
                            {"label": item.label, "box": list(item.box.as_tuple())}
                            for item in scene.detections
                        ],
                    }
                    for scene in self.scenes
                ],
            }
        )


@dataclass(frozen=True)
class BucketMetrics:
    card_count: int
    supported: bool
    scene_count: int
    exact_detection_accuracy: float | None
    exact_multiset_accuracy: float | None
    exact_total_accuracy: float | None

    @classmethod
    def unsupported(cls, card_count: int) -> BucketMetrics:
        return cls(card_count, False, 0, None, None, None)

    def to_dict(self) -> dict[str, object]:
        return {
            "cardCount": self.card_count,
            "supported": self.supported,
            "sceneCount": self.scene_count,
            "exactDetectionAccuracy": self.exact_detection_accuracy,
            "exactMultisetAccuracy": self.exact_multiset_accuracy,
            "exactTotalAccuracy": self.exact_total_accuracy,
        }


@dataclass(frozen=True)
class ClassMetrics:
    true_positive_count: int
    false_positive_count: int
    false_negative_count: int
    precision: float
    recall: float

    @classmethod
    def from_counts(
        cls, true_positive: int, false_positive: int, false_negative: int
    ) -> ClassMetrics:
        predicted = true_positive + false_positive
        expected = true_positive + false_negative
        return cls(
            true_positive,
            false_positive,
            false_negative,
            true_positive / predicted if predicted else 0.0,
            true_positive / expected if expected else 0.0,
        )

    def to_dict(self) -> dict[str, int | float]:
        return {
            "truePositiveCount": self.true_positive_count,
            "falsePositiveCount": self.false_positive_count,
            "falseNegativeCount": self.false_negative_count,
            "precision": self.precision,
            "recall": self.recall,
        }


@dataclass(frozen=True)
class EvaluationReport:
    partition: str
    dataset_sha256: str
    candidate_sha256: str | None
    confidence: float
    iou: float
    localization_iou: float
    scene_count: int
    truth_card_count: int
    predicted_card_count: int
    true_positive_count: int
    false_positive_count: int
    false_negative_count: int
    precision: float
    recall: float
    false_positive_share: float
    exact_detection_accuracy: float
    exact_count_accuracy: float
    exact_multiset_accuracy: float
    exact_total_accuracy: float
    abstention_count: int
    abstention_rate: float
    abstention_reasons: Mapping[str, int]
    per_class: Mapping[str, ClassMetrics]
    confusion: Mapping[str, Mapping[str, int]]
    buckets: Mapping[int, BucketMetrics]

    def to_dict(self) -> dict[str, object]:
        return {
            "partition": self.partition,
            "datasetSha256": self.dataset_sha256,
            "candidateSha256": self.candidate_sha256,
            "thresholds": {"confidence": self.confidence, "iou": self.iou},
            "localizationIou": self.localization_iou,
            "sceneCount": self.scene_count,
            "truthCardCount": self.truth_card_count,
            "predictedCardCount": self.predicted_card_count,
            "truePositiveCount": self.true_positive_count,
            "falsePositiveCount": self.false_positive_count,
            "falseNegativeCount": self.false_negative_count,
            "precision": self.precision,
            "recall": self.recall,
            "falsePositiveShare": self.false_positive_share,
            "exactDetectionAccuracy": self.exact_detection_accuracy,
            "exactCountAccuracy": self.exact_count_accuracy,
            "exactMultisetAccuracy": self.exact_multiset_accuracy,
            "exactTotalAccuracy": self.exact_total_accuracy,
            "abstentionCount": self.abstention_count,
            "abstentionRate": self.abstention_rate,
            "abstentionReasons": dict(sorted(self.abstention_reasons.items())),
            "perClass": {label: self.per_class[label].to_dict() for label in CARD_LABELS},
            "confusion": {
                truth_label: dict(sorted(predicted.items()))
                for truth_label, predicted in sorted(self.confusion.items())
            },
            "buckets": {str(count): self.buckets[count].to_dict() for count in range(1, 11)},
        }

    @property
    def sha256(self) -> str:
        return _hash_json(self.to_dict())


@dataclass(frozen=True)
class ThresholdSelection:
    source_checkpoint_sha256: str
    confidence: float
    iou: float
    validation_dataset_sha256: str
    validation_predictions_sha256: str
    validation_truth_sha256: str
    validation_report: EvaluationReport

    def to_dict(self) -> dict[str, object]:
        return {
            "selectedOn": "validation",
            "sourceCheckpointSha256": self.source_checkpoint_sha256,
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
    """Evaluate one partition with IoU-matched detections and separate hand metrics."""
    pairs = _validated_pairs(predictions, truth)
    scene_results: list[tuple[int, bool, bool, bool, bool, int, int, int, str | None]] = []
    class_counts = {label: [0, 0, 0] for label in CARD_LABELS}
    confusion: dict[str, dict[str, int]] = {}
    for raw, expected in pairs:
        abstention_reason = None
        try:
            detections = decode_yolo(raw.output, raw.geometry, confidence, iou, max_cards=10)
        except TooManyDetections as error:
            detections = error.detections
        except IncompleteDetections:
            detections = ()
            abstention_reason = INCOMPLETE_CARDS_ABSTENTION
        if not detections and abstention_reason is None:
            abstention_reason = NO_CARDS_ABSTENTION
        predicted_labels = tuple(item.label for item in detections)
        expected_counter = Counter(expected.labels)
        predicted_counter = Counter(predicted_labels)
        matches = _match_detections(detections, expected.detections)
        matched_predictions = {prediction_index for prediction_index, _ in matches}
        matched_truth = {truth_index for _, truth_index in matches}
        true_positives = 0
        for prediction_index, truth_index in matches:
            predicted_label = detections[prediction_index].label
            truth_label = expected.detections[truth_index].label
            row = confusion.setdefault(truth_label, {})
            row[predicted_label] = row.get(predicted_label, 0) + 1
            if predicted_label == truth_label:
                true_positives += 1
                class_counts[truth_label][0] += 1
            else:
                class_counts[predicted_label][1] += 1
                class_counts[truth_label][2] += 1
        for index, detection in enumerate(detections):
            if index not in matched_predictions:
                class_counts[detection.label][1] += 1
                row = confusion.setdefault(BACKGROUND_LABEL, {})
                row[detection.label] = row.get(detection.label, 0) + 1
        for index, detection in enumerate(expected.detections):
            if index not in matched_truth:
                class_counts[detection.label][2] += 1
                row = confusion.setdefault(detection.label, {})
                row[BACKGROUND_LABEL] = row.get(BACKGROUND_LABEL, 0) + 1
        false_positives = len(detections) - true_positives
        false_negatives = len(expected.detections) - true_positives
        exact_detection = len(detections) == len(expected.detections) and true_positives == len(
            expected.detections
        )
        scene_results.append(
            (
                len(expected.labels),
                exact_detection,
                len(predicted_labels) == len(expected.labels),
                predicted_counter == expected_counter,
                _same_pontinho_totals(predicted_labels, expected.labels),
                true_positives,
                false_positives,
                false_negatives,
                abstention_reason,
            )
        )

    scene_count = len(scene_results)
    truth_count = sum(item[0] for item in scene_results)
    true_positive_count = sum(item[5] for item in scene_results)
    false_positive_count = sum(item[6] for item in scene_results)
    false_negative_count = sum(item[7] for item in scene_results)
    abstention_reasons = Counter(item[8] for item in scene_results if item[8] is not None)
    abstention_count = sum(abstention_reasons.values())
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
                exact_detection_accuracy=sum(item[1] for item in subset) / len(subset),
                exact_multiset_accuracy=sum(item[3] for item in subset) / len(subset),
                exact_total_accuracy=sum(item[4] for item in subset) / len(subset),
            )
    return EvaluationReport(
        partition=predictions.partition,
        dataset_sha256=predictions.dataset_sha256,
        candidate_sha256=candidate_sha256,
        confidence=confidence,
        iou=iou,
        localization_iou=LOCALIZATION_IOU,
        scene_count=scene_count,
        truth_card_count=truth_count,
        predicted_card_count=predicted_count,
        true_positive_count=true_positive_count,
        false_positive_count=false_positive_count,
        false_negative_count=false_negative_count,
        precision=true_positive_count / predicted_count if predicted_count else 0.0,
        recall=true_positive_count / truth_count if truth_count else 0.0,
        false_positive_share=(false_positive_count / predicted_count if predicted_count else 0.0),
        exact_detection_accuracy=(
            sum(item[1] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        exact_count_accuracy=(
            sum(item[2] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        exact_multiset_accuracy=(
            sum(item[3] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        exact_total_accuracy=(
            sum(item[4] for item in scene_results) / scene_count if scene_count else 0.0
        ),
        abstention_count=abstention_count,
        abstention_rate=abstention_count / scene_count if scene_count else 0.0,
        abstention_reasons=dict(abstention_reasons),
        per_class={label: ClassMetrics.from_counts(*class_counts[label]) for label in CARD_LABELS},
        confusion=confusion,
        buckets=buckets,
    )


def select_thresholds(
    validation_predictions: PredictionPartition,
    truth: TruthPartition,
    *,
    checkpoint: Path,
) -> ThresholdSelection:
    """Choose thresholds exclusively from a nonempty validation partition."""
    if validation_predictions.partition != truth.partition:
        raise EvaluationError("predictions and truth must identify the same partition")
    if validation_predictions.partition != "validation":
        raise EvaluationError("threshold selection accepts validation data only")
    if not validation_predictions.scenes:
        raise EvaluationError("threshold selection requires nonempty validation data")
    checkpoint = Path(checkpoint)
    if checkpoint.is_symlink() or not checkpoint.is_file():
        raise EvaluationError("source checkpoint must be a regular file")
    checkpoint_sha256 = _hash_file(checkpoint)
    if validation_predictions.source_model_sha256 != checkpoint_sha256:
        raise EvaluationError("validation predictions do not match the source model bytes")
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
        ),
    )
    return ThresholdSelection(
        source_checkpoint_sha256=checkpoint_sha256,
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
    candidate_sha256: str,
    dataset_receipt_sha256: str,
    dataset_audit_sha256: str,
    invocation_sha256: str,
) -> EvaluationReport:
    """Claim and read test once within the controlled canonical repository pipeline."""

    def evaluate() -> EvaluationReport:
        predictions, truth = open_test()
        if predictions.partition != "test" or truth.partition != "test":
            raise EvaluationError("holdout evaluation requires the test partition")
        if predictions.dataset_sha256 != selection.validation_dataset_sha256:
            raise EvaluationError("test data does not belong to the validation-selected dataset")
        if predictions.source_model_sha256 != candidate_sha256:
            raise EvaluationError("test predictions do not match the selected candidate bytes")
        return evaluate_predictions(
            predictions,
            truth,
            confidence=selection.confidence,
            iou=selection.iou,
            candidate_sha256=candidate_sha256,
        )

    return consume_holdout_once(
        evaluate,
        selection=selection,
        candidate_sha256=candidate_sha256,
        dataset_receipt_sha256=dataset_receipt_sha256,
        dataset_audit_sha256=dataset_audit_sha256,
        invocation_sha256=invocation_sha256,
    )


def consume_holdout_once(
    evaluate: Callable[[], EvaluationReport],
    *,
    selection: ThresholdSelection,
    candidate_sha256: str,
    dataset_receipt_sha256: str,
    dataset_audit_sha256: str,
    invocation_sha256: str,
) -> EvaluationReport:
    """Run one holdout action within the controlled canonical repository pipeline."""
    _validate_sha256(candidate_sha256, "candidate SHA-256")
    _validate_sha256(dataset_receipt_sha256, "dataset receipt SHA-256")
    _validate_sha256(dataset_audit_sha256, "dataset audit SHA-256")
    _validate_sha256(invocation_sha256, "invocation SHA-256")
    if selection.validation_report.partition != "validation":
        raise EvaluationError("threshold selection is not validation-derived")
    repository = _discover_repository_identity()
    active_path = canonical_holdout_active_path()
    receipt_path = canonical_holdout_receipt_path()
    active_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    started = {
        "schemaVersion": 3,
        "status": "started",
        "candidateSha256": candidate_sha256,
        "sourceCheckpointSha256": selection.source_checkpoint_sha256,
        "selectionSha256": selection.sha256,
        "validationDatasetSha256": selection.validation_dataset_sha256,
        "datasetReceiptSha256": dataset_receipt_sha256,
        "datasetAuditSha256": dataset_audit_sha256,
        "invocationSha256": invocation_sha256,
        "repositoryCommonDirSha256": repository.common_dir_sha256,
    }
    started = _record_with_checksum(started)
    if active_path in _CLAIMED_HOLDOUT_PATHS:
        raise EvaluationError(f"test partition is already claimed: {active_path}")
    if receipt_path.exists():
        _read_verified_record(receipt_path)
        raise EvaluationError(f"test partition is already claimed: {receipt_path}")
    try:
        _write_json_exclusive(active_path, started)
    except FileExistsError as error:
        raise EvaluationError(f"test partition is already claimed: {active_path}") from error
    _CLAIMED_HOLDOUT_PATHS.add(active_path)
    initial_identity = _file_identity(active_path)

    report = evaluate()
    if report.partition != "test":
        raise EvaluationError("holdout evaluation must return a test report")
    if report.dataset_sha256 != selection.validation_dataset_sha256:
        raise EvaluationError("test report belongs to a different dataset")
    if report.candidate_sha256 != candidate_sha256:
        raise EvaluationError("test report is not bound to the selected candidate bytes")
    if report.confidence != selection.confidence or report.iou != selection.iou:
        raise EvaluationError("test report did not use validation-selected thresholds")
    if (
        _file_identity(active_path) != initial_identity
        or _read_verified_record(active_path) != started
    ):
        raise EvaluationError(
            "test-evaluation active claim was deleted or replaced during evaluation"
        )
    completed = {
        **{key: value for key, value in started.items() if key != "recordChecksumSha256"},
        "status": "completed",
        "evaluationSha256": report.sha256,
    }
    completed = _record_with_checksum(completed)
    _atomic_json_replace(active_path, completed)
    try:
        _write_json_exclusive(receipt_path, completed)
    except FileExistsError as error:
        raise EvaluationError("durable holdout receipt already exists") from error
    return report


def canonical_holdout_active_path() -> Path:
    """Return shared active state under the internally discovered Git common directory."""
    repository = _discover_repository_identity()
    return repository.common_git_dir / "pontinho-card-recognition" / "test-evaluation.active.json"


def canonical_holdout_receipt_path() -> Path:
    """Return the tracked receipt path used to block clean post-evaluation clones."""
    repository = _discover_repository_identity()
    return repository.yolo_root / "reports" / "test-evaluation-receipt.json"


def verify_holdout_receipt() -> tuple[Mapping[str, object], str]:
    """Fail closed unless the canonical durable receipt is intact and completed."""
    path = canonical_holdout_receipt_path()
    record = _read_verified_record(path)
    if record.get("status") != "completed":
        raise EvaluationError("canonical test-evaluation receipt is not completed")
    identity_keys = (
        "candidateSha256",
        "sourceCheckpointSha256",
        "selectionSha256",
        "validationDatasetSha256",
        "datasetReceiptSha256",
        "datasetAuditSha256",
        "invocationSha256",
    )
    active_path = canonical_holdout_active_path()
    if active_path.exists():
        active = _read_verified_record(active_path)
        if any(record.get(key) != active.get(key) for key in identity_keys):
            raise EvaluationError("active holdout claim does not match durable receipt")
    return record, hashlib.sha256(path.read_bytes()).hexdigest()


def verify_committed_holdout_receipt() -> tuple[Mapping[str, object], str]:
    """Require the durable receipt's exact bytes at HEAD for post-Task-10 verification."""
    record, digest = verify_holdout_receipt()
    repository = _discover_repository_identity()
    path = canonical_holdout_receipt_path()
    try:
        relative = path.relative_to(repository.worktree_root).as_posix()
        result = subprocess.run(
            ["git", "-C", str(repository.worktree_root), "show", f"HEAD:{relative}"],
            check=True,
            capture_output=True,
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise EvaluationError("durable holdout receipt is not committed at HEAD") from error
    if result.stdout != path.read_bytes():
        raise EvaluationError("durable holdout receipt differs from the bytes committed at HEAD")
    return record, digest


def _discover_repository_identity() -> RepositoryIdentity:
    """Discover the current worktree and shared Git directory without caller input."""
    module_path = Path(__file__).resolve()
    worktree_text = _git_output(module_path.parent, "rev-parse", "--show-toplevel")
    worktree = Path(worktree_text).resolve()
    common_text = _git_output(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir")
    common = Path(common_text)
    if not common.is_absolute():
        common = worktree / common
    common = common.resolve()
    expected_yolo = worktree / "ml" / "card-recognition" / "yolo"
    if not expected_yolo.is_dir() or not common.is_dir():
        raise EvaluationError("canonical repository layout or Git common directory is missing")
    return RepositoryIdentity(worktree, common)


def _git_output(cwd: Path, *arguments: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(cwd), *arguments],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise EvaluationError("cannot discover canonical Git repository identity") from error
    value = result.stdout.strip()
    if not value:
        raise EvaluationError("Git repository identity output is empty")
    return value


def _validated_pairs(
    predictions: PredictionPartition, truth: TruthPartition
) -> tuple[tuple[RawScenePrediction, TruthScene], ...]:
    if predictions.partition not in {"validation", "test"}:
        raise EvaluationError("partition must be validation or test")
    if predictions.partition != truth.partition:
        raise EvaluationError("predictions and truth must identify the same partition")
    _validate_sha256(predictions.dataset_sha256, "prediction dataset SHA-256")
    _validate_sha256(predictions.source_model_sha256, "prediction source-model SHA-256")
    _validate_sha256(truth.dataset_sha256, "truth dataset SHA-256")
    if predictions.dataset_sha256 != truth.dataset_sha256:
        raise EvaluationError("predictions and truth identify different datasets")
    predicted_ids = [scene.scene_id for scene in predictions.scenes]
    truth_ids = [scene.scene_id for scene in truth.scenes]
    if len(set(predicted_ids)) != len(predicted_ids) or len(set(truth_ids)) != len(truth_ids):
        raise EvaluationError("scene IDs must be unique")
    if predicted_ids != truth_ids:
        raise EvaluationError("prediction and truth scene order differs")
    for prediction_scene, scene in zip(predictions.scenes, truth.scenes, strict=True):
        if not scene.detections or not all(
            detection.label in CARD_LABELS for detection in scene.detections
        ):
            raise EvaluationError(f"scene {scene.scene_id!r} has invalid truth labels")
        if len(scene.detections) > 10:
            raise EvaluationError(f"scene {scene.scene_id!r} exceeds ten truth cards")
        for detection in scene.detections:
            box = detection.box
            if (
                not all(math.isfinite(value) for value in box.as_tuple())
                or box.left < 0
                or box.top < 0
                or box.right > prediction_scene.geometry.source_width
                or box.bottom > prediction_scene.geometry.source_height
                or box.area <= 0
            ):
                raise EvaluationError(f"scene {scene.scene_id!r} has an invalid truth box")
    return tuple(zip(predictions.scenes, truth.scenes, strict=True))


def _match_detections(
    detections: tuple[Detection, ...], truth: tuple[TruthDetection, ...]
) -> tuple[tuple[int, int], ...]:
    correct = _greedy_match(
        detections,
        truth,
        allowed=lambda prediction, expected: prediction.label == expected.label,
    )
    matched_predictions = {prediction_index for prediction_index, _ in correct}
    matched_truth = {truth_index for _, truth_index in correct}
    confusion = _greedy_match(
        detections,
        truth,
        allowed=lambda prediction, expected: prediction.label != expected.label,
        excluded_predictions=matched_predictions,
        excluded_truth=matched_truth,
    )
    return correct + confusion


def _greedy_match(
    detections: tuple[Detection, ...],
    truth: tuple[TruthDetection, ...],
    *,
    allowed: Callable[[Detection, TruthDetection], bool],
    excluded_predictions: set[int] | None = None,
    excluded_truth: set[int] | None = None,
) -> tuple[tuple[int, int], ...]:
    matched_predictions = set(excluded_predictions or ())
    matched_truth = set(excluded_truth or ())
    candidates = sorted(
        (-iou, prediction_index, truth_index)
        for prediction_index, prediction in enumerate(detections)
        for truth_index, expected in enumerate(truth)
        if prediction_index not in matched_predictions
        and truth_index not in matched_truth
        and allowed(prediction, expected)
        and (iou := _intersection_over_union(prediction.box, expected.box)) >= LOCALIZATION_IOU
    )
    matches: list[tuple[int, int]] = []
    for _, prediction_index, truth_index in candidates:
        if prediction_index not in matched_predictions and truth_index not in matched_truth:
            matched_predictions.add(prediction_index)
            matched_truth.add(truth_index)
            matches.append((prediction_index, truth_index))
    return tuple(matches)


def _intersection_over_union(first: Box, second: Box) -> float:
    left = max(first.left, second.left)
    top = max(first.top, second.top)
    right = min(first.right, second.right)
    bottom = min(first.bottom, second.bottom)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = first.area + second.area - intersection
    return intersection / union if union > 0 else 0.0


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


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _record_with_checksum(payload: Mapping[str, object]) -> dict[str, object]:
    unchecked = {key: value for key, value in payload.items() if key != "recordChecksumSha256"}
    return {**unchecked, "recordChecksumSha256": _hash_json(unchecked)}


def _read_verified_record(path: Path) -> dict[str, object]:
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError) as error:
        raise EvaluationError("canonical test-evaluation claim is missing or unreadable") from error
    if not isinstance(record, dict) or record != _record_with_checksum(record):
        raise EvaluationError("canonical test-evaluation record checksum mismatch")
    return record


def _write_json_exclusive(path: Path, payload: object) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def _file_identity(path: Path) -> tuple[int, int, int]:
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_ctime_ns


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
