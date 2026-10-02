"""Evaluate detector candidates once against a frozen, real-photo holdout.

The Android UI asks for the wildcard (coringa) directly. Android stores the
preceding indicator internally, so this module accepts the chosen wildcard
rank/color and mirrors the effective CardValueCalculator result.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import math
import os
import random
import re
import stat
import tempfile
from collections import Counter, defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable

import numpy as np
from PIL import Image

from pontinho_cards.labels import CARD_LABELS, RANK_CODES, parse_card_label

_COLORS = {"C": "BLACK", "D": "RED", "H": "RED", "S": "BLACK"}
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_HAS_SECURE_COMPONENT_OPEN = (
    os.name == "posix"
    and hasattr(os, "O_NOFOLLOW")
    and hasattr(os, "O_DIRECTORY")
    and os.open in os.supports_dir_fd
)
JOKER_CONTEXTS: tuple[tuple[str, str], ...] = tuple(
    (rank, color) for rank in RANK_CODES for color in ("RED", "BLACK")
)


class EvaluationError(ValueError):
    """Raised before incomplete or untrusted evidence can become a report."""


@dataclass(frozen=True)
class Detection:
    label: str
    box: tuple[float, float, float, float]
    confidence: float = 1.0


@dataclass(frozen=True)
class PostprocessResult:
    detections: tuple[Detection, ...]
    abstained: bool
    flags: tuple[str, ...]


def normalize_rgb_input(array: np.ndarray, normalization: Mapping[str, Any]) -> np.ndarray:
    """Apply the exact RGB normalization embedded in the exported model contract."""

    mean = normalization.get("mean")
    std = normalization.get("std")
    numbers = [*(mean if isinstance(mean, list) else []), *(std if isinstance(std, list) else [])]
    if (
        not isinstance(mean, list)
        or not isinstance(std, list)
        or len(mean) != 3
        or len(std) != 3
        or any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in numbers)
        or any(float(value) <= 0 for value in std)
    ):
        raise EvaluationError("model contract RGB normalization is invalid")
    return (array.astype(np.float32) - np.asarray(mean, dtype=np.float32)) / np.asarray(
        std, dtype=np.float32
    )


def prepare_model_input(
    pixels: np.ndarray, input_detail: Mapping[str, Any], contract: Mapping[str, Any]
) -> np.ndarray:
    """Validate the runtime tensor contract, then normalize and quantize RGB pixels."""

    declared = contract.get("input")
    if not isinstance(declared, Mapping):
        raise EvaluationError("model contract input is missing")
    runtime_shape = [int(value) for value in input_detail.get("shape", [])]
    if runtime_shape != declared.get("shape") or runtime_shape != [1, *pixels.shape]:
        raise EvaluationError("runtime input shape differs from model contract")
    try:
        dtype = np.dtype(input_detail.get("dtype"))
    except TypeError as error:
        raise EvaluationError("runtime input dtype is invalid") from error
    if dtype.name != declared.get("dtype") or dtype.name not in {"float32", "uint8", "int8"}:
        raise EvaluationError("runtime input dtype differs from model contract")
    normalization = contract.get("normalization")
    if not isinstance(normalization, Mapping):
        raise EvaluationError("model contract lacks RGB normalization")
    normalized = normalize_rgb_input(pixels, normalization)
    quantization = contract.get("quantization")
    quantization_input = quantization.get("input") if isinstance(quantization, Mapping) else None
    if dtype == np.dtype(np.float32):
        if quantization_input is not None:
            raise EvaluationError("float input cannot declare integer quantization")
        return normalized
    if not isinstance(quantization_input, Mapping):
        raise EvaluationError("integer input requires quantization parameters")
    scales = quantization_input.get("scales")
    zero_points = quantization_input.get("zeroPoints")
    if (
        not isinstance(scales, list)
        or not isinstance(zero_points, list)
        or len(scales) != 1
        or len(zero_points) != 1
        or isinstance(scales[0], bool)
        or not isinstance(scales[0], (int, float))
        or not math.isfinite(float(scales[0]))
        or float(scales[0]) <= 0
        or type(zero_points[0]) is not int
    ):
        raise EvaluationError("integer input quantization is invalid")
    limits = np.iinfo(dtype)
    if not limits.min <= zero_points[0] <= limits.max:
        raise EvaluationError("input quantization zero point is outside dtype range")
    quantized = np.rint(normalized / float(scales[0]) + zero_points[0])
    return np.clip(quantized, limits.min, limits.max).astype(dtype)


def model_box_to_image_box(
    box: Sequence[float], image_width: int, image_height: int
) -> tuple[float, float, float, float]:
    """Convert normalized ymin/xmin/ymax/xmax model coordinates to image pixels."""

    if len(box) != 4:
        raise EvaluationError("model box must contain four coordinates")
    ymin, xmin, ymax, xmax = (float(value) for value in box)
    return (
        xmin * image_width,
        ymin * image_height,
        xmax * image_width,
        ymax * image_height,
    )


def _digest(path: Path) -> str:
    return hashlib.sha256(_read_regular_bytes(path)).hexdigest()


def _read_regular_bytes(path: Path, *, root: Path | None = None) -> bytes:
    if os.name != "posix":
        # Windows does not expose an openat-style relative open in the Python
        # standard library. This reader is retained for fail-closed preflight
        # diagnostics only; evaluate_candidate refuses to evaluate below.
        return _read_regular_bytes_windows_preflight(path, root=root)
    if not _HAS_SECURE_COMPONENT_OPEN:
        raise EvaluationError("secure component-relative open is unsupported on this platform")

    absolute = Path(os.path.abspath(path))
    root_absolute = Path(os.path.abspath(root if root is not None else absolute.parent))
    try:
        relative = absolute.relative_to(root_absolute)
    except ValueError as error:
        raise EvaluationError(f"path escapes trusted root: {path}") from error
    parts = relative.parts
    if not parts or any(part in ("", ".", "..") for part in parts):
        raise EvaluationError(f"required input path is invalid: {path}")

    root_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_NOFOLLOW
    descriptors: list[int] = []
    try:
        anchor = Path(root_absolute.anchor)
        current = os.open(anchor, root_flags)
        descriptors.append(current)
        if not stat.S_ISDIR(os.fstat(current).st_mode):
            raise EvaluationError(f"filesystem anchor must be a directory: {anchor}")
        for component in root_absolute.parts[1:]:
            current = os.open(component, root_flags, dir_fd=current)
            descriptors.append(current)
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise EvaluationError(f"trusted root must be a directory: {root_absolute}")
        for component in parts[:-1]:
            current = os.open(component, root_flags, dir_fd=current)
            descriptors.append(current)
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise EvaluationError(f"required input parent must be a directory: {path}")
        descriptor = os.open(parts[-1], file_flags, dir_fd=current)
        descriptors.append(descriptor)
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise EvaluationError(f"required input must be a regular file: {path}")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    except OSError as error:
        raise EvaluationError(
            f"cannot securely read required input {path}; symbolic links or replaced path "
            f"components are rejected: {error}"
        ) from error
    finally:
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def _read_regular_bytes_windows_preflight(path: Path, *, root: Path | None = None) -> bytes:
    absolute = Path(os.path.abspath(path))
    if root is not None:
        root_absolute = Path(os.path.abspath(root))
        if absolute != root_absolute and root_absolute not in absolute.parents:
            raise EvaluationError(f"path escapes trusted root: {path}")
    for component in (absolute, *absolute.parents):
        if component == Path(component.anchor):
            break
        try:
            mode = component.lstat().st_mode
        except OSError as error:
            raise EvaluationError(f"cannot access required input {path}: {error}") from error
        if stat.S_ISLNK(mode):
            raise EvaluationError(f"required input cannot use symbolic links: {path}")
        if component == absolute and not stat.S_ISREG(mode):
            raise EvaluationError(f"required input must be a regular file: {path}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(absolute, flags)
        with os.fdopen(descriptor, "rb") as handle:
            if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
                raise EvaluationError(f"required input must remain regular: {path}")
            return handle.read()
    except OSError as error:
        raise EvaluationError(f"cannot securely read required input {path}: {error}") from error


def _json_bytes(payload: bytes, name: str) -> Mapping[str, Any]:
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise EvaluationError(f"cannot decode {name}: {error}") from error
    if not isinstance(document, Mapping):
        raise EvaluationError(f"{name} must be an object")
    return document


def _unit(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluationError(f"{name} must be a finite number in [0, 1]")
    number = float(value)
    if not math.isfinite(number) or not 0 <= number <= 1:
        raise EvaluationError(f"{name} must be a finite number in [0, 1]")
    return number


def _validate_detection(detection: Detection) -> None:
    try:
        parse_card_label(detection.label)
    except ValueError as error:
        raise EvaluationError(str(error)) from error
    if len(detection.box) != 4 or not all(math.isfinite(value) for value in detection.box):
        raise EvaluationError("detection box must contain four finite coordinates")
    xmin, ymin, xmax, ymax = detection.box
    if xmax <= xmin or ymax <= ymin:
        raise EvaluationError("detection box must have positive area")
    _unit(detection.confidence, "detection confidence")


def _iou(left: Detection, right: Detection) -> float:
    lx1, ly1, lx2, ly2 = left.box
    rx1, ry1, rx2, ry2 = right.box
    width = max(0.0, min(lx2, rx2) - max(lx1, rx1))
    height = max(0.0, min(ly2, ry2) - max(ly1, ry1))
    intersection = width * height
    union = (lx2 - lx1) * (ly2 - ly1) + (rx2 - rx1) * (ry2 - ry1) - intersection
    return intersection / union if union else 0.0


def postprocess_detections(
    detections: Iterable[Detection],
    *,
    confidence_threshold: float,
    iou_threshold: float,
    maximum_card_count: int = 15,
) -> PostprocessResult:
    """Apply validation-selected thresholds without deduplicating by class label."""

    confidence_threshold = _unit(confidence_threshold, "confidence threshold")
    iou_threshold = _unit(iou_threshold, "IoU threshold")
    raw = list(detections)
    for detection in raw:
        _validate_detection(detection)
    flags: list[str] = []
    uncertainty_floor = confidence_threshold * 0.8
    if any(uncertainty_floor <= item.confidence < confidence_threshold for item in raw):
        flags.append("low_confidence_detection")
    candidates = sorted(
        (item for item in raw if item.confidence >= confidence_threshold),
        key=lambda item: (-item.confidence, item.label, item.box),
    )
    kept: list[Detection] = []
    for candidate in candidates:
        # Class-agnostic NMS also removes two competing labels for one physical card.
        if all(_iou(candidate, accepted) <= iou_threshold for accepted in kept):
            kept.append(candidate)
    if len(kept) > maximum_card_count:
        flags.append("card_count_anomaly")
    if not kept and "low_confidence_detection" not in flags:
        flags.append("no_confident_cards")
    return PostprocessResult(tuple(kept), bool(flags), tuple(sorted(set(flags))))


def pontinho_total(labels: Iterable[str], joker_rank: str, joker_color: str) -> int:
    """Mirror Android scoring while accepting the wildcard selected by the user."""

    if joker_rank not in RANK_CODES or joker_color not in {"RED", "BLACK"}:
        raise EvaluationError("chosen joker needs a canonical rank and RED/BLACK color")
    total = 0
    for label in labels:
        try:
            rank, suit = parse_card_label(label)
        except ValueError as error:
            raise EvaluationError(str(error)) from error
        if rank == joker_rank and _COLORS[suit] == joker_color:
            total += 20
        elif rank == "A":
            total += 15
        elif rank in {"J", "Q", "K"}:
            total += 10
        else:
            total += int(rank)
    return total


def _match(
    truth: Sequence[Detection], predicted: Sequence[Detection], iou_threshold: float
) -> tuple[list[tuple[Detection, bool]], int, int, dict[str, list[int]]]:
    matched_truth: set[int] = set()
    classified: list[tuple[Detection, bool]] = []
    counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])  # tp, fp, fn
    for prediction in sorted(predicted, key=lambda item: -item.confidence):
        options = [
            (index, _iou(prediction, actual))
            for index, actual in enumerate(truth)
            if index not in matched_truth and actual.label == prediction.label
        ]
        options = [option for option in options if option[1] >= iou_threshold]
        if options:
            selected = max(options, key=lambda option: (option[1], -option[0]))[0]
            matched_truth.add(selected)
            counts[prediction.label][0] += 1
            classified.append((prediction, True))
        else:
            counts[prediction.label][1] += 1
            classified.append((prediction, False))
    for index, actual in enumerate(truth):
        if index not in matched_truth:
            counts[actual.label][2] += 1
    false_positives = sum(values[1] for values in counts.values())
    false_negatives = sum(values[2] for values in counts.values())
    return classified, false_positives, false_negatives, counts


def _average_precision(entries: list[tuple[float, bool]], support: int) -> float:
    if support == 0:
        return 0.0
    entries.sort(key=lambda item: -item[0])
    true_positives = 0
    precisions: list[float] = []
    recalls: list[float] = []
    for index, (_, correct) in enumerate(entries, start=1):
        true_positives += int(correct)
        precisions.append(true_positives / index)
        recalls.append(true_positives / support)
    return (
        sum(
            max((p for p, r in zip(precisions, recalls) if r >= point), default=0.0)
            for point in (index / 100 for index in range(101))
        )
        / 101
    )


def _calibration(entries: Sequence[tuple[Detection, bool]]) -> dict[str, Any]:
    if not entries:
        return {"expectedCalibrationError": 0.0, "brierScore": 0.0, "bins": []}
    bins: list[dict[str, Any]] = []
    weighted_error = 0.0
    for lower_index in range(10):
        lower, upper = lower_index / 10, (lower_index + 1) / 10
        members = [
            (item.confidence, correct)
            for item, correct in entries
            if lower <= item.confidence < upper or (upper == 1 and item.confidence == 1)
        ]
        if not members:
            continue
        confidence = sum(item[0] for item in members) / len(members)
        accuracy = sum(item[1] for item in members) / len(members)
        weighted_error += len(members) / len(entries) * abs(confidence - accuracy)
        bins.append(
            {
                "lower": lower,
                "upper": upper,
                "count": len(members),
                "meanConfidence": confidence,
                "accuracy": accuracy,
            }
        )
    brier = sum((item.confidence - int(correct)) ** 2 for item, correct in entries) / len(entries)
    return {"expectedCalibrationError": weighted_error, "brierScore": brier, "bins": bins}


def _bootstrap(values: Sequence[float | bool], seed: int, samples: int) -> dict[str, float]:
    if not values:
        return {"lower95": 0.0, "upper95": 0.0}
    generator = random.Random(seed)
    estimates = sorted(
        sum(generator.choice(values) for _ in values) / len(values) for _ in range(samples)
    )
    return {
        "lower95": estimates[int(0.025 * (samples - 1))],
        "upper95": estimates[int(0.975 * (samples - 1))],
    }


def _slice(exact_total: list[bool], exact_set: list[bool], indices: list[int]) -> dict[str, Any]:
    return {
        "sceneCount": len(indices),
        "exactHandTotalAccuracy": sum(exact_total[index] for index in indices) / len(indices),
        "exactCardMultisetAccuracy": sum(exact_set[index] for index in indices) / len(indices),
    }


def evaluate_scenes(
    scenes: Iterable[Mapping[str, Any]],
    *,
    confidence_threshold: float,
    iou_threshold: float,
    seed: int,
    bootstrap_samples: int = 2000,
) -> dict[str, Any]:
    """Calculate detection and hand-level metrics from hand-checkable scene records."""

    if type(seed) is not int or type(bootstrap_samples) is not int or bootstrap_samples < 20:
        raise EvaluationError("seed must be an integer and bootstrap_samples must be at least 20")
    records = list(scenes)
    if not records:
        raise EvaluationError("evaluation scenes cannot be empty")
    exact_set: list[bool] = []
    exact_count: list[bool] = []
    exact_total: list[float] = []
    exact_total_scenes: list[bool] = []
    context_results: dict[str, list[bool]] = {
        f"{rank}_{color}": [] for rank, color in JOKER_CONTEXTS
    }
    abstentions = 0
    false_positives = 0
    false_negatives = 0
    class_counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    ap_entries: dict[str, list[tuple[float, bool]]] = defaultdict(list)
    calibration_entries: list[tuple[Detection, bool]] = []
    scene_reports: list[dict[str, Any]] = []
    slices: dict[str, dict[str, list[int]]] = {
        "deckGroup": defaultdict(list),
        "cardCount": defaultdict(list),
        "lighting": defaultdict(list),
    }
    for index, scene in enumerate(records):
        truth = list(scene.get("truth", []))
        predicted = list(scene.get("predictions", []))
        if not all(isinstance(item, Detection) for item in [*truth, *predicted]):
            raise EvaluationError("truth and predictions must contain Detection objects")
        for item in truth:
            _validate_detection(item)
        processed = postprocess_detections(
            predicted,
            confidence_threshold=confidence_threshold,
            iou_threshold=iou_threshold,
        )
        box_predictions = list(processed.detections)
        evaluated_predictions = [] if processed.abstained else box_predictions
        classified, fp, fn, counts = _match(truth, box_predictions, iou_threshold)
        calibration_entries.extend(classified)
        false_positives += fp
        false_negatives += fn
        for label, values in counts.items():
            for offset, value in enumerate(values):
                class_counts[label][offset] += value
        for detection, correct in classified:
            ap_entries[detection.label].append((detection.confidence, correct))
        truth_labels = [item.label for item in truth]
        predicted_labels = [item.label for item in evaluated_predictions]
        set_match = not processed.abstained and Counter(truth_labels) == Counter(predicted_labels)
        count_match = not processed.abstained and len(truth_labels) == len(predicted_labels)
        total_by_context: dict[str, bool] = {}
        totals_by_context: dict[str, dict[str, int | None]] = {}
        for rank, color in JOKER_CONTEXTS:
            key = f"{rank}_{color}"
            truth_total = pontinho_total(truth_labels, rank, color)
            predicted_total = pontinho_total(predicted_labels, rank, color)
            matched = not processed.abstained and truth_total == predicted_total
            total_by_context[key] = matched
            context_results[key].append(matched)
            totals_by_context[key] = {
                "truth": truth_total,
                "predicted": None if processed.abstained else predicted_total,
            }
        total_accuracy = sum(total_by_context.values()) / len(JOKER_CONTEXTS)
        total_scene_match = all(total_by_context.values())
        exact_set.append(set_match)
        exact_count.append(count_match)
        exact_total.append(total_accuracy)
        exact_total_scenes.append(total_scene_match)
        abstentions += int(processed.abstained)
        scene_reports.append(
            {
                "sceneId": scene.get("sceneId"),
                "status": "ABSTAINED" if processed.abstained else "EVALUATED",
                "flags": list(processed.flags),
                "totalsByJokerContext": totals_by_context,
                "exactCardMultiset": set_match,
                "exactCardCount": count_match,
                "exactHandTotalByJokerContext": total_by_context,
                "exactHandTotalAccuracy": total_accuracy,
                "exactHandTotalAllContexts": total_scene_match,
            }
        )
        for slice_name, key in (
            ("deckGroup", str(scene.get("deckGroupId"))),
            ("cardCount", str(len(truth))),
            ("lighting", str(scene.get("lighting"))),
        ):
            slices[slice_name][key].append(index)
    per_class = {}
    average_precisions = []
    for label in CARD_LABELS:
        tp, fp, fn = class_counts[label]
        support = tp + fn
        if support:
            average_precisions.append(_average_precision(ap_entries[label], support))
        if support or fp:
            per_class[label] = {
                "precision": tp / (tp + fp) if tp + fp else 0.0,
                "recall": tp / support if support else 0.0,
                "support": support,
            }
    return {
        "sceneCount": len(records),
        "boxMap50": sum(average_precisions) / len(average_precisions)
        if average_precisions
        else 0.0,
        "perClass": per_class,
        "exactCardMultisetAccuracy": sum(exact_set) / len(records),
        "exactCardCountAccuracy": sum(exact_count) / len(records),
        "exactHandTotalAccuracy": sum(exact_total) / len(records),
        "exactHandTotalSceneAccuracy": sum(exact_total_scenes) / len(records),
        "exactHandTotalContextCount": len(records) * len(JOKER_CONTEXTS),
        "perJokerContext": {
            key: {"sceneCount": len(values), "exactHandTotalAccuracy": sum(values) / len(values)}
            for key, values in context_results.items()
        },
        "falsePositives": false_positives,
        "falseNegatives": false_negatives,
        "confidenceCalibration": _calibration(calibration_entries),
        "abstentionRate": abstentions / len(records),
        "confidenceIntervals": {
            "exactCardMultisetAccuracy": _bootstrap(exact_set, seed, bootstrap_samples),
            "exactCardCountAccuracy": _bootstrap(exact_count, seed + 1, bootstrap_samples),
            "exactHandTotalAccuracy": _bootstrap(exact_total, seed + 2, bootstrap_samples),
        },
        "slices": {
            dimension: {
                key: _slice(exact_total, exact_set, indices)
                for key, indices in sorted(groups.items())
            }
            for dimension, groups in slices.items()
        },
        "scenes": scene_reports,
    }


def _read_json(path: Path, name: str) -> Mapping[str, Any]:
    return _json_bytes(_read_regular_bytes(path), name)


def _validate_holdout_record(record: Mapping[str, Any], seen_ids: set[str]) -> None:
    image_id = record.get("imageId")
    if not isinstance(image_id, str) or not image_id or image_id in seen_ids:
        raise EvaluationError("holdout image IDs must be unique non-empty text")
    seen_ids.add(image_id)
    path_value = record.get("path")
    if not isinstance(path_value, str) or not path_value or "\\" in path_value:
        raise EvaluationError("holdout image path is missing")
    relative_path = PurePosixPath(path_value)
    if relative_path.is_absolute() or any(part in {"", ".", ".."} for part in relative_path.parts):
        raise EvaluationError("holdout image path must be safe and relative")
    digest = record.get("imageSha256")
    if not isinstance(digest, str) or not _SHA256_PATTERN.fullmatch(digest):
        raise EvaluationError("holdout image SHA-256 is invalid")
    if not isinstance(record.get("deckGroupId"), str) or not record["deckGroupId"]:
        raise EvaluationError("holdout deck group is missing")
    if not isinstance(record.get("lighting"), str) or not record["lighting"]:
        raise EvaluationError("holdout lighting slice is missing")
    if (
        type(record.get("width")) is not int
        or type(record.get("height")) is not int
        or record["width"] <= 0
        or record["height"] <= 0
    ):
        raise EvaluationError("holdout image dimensions are missing")
    boxes = record.get("boxes")
    if not isinstance(boxes, list) or not boxes:
        raise EvaluationError("holdout scene requires nonempty ground-truth boxes")
    for box in boxes:
        if not isinstance(box, Mapping):
            raise EvaluationError("holdout box must be an object")
        try:
            detection = Detection(
                str(box.get("label")),
                tuple(float(box.get(field)) for field in ("xmin", "ymin", "xmax", "ymax")),
            )
        except (TypeError, ValueError) as error:
            raise EvaluationError("holdout box coordinates are invalid") from error
        _validate_detection(detection)
        xmin, ymin, xmax, ymax = detection.box
        if xmin < 0 or ymin < 0 or xmax > record["width"] or ymax > record["height"]:
            raise EvaluationError("holdout box lies outside image bounds")


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _holdout_identity(manifest: Mapping[str, Any]) -> str:
    schema = manifest.get("schemaVersion")
    split_hash = manifest.get("splitManifestSha256")
    test_hash = manifest.get("testPartitionSha256")
    if (
        type(schema) is not int
        or not isinstance(split_hash, str)
        or not _SHA256_PATTERN.fullmatch(split_hash)
        or not isinstance(test_hash, str)
        or not _SHA256_PATTERN.fullmatch(test_hash)
    ):
        raise EvaluationError("holdout semantic identity is invalid")
    return f"v{schema}.{split_hash}.{test_hash}"


def _validate_partition_disjointness(partitions: Mapping[str, Any]) -> None:
    names = ("train", "validation", "test")
    records_by_partition: dict[str, list[Mapping[str, Any]]] = {}
    for name in names:
        records = partitions.get(name)
        if not isinstance(records, list) or not all(isinstance(item, Mapping) for item in records):
            raise EvaluationError(f"split partition {name} is invalid")
        records_by_partition[name] = records
    for field in ("imageSha256", "sourceDerivativeId", "deckGroupId", "splitGroupId"):
        values = {
            name: {record.get(field) for record in records_by_partition[name]} for name in names
        }
        for index, left in enumerate(names):
            for right in names[index + 1 :]:
                if None in values[left] or None in values[right] or values[left] & values[right]:
                    raise EvaluationError(f"split partitions overlap by {field}")
    families = {
        name: {
            value
            for record in records_by_partition[name]
            for value in (record.get("imageId"), record.get("augmentationParentId"))
            if value is not None
        }
        for name in names
    }
    for index, left in enumerate(names):
        for right in names[index + 1 :]:
            if families[left] & families[right]:
                raise EvaluationError("split partitions overlap by augmentation family")


def _validate_inputs(
    model: Path, manifest_path: Path
) -> tuple[Mapping[str, Any], Mapping[str, Any], bytes, str, str, str, str]:
    contract_path = model.with_name("model-contract.json")
    model_bytes = _read_regular_bytes(model)
    model_hash = hashlib.sha256(model_bytes).hexdigest()
    contract_bytes = _read_regular_bytes(contract_path)
    contract_hash = hashlib.sha256(contract_bytes).hexdigest()
    contract = _json_bytes(contract_bytes, "model contract")
    if contract.get("modelSha256") != model_hash:
        raise EvaluationError("model hash does not match model contract")
    if contract.get("schemaVersion") != 1 or tuple(contract.get("labels", [])) != CARD_LABELS:
        raise EvaluationError("model contract provenance or label order is invalid")
    _unit(contract.get("scoreThreshold"), "contract score threshold")
    _unit(contract.get("iouThreshold"), "contract IoU threshold")
    if contract.get("thresholdProvenance") != "configured-not-validation-selected":
        raise EvaluationError("model threshold provenance is missing or unsupported")
    manifest_bytes = _read_regular_bytes(manifest_path)
    manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
    manifest = _json_bytes(manifest_bytes, "frozen holdout manifest")
    if manifest.get("schemaVersion") != 1 or manifest.get("kind") != "pontinho-frozen-holdout":
        raise EvaluationError("holdout manifest must use the frozen producer schema")
    holdout_identity = _holdout_identity(manifest)
    split_name = manifest.get("splitManifest")
    if (
        not isinstance(split_name, str)
        or PurePosixPath(split_name).name != split_name
        or split_name in {".", ".."}
    ):
        raise EvaluationError("holdout split manifest path is invalid")
    split_path = manifest_path.parent / split_name
    split_bytes = _read_regular_bytes(split_path, root=manifest_path.parent)
    split_hash = hashlib.sha256(split_bytes).hexdigest()
    if manifest.get("splitManifestSha256") != split_hash:
        raise EvaluationError("holdout split manifest hash is invalid")
    if contract.get("inputManifestSha256") != split_hash:
        raise EvaluationError("model contract is not bound to the frozen split manifest")
    split = _json_bytes(split_bytes, "split manifest")
    if split.get("schemaVersion") != 1 or split.get("seed") != manifest.get("seed"):
        raise EvaluationError("holdout and split schema or seed differ")
    partitions = split.get("partitions")
    if not isinstance(partitions, Mapping):
        raise EvaluationError("split partitions are missing")
    _validate_partition_disjointness(partitions)
    records = manifest.get("test")
    if not isinstance(records, list) or len(records) < 200:
        raise EvaluationError("holdout requires at least 200 real photos")
    if not all(
        isinstance(record, Mapping) and record.get("isRealPhoto") is True for record in records
    ):
        raise EvaluationError("holdout must contain real photos only")
    if records != partitions.get("test"):
        raise EvaluationError("holdout test must exactly equal split.test")
    if manifest.get("testPartitionSha256") != hashlib.sha256(_canonical_json(records)).hexdigest():
        raise EvaluationError("holdout test partition hash is invalid")
    seen_ids: set[str] = set()
    for record in records:
        _validate_holdout_record(record, seen_ids)
    unique_photo_count = len({record["imageSha256"] for record in records})
    if unique_photo_count < 200:
        raise EvaluationError("holdout requires at least 200 distinct real photos")
    decks = {record.get("deckGroupId") for record in records}
    if None in decks or len(decks) < 10:
        raise EvaluationError("holdout requires at least 10 unseen deck groups")
    return (
        contract,
        manifest,
        model_bytes,
        model_hash,
        contract_hash,
        manifest_hash,
        holdout_identity,
    )


def _infer_scenes(
    model: Path,
    contract: Mapping[str, Any],
    manifest: Mapping[str, Any],
    manifest_path: Path,
    interpreter_factory: Callable[..., Any] | None = None,
) -> list[dict[str, Any]]:
    if interpreter_factory is None:
        try:
            import tensorflow as tf
        except ImportError as error:
            raise EvaluationError("TensorFlow Lite runtime is unavailable") from error
        interpreter_factory = tf.lite.Interpreter
    interpreter = interpreter_factory(model_path=str(model))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()
    declared_outputs = contract.get("outputs")
    if not isinstance(declared_outputs, list) or not all(
        isinstance(item, Mapping) and isinstance(item.get("semantic"), str)
        for item in declared_outputs
    ):
        raise EvaluationError("model contract detector outputs are invalid")
    semantics = {item["semantic"]: item for item in declared_outputs}
    if set(semantics) != {"boxes", "classes", "scores", "count"}:
        raise EvaluationError("model contract does not declare detector output semantics")
    by_name = {str(item["name"]): item for item in output_details}
    if any(str(details.get("tensorName")) not in by_name for details in semantics.values()):
        raise EvaluationError("model outputs do not match the model contract")
    root = manifest_path.parent
    scenes: list[dict[str, Any]] = []
    for record in manifest["test"]:
        image_path = root / str(record["path"])
        image_bytes = _read_regular_bytes(image_path, root=root)
        if hashlib.sha256(image_bytes).hexdigest() != record.get("imageSha256"):
            raise EvaluationError(f"holdout image hash mismatch: {record.get('imageId')}")
        height, width = (int(value) for value in input_detail["shape"][1:3])
        try:
            image_source = Image.open(io.BytesIO(image_bytes))
        except (OSError, SyntaxError) as error:
            raise EvaluationError(
                f"holdout image cannot be decoded: {record['imageId']}"
            ) from error
        with image_source as image:
            if image.size != (record["width"], record["height"]):
                raise EvaluationError(f"holdout image dimensions mismatch: {record['imageId']}")
            array = np.asarray(image.convert("RGB").resize((width, height)))
        prepared = prepare_model_input(array, input_detail, contract)
        interpreter.set_tensor(int(input_detail["index"]), np.expand_dims(prepared, 0))
        interpreter.invoke()
        values = {
            semantic: interpreter.get_tensor(int(by_name[str(details["tensorName"])]["index"]))[0]
            for semantic, details in semantics.items()
        }
        count = int(values["count"][0] if np.ndim(values["count"]) else values["count"])
        available = min(len(values["boxes"]), len(values["classes"]), len(values["scores"]))
        if count < 0 or count > available:
            raise EvaluationError("model emitted an invalid detection count")
        predictions = []
        for index in range(count):
            label_index = int(values["classes"][index])
            if not 0 <= label_index < len(CARD_LABELS):
                raise EvaluationError("model emitted a class outside the canonical vocabulary")
            predictions.append(
                Detection(
                    CARD_LABELS[label_index],
                    model_box_to_image_box(
                        values["boxes"][index], int(record["width"]), int(record["height"])
                    ),
                    float(values["scores"][index]),
                )
            )
        truth = [
            Detection(
                str(box["label"]),
                (float(box["xmin"]), float(box["ymin"]), float(box["xmax"]), float(box["ymax"])),
            )
            for box in record["boxes"]
        ]
        scenes.append(
            {**record, "sceneId": record["imageId"], "truth": truth, "predictions": predictions}
        )
    return scenes


def _publish_report_pair(report_path: Path, json_payload: bytes, markdown_payload: bytes) -> Path:
    """Publish a recoverable no-clobber report transaction and return its marker."""

    report_path, markdown_path = _report_paths(report_path)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    marker = report_path.with_name(f".{report_path.name}.transaction")
    expected = {
        "jsonSha256": hashlib.sha256(json_payload).hexdigest(),
        "markdownSha256": hashlib.sha256(markdown_payload).hexdigest(),
    }
    if marker.exists():
        transaction = _read_json(marker, "report transaction")
        if any(transaction.get(key) != value for key, value in expected.items()):
            raise EvaluationError("existing report transaction belongs to different evidence")
        for target, key, temporary_field in (
            (markdown_path, "markdownSha256", "markdownTemporary"),
            (report_path, "jsonSha256", "jsonTemporary"),
        ):
            if target.exists():
                if _digest(target) != transaction[key]:
                    raise EvaluationError("published report transaction hash mismatch")
                continue
            temporary_name = transaction.get(temporary_field)
            if not isinstance(temporary_name, str) or Path(temporary_name).name != temporary_name:
                raise EvaluationError("report transaction temporary path is invalid")
            temporary = report_path.parent / temporary_name
            if _digest(temporary) != transaction[key]:
                raise EvaluationError("staged report transaction hash mismatch")
            temporary.replace(target)
        return marker
    if report_path.exists() or markdown_path.exists():
        raise EvaluationError("report already exists; refusing to replace evidence")
    staged: list[Path] = []
    owns_marker = False
    try:
        for target, payload in (
            (markdown_path, markdown_payload),
            (report_path, json_payload),
        ):
            descriptor, temporary_name = tempfile.mkstemp(
                prefix=f".{target.name}.", dir=target.parent
            )
            temporary = Path(temporary_name)
            staged.append(temporary)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
        transaction = {
            "schemaVersion": 1,
            **expected,
            "markdownTemporary": staged[0].name,
            "jsonTemporary": staged[1].name,
        }
        _write_exclusive(marker, _canonical_json(transaction))
        owns_marker = True
        staged[0].replace(markdown_path)
        staged[1].replace(report_path)
        return marker
    except BaseException:
        if not owns_marker:
            for temporary in staged:
                temporary.unlink(missing_ok=True)
        raise


def _report_paths(report_path: Path) -> tuple[Path, Path]:
    if report_path.suffix != ".json" or report_path.name == ".json":
        raise EvaluationError("report path must have the exact .json suffix")
    canonical = Path(os.path.abspath(report_path))
    markdown = canonical.with_suffix(".md")
    if os.path.normcase(str(canonical)) == os.path.normcase(str(markdown)):
        raise EvaluationError("report JSON and Markdown paths must be distinct")
    return canonical, markdown


def _lock_file(handle: Any) -> None:
    try:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except ImportError:  # pragma: no cover - Windows host path
        import msvcrt

        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise BlockingIOError from error


def _write_exclusive(path: Path, payload: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    except FileExistsError as error:
        raise EvaluationError(f"durable evidence already exists: {path}") from error


@contextlib.contextmanager
def _evaluation_claim(
    manifest_path: Path, model_hash: str, holdout_identity: str, report_path: Path
) -> Iterable[Path]:
    receipt_dir = manifest_path.parent / ".evaluation-receipts"
    try:
        receipt_dir.mkdir(mode=0o700, exist_ok=True)
    except OSError as error:
        raise EvaluationError(f"cannot create evaluation receipt directory: {error}") from error
    if stat.S_ISLNK(receipt_dir.lstat().st_mode):
        raise EvaluationError("evaluation receipt directory cannot be a symbolic link")
    key = f"{model_hash}.{holdout_identity}"
    receipt = receipt_dir / f"{key}.json"
    if receipt.exists():
        raise EvaluationError("model and frozen holdout were already evaluated")
    claim = receipt_dir / f"{key}.claim"
    if claim.exists() and stat.S_ISLNK(claim.lstat().st_mode):
        raise EvaluationError("evaluation claim cannot be a symbolic link")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(claim, flags, 0o600)
        handle = os.fdopen(descriptor, "r+b")
        if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
            handle.close()
            raise EvaluationError("evaluation claim must be a regular file")
    except OSError as error:
        raise EvaluationError(f"cannot open evaluation claim: {error}") from error
    try:
        try:
            _lock_file(handle)
        except BlockingIOError as error:
            raise EvaluationError("evaluation is already in progress") from error
        if receipt.exists():
            raise EvaluationError("model and frozen holdout were already evaluated")
        metadata = {
            "schemaVersion": 1,
            "modelSha256": model_hash,
            "holdoutIdentity": holdout_identity,
            "report": str(Path(os.path.abspath(report_path))),
        }
        handle.seek(0)
        handle.truncate()
        handle.write((json.dumps(metadata, sort_keys=True) + "\n").encode())
        handle.flush()
        os.fsync(handle.fileno())
        yield receipt
    finally:
        handle.close()


def _markdown(report: Mapping[str, Any]) -> str:
    return (
        "# Card recognition validation\n\n"
        f"- Status: `{report['status']}`\n"
        f"- Holdout records: {report['holdoutRecordCount']}\n"
        f"- Distinct frozen real photos: {report['holdoutUniquePhotoCount']}\n"
        f"- Unseen deck groups: {report['holdoutDeckGroupCount']}\n"
        f"- Exact hand-total accuracy: {report['metrics']['exactHandTotalAccuracy']:.4f}\n"
        f"- Exact card-multiset accuracy: {report['metrics']['exactCardMultisetAccuracy']:.4f}\n"
        f"- Abstention rate: {report['metrics']['abstentionRate']:.4f}\n"
    )


def evaluate_candidate(model: Path, manifest_path: Path, report_path: Path) -> dict[str, Any]:
    report_path, _ = _report_paths(report_path)
    (
        contract,
        manifest,
        model_bytes,
        model_hash,
        contract_hash,
        manifest_hash,
        holdout_identity,
    ) = _validate_inputs(model, manifest_path)
    if not _HAS_SECURE_COMPONENT_OPEN:
        raise EvaluationError(
            "secure component-relative open is unsupported on this platform; "
            "release evaluation must run on POSIX"
        )
    with _evaluation_claim(manifest_path, model_hash, holdout_identity, report_path) as receipt:
        with tempfile.TemporaryDirectory(prefix="pontinho-evaluation-") as temporary_directory:
            snapshot_model = Path(temporary_directory) / "card_detector.tflite"
            snapshot_model.write_bytes(model_bytes)
            scenes = _infer_scenes(snapshot_model, contract, manifest, manifest_path)
        metrics = evaluate_scenes(
            scenes,
            confidence_threshold=float(contract["scoreThreshold"]),
            iou_threshold=float(contract["iouThreshold"]),
            seed=int(manifest.get("seed", 20261014)),
        )
        report = {
            "schemaVersion": 1,
            "status": "NOT_APPROVED_CONFIGURED_THRESHOLDS",
            "modelSha256": model_hash,
            "modelContractSha256": contract_hash,
            "testManifestSha256": manifest_hash,
            "holdoutIdentity": holdout_identity,
            "splitManifestSha256": manifest["splitManifestSha256"],
            "testPartitionSha256": manifest["testPartitionSha256"],
            "holdoutRecordCount": len(manifest["test"]),
            "holdoutUniquePhotoCount": len(
                {record["imageSha256"] for record in manifest["test"]}
            ),
            "holdoutDeckGroupCount": len({record["deckGroupId"] for record in manifest["test"]}),
            "thresholdProvenance": contract["thresholdProvenance"],
            "scoreThreshold": contract["scoreThreshold"],
            "iouThreshold": contract["iouThreshold"],
            "metrics": metrics,
        }
        payload = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode()
        markdown_payload = _markdown(report).encode()
        transaction_marker = _publish_report_pair(report_path, payload, markdown_payload)
        receipt_document = {
            "schemaVersion": 1,
            "modelSha256": model_hash,
            "holdoutIdentity": holdout_identity,
            "splitManifestSha256": manifest["splitManifestSha256"],
            "testPartitionSha256": manifest["testPartitionSha256"],
            "reportJsonSha256": hashlib.sha256(payload).hexdigest(),
            "reportMarkdownSha256": hashlib.sha256(markdown_payload).hexdigest(),
        }
        _write_exclusive(receipt, _canonical_json(receipt_document))
        try:
            transaction_marker.unlink()
        except OSError:
            # The durable receipt is authoritative; a stale marker is recoverable evidence.
            pass
        return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--test-manifest", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    evaluate_candidate(args.model, args.test_manifest, args.report)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised through main
    raise SystemExit(main())
