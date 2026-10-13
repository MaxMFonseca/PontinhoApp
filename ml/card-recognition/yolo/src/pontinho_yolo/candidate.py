"""Execution adapters for the measured Stage 10 candidate run."""

from __future__ import annotations

import hashlib
import os
from collections.abc import Iterable
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from pontinho_yolo.evaluate import (
    EvaluationError,
    PredictionPartition,
    RawScenePrediction,
    TruthDetection,
    TruthPartition,
    TruthScene,
)
from pontinho_yolo.labels import CARD_LABELS
from pontinho_yolo.postprocess import OUTPUT_SHAPE, Box, LetterboxGeometry

_IMAGE_SUFFIXES = frozenset({".jpg", ".jpeg", ".png"})


def _litert_thread_count() -> int:
    """Bound native LiteRT parallelism for fast, predictable offline evaluation."""
    return max(1, min(os.cpu_count() or 1, 8))


def load_truth_partition(
    dataset_root: Path,
    partition: str,
    dataset_sha256: str,
) -> tuple[TruthPartition, tuple[Path, ...]]:
    """Load one YOLO split in stable order without crossing split boundaries."""
    split = {"validation": "valid", "test": "test"}.get(partition)
    if split is None:
        raise EvaluationError("candidate truth partition must be validation or test")
    _validate_sha256(dataset_sha256, "dataset SHA-256")
    root = Path(dataset_root)
    image_root = root / split / "images"
    label_root = root / split / "labels"
    if root.is_symlink() or not image_root.is_dir() or not label_root.is_dir():
        raise EvaluationError(f"dataset split is missing: {split}")
    images = tuple(
        sorted(
            (
                path
                for path in image_root.iterdir()
                if path.is_file()
                and not path.is_symlink()
                and path.suffix.lower() in _IMAGE_SUFFIXES
            ),
            key=lambda path: path.name,
        )
    )
    if not images:
        raise EvaluationError(f"dataset split has no images: {split}")
    scenes: list[TruthScene] = []
    for image_path in images:
        label_path = label_root / f"{image_path.stem}.txt"
        if label_path.is_symlink() or not label_path.is_file():
            raise EvaluationError(f"missing regular label file for {image_path.name}")
        with Image.open(image_path) as image:
            width, height = image.size
        detections = _parse_yolo_labels(label_path, width, height)
        scenes.append(TruthScene(image_path.name, detections))
    label_stems = {path.stem for path in label_root.iterdir() if path.is_file()}
    image_stems = {path.stem for path in images}
    if label_stems != image_stems:
        raise EvaluationError(f"image/label membership differs in split: {split}")
    return TruthPartition(partition, dataset_sha256, tuple(scenes)), images


def prepare_image(path: Path) -> tuple[np.ndarray, LetterboxGeometry]:
    """Return the pinned RGB CHW float32 centered-letterbox input."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise EvaluationError(f"inference image must be a regular file: {path}")
    with Image.open(path) as source:
        rgb = source.convert("RGB")
        geometry = LetterboxGeometry.for_source(*rgb.size)
        source_array = np.asarray(rgb, dtype=np.uint8)
        resized = cv2.resize(
            source_array,
            (geometry.resized_width, geometry.resized_height),
            interpolation=cv2.INTER_LINEAR,
        )
        canvas = cv2.copyMakeBorder(
            resized,
            int(geometry.pad_y),
            geometry.pad_bottom,
            int(geometry.pad_x),
            geometry.pad_right,
            cv2.BORDER_CONSTANT,
            value=(114, 114, 114),
        )
        array = canvas.astype(np.float32) / np.float32(255.0)
    return np.ascontiguousarray(array.transpose(2, 0, 1)), geometry


def checkpoint_output_to_contract(output: np.ndarray) -> np.ndarray:
    """Convert Ultralytics checkpoint pixel-space xywh to normalized raw output."""
    value = np.asarray(output, dtype=np.float32)
    if value.shape != OUTPUT_SHAPE or not np.isfinite(value).all():
        raise EvaluationError(f"checkpoint output must be finite with shape {OUTPUT_SHAPE}")
    normalized = np.array(value, dtype=np.float32, copy=True, order="C")
    normalized[:, :4, :] /= np.float32(640.0)
    return normalized


def predict_checkpoint(
    checkpoint: Path,
    image_paths: Iterable[Path],
    *,
    partition: str,
    dataset_sha256: str,
    batch_size: int = 16,
) -> PredictionPartition:
    """Run one PyTorch checkpoint and bind raw outputs to its exact bytes."""
    import torch
    from ultralytics import YOLO

    checkpoint = Path(checkpoint)
    paths = tuple(Path(path) for path in image_paths)
    if batch_size <= 0 or not paths:
        raise EvaluationError("checkpoint inference requires images and a positive batch size")
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    if device.type != "cuda":
        raise EvaluationError("real candidate checkpoint inference requires CUDA")
    wrapper = YOLO(str(checkpoint))
    if tuple(wrapper.names[index] for index in range(len(wrapper.names))) != CARD_LABELS:
        raise EvaluationError("checkpoint label order differs from the canonical 52 labels")
    model = wrapper.model.to(device).eval()
    scenes: list[RawScenePrediction] = []
    with torch.inference_mode():
        for start in range(0, len(paths), batch_size):
            batch_paths = paths[start : start + batch_size]
            prepared = [prepare_image(path) for path in batch_paths]
            tensor = torch.from_numpy(np.stack([item[0] for item in prepared])).to(device)
            result = model(tensor)
            raw = result[0] if isinstance(result, tuple) else result
            values = raw.detach().float().cpu().numpy()
            if values.shape != (len(batch_paths), *OUTPUT_SHAPE[1:]):
                raise EvaluationError(
                    f"checkpoint returned unexpected output shape: {values.shape}"
                )
            for path, (_, geometry), value in zip(batch_paths, prepared, values, strict=True):
                scenes.append(
                    RawScenePrediction(
                        scene_id=path.name,
                        output=checkpoint_output_to_contract(value[np.newaxis, ...]),
                        geometry=geometry,
                    )
                )
    return PredictionPartition.from_model(
        partition=partition,
        dataset_sha256=dataset_sha256,
        source_model=checkpoint,
        scenes=tuple(scenes),
    )


def predict_tflite(
    model_path: Path,
    image_paths: Iterable[Path],
    *,
    partition: str,
    dataset_sha256: str,
) -> PredictionPartition:
    """Run raw LiteRT inference using the exported float graph boundary."""
    from ai_edge_litert.interpreter import Interpreter

    model_path = Path(model_path)
    paths = tuple(Path(path) for path in image_paths)
    if not paths:
        raise EvaluationError("LiteRT inference requires at least one image")
    interpreter = Interpreter(model_path=str(model_path), num_threads=_litert_thread_count())
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise EvaluationError("LiteRT candidate must have one input and one output")
    input_detail = inputs[0]
    output_detail = outputs[0]
    input_shape = tuple(int(value) for value in input_detail["shape"])
    if input_shape not in {(1, 640, 640, 3), (1, 3, 640, 640)}:
        raise EvaluationError(f"LiteRT input shape is unsupported: {input_shape}")
    if input_detail["dtype"] != np.float32 or output_detail["dtype"] != np.float32:
        raise EvaluationError("LiteRT candidate graph boundaries must be float32")
    scenes: list[RawScenePrediction] = []
    for path in paths:
        chw, geometry = prepare_image(path)
        value = chw[np.newaxis, ...]
        if input_shape == (1, 640, 640, 3):
            value = value.transpose(0, 2, 3, 1)
        interpreter.set_tensor(int(input_detail["index"]), np.ascontiguousarray(value))
        interpreter.invoke()
        output = np.asarray(interpreter.get_tensor(int(output_detail["index"])), dtype=np.float32)
        if output.shape != OUTPUT_SHAPE or not np.isfinite(output).all():
            raise EvaluationError(f"LiteRT output must be finite with shape {OUTPUT_SHAPE}")
        scenes.append(RawScenePrediction(path.name, np.array(output, copy=True), geometry))
    return PredictionPartition.from_model(
        partition=partition,
        dataset_sha256=dataset_sha256,
        source_model=model_path,
        scenes=tuple(scenes),
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _parse_yolo_labels(path: Path, width: int, height: int) -> tuple[TruthDetection, ...]:
    detections: list[TruthDetection] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        fields = line.split()
        if len(fields) != 5:
            raise EvaluationError(f"invalid YOLO label row at {path}:{line_number}")
        try:
            class_index = int(fields[0])
            cx, cy, box_width, box_height = (float(value) for value in fields[1:])
        except ValueError as error:
            raise EvaluationError(f"invalid YOLO label row at {path}:{line_number}") from error
        if (
            not 0 <= class_index < len(CARD_LABELS)
            or not all(np.isfinite((cx, cy, box_width, box_height)))
            or not 0.0 < box_width <= 1.0
            or not 0.0 < box_height <= 1.0
            or not 0.0 <= cx <= 1.0
            or not 0.0 <= cy <= 1.0
        ):
            raise EvaluationError(f"out-of-contract YOLO label at {path}:{line_number}")
        left = (cx - box_width / 2.0) * width
        top = (cy - box_height / 2.0) * height
        right = (cx + box_width / 2.0) * width
        bottom = (cy + box_height / 2.0) * height
        if left < 0.0 or top < 0.0 or right > width or bottom > height:
            raise EvaluationError(f"YOLO label crosses image bounds at {path}:{line_number}")
        detections.append(
            TruthDetection(CARD_LABELS[class_index], Box(left, top, right, bottom))
        )
    if not 1 <= len(detections) <= 10:
        raise EvaluationError(f"truth scene must contain 1..10 cards: {path}")
    return tuple(detections)


def _validate_sha256(value: str, description: str) -> None:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise EvaluationError(f"{description} is invalid")
