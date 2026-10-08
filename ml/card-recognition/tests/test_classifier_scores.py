"""Quantized softmax rounding must not be mistaken for invalid probability mass."""

import importlib

import numpy as np
import pytest


def normalize(raw, **kwargs):
    assert importlib.util.find_spec("pontinho_cards.classifier_scores"), "score boundary absent"
    return importlib.import_module("pontinho_cards.classifier_scores").normalize_classifier_output(
        raw, **kwargs
    )


@pytest.mark.parametrize(
    "high_count,low,total", [(8, 5, 268), (23, 4, 231), (25, 4, 233), (35, 4, 243)]
)
@pytest.mark.parametrize("dtype,zero", [(np.uint8, 0), (np.int8, -128)])
def test_rounding_52_valid_probabilities_can_change_sum_beyond_old_tolerance(
    high_count, low, total, dtype, zero
):
    codes = np.asarray([low + 1] * high_count + [low] * (52 - high_count))
    # These are valid normalized probabilities whose independent nearest-code
    # rounding gives exactly the four sums observed by review. Each correction
    # is below half a code: -12/52, 25/52, 23/52, or 13/52.
    probabilities = (codes + (256 - total) / 52) / 256
    assert probabilities.sum() == pytest.approx(1)
    assert np.all(probabilities > 0)
    np.testing.assert_array_equal(np.rint(probabilities * 256), codes)
    raw = (codes + zero).astype(dtype)[None, :]
    assert codes.sum() == total
    assert abs(total / 256 - 1) > 0.025
    result = normalize(raw, quantization=(1 / 256, zero))
    np.testing.assert_allclose(result, [codes / total], rtol=0, atol=1e-15)
    assert result.sum() == pytest.approx(1)
    np.testing.assert_array_equal(result.argmax(axis=1), raw.argmax(axis=1))


@pytest.mark.parametrize(
    "scale,total", [(1 / 256, 230), (1 / 256, 282), (1 / 128, 102), (1 / 128, 154)]
)
def test_quantization_mass_bound_depends_on_scale_and_includes_half_step_boundary(scale, total):
    # Sum error exactly 52 * scale / 2 is realizable by ties at every coordinate.
    low, high_count = divmod(total, 52)
    codes = np.asarray([low + 1] * high_count + [low] * (52 - high_count), dtype=np.uint8)
    result = normalize(codes[None, :], quantization=(scale, 0))
    assert result.sum() == pytest.approx(1)
    # One code beyond the derived bound is not explainable by nearest rounding.
    beyond = codes.copy()
    beyond[0] += 1 if total * scale > 1 else -1
    with pytest.raises(ValueError):
        normalize(beyond[None, :], quantization=(scale, 0))


@pytest.mark.parametrize(
    "raw,quantization",
    [
        (np.zeros((1, 52), dtype=np.uint8), (1 / 256, 0)),
        (np.ones((1, 52), dtype=np.uint8), (1 / 256, 0)),
        (np.full((1, 52), 255, dtype=np.uint8), (1 / 256, 0)),
        (np.zeros((1, 51), dtype=np.uint8), (1 / 256, 0)),
        (np.zeros((0, 52), dtype=np.uint8), (1 / 256, 0)),
        (np.full((1, 52), -128, dtype=np.int8), (1 / 256, 0)),
        (np.ones((1, 52), dtype=np.uint8), (float("nan"), 0)),
        (np.ones((1, 52), dtype=np.uint8), (0, 0)),
        (np.ones((1, 52), dtype=np.uint8), (-1 / 256, 0)),
        (np.ones((1, 52), dtype=np.uint8), (1 / 256, -128)),
        (np.full((1, 52), np.nan), None),
        (np.full((1, 52), np.inf), None),
        (np.full((1, 52), -1 / 52), None),
        (np.full((1, 52), 0.99 / 52), None),
        (np.full((1, 52), 1.01 / 52), None),
    ],
)
def test_invalid_classifier_outputs_are_not_rescued_by_normalization(raw, quantization):
    with pytest.raises(ValueError):
        normalize(raw, quantization=quantization)


def test_float32_softmax_roundoff_is_accepted_without_integer_rounding_allowance():
    result = normalize(np.full((1, 52), np.float32(1 / 52)))
    np.testing.assert_allclose(result, np.full((1, 52), 1 / 52), rtol=0, atol=1e-15)


def test_strict_calibration_boundary_does_not_normalize_unexplained_probability_mass():
    from pontinho_cards.train_classifier import _probabilities

    with pytest.raises(ValueError):
        _probabilities(np.full((1, 52), 0.99 / 52), 1)


@pytest.fixture(scope="module")
def real_quantized_softmax(tmp_path_factory):
    """An actual 52-way softmax whose low confidences round down independently."""
    import tensorflow as tf
    from PIL import Image

    from pontinho_cards.train_classifier import _export_classifier
    from pontinho_cards.two_stage_data import ClassifierCrop

    root = tmp_path_factory.mktemp("quantized-softmax-regression")
    records = []
    for color in ("black", "white"):
        path = root / f"{color}.png"
        Image.new("RGB", (224, 224), color).save(path)
        records.append(ClassifierCrop("AC", (0, 0, 224, 224), color, "0" * 64, path))
    codes = np.asarray([5] * 23 + [4] * 29)
    probabilities = (codes + 25 / 52) / 256
    model = tf.keras.Sequential(
        [
            tf.keras.layers.InputLayer(input_shape=(224, 224, 3)),
            tf.keras.layers.GlobalAveragePooling2D(),
            tf.keras.layers.Dense(52, activation="softmax"),
        ]
    )
    # A common input-dependent logit shift leaves these probabilities invariant.
    model.layers[-1].set_weights(
        [np.full((3, 52), 0.01, dtype=np.float32), np.log(probabilities).astype(np.float32)]
    )
    filename = _export_classifier(model, records, root, "int8")
    path = root / filename
    interpreter = tf.lite.Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    source, target = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    interpreter.set_tensor(source["index"], np.zeros(source["shape"], dtype=source["dtype"]))
    interpreter.invoke()
    scale, zero = target["quantization"]
    raw_probabilities = (interpreter.get_tensor(target["index"]).astype(np.float64) - zero) * scale
    assert raw_probabilities.sum() == pytest.approx(0.90234375)
    return path, records


def test_runtime_normalizes_real_quantized_softmax_before_strict_calibration(
    real_quantized_softmax,
):
    from pontinho_cards.train_classifier import _infer_classifier, _probabilities

    path, records = real_quantized_softmax
    scores = _infer_classifier(path, records)
    np.testing.assert_allclose(scores.sum(axis=1), [1.0, 1.0], rtol=0, atol=1e-15)
    np.testing.assert_allclose(_probabilities(scores, 2), scores, rtol=0, atol=1e-15)


def test_export_inspection_accepts_real_quantized_softmax_rounding(real_quantized_softmax):
    from pontinho_cards.export import inspect_classifier_tflite

    inspection = inspect_classifier_tflite(real_quantized_softmax[0], expected_quantization="int8")
    assert inspection["interpreterInvocation"] == "passed"
