"""Train a one-class detector; publish measured candidates, never release assets.

The CLI input is an audited normalized split document, relative to the JSON's
directory, consumed by Stage 2's builder. Only train/validation are materialized.
Generated smoke fixtures exercise infrastructure and are never quality evidence.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import io
import json
import math
import os
import random
import tempfile
import uuid
import xml.etree.ElementTree as ET
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any

import yaml
from PIL import Image

from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.secure_io import (
    SecureReadError,
    _open_absolute_directory,
    safe_name,
    secure_read_bytes,
    secure_write_bytes,
)
from pontinho_cards.train import (
    TrainingConfig,
    TrainingConfigError,
    _augmentation_hparams,
    _git_commit,
    _save_selected_weights,
    assert_negative_examples_loaded,
    load_training_config_bytes,
)
from pontinho_cards.two_stage_data import (
    DetectorRecord,
    TwoStageInputManifest,
    build_two_stage_inputs,
)

DetectorTrainingConfig = TrainingConfig
_PARTITIONS = ("train", "validation")
_BUCKETS = (1, 3, 5, 8, 10)


@dataclass(frozen=True)
class DetectorCandidate:
    # float16 is a stable reference, not a selected or approved quantization.
    model_path: Path
    contract_path: Path
    validation_metrics_path: Path


def load_detector_config(path: Path) -> DetectorTrainingConfig:
    """Reuse the bounded legacy training policy with a strict one-class schema."""
    try:
        document = yaml.safe_load(secure_read_bytes(path, root=path.parent))
    except (SecureReadError, yaml.YAMLError) as error:
        raise TrainingConfigError(f"cannot read detector config: {error}") from error
    expected = {
        "schema_version",
        "seed",
        "detector_family",
        "image_size",
        "batch_size",
        "confidence_threshold",
        "iou_threshold",
        "labels",
        "partitions",
        "training",
    }
    if not isinstance(document, dict) or set(document) != expected:
        raise TrainingConfigError("detector config has missing or unknown fields")
    if document["schema_version"] != 1 or document["labels"] != ["card"]:
        raise TrainingConfigError("detector schema 1 requires exactly the card label")
    if document["partitions"] != {"train": "train", "validation": "validation"}:
        raise TrainingConfigError("detector partitions must be train/validation only")
    training = document["training"]
    if (
        not isinstance(training, dict)
        or set(training) != {"epochs", "early_stopping", "train_whole_model", "augmentation"}
        or not isinstance(training["early_stopping"], dict)
        or set(training["early_stopping"]) != {"enabled", "patience"}
    ):
        raise TrainingConfigError("invalid detector training fields")
    common = load_training_config_bytes(
        yaml.safe_dump({**document, "labels": list(CARD_LABELS)}).encode()
    )
    if common.confidence_threshold <= 0 or common.iou_threshold <= 0:
        raise TrainingConfigError("detector confidence and IoU thresholds must be in (0, 1]")
    return replace(common, labels=("card",), document=document)


def snapshot_detector_inputs(
    inputs: TwoStageInputManifest,
    staging: Path,
) -> dict[str, tuple[DetectorRecord, ...]]:
    """Verify both partitions fully before writing any immutable source snapshot."""
    snapshots = []
    identities: dict[str, set[str]] = {}
    for partition in _PARTITIONS:
        records = inputs.detector_partitions.get(partition)
        if not isinstance(records, tuple) or not records or not any(r.boxes for r in records):
            raise TrainingConfigError(f"{partition} requires positive detector scenes")
        seen: set[str] = set()
        for record in records:
            if not isinstance(record, DetectorRecord):
                raise TrainingConfigError("detector record type is invalid")
            for field in (record.image_id, record.deck_group_id, record.split_group_id):
                if not isinstance(field, str) or not field or any(ord(c) < 32 for c in field):
                    raise TrainingConfigError("detector identity is missing or invalid")
            if record.image_id in seen:
                raise TrainingConfigError("duplicate detector image ID")
            seen.add(record.image_id)
            keys = {
                f"id:{record.image_id}",
                f"deck:{record.deck_group_id}",
                f"split:{record.split_group_id}",
                f"hash:{record.source_image_sha256}",
            }
            identities.setdefault(partition, set()).update(keys)
            if any(type(v) is not int or v <= 0 for v in (record.width, record.height)):
                raise TrainingConfigError("detector dimensions must be positive integers")
            for box in record.boxes:
                if (
                    box.label != "card"
                    or len(box.bounds) != 4
                    or any(type(v) not in {int, float} or not math.isfinite(v) for v in box.bounds)
                ):
                    raise TrainingConfigError("detector requires finite card boxes")
                x0, y0, x1, y1 = box.bounds
                if not (0 <= x0 < x1 <= record.width and 0 <= y0 < y1 <= record.height):
                    raise TrainingConfigError("detector box is outside image bounds")
            try:
                payload = secure_read_bytes(record.path, root=inputs.output_root)
                if hashlib.sha256(payload).hexdigest() != record.source_image_sha256:
                    raise TrainingConfigError("detector source hash mismatch")
                with Image.open(io.BytesIO(payload)) as image:
                    image.load()
                    if image.size != (record.width, record.height):
                        raise TrainingConfigError("detector source dimensions mismatch")
                    suffix = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}.get(
                        image.format, ".img"
                    )
            except (OSError, SyntaxError) as error:
                raise TrainingConfigError(f"cannot snapshot detector image: {error}") from error
            snapshots.append((partition, record, payload, suffix))
    if identities["train"] & identities["validation"]:
        raise TrainingConfigError("detector partitions leak image/deck/split identity")
    result: dict[str, list[DetectorRecord]] = {key: [] for key in _PARTITIONS}
    for partition, record, payload, suffix in snapshots:
        member = f"images/{partition}/{record.source_image_sha256}{suffix}"
        secure_write_bytes(staging, member, payload)
        result[partition].append(replace(record, path=staging / member))
    return {key: tuple(records) for key, records in result.items()}


def _iou(left: Sequence[float], right: Sequence[float]) -> float:
    intersection = max(0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0, min(left[3], right[3]) - max(left[1], right[1])
    )
    union = (
        (left[2] - left[0]) * (left[3] - left[1])
        + (right[2] - right[0]) * (right[3] - right[1])
        - intersection
    )
    return intersection / union if union > 0 else 0.0


def measure_detector_predictions(
    records: Sequence[DetectorRecord],
    predictions: Mapping[str, Sequence[tuple[float, Sequence[float]]]],
    *,
    score_threshold: float,
    iou_threshold: float,
) -> dict[str, Any]:
    """Compute one-to-one AP50 and operating recall/FP from normalized xyxy boxes.

    AP50 integrates the precision envelope at all emitted scores; operating
    metrics use the configured score and matching IoU. No final holdout is read.
    """
    if not records or set(predictions) != {r.image_id for r in records}:
        raise TrainingConfigError("predictions must cover exactly the validation images")
    for value in (score_threshold, iou_threshold):
        if type(value) not in {int, float} or not math.isfinite(value) or not 0 < value <= 1:
            raise TrainingConfigError("metric thresholds must be in (0, 1]")
    scenes = []
    for record in records:
        truth = [
            tuple(
                v / (record.width if i % 2 == 0 else record.height)
                for i, v in enumerate(box.bounds)
            )
            for box in record.boxes
        ]
        predicted = list(predictions[record.image_id])
        for score, bounds in predicted:
            if (
                not math.isfinite(score)
                or not 0 <= score <= 1
                or len(bounds) != 4
                or any(not math.isfinite(v) for v in bounds)
                or bounds[0] >= bounds[2]
                or bounds[1] >= bounds[3]
            ):
                raise TrainingConfigError("invalid detector prediction")
        predicted.sort(key=lambda item: -item[0])

        def match(threshold, minimum_score):
            matched = set()
            entries = []
            for score, bounds in predicted:
                if score < minimum_score:
                    continue
                options = [
                    (i, _iou(bounds, box)) for i, box in enumerate(truth) if i not in matched
                ]
                best = max(options, key=lambda p: (p[1], -p[0]), default=(-1, -1))
                correct = best[1] >= threshold
                if correct:
                    matched.add(best[0])
                entries.append((score, correct))
            return entries

        scenes.append((record, match(iou_threshold, score_threshold), match(0.5, 0.0)))

    def summarize(selected):
        support = sum(len(row[0].boxes) for row in selected)
        operating = [item for row in selected for item in row[1]]
        ranked = sorted((item for row in selected for item in row[2]), key=lambda p: -p[0])
        tp = sum(correct for _, correct in operating)
        fp = len(operating) - tp
        cumulative, points = 0, []
        for index, (_, correct) in enumerate(ranked, 1):
            cumulative += correct
            points.append((cumulative / support if support else 0, cumulative / index))
        envelope = 0.0
        area = 0.0
        for index in range(len(points) - 1, -1, -1):
            recall, precision = points[index]
            envelope = max(envelope, precision)
            previous = points[index - 1][0] if index else 0.0
            area += (recall - previous) * envelope
        return {
            "sceneCount": len(selected),
            "cardCount": support,
            "truePositives": tp,
            "falsePositives": fp,
            "falseNegatives": support - tp,
            "recall": tp / support if support else 0.0,
            "precision": tp / len(operating) if operating else 0.0,
            "falsePositiveShare": fp / len(operating) if operating else 0.0,
            "AP50": area,
        }

    report = summarize(scenes)
    report["byCardCount"] = {
        str(count): summarize([r for r in scenes if len(r[0].boxes) == count])
        for count in sorted({len(r.boxes) for r in records})
    }
    report["byDeckGroup"] = {
        deck: summarize([r for r in scenes if r[0].deck_group_id == deck])
        for deck in sorted({r.deck_group_id for r in records})
    }
    report["missingCardCountBuckets"] = [n for n in _BUCKETS if str(n) not in report["byCardCount"]]
    report["negativeSceneCount"] = sum(not r.boxes for r in records)
    slices = [
        report,
        *[report["byCardCount"][str(n)] for n in _BUCKETS if str(n) in report["byCardCount"]],
    ]
    passes = (
        not report["missingCardCountBuckets"]
        and report["negativeSceneCount"] > 0
        and all(s["recall"] >= 0.97 and s["falsePositiveShare"] <= 0.01 for s in slices)
    )
    report["status"] = (
        "VALIDATION_ENVELOPE_MET_NOT_RELEASE_APPROVED" if passes else "BLOCKED_DETECTOR_QUALITY"
    )
    return report


def _fit_and_restore_best(model, training, *, early_stopping, **fit_arguments):
    """Select the monitored best epoch even when the configured epoch limit wins."""
    callbacks = [*fit_arguments.pop("callbacks", ()), early_stopping]
    history = model.fit(training, callbacks=callbacks, **fit_arguments)
    # Pinned Keras 2.8 restores inside its patience branch, not on_train_end.
    # Always restore before the caller saves, reloads, evaluates or exports.
    if early_stopping.best_weights is None:
        raise TrainingConfigError("early stopping did not observe weights for checkpoint selection")
    model.set_weights(early_stopping.best_weights)
    return history


def _fit_detector(partitions, config, staging, one_batch_smoke):
    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    import numpy as np
    import tensorflow as tf
    from tensorflow_examples.lite.model_maker.third_party.efficientdet.keras import train, train_lib
    from tflite_model_maker import object_detector

    random.seed(config.seed)
    np.random.seed(config.seed)
    tf.random.set_seed(config.seed)
    loaders = {}
    for partition, records in partitions.items():
        names = []
        for index, record in enumerate(records):
            # Model Maker's Pascal reader accepts JPEG only. Preserve the audited
            # snapshot for inference; encode a separate training derivative.
            with Image.open(io.BytesIO(secure_read_bytes(record.path))) as image:
                encoded = io.BytesIO()
                image.convert("RGB").save(encoded, format="JPEG", quality=95)
            jpeg_member = f"model-maker-images/{partition}/{index:06d}.jpg"
            secure_write_bytes(staging, jpeg_member, encoded.getvalue())
            annotation = ET.Element("annotation")
            ET.SubElement(annotation, "filename").text = str(staging / jpeg_member)
            size = ET.SubElement(annotation, "size")
            for key, value in (("width", record.width), ("height", record.height), ("depth", 3)):
                ET.SubElement(size, key).text = str(value)
            for box in record.boxes:
                item = ET.SubElement(annotation, "object")
                ET.SubElement(item, "name").text = "card"
                ET.SubElement(item, "difficult").text = "0"
                ET.SubElement(item, "truncated").text = "0"
                ET.SubElement(item, "pose").text = "Unspecified"
                bounds = ET.SubElement(item, "bndbox")
                # VOC stores integer pixels; floor minima/ceil maxima preserve tiny boxes.
                values = [
                    math.floor(box.bounds[0]),
                    math.floor(box.bounds[1]),
                    math.ceil(box.bounds[2]),
                    math.ceil(box.bounds[3]),
                ]
                for key, value in zip(("xmin", "ymin", "xmax", "ymax"), values):
                    ET.SubElement(bounds, key).text = str(value)
            name = f"scene-{index:06d}"
            names.append(name)
            secure_write_bytes(staging, f"pascal/{partition}/{name}.xml", ET.tostring(annotation))
        loaders[partition] = object_detector.DataLoader.from_pascal_voc(
            images_dir="/",
            annotations_dir=str(staging / "pascal" / partition),
            label_map=["card"],
            annotation_filenames=names,
            num_shards=1,
            cache_dir=str(staging / "cache" / partition),
        )
        if loaders[partition].label_map != {1: "card"} or len(loaders[partition]) != len(records):
            raise TrainingConfigError("one-class Model Maker loader changed labels or scene count")
        assert_negative_examples_loaded(loaders[partition], sum(not r.boxes for r in records))
    batch_size = 1 if one_batch_smoke else config.batch_size
    if len(loaders["train"]) < batch_size or len(loaders["validation"]) < batch_size:
        raise TrainingConfigError("train/validation scene count is smaller than batch size")
    spec = object_detector.EfficientDetLite0Spec(
        model_dir=str(staging / "model"),
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        steps_per_execution=1,
        hparams=_augmentation_hparams(config),
        tf_random_seed=config.seed,
    )
    detector = object_detector.create(
        loaders["train"],
        model_spec=spec,
        validation_data=loaders["validation"],
        epochs=1 if one_batch_smoke else config.epochs,
        batch_size=batch_size,
        train_whole_model=False if one_batch_smoke else config.train_whole_model,
        do_train=False,
    )
    # Model Maker 0.4.3 silently forces >=2 output classes and emits an extra
    # "???" label for a one-class map. Rebuild with exactly one class; the real
    # training/export smoke covers this pinned-stack compatibility override.
    spec.config.num_classes = 1
    training, steps, _ = detector._get_dataset_and_steps(
        loaders["train"], batch_size, is_training=True
    )
    validation, val_steps, val_json = detector._get_dataset_and_steps(
        loaders["validation"], batch_size, is_training=False
    )
    if one_batch_smoke:
        steps, validation, val_steps, val_json = 1, None, 0, None
    model = detector.create_model()
    spec.config.update(
        {
            "steps_per_epoch": steps,
            "eval_samples": batch_size * val_steps,
            "val_json_file": val_json,
            "batch_size": batch_size,
        }
    )
    train.setup_model(model, spec.config)
    train.init_experimental(spec.config)
    callbacks = train_lib.get_callbacks(spec.config.as_dict(), validation)
    early_stopping = tf.keras.callbacks.EarlyStopping(
        monitor="loss" if one_batch_smoke else "val_loss",
        patience=0 if one_batch_smoke else config.early_stopping_patience,
        restore_best_weights=True,
    )
    history = _fit_and_restore_best(
        model,
        training,
        early_stopping=early_stopping,
        epochs=1 if one_batch_smoke else config.epochs,
        steps_per_epoch=steps,
        callbacks=callbacks,
        validation_data=validation,
        validation_steps=val_steps or None,
        verbose=2,
    )
    detector.model = model
    return (
        detector,
        loaders["train"],
        {k: [float(v) for v in vs] for k, vs in history.history.items()},
    )


def _infer_detector(path, inspection, records):
    import numpy as np
    import tensorflow as tf

    from pontinho_cards.evaluate import prepare_model_input

    interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=1)
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_by_name = {d["name"]: d for d in interpreter.get_output_details()}
    result = {}
    for record in records:
        with Image.open(io.BytesIO(secure_read_bytes(record.path))) as image:
            pixels = np.asarray(image.convert("RGB").resize((320, 320), Image.Resampling.BILINEAR))
        prepared = prepare_model_input(pixels, input_detail, inspection)
        interpreter.set_tensor(int(input_detail["index"]), np.expand_dims(prepared, 0))
        interpreter.invoke()
        outputs = {
            d["semantic"]: interpreter.get_tensor(int(output_by_name[d["tensorName"]]["index"]))[0]
            for d in inspection["outputs"]
        }
        raw_count = float(outputs["count"])
        if (
            not math.isfinite(raw_count)
            or raw_count != int(raw_count)
            or not 0 <= raw_count <= len(outputs["boxes"])
        ):
            raise TrainingConfigError("invalid detector result count")
        detections = []
        for index in range(int(raw_count)):
            if float(outputs["classes"][index]) != 0:
                raise TrainingConfigError("one-class detector emitted a non-card class")
            y0, x0, y1, x1 = (float(v) for v in outputs["boxes"][index])
            detections.append((float(outputs["scores"][index]), (x0, y0, x1, y1)))
        result[record.image_id] = detections
    return result


def _publish_candidate(staging: Path, root: Path, run_id: str, filenames: Sequence[str]) -> Path:
    """Copy into a held parent then atomically rename without replacing any run."""
    parent, descriptors = _open_absolute_directory(root, create=True)
    temporary = f".detector-{uuid.uuid4().hex}"
    created = False
    try:
        os.mkdir(temporary, mode=0o700, dir_fd=parent)
        created = True
        target = os.open(temporary, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        descriptors.append(target)
        for name in filenames:
            payload = secure_read_bytes(staging / name, root=staging)
            descriptor = os.open(
                name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=target
            )
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
        os.fsync(target)
        # Linux RENAME_NOREPLACE avoids even replacing an empty concurrent target.
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.renameat2(parent, temporary.encode(), parent, run_id.encode(), 1) != 0:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error))
        created = False
        os.fsync(parent)
    finally:
        if created:
            for name in filenames:
                try:
                    os.unlink(name, dir_fd=target)
                except (FileNotFoundError, UnboundLocalError):
                    pass
            os.rmdir(temporary, dir_fd=parent)
        for descriptor in reversed(descriptors):
            os.close(descriptor)
    return root / run_id


def train_detector(
    inputs: TwoStageInputManifest,
    config: DetectorTrainingConfig,
    run_id: str,
    *,
    one_batch_smoke: bool = False,
    candidate_root: Path | None = None,
) -> DetectorCandidate:
    safe_name(run_id, field="detector run ID")
    # Revalidate mutable nested config and prohibit mismatched dataclass overrides.
    with tempfile.TemporaryDirectory(prefix="pontinho-detector-") as directory:
        staging = Path(directory)
        config_payload = yaml.safe_dump(config.document, sort_keys=True).encode()
        secure_write_bytes(staging, "resolved-config.yaml", config_payload)
        if load_detector_config(staging / "resolved-config.yaml") != config:
            raise TrainingConfigError("detector configuration differs from validated document")
        config = load_detector_config(staging / "resolved-config.yaml")
        partitions = snapshot_detector_inputs(inputs, staging)
        input_document = {
            key: [{**asdict(r), "path": str(r.path.relative_to(staging))} for r in partitions[key]]
            for key in _PARTITIONS
        }
        input_payload = json.dumps(input_document, sort_keys=True).encode()
        secure_write_bytes(staging, "training-inputs.json", input_payload)
        project = Path(__file__).resolve().parents[2]
        lock = secure_read_bytes(project / "requirements.lock", root=project)
        git_commit = _git_commit(project)
        detector, representative, history = _fit_detector(
            partitions, config, staging, one_batch_smoke
        )
        checkpoint = staging / "selected.weights.h5"
        selected_hash = _save_selected_weights(detector.model, checkpoint)
        detector.model.load_weights(str(checkpoint))

        from tensorflow_examples.lite.model_maker.core.task import configs

        from pontinho_cards.export import inspect_tflite

        variants, measured = {}, {}
        for variant in ("float16", "int8"):
            filename = f"card_detector.{variant}.tflite"
            quantization = (
                configs.QuantizationConfig.for_float16()
                if variant == "float16"
                else detector.model_spec.get_default_quantization_config(representative)
            )
            detector.export(
                str(staging),
                tflite_filename=filename,
                quantization_config=quantization,
                with_metadata=True,
            )
            inspection = inspect_tflite(
                staging / filename, expected_quantization=variant, expected_labels=("card",)
            )
            variants[variant] = {"filename": filename, **inspection}
            predictions = _infer_detector(staging / filename, inspection, partitions["validation"])
            measured[variant] = measure_detector_predictions(
                partitions["validation"],
                predictions,
                score_threshold=config.confidence_threshold,
                iou_threshold=config.iou_threshold,
            )
            measured[variant]["modelSha256"] = inspection["modelSha256"]
        contract = {
            "schemaVersion": 1,
            "kind": "ONE_CLASS_DETECTOR_CANDIDATE",
            "runId": run_id,
            "labels": ["card"],
            "inputSize": [320, 320],
            "boxCoordinates": "normalized-ymin-xmin-ymax-xmax",
            "scoreThreshold": config.confidence_threshold,
            "iouThreshold": config.iou_threshold,
            "thresholdProvenance": "configured-not-validation-selected",
            "selectedCheckpointSha256": selected_hash,
            "configSha256": hashlib.sha256(config_payload).hexdigest(),
            "trainingInputSha256": hashlib.sha256(input_payload).hexdigest(),
            "requirementsLockSha256": hashlib.sha256(lock).hexdigest(),
            "gitCommit": git_commit,
            "variants": variants,
            "selectedQuantization": None,
            "releaseApproved": False,
        }
        status = (
            "SMOKE_ONLY"
            if one_batch_smoke
            else (
                "VALIDATION_ENVELOPE_MET_NOT_RELEASE_APPROVED"
                if any(
                    v["status"] == "VALIDATION_ENVELOPE_MET_NOT_RELEASE_APPROVED"
                    for v in measured.values()
                )
                else "BLOCKED_DETECTOR_QUALITY"
            )
        )
        metrics = {
            "schemaVersion": 1,
            "status": status,
            "partition": "validation",
            "history": history,
            "variants": measured,
        }
        metrics_payload = (json.dumps(metrics, indent=2, sort_keys=True) + "\n").encode()
        contract["validationMetricsSha256"] = hashlib.sha256(metrics_payload).hexdigest()
        for name, value in (
            ("candidate-contract.json", contract),
            ("validation-metrics.json", metrics),
        ):
            secure_write_bytes(
                staging, name, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
            )
        secure_write_bytes(staging, "training-inputs.json", input_payload)
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
            else project / "artifacts/detector-candidates",
            run_id,
            filenames,
        )
    return DetectorCandidate(
        output / "card_detector.float16.tflite",
        output / "candidate-contract.json",
        output / "validation-metrics.json",
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument(
        "--inputs",
        type=Path,
        required=True,
        help="audited normalized split JSON for the Stage 2 builder",
    )
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args(argv)
    try:
        config = load_detector_config(args.config)
        document = json.loads(secure_read_bytes(args.inputs, root=args.inputs.parent))
        inputs = build_two_stage_inputs(document, args.inputs.parent)
        candidate = train_detector(inputs, config, args.run_id)
    except (ValueError, OSError) as error:
        parser.exit(2, f"BLOCKED_DETECTOR_QUALITY: {error}\n")
    print(candidate.contract_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
