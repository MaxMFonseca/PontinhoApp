"""Shared numerical fixtures, not recognition-quality evidence."""

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

CASES = json.loads(
    (Path(__file__).parent / "fixtures/android-release/model-resize-golden.json").read_text()
)["cases"]


def source_image(case):
    width, height = case["sourceSize"]
    if "rgb" in case:
        pixels = np.asarray(case["rgb"], dtype=np.uint8).reshape(height, width, 3)
    else:
        y, x = np.indices((height, width))
        pixels = np.stack(
            [(a * x + b * y + c) % 256 for a, b, c in case["pattern"]], axis=-1
        ).astype(np.uint8)
    return Image.fromarray(pixels)


def fingerprint(pixels):
    value = 0xCBF29CE484222325
    for byte in np.asarray(pixels, dtype=np.uint8).ravel():
        value = ((value ^ int(byte)) * 0x100000001B3) & ((1 << 64) - 1)
    return f"{value:016x}"


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_resize_matches_shared_complete_rgb_goldens_and_literal_samples(case):
    from pontinho_cards.model_resize import resize_rgb

    source = source_image(case)
    before = source.tobytes()
    resized = resize_rgb(source, tuple(case["targetSize"]))
    assert fingerprint(resized) == case["expectedFnv1a64Rgb"]
    for sample in case["samples"]:
        assert resized[sample["y"], sample["x"]].tolist() == sample["rgb"]
    if "expectedRgb" in case:
        assert resized.ravel().tolist() == case["expectedRgb"]
    assert source.tobytes() == before


def test_transparent_source_is_rejected_even_outside_sample_locations():
    from pontinho_cards.model_resize import resize_rgb

    image = Image.new("RGBA", (20, 20), (255, 255, 255, 255))
    image.putpixel((0, 0), (255, 0, 0, 0))
    with pytest.raises(ValueError, match="opaque"):
        resize_rgb(image, (1, 1))


@pytest.mark.parametrize(
    "case",
    [c for c in CASES if c["id"].startswith(("detector", "classifier"))],
    ids=lambda c: c["id"],
)
def test_real_evaluation_preprocessing_delivers_shared_golden_to_interpreter(
    tmp_path, monkeypatch, case
):
    kind = case["id"].split("-")[0]
    path = tmp_path / "source.png"
    source_image(case).save(path)
    inputs = []

    class Interpreter:
        def __init__(self, **kwargs):
            pass

        def allocate_tensors(self):
            pass

        def get_input_details(self):
            return [
                {
                    "index": 0,
                    "dtype": np.uint8,
                    "shape": [1, case["targetSize"][1], case["targetSize"][0], 3],
                    "quantization": (1 / 255, 0),
                }
            ]

        def get_output_details(self):
            if kind == "classifier":
                return [{"index": 1, "dtype": np.float32}]
            return [
                {"name": name, "index": i + 1}
                for i, name in enumerate(("boxes", "scores", "classes", "count"))
            ]

        def set_tensor(self, index, pixels):
            inputs.append(pixels.copy())

        def invoke(self):
            pass

        def get_tensor(self, index):
            if kind == "classifier":
                return np.eye(1, 52, dtype=np.float32)
            return {
                1: np.zeros((1, 1, 4)),
                2: np.zeros((1, 1)),
                3: np.zeros((1, 1)),
                4: np.array([0]),
            }[index]

    # Replace only the expensive native interpreter; file decode, resize and
    # normalization/quantization are production paths under test.
    monkeypatch.setitem(
        sys.modules, "tensorflow", SimpleNamespace(lite=SimpleNamespace(Interpreter=Interpreter))
    )
    record = SimpleNamespace(path=path, image_id="photo")
    if kind == "classifier":
        from pontinho_cards.train_classifier import _infer_classifier

        _infer_classifier(tmp_path / "model", [record])
    else:
        from pontinho_cards.train_detector import _infer_detector

        contract = {
            "input": {"dtype": "uint8", "shape": [1, 320, 320, 3]},
            "quantization": {"input": {"scales": [1 / 255], "zeroPoints": [0]}},
            "normalization": {"mean": [0.0] * 3, "std": [255.0] * 3},
            "outputs": [
                {"semantic": name, "tensorName": name}
                for name in ("boxes", "scores", "classes", "count")
            ],
        }
        _infer_detector(tmp_path / "model", contract, [record])
    assert len(inputs) == 1
    assert inputs[0].shape == (1, case["targetSize"][1], case["targetSize"][0], 3)
    assert fingerprint(inputs[0]) == case["expectedFnv1a64Rgb"]
