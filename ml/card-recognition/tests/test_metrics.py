from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

import pontinho_cards.evaluate as evaluate_module
from pontinho_cards.evaluate import (
    Detection,
    EvaluationError,
    _evaluation_claim,
    _holdout_identity,
    _infer_scenes,
    _publish_report_pair,
    _read_regular_bytes,
    _validate_holdout_record,
    _validate_partition_disjointness,
    _write_exclusive,
    evaluate_scenes,
    main,
    model_box_to_image_box,
    normalize_rgb_input,
    pontinho_total,
    postprocess_detections,
    prepare_model_input,
)
from pontinho_cards.split import write_split_manifests


def test_shared_android_equal_confidence_nms_fixtures() -> None:
    with (Path(__file__).parent / "fixtures/android-release/nms-ties.csv").open() as handle:
        rows = list(csv.DictReader(handle))
    for case in dict.fromkeys(row["case"] for row in rows):
        group = [row for row in rows if row["case"] == case]

        def detection(row):
            return Detection(row["label"], tuple(float(row[key]) for key in
                ("left", "top", "right", "bottom")), float(row["confidence"]))
        expected = detection(next(row for row in group if row["keep"] == "true"))
        for order in (group, list(reversed(group))):
            inputs = list(map(detection, order))
            result = postprocess_detections(inputs, confidence_threshold=.5, iou_threshold=.5)
            assert result.detections == (expected,), case
            if case == "exact_tie":
                assert result.detections[0] is inputs[0]



def _d(label: str, box: tuple[float, float, float, float], score: float = 0.9) -> Detection:
    return Detection(label=label, box=box, confidence=score)


def _scene(
    truth: list[Detection],
    predicted: list[Detection],
    *,
    scene_id: str = "scene",
) -> dict[str, object]:
    return {
        "sceneId": scene_id,
        "deckGroupId": "deck-a",
        "lighting": "daylight",
        "truth": truth,
        "predictions": predicted,
    }


def test_real_split_producer_and_evaluator_share_one_frozen_schema(tmp_path: Path) -> None:
    split_path = tmp_path / "splits.json"
    write_split_manifests([], out=split_path, seed=17)
    holdout = json.loads((tmp_path / "final-holdout.json").read_text(encoding="utf-8"))

    assert holdout["splitManifestSha256"] == hashlib.sha256(split_path.read_bytes()).hexdigest()
    assert (
        holdout["test"] == json.loads(split_path.read_text(encoding="utf-8"))["partitions"]["test"]
    )
    model = tmp_path / "card_detector.tflite"
    model.write_bytes(b"candidate")
    (tmp_path / "model-contract.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "modelSha256": hashlib.sha256(model.read_bytes()).hexdigest(),
                "splitManifestSha256": holdout["splitManifestSha256"],
                "scoreThreshold": 0.5,
                "iouThreshold": 0.5,
                "thresholdProvenance": "configured-not-validation-selected",
                "labels": [
                    f"{r}{s}"
                    for s in "CDHS"
                    for r in ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
                ],
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(EvaluationError, match="at least 200 real photos"):
        main(
            [
                "--model",
                str(model),
                "--test-manifest",
                str(tmp_path / "final-holdout.json"),
                "--report",
                str(tmp_path / "validation.json"),
            ]
        )


def test_directly_chosen_joker_matches_android_effective_scoring() -> None:
    # 6H/6D are the directly selected red jokers. Ace is 15, faces are 10.
    assert pontinho_total(["6H", "6D", "AC", "10S", "KC"], "6", "RED") == 75
    assert pontinho_total(["6C", "6S"], "6", "RED") == 12


def test_inference_uses_exported_normalization_and_image_geometry() -> None:
    pixels = np.array([[[127, 255, 0]]], dtype=np.uint8)

    normalized = normalize_rgb_input(
        pixels, {"mean": [127.0, 127.0, 127.0], "std": [128.0, 128.0, 128.0]}
    )

    assert normalized.tolist() == [[[0.0, 1.0, -0.9921875]]]
    assert model_box_to_image_box((0.25, 0.1, 0.75, 0.9), 200, 100) == (
        20.0,
        25.0,
        180.0,
        75.0,
    )


def test_distinct_repeated_cards_survive_nms_but_true_overlap_is_suppressed() -> None:
    detections = [
        _d("5H", (0, 0, 10, 10), 0.98),
        _d("5H", (20, 0, 30, 10), 0.96),
        _d("5H", (20.2, 0, 30.2, 10), 0.70),
    ]

    result = postprocess_detections(detections, confidence_threshold=0.5, iou_threshold=0.5)

    assert [d.box for d in result.detections] == [(0, 0, 10, 10), (20, 0, 30, 10)]
    assert result.abstained is False


def test_hand_calculated_scene_metrics_expose_cancelling_and_count_errors() -> None:
    box_a, box_b = (0, 0, 10, 10), (20, 0, 30, 10)
    scenes = [
        _scene(
            [_d("5H", box_a), _d("5H", box_b)],
            [_d("5H", box_a), _d("5H", box_b)],
            scene_id="repeat",
        ),
        _scene([_d("AC", box_a), _d("9S", box_b)], [_d("AC", box_a)], scene_id="miss"),
        _scene([_d("4C", box_a)], [_d("4C", box_a), _d("4C", box_b)], scene_id="duplicate"),
        # Wrong suit, same rank: the total happens to match but the multiset must not.
        _scene([_d("9H", box_a)], [_d("9D", box_a)], scene_id="wrong-suit"),
        # The selected joker is red 6; predicting black 6 changes 20 to 6.
        _scene([_d("6H", box_a)], [_d("6S", box_a)], scene_id="wrong-joker"),
    ]

    report = evaluate_scenes(
        scenes, confidence_threshold=0.5, iou_threshold=0.5, seed=7, bootstrap_samples=200
    )

    assert report["sceneCount"] == 5
    assert report["exactCardMultisetAccuracy"] == pytest.approx(0.2)
    assert report["exactCardCountAccuracy"] == pytest.approx(0.6)
    assert report["exactHandTotalAccuracy"] == pytest.approx(76 / 130)
    assert report["exactHandTotalSceneAccuracy"] == pytest.approx(0.4)
    assert report["exactHandTotalContextCount"] == 130
    assert report["perJokerContext"]["6_RED"]["exactHandTotalAccuracy"] == pytest.approx(0.4)
    assert report["falsePositives"] == 3
    assert report["falseNegatives"] == 3
    assert report["perClass"]["5H"] == {"precision": 1.0, "recall": 1.0, "support": 2}
    assert report["slices"]["cardCount"]["1"]["sceneCount"] == 3
    assert (
        report["confidenceIntervals"]["exactHandTotalAccuracy"]
        == evaluate_scenes(
            scenes, confidence_threshold=0.5, iou_threshold=0.5, seed=7, bootstrap_samples=200
        )["confidenceIntervals"]["exactHandTotalAccuracy"]
    )


def test_low_confidence_causes_abstention_instead_of_inventing_a_hand() -> None:
    box = (0, 0, 10, 10)
    report = evaluate_scenes(
        [_scene([_d("AC", box)], [_d("AC", box, 0.49)], scene_id="uncertain")],
        confidence_threshold=0.5,
        iou_threshold=0.5,
        seed=1,
        bootstrap_samples=20,
    )

    assert report["abstentionRate"] == 1.0
    assert report["exactHandTotalAccuracy"] == 0.0
    assert report["scenes"][0]["status"] == "ABSTAINED"
    assert report["scenes"][0]["flags"] == ["low_confidence_detection"]


def test_zero_detector_output_abstains_instead_of_claiming_an_empty_hand() -> None:
    box = (0, 0, 10, 10)

    report = evaluate_scenes(
        [_scene([_d("AC", box)], [], scene_id="empty-output")],
        confidence_threshold=0.5,
        iou_threshold=0.5,
        seed=1,
        bootstrap_samples=20,
    )

    assert report["abstentionRate"] == 1.0
    assert report["scenes"][0]["status"] == "ABSTAINED"
    assert report["scenes"][0]["flags"] == ["no_confident_cards"]


def test_release_holdout_scene_requires_nonempty_ground_truth() -> None:
    record = _records(1)[0]
    record["boxes"] = []

    with pytest.raises(EvaluationError, match="nonempty ground-truth"):
        _validate_holdout_record(record, set())


def test_semantically_identical_reformatted_holdout_has_same_receipt_identity(
    tmp_path: Path,
) -> None:
    first = {
        "schemaVersion": 1,
        "splitManifestSha256": "1" * 64,
        "testPartitionSha256": "2" * 64,
        "test": [{"imageId": "one"}],
    }
    second = json.loads(json.dumps(first, indent=4))

    first_identity = _holdout_identity(first)
    second_identity = _holdout_identity(second)
    assert first_identity == second_identity

    holdout = tmp_path / "final-holdout.json"
    holdout.write_text(json.dumps(first), encoding="utf-8")
    with _evaluation_claim(holdout, "3" * 64, first_identity, tmp_path / "first.json") as receipt:
        _write_exclusive(receipt, b"{}\n")
    holdout.write_text(json.dumps(second, indent=4), encoding="utf-8")
    with pytest.raises(EvaluationError, match="already evaluated"):
        with _evaluation_claim(holdout, "3" * 64, second_identity, tmp_path / "second.json"):
            pass


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize(
    ("dtype", "scale", "zero_point", "expected"),
    [
        (np.float32, None, None, [[[0.0, 1.0, -0.9921875]]]),
        (np.uint8, 0.5, 128, [[[128, 130, 126]]]),
        (np.int8, 0.5, 0, [[[0, 2, -2]]]),
    ],
)
def test_runtime_preprocessing_normalizes_then_quantizes_exact_contract(
    dtype: object, scale: float | None, zero_point: int | None, expected: list[object]
) -> None:
    pixels = np.array([[[127, 255, 0]]], dtype=np.uint8)
    detail = {"shape": np.array([1, 1, 1, 3]), "dtype": dtype}
    contract = {
        "input": {"shape": [1, 1, 1, 3], "dtype": np.dtype(dtype).name},
        "normalization": {"mean": [127.0] * 3, "std": [128.0] * 3},
        "quantization": {
            "input": None
            if scale is None
            else {"scales": [scale], "zeroPoints": [zero_point], "quantizedDimension": 0}
        },
    }

    assert prepare_model_input(pixels, detail, contract).tolist() == expected


@pytest.mark.parametrize(
    ("dtype", "scale", "zero_point", "expected_pixel"),
    [(np.float32, None, None, 0.0), (np.uint8, 0.5, 128, 128), (np.int8, 0.5, 0, 0)],
)
def test_mocked_interpreter_receives_contract_preprocessed_tensor(
    tmp_path: Path,
    dtype: object,
    scale: float | None,
    zero_point: int | None,
    expected_pixel: float,
) -> None:
    image_path = tmp_path / "image.png"
    Image.new("RGB", (2, 2), (127, 127, 127)).save(image_path)
    record = {
        "imageId": "image-1",
        "path": "image.png",
        "width": 2,
        "height": 2,
        "imageSha256": hashlib.sha256(image_path.read_bytes()).hexdigest(),
        "deckGroupId": "deck-a",
        "lighting": "daylight",
        "boxes": [{"label": "AC", "xmin": 0, "ymin": 0, "xmax": 1, "ymax": 1}],
    }

    class FakeInterpreter:
        received: np.ndarray | None = None

        def __init__(self, **_kwargs: object) -> None:
            pass

        def allocate_tensors(self) -> None:
            pass

        def get_input_details(self) -> list[dict[str, object]]:
            return [{"index": 0, "shape": np.array([1, 2, 2, 3]), "dtype": dtype}]

        def get_output_details(self) -> list[dict[str, object]]:
            return [
                {"index": 1, "name": "boxes"},
                {"index": 2, "name": "classes"},
                {"index": 3, "name": "scores"},
                {"index": 4, "name": "count"},
            ]

        def set_tensor(self, _index: int, value: np.ndarray) -> None:
            FakeInterpreter.received = value

        def invoke(self) -> None:
            pass

        def get_tensor(self, index: int) -> np.ndarray:
            return {
                1: np.array([[[0.0, 0.0, 0.5, 0.5]]]),
                2: np.array([[0.0]]),
                3: np.array([[0.9]]),
                4: np.array([[1.0]]),
            }[index]

    contract = {
        "input": {"shape": [1, 2, 2, 3], "dtype": np.dtype(dtype).name},
        "normalization": {"mean": [127.0] * 3, "std": [128.0] * 3},
        "quantization": {
            "input": None
            if scale is None
            else {"scales": [scale], "zeroPoints": [zero_point], "quantizedDimension": 0}
        },
        "outputs": [
            {"semantic": semantic, "tensorName": semantic}
            for semantic in ("boxes", "classes", "scores", "count")
        ],
    }

    scenes = _infer_scenes(
        tmp_path / "model.tflite",
        contract,
        {"test": [record]},
        tmp_path / "final-holdout.json",
        interpreter_factory=FakeInterpreter,
    )

    assert scenes[0]["predictions"][0].label == "AC"
    assert FakeInterpreter.received is not None
    assert FakeInterpreter.received.dtype == np.dtype(dtype)
    assert FakeInterpreter.received[0, 0, 0, 0] == expected_pixel


def test_failed_claim_is_recoverable_but_durable_receipt_blocks_reuse(tmp_path: Path) -> None:
    holdout = tmp_path / "final-holdout.json"
    holdout.write_text("{}", encoding="utf-8")
    report = tmp_path / "validation.json"

    with _evaluation_claim(holdout, "1" * 64, "2" * 64, report):
        pass
    with _evaluation_claim(holdout, "1" * 64, "2" * 64, report) as receipt:
        _write_exclusive(receipt, b"{}\n")
    with pytest.raises(EvaluationError, match="already evaluated"):
        with _evaluation_claim(holdout, "1" * 64, "2" * 64, report):
            pass


def test_report_transaction_recovers_one_published_half_without_clobber(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    report = tmp_path / "validation.json"
    original_replace = Path.replace
    calls = 0

    def fail_second(source: Path, target: Path) -> Path:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("simulated crash")
        return original_replace(source, target)

    monkeypatch.setattr(Path, "replace", fail_second)
    with pytest.raises(OSError, match="simulated crash"):
        _publish_report_pair(report, b'{"ok":true}\n', b"# ok\n")
    monkeypatch.setattr(Path, "replace", original_replace)

    marker = _publish_report_pair(report, b'{"ok":true}\n', b"# ok\n")

    assert report.read_bytes() == b'{"ok":true}\n'
    assert report.with_suffix(".md").read_bytes() == b"# ok\n"
    assert marker.exists()


def test_report_requires_exact_json_suffix_and_distinct_markdown_path(tmp_path: Path) -> None:
    for invalid in (tmp_path / "report.md", tmp_path / "report.JSON", tmp_path / ".json"):
        with pytest.raises(EvaluationError, match=r"\.json"):
            _publish_report_pair(invalid, b"{}\n", b"# report\n")


def test_secure_snapshot_rejects_symlinked_input_and_parent(tmp_path: Path) -> None:
    real = tmp_path / "real.bin"
    real.write_bytes(b"real")
    link = tmp_path / "link.bin"
    link.symlink_to(real)
    with pytest.raises(EvaluationError, match="symbolic links"):
        _read_regular_bytes(link)

    real_dir = tmp_path / "real-dir"
    real_dir.mkdir()
    (real_dir / "inside.bin").write_bytes(b"inside")
    linked_dir = tmp_path / "linked-dir"
    linked_dir.symlink_to(real_dir, target_is_directory=True)
    with pytest.raises(EvaluationError, match="symbolic links"):
        _read_regular_bytes(linked_dir / "inside.bin")


@pytest.mark.skipif(
    evaluate_module.os.name != "posix", reason="openat/O_NOFOLLOW is a POSIX release capability"
)
def test_secure_snapshot_rejects_parent_swapped_after_root_open(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "root"
    images = root / "images"
    images.mkdir(parents=True)
    (images / "card.bin").write_bytes(b"trusted")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "card.bin").write_bytes(b"swapped")
    original_open = evaluate_module.os.open
    swapped = False

    def swap_parent(
        path: object, flags: int, mode: int = 0o777, *, dir_fd: int | None = None
    ) -> int:
        nonlocal swapped
        if path == "images" and dir_fd is not None and not swapped:
            swapped = True
            images.rename(root / "original-images")
            images.symlink_to(outside, target_is_directory=True)
        if dir_fd is None:
            return original_open(path, flags, mode)
        return original_open(path, flags, mode, dir_fd=dir_fd)

    monkeypatch.setattr(evaluate_module.os, "open", swap_parent)

    with pytest.raises(EvaluationError, match="securely read"):
        _read_regular_bytes(images / "card.bin", root=root)


@pytest.mark.parametrize(
    "field", ["imageSha256", "sourceDerivativeId", "deckGroupId", "splitGroupId"]
)
def test_evaluator_rechecks_cross_partition_leakage(field: str) -> None:
    train, test = _records(2)
    test["imageId"] = "test-image"
    test[field] = train[field]

    with pytest.raises(EvaluationError, match=field):
        _validate_partition_disjointness({"train": [train], "validation": [], "test": [test]})


def _records(count: int) -> list[dict[str, object]]:
    return [
        {
            "imageId": f"image-{index}",
            "path": f"images/{index}.jpg",
            "width": 100,
            "height": 80,
            "sourceId": "source-a",
            "imageSha256": f"{index:064x}",
            "sourceDerivativeId": f"derivative-{index}",
            "deckGroupId": f"deck-{index % 10}",
            "splitGroupId": f"session-{index}",
            "augmentationParentId": None,
            "isRealPhoto": True,
            "lighting": "daylight",
            "boxes": [{"label": "AC", "xmin": 1, "ymin": 1, "xmax": 10, "ymax": 10}],
        }
        for index in range(count)
    ]


def _write_evaluation_inputs(
    tmp_path: Path, test_records: list[dict[str, object]]
) -> tuple[Path, Path]:
    split = {
        "schemaVersion": 1,
        "seed": 20261014,
        "partitions": {"test": test_records, "train": [], "validation": []},
    }
    split_path = tmp_path / "splits.json"
    split_payload = (json.dumps(split, indent=2, sort_keys=True) + "\n").encode()
    split_path.write_bytes(split_payload)
    test_payload = (json.dumps(test_records, indent=2, sort_keys=True) + "\n").encode()
    holdout = {
        "schemaVersion": 1,
        "kind": "pontinho-frozen-holdout",
        "seed": 20261014,
        "splitManifest": "splits.json",
        "splitManifestSha256": hashlib.sha256(split_payload).hexdigest(),
        "testPartitionSha256": hashlib.sha256(test_payload).hexdigest(),
        "test": test_records,
    }
    holdout_path = tmp_path / "final-holdout.json"
    holdout_path.write_text(json.dumps(holdout), encoding="utf-8")
    model = tmp_path / "card_detector.tflite"
    model.write_bytes(b"candidate")
    (tmp_path / "model-contract.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "modelSha256": _sha(model),
                "splitManifestSha256": hashlib.sha256(split_payload).hexdigest(),
                "scoreThreshold": 0.5,
                "iouThreshold": 0.5,
                "thresholdProvenance": "configured-not-validation-selected",
                "labels": [
                    f"{r}{s}"
                    for s in "CDHS"
                    for r in ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
                ],
            }
        ),
        encoding="utf-8",
    )
    return model, holdout_path


def test_cli_fails_closed_without_real_frozen_release_holdout_and_writes_nothing(
    tmp_path: Path,
) -> None:
    model, manifest = _write_evaluation_inputs(tmp_path, [])
    report = tmp_path / "validation.json"

    with pytest.raises(EvaluationError, match="at least 200 real photos"):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])

    assert not report.exists()


def test_holdout_requires_200_distinct_real_photo_hashes(tmp_path: Path) -> None:
    records = _records(200)
    records[1]["imageSha256"] = records[0]["imageSha256"]
    model, manifest = _write_evaluation_inputs(tmp_path, records)
    report = tmp_path / "validation.json"

    with pytest.raises(EvaluationError, match="200 distinct real photos"):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])

    assert not report.exists()


def test_release_candidate_evaluation_requires_bound_threshold_and_source_evidence(
    tmp_path: Path,
) -> None:
    model, manifest = _write_evaluation_inputs(tmp_path, _records(200))
    contract_path = model.with_name("model-contract.json")
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    contract["thresholdProvenance"] = "validation-selected"
    contract_path.write_text(json.dumps(contract), encoding="utf-8")
    report = tmp_path / "validation.json"

    with pytest.raises(EvaluationError, match="threshold evidence"):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])

    contract["thresholdSelectionSha256"] = "a" * 64
    contract_path.write_text(json.dumps(contract), encoding="utf-8")
    with pytest.raises(EvaluationError, match="source manifest"):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])

    assert not report.exists()


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda document: document.update(kind="mutable"), "frozen"),
        (lambda document: document.update(splitManifestSha256="0" * 64), "split manifest hash"),
        (lambda document: document["test"][0].update(isRealPhoto=False), "real photos"),
    ],
)
def test_cli_rejects_untrusted_holdout_state_before_inference(
    tmp_path: Path, mutation: object, message: str
) -> None:
    model, manifest = _write_evaluation_inputs(tmp_path, _records(200))
    document = json.loads(manifest.read_text(encoding="utf-8"))
    mutation(document)  # type: ignore[operator]
    manifest.write_text(json.dumps(document), encoding="utf-8")
    report = tmp_path / "validation.json"

    with pytest.raises(EvaluationError, match=message):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])

    assert not report.exists()


def test_cli_rejects_model_contract_hash_mismatch_and_existing_report(tmp_path: Path) -> None:
    model = tmp_path / "card_detector.tflite"
    model.write_bytes(b"candidate")
    (tmp_path / "model-contract.json").write_text(
        json.dumps(
            {"modelSha256": "0" * 64, "scoreThreshold": 0.5, "iouThreshold": 0.5, "labels": []}
        ),
        encoding="utf-8",
    )
    manifest = tmp_path / "final-holdout.json"
    manifest.write_text("{}", encoding="utf-8")
    report = tmp_path / "validation.json"

    with pytest.raises(EvaluationError, match="model hash"):
        main(["--model", str(model), "--test-manifest", str(manifest), "--report", str(report)])
    report.write_text("do not replace", encoding="utf-8")
    with pytest.raises(EvaluationError, match="already exists"):
        _publish_report_pair(report, b"replacement", b"replacement")
    assert report.read_text(encoding="utf-8") == "do not replace"
