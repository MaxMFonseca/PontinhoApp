"""Train canonical card classifiers; calibrate validation scores, never approve releases."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import random
import re
import tempfile
from collections.abc import Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any

import numpy as np
import yaml
from PIL import Image

from pontinho_cards.classifier_scores import normalize_classifier_output
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.model_resize import RESIZE_POLICY, resize_rgb
from pontinho_cards.native_nms import NMS_POLICY
from pontinho_cards.secure_io import safe_name, secure_read_bytes, secure_write_bytes
from pontinho_cards.train import TrainingConfigError, _git_commit, _save_selected_weights
from pontinho_cards.train_detector import _fit_and_restore_best, _publish_candidate
from pontinho_cards.two_stage_data import (
    ClassifierCrop,
    TwoStageInputManifest,
    build_two_stage_inputs,
)

_PARTITIONS = ("train", "validation")
_TRANSFER_URI = "https://tfhub.dev/tensorflow/efficientnet/lite0/feature-vector/2"


@dataclass(frozen=True)
class ClassifierTrainingConfig:
    seed: int
    classifier_family: str
    image_size: int
    batch_size: int
    labels: tuple[str, ...]
    epochs: int
    early_stopping_patience: int
    train_whole_model: bool
    augmentation: dict[str, Any]
    document: dict[str, Any]


@dataclass(frozen=True)
class ClassifierCandidate:
    model_path: Path
    contract_path: Path
    validation_metrics_path: Path


def load_classifier_config(path: Path) -> ClassifierTrainingConfig:
    document = yaml.safe_load(secure_read_bytes(path, root=path.parent))
    fields = {
        "schema_version",
        "seed",
        "classifier_family",
        "image_size",
        "batch_size",
        "labels",
        "partitions",
        "training",
    }
    if not isinstance(document, dict) or set(document) != fields:
        raise TrainingConfigError("classifier config has missing or unknown fields")
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["classifier_family"] != "efficientnet_lite0"
        or type(document["image_size"]) is not int
        or document["image_size"] != 224
        or document["labels"] != list(CARD_LABELS)
        or document["partitions"] != {"train": "train", "validation": "validation"}
    ):
        raise TrainingConfigError(
            "classifier requires Lite0/224, canonical labels, train/validation"
        )
    for key, maximum in (("seed", 2**31 - 1), ("batch_size", 256)):
        if type(document[key]) is not int or not 1 <= document[key] <= maximum:
            raise TrainingConfigError(f"invalid classifier {key}")
    training = document["training"]
    if not isinstance(training, dict) or set(training) != {
        "epochs",
        "early_stopping",
        "train_whole_model",
        "augmentation",
    }:
        raise TrainingConfigError("invalid classifier training fields")
    epochs, early = training["epochs"], training["early_stopping"]
    if (
        type(epochs) is not int
        or not 2 <= epochs <= 200
        or not isinstance(early, dict)
        or set(early) != {"enabled", "patience"}
        or early["enabled"] is not True
        or type(early["patience"]) is not int
        or not 1 <= early["patience"] < epochs
        or type(training["train_whole_model"]) is not bool
    ):
        raise TrainingConfigError("classifier requires bounded epochs and early stopping")
    augmentation = training["augmentation"]
    if (
        not isinstance(augmentation, dict)
        or set(augmentation) != {"rotation_180", "brightness_delta"}
        or type(augmentation["rotation_180"]) is not bool
        or type(augmentation["brightness_delta"]) not in {float, int}
        or not math.isfinite(augmentation["brightness_delta"])
        or not 0 <= augmentation["brightness_delta"] <= 0.1
    ):
        raise TrainingConfigError("augmentation must preserve card corners, color and identity")
    return ClassifierTrainingConfig(
        document["seed"],
        document["classifier_family"],
        224,
        document["batch_size"],
        CARD_LABELS,
        epochs,
        early["patience"],
        training["train_whole_model"],
        augmentation,
        document,
    )


def snapshot_classifier_inputs(inputs: TwoStageInputManifest, staging: Path):
    """Validate Stage 2 content-addressed crops before writing any source snapshot."""
    pending, identities = [], {}
    if inputs.evidence_mode not in {"DECK_DISJOINT", "EXPERIMENTAL_ONE_DECK"}:
        raise TrainingConfigError("unknown evidence mode")
    for partition in _PARTITIONS:
        records = inputs.classifier_partitions.get(partition)
        if not isinstance(records, tuple) or not records:
            raise TrainingConfigError(f"{partition} requires classifier crops")
        labels, seen = set(), set()
        identities[partition] = set()
        for record in records:
            if not isinstance(record, ClassifierCrop) or record.label not in CARD_LABELS:
                raise TrainingConfigError("invalid classifier crop or label")
            if (
                not isinstance(record.deck_group_id, str)
                or not record.deck_group_id
                or any(ord(c) < 32 for c in record.deck_group_id)
            ):
                raise TrainingConfigError("invalid classifier deck identity")
            if not re.fullmatch(r"[0-9a-f]{64}", record.source_image_sha256):
                raise TrainingConfigError("invalid classifier source hash")
            match = re.fullmatch(
                r"([0-9a-f]{64})-b([0-9]{4,})-([A-Z0-9]+)-([0-9a-f]{64})\.png", record.path.name
            )
            if (
                not match
                or match[1] != record.source_image_sha256
                or match[3] != record.label
                or record.path.parent.name != record.label
            ):
                raise TrainingConfigError(
                    "crop path must bind original source, box, label and hash"
                )
            identity = (record.source_image_sha256, int(match[2]))
            if identity in seen:
                raise TrainingConfigError("duplicate physical classifier crop")
            seen.add(identity)
            labels.add(record.label)
            identities[partition].update(
                (
                    f"source:{record.source_image_sha256}",
                    f"crop:{match[4]}",
                )
            )
            if inputs.evidence_mode != "EXPERIMENTAL_ONE_DECK":
                identities[partition].add(f"deck:{record.deck_group_id}")
            bounds = record.source_bounds
            if (
                len(bounds) != 4
                or any(type(v) is not int for v in bounds)
                or not (0 <= bounds[0] < bounds[2] and 0 <= bounds[1] < bounds[3])
            ):
                raise TrainingConfigError("invalid original-resolution crop bounds")
            payload = secure_read_bytes(record.path, root=inputs.output_root)
            if hashlib.sha256(payload).hexdigest() != match[4]:
                raise TrainingConfigError("classifier crop hash mismatch")
            with Image.open(io.BytesIO(payload)) as image:
                image.load()
                if image.format != "PNG" or image.size != (
                    bounds[2] - bounds[0],
                    bounds[3] - bounds[1],
                ):
                    raise TrainingConfigError("classifier crop differs from source bounds")
            pending.append((partition, record, payload))
        if labels != set(CARD_LABELS):
            raise TrainingConfigError(f"{partition} must contain all 52 classes")
    if identities["train"] & identities["validation"]:
        raise TrainingConfigError("classifier partitions leak deck, source or crop identity")
    result = {key: [] for key in _PARTITIONS}
    for partition, record, payload in pending:
        member = f"crops/{partition}/{record.label}/{record.path.name}"
        secure_write_bytes(staging, member, payload)
        result[partition].append(replace(record, path=staging / member))
    return {key: tuple(value) for key, value in result.items()}


def _probabilities(values, count):
    values = np.asarray(values, dtype=np.float64)
    if values.shape != (count, 52):
        raise TrainingConfigError("classifier requires finite normalized 52-class probabilities")
    try:
        return normalize_classifier_output(values)
    except ValueError as error:
        raise TrainingConfigError(str(error)) from error


def apply_temperature(probabilities, temperature):
    """Contract score transform: softmax(log(max(raw_probability, 1e-12)) / T)."""
    if type(temperature) not in {int, float} or not math.isfinite(temperature) or temperature <= 0:
        raise TrainingConfigError("invalid calibration temperature")
    values = _probabilities(probabilities, len(probabilities))
    logits = np.log(np.maximum(values, 1e-12)) / temperature
    logits -= logits.max(axis=1, keepdims=True)
    scores = np.exp(logits)
    return scores / scores.sum(axis=1, keepdims=True)


def calibrate_validation_scores(probabilities, validation_records):
    """Fit one bounded scalar temperature on validation only, including T=1 baseline."""
    values = _probabilities(probabilities, len(validation_records))
    truth = np.asarray([CARD_LABELS.index(r.label) for r in validation_records])

    def nll(scores):
        return float(-np.log(np.maximum(scores[np.arange(len(truth)), truth], 1e-12)).mean())

    temperatures = sorted({1.0, *np.geomspace(0.25, 4.0, 81).tolist()})
    best = min(temperatures, key=lambda t: (nll(apply_temperature(values, t)), abs(t - 1)))
    calibrated = apply_temperature(values, best)
    return calibrated, {
        "method": "temperature-scaling",
        "partition": "validation",
        "temperature": best,
        "nllBefore": nll(values),
        "nllAfter": nll(calibrated),
        "sampleCount": len(truth),
        "scoreTransform": "softmax(log(max(raw_probability,1e-12))/temperature)",
        "evaluation": "in-sample-validation-not-holdout-quality",
    }


def measure_classifier_predictions(records, probabilities):
    scores = _probabilities(probabilities, len(records))
    truth = np.asarray([CARD_LABELS.index(r.label) for r in records])
    correct = scores.argmax(axis=1) == truth
    confidence = scores.max(axis=1)

    def summarize(indices):
        indices = np.asarray(indices, dtype=int)
        count = len(indices)
        hits = int(correct[indices].sum())
        return {"count": count, "correct": hits, "top1Accuracy": hits / count if count else None}

    report = summarize(list(range(len(records))))
    for key, groups, get in (
        ("perClass", CARD_LABELS, lambda r: r.label),
        (
            "perRank",
            tuple(dict.fromkeys(label[:-1] for label in CARD_LABELS)),
            lambda r: r.label[:-1],
        ),
        ("perSuit", ("C", "D", "H", "S"), lambda r: r.label[-1]),
        ("perDeck", sorted({r.deck_group_id for r in records}), lambda r: r.deck_group_id),
    ):
        report[key] = {
            g: summarize([i for i, r in enumerate(records) if get(r) == g]) for g in groups
        }
    good, wrong = confidence[correct], confidence[~correct]
    # Rank-based Mann-Whitney probability with half credit for ties, bounded memory.
    ordered = np.sort(wrong)
    auc = (
        float(
            np.mean(
                (
                    np.searchsorted(ordered, good, side="left")
                    + np.searchsorted(ordered, good, side="right")
                )
                / (2 * len(wrong))
            )
        )
        if len(good) and len(wrong)
        else None
    )
    report["confidenceCorrectnessAuc"] = auc
    report["calibrationBins"] = []
    for low in np.linspace(0, 0.9, 10):
        selected = (confidence >= low) & (confidence < low + 0.1 if low < 0.9 else confidence <= 1)
        report["calibrationBins"].append(
            {
                "lower": float(low),
                "count": int(selected.sum()),
                "accuracy": float(correct[selected].mean()) if selected.any() else None,
                "meanConfidence": float(confidence[selected].mean()) if selected.any() else None,
            }
        )
    report["status"] = (
        "VALIDATION_SEPARATION_OBSERVED_NOT_RELEASE_APPROVED"
        if auc is not None and auc > 0.5
        else "BLOCKED_CLASSIFIER_QUALITY"
    )
    return report


def _pixels(record):
    with Image.open(io.BytesIO(secure_read_bytes(record.path))) as image:
        return resize_rgb(image, (224, 224)).astype(np.float32) / 255.0


def _fit_classifier(partitions, config, staging, one_batch_smoke):
    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    import tensorflow as tf
    import tensorflow_hub as hub

    random.seed(config.seed)
    np.random.seed(config.seed)
    tf.random.set_seed(config.seed)

    def dataset(records, augment):
        def generate():
            for record in records:
                yield _pixels(record), CARD_LABELS.index(record.label)

        result = tf.data.Dataset.from_generator(
            generate,
            output_signature=(
                tf.TensorSpec((224, 224, 3), tf.float32),
                tf.TensorSpec((), tf.int32),
            ),
        )
        if augment:
            result = result.shuffle(len(records), seed=config.seed)

            def transform(pixels, label):
                if config.augmentation["rotation_180"]:
                    pixels = tf.image.rot90(pixels, 2 * tf.random.uniform((), 0, 2, dtype=tf.int32))
                pixels = tf.clip_by_value(
                    tf.image.random_brightness(pixels, config.augmentation["brightness_delta"]),
                    0,
                    1,
                )
                return pixels, label

            result = result.map(transform, num_parallel_calls=1)
        return result.batch(1 if one_batch_smoke else config.batch_size)

    backbone = hub.KerasLayer(
        _TRANSFER_URI, trainable=config.train_whole_model and not one_batch_smoke
    )
    model = tf.keras.Sequential(
        [
            tf.keras.layers.InputLayer(input_shape=(224, 224, 3)),
            backbone,
            tf.keras.layers.Dense(52, activation="softmax"),
        ]
    )
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    training = dataset(partitions["train"], True)
    validation = dataset(partitions["validation"], False)
    early = tf.keras.callbacks.EarlyStopping(
        monitor="val_loss", restore_best_weights=True, patience=config.early_stopping_patience
    )
    history = _fit_and_restore_best(
        model,
        training.take(1) if one_batch_smoke else training,
        early_stopping=early,
        epochs=1 if one_batch_smoke else config.epochs,
        validation_data=validation.take(1) if one_batch_smoke else validation,
        verbose=2,
    )
    return model, {key: [float(v) for v in values] for key, values in history.history.items()}


def _export_classifier(model, records, staging, variant):
    import tensorflow as tf
    from tflite_support.metadata_writers import image_classifier

    concrete = tf.function(lambda images: model(images, training=False)).get_concrete_function(
        tf.TensorSpec([1, 224, 224, 3], tf.float32)
    )
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    if variant == "float16":
        converter.target_spec.supported_types = [tf.float16]
    else:

        def representative():
            # All calibration pixels come from the snapshotted training partition.
            for record in records:
                yield [np.expand_dims(_pixels(record), 0)]

        converter.representative_dataset = representative
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter.inference_input_type = tf.uint8
        converter.inference_output_type = tf.uint8
    labels = staging / "labels.txt"
    secure_write_bytes(staging, "labels.txt", ("\n".join(CARD_LABELS) + "\n").encode())
    writer = image_classifier.MetadataWriter.create_for_inference(
        bytearray(converter.convert()), [0.0], [255.0], [str(labels)]
    )
    filename = f"card_classifier.{variant}.tflite"
    secure_write_bytes(staging, filename, bytes(writer.populate()))
    return filename


def _infer_classifier(path, records):
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=1)
    interpreter.allocate_tensors()
    input_detail, output_detail = (
        interpreter.get_input_details()[0],
        interpreter.get_output_details()[0],
    )
    rows = []
    for record in records:
        pixels = np.expand_dims(_pixels(record), 0)
        if np.issubdtype(input_detail["dtype"], np.integer):
            scale, zero = input_detail["quantization"]
            limits = np.iinfo(input_detail["dtype"])
            pixels = np.clip(np.rint(pixels / scale + zero), limits.min, limits.max)
        interpreter.set_tensor(input_detail["index"], pixels.astype(input_detail["dtype"]))
        interpreter.invoke()
        rows.append(interpreter.get_tensor(output_detail["index"])[0])
    quantization = (
        output_detail["quantization"] if np.issubdtype(output_detail["dtype"], np.integer) else None
    )
    scores = normalize_classifier_output(rows, quantization=quantization)
    return _probabilities(scores, len(records))


def train_classifier(
    inputs: TwoStageInputManifest,
    config: ClassifierTrainingConfig,
    run_id: str,
    *,
    one_batch_smoke: bool = False,
    candidate_root: Path | None = None,
):
    safe_name(run_id, field="classifier run ID")
    with tempfile.TemporaryDirectory(prefix="pontinho-classifier-") as directory:
        staging = Path(directory)
        config_payload = yaml.safe_dump(config.document, sort_keys=True).encode()
        secure_write_bytes(staging, "resolved-config.yaml", config_payload)
        if load_classifier_config(staging / "resolved-config.yaml") != config:
            raise TrainingConfigError("classifier config differs from validated document")
        config = load_classifier_config(staging / "resolved-config.yaml")
        partitions = snapshot_classifier_inputs(inputs, staging)
        input_payload = json.dumps(
            {
                key: [
                    {**asdict(r), "path": str(r.path.relative_to(staging))} for r in partitions[key]
                ]
                for key in _PARTITIONS
            },
            sort_keys=True,
        ).encode()
        secure_write_bytes(staging, "training-inputs.json", input_payload)
        project = Path(__file__).resolve().parents[2]
        lock = secure_read_bytes(project / "requirements.lock", root=project)
        commit = _git_commit(project)
        model, history = _fit_classifier(partitions, config, staging, one_batch_smoke)
        selected_hash = _save_selected_weights(model, staging / "selected.weights.h5")
        model.load_weights(str(staging / "selected.weights.h5"))
        from pontinho_cards.export import inspect_classifier_tflite

        variants, measured = {}, {}
        for variant in ("float16", "int8"):
            filename = _export_classifier(model, partitions["train"], staging, variant)
            inspection = inspect_classifier_tflite(
                staging / filename, expected_quantization=variant
            )
            scores, calibration = calibrate_validation_scores(
                _infer_classifier(staging / filename, partitions["validation"]),
                partitions["validation"],
            )
            variants[variant] = {"filename": filename, **inspection, "calibration": calibration}
            measured[variant] = {
                **measure_classifier_predictions(partitions["validation"], scores),
                "modelSha256": inspection["modelSha256"],
                "calibration": calibration,
            }
        metrics = {
            "schemaVersion": 1,
            "resizePolicy": RESIZE_POLICY,
            "nmsPolicy": NMS_POLICY,
            "partition": "validation",
            "evidenceMode": inputs.evidence_mode,
            "history": history,
            "variants": measured,
            "status": "SMOKE_ONLY"
            if one_batch_smoke
            else (
                "VALIDATION_SEPARATION_OBSERVED_NOT_RELEASE_APPROVED"
                if all(
                    v["status"] == "VALIDATION_SEPARATION_OBSERVED_NOT_RELEASE_APPROVED"
                    for v in measured.values()
                )
                else "BLOCKED_CLASSIFIER_QUALITY"
            ),
        }
        metrics_payload = (json.dumps(metrics, indent=2, sort_keys=True) + "\n").encode()
        contract = {
            "schemaVersion": 1,
            "kind": "CARD_CLASSIFIER_CANDIDATE",
            "evidenceMode": inputs.evidence_mode,
            "resizePolicy": RESIZE_POLICY,
            "nmsPolicy": NMS_POLICY,
            "runId": run_id,
            "labels": list(CARD_LABELS),
            "inputSize": [224, 224],
            "transferWeights": _TRANSFER_URI,
            "scoreSemantics": "raw model probabilities require per-variant calibration transform",
            "selectedCheckpointSha256": selected_hash,
            "configSha256": hashlib.sha256(config_payload).hexdigest(),
            "trainingInputSha256": hashlib.sha256(input_payload).hexdigest(),
            "requirementsLockSha256": hashlib.sha256(lock).hexdigest(),
            "gitCommit": commit,
            "validationMetricsSha256": hashlib.sha256(metrics_payload).hexdigest(),
            "variants": variants,
            "selectedQuantization": None,
            "releaseApproved": False,
        }
        secure_write_bytes(staging, "validation-metrics.json", metrics_payload)
        secure_write_bytes(
            staging,
            "candidate-contract.json",
            (json.dumps(contract, indent=2, sort_keys=True) + "\n").encode(),
        )
        secure_write_bytes(staging, "requirements.lock", lock)
        filenames = [v["filename"] for v in variants.values()] + [
            "selected.weights.h5",
            "resolved-config.yaml",
            "training-inputs.json",
            "requirements.lock",
            "candidate-contract.json",
            "validation-metrics.json",
        ]
        output = _publish_candidate(
            staging,
            candidate_root
            if candidate_root is not None
            else project / "artifacts/classifier-candidates",
            run_id,
            filenames,
            allow_portable_fallback=inputs.evidence_mode == "EXPERIMENTAL_ONE_DECK",
        )
    return ClassifierCandidate(
        output / "card_classifier.float16.tflite",
        output / "candidate-contract.json",
        output / "validation-metrics.json",
    )


def main(argv: Sequence[str] | None = None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--inputs", required=True, type=Path, help="audited normalized split JSON")
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args(argv)
    try:
        config = load_classifier_config(args.config)
        document = json.loads(secure_read_bytes(args.inputs, root=args.inputs.parent))
        candidate = train_classifier(
            build_two_stage_inputs(document, args.inputs.parent), config, args.run_id
        )
    except (ValueError, OSError) as error:
        parser.exit(2, f"BLOCKED_CLASSIFIER_QUALITY: {error}\n")
    print(candidate.contract_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
