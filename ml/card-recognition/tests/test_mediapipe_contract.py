"""Runtime input parity checks; synthetic tensors are never release-quality evidence."""

import numpy as np
import pytest

from pontinho_cards.evaluate import prepare_model_input
from pontinho_cards.mediapipe_contract import require_mediapipe_input


def contract(dtype="uint8", scale=1 / 255, zero=0):
    return {
        "input": {"dtype": dtype, "shape": [1, 1, 256, 3]},
        "outputs": [{"dtype": "uint8"}],
        "normalization": {"mean": [0.0] * 3, "std": [255.0] * 3},
        "quantization": {"input": {"scales": [scale], "zeroPoints": [zero]}},
    }


@pytest.mark.parametrize("scale,zero", [(0.5, 128), (1 / 128, 0), (1 / 255, 1)])
def test_nonidentity_quantized_tensor_cannot_enter_mediapipe_release(scale, zero):
    model = contract(scale=scale, zero=zero)
    pixels = np.repeat(np.arange(256, dtype=np.uint8)[None, :, None], 3, axis=2)
    prepared = prepare_model_input(pixels, {"shape": [1, 1, 256, 3], "dtype": np.uint8}, model)
    assert not np.array_equal(prepared, pixels)  # Actual Python inference preprocessing.
    with pytest.raises(ValueError, match="MediaPipe"):
        require_mediapipe_input(model, "detector")


def test_uint8_classifier_export_contract_can_be_nonunit_but_pixel_equivalent():
    model = contract(scale=float(np.float32(1 / 255)))
    pixels = np.repeat(np.arange(256, dtype=np.uint8)[None, :, None], 3, axis=2)
    prepared = prepare_model_input(pixels, {"shape": [1, 1, 256, 3], "dtype": np.uint8}, model)
    np.testing.assert_array_equal(prepared, pixels)
    require_mediapipe_input(model, "detector")
    model["input"]["quantization"] = [float(np.float32(1 / 255)), 0]
    require_mediapipe_input(model, "classifier")


def test_model_maker_observed_uint8_contract_is_equivalent_with_nonzero_zero_point():
    # Tensor parameters observed in the actual rf100 candidate export. No accuracy claim.
    model = contract(scale=1 / 128, zero=127)
    model["normalization"] = {"mean": [127.0] * 3, "std": [128.0] * 3}
    pixels = np.repeat(np.arange(256, dtype=np.uint8)[None, :, None], 3, axis=2)
    prepared = prepare_model_input(pixels, {"shape": [1, 1, 256, 3], "dtype": np.uint8}, model)
    np.testing.assert_array_equal(prepared, pixels)
    require_mediapipe_input(model, "detector")


def test_int8_and_per_channel_float_normalization_are_not_native_image_contracts():
    with pytest.raises(ValueError, match="MediaPipe"):
        require_mediapipe_input(contract(dtype="int8", zero=-128), "detector")
    model = contract(dtype="float32")
    model["quantization"]["input"] = None
    require_mediapipe_input(model, "detector")
    model["normalization"]["mean"] = [0, 1, 0]
    with pytest.raises(ValueError, match="MediaPipe"):
        require_mediapipe_input(model, "detector")
