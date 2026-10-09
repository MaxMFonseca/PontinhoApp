"""Measure real one-deck candidates and publish only an explicitly experimental debug bundle."""

import argparse
import io
import json
import tempfile
from dataclasses import asdict
from itertools import product
from pathlib import Path

import numpy as np
from PIL import Image

from pontinho_cards.classifier_scores import normalize_classifier_output
from pontinho_cards.evaluate import Detection
from pontinho_cards.evaluate_two_stage import (
    Thresholds,
    canonical,
    digest,
    measure_two_stage,
    recognize_scene,
    snapshot_candidate,
    snapshot_scenes,
)
from pontinho_cards.experimental_two_stage import EVIDENCE_MODE, load_experimental_inputs
from pontinho_cards.labels import CARD_LABELS
from pontinho_cards.mediapipe_contract import NATIVE_BOX_POLICY, mediapipe_compatible
from pontinho_cards.model_resize import RESIZE_POLICY, resize_rgb
from pontinho_cards.native_nms import NMS_POLICY
from pontinho_cards.secure_io import (
    secure_read_bytes,
    secure_write_bytes,
    secure_write_bytes_immutable,
)
from pontinho_cards.train_classifier import (
    ClassifierCandidate,
    _infer_classifier,
    apply_temperature,
    measure_classifier_predictions,
)
from pontinho_cards.train_detector import (
    DetectorCandidate,
    _infer_detector,
    _publish_candidate,
    measure_detector_predictions,
)
from pontinho_cards.two_stage_data import build_two_stage_inputs


def publish_experimental_bundle(destination, payloads):
    destination = Path(destination)
    if destination.parts[-4:] != ("app", "src", "debug", "assets"):
        raise ValueError("experimental models may only be published to app/src/debug/assets")
    expected = {"card_detector.tflite", "card_classifier.tflite", "card_recognition.release.json"}
    if set(payloads) != expected:
        raise ValueError("complete two-model experiment required")
    with tempfile.TemporaryDirectory(prefix="pontinho-experiment-") as directory:
        staging = Path(directory)
        for name, payload in payloads.items():
            secure_write_bytes(staging, name, payload)
        return _publish_candidate(
            staging,
            destination.parent,
            destination.name,
            list(payloads),
            allow_portable_fallback=True,
        )


def select_experimental_entry(entries):
    if not entries:
        raise ValueError("no compatible experimental candidates")
    return max(
        entries,
        key=lambda row: (
            row["metrics"]["exactCardMultisetAccuracy"],
            row["metrics"]["exactHandTotalAccuracy"],
            row["metrics"]["detectorRecall"],
            -row["metrics"]["detectorFalsePositiveShare"],
        ),
    )


class NativeClassifier:
    """One real TFLite interpreter, with the runtime's exact RGB and score contracts."""

    def __init__(self, path):
        import tensorflow as tf

        self.interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=1)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]
        self.cache = {}

    def __call__(self, image):
        pixels = resize_rgb(image, (224, 224))
        key = digest(pixels.tobytes())
        if key not in self.cache:
            values = pixels.astype(np.float32)[None, ...] / 255.0
            if np.issubdtype(self.input["dtype"], np.integer):
                scale, zero = self.input["quantization"]
                limits = np.iinfo(self.input["dtype"])
                values = np.clip(np.rint(values / scale + zero), limits.min, limits.max)
            self.interpreter.set_tensor(self.input["index"], values.astype(self.input["dtype"]))
            self.interpreter.invoke()
            raw = self.interpreter.get_tensor(self.output["index"])
            quantization = (
                self.output["quantization"]
                if np.issubdtype(self.output["dtype"], np.integer)
                else None
            )
            self.cache[key] = normalize_classifier_output(raw, quantization=quantization)[0]
        return self.cache[key]


def run_joint(records, raw, classify, thresholds, temperature):
    scenes = []
    for record in records:
        with Image.open(io.BytesIO(secure_read_bytes(record["snapshotPath"]))) as image:
            result = recognize_scene(
                image.convert("RGB"), raw[record["imageId"]], classify, thresholds, temperature
            )
        scenes.append(
            {
                "imageId": record["imageId"],
                "deckGroupId": record["deckGroupId"],
                "truth": tuple(
                    Detection(b["label"], tuple(b[k] for k in ("xmin", "ymin", "xmax", "ymax")))
                    for b in record["boxes"]
                ),
                **result,
            }
        )
    return measure_two_stage(scenes)


def _summary(metrics):
    return {key: value for key, value in metrics.items() if key != "scenes"}


def evaluate_experiment(detector_root, classifier_root, splits, sources, report_path, destination):
    document, inputs = load_experimental_inputs(splits, sources)
    candidates = {
        "detector": DetectorCandidate(
            detector_root / "card_detector.float16.tflite",
            detector_root / "candidate-contract.json",
            detector_root / "validation-metrics.json",
        ),
        "classifier": ClassifierCandidate(
            classifier_root / "card_classifier.float16.tflite",
            classifier_root / "candidate-contract.json",
            classifier_root / "validation-metrics.json",
        ),
    }
    with tempfile.TemporaryDirectory(prefix="pontinho-one-deck-evaluation-") as temporary:
        staging = Path(temporary)
        snapshots = {
            kind: snapshot_candidate(candidate, kind, staging / kind, evidence_mode=EVIDENCE_MODE)
            for kind, candidate in candidates.items()
        }
        for kind, snapshot in snapshots.items():
            for partition in ("train", "validation"):
                expected = {
                    r["imageSha256"]
                    for r in document["partitions"][partition]
                    if kind == "detector" or r["boxes"]
                }
                observed = {r["source_image_sha256"] for r in snapshot["inputs"][partition]}
                if observed != expected or {
                    r["deck_group_id"] for r in snapshot["inputs"][partition]
                } != {"rf100-upstream-deck"}:
                    raise ValueError("candidate provenance differs from admitted experiment")
        validation = snapshot_scenes(
            document["partitions"]["validation"], splits.parent, staging / "validation"
        )
        compatible = {
            kind: [
                variant
                for variant, contract in snapshot["contract"]["variants"].items()
                if mediapipe_compatible(contract, kind)
            ]
            for kind, snapshot in snapshots.items()
        }
        classifiers = {
            variant: NativeClassifier(snapshots["classifier"]["paths"][variant])
            for variant in compatible["classifier"]
        }
        raw = {
            variant: _infer_detector(
                snapshots["detector"]["paths"][variant],
                snapshots["detector"]["contract"]["variants"][variant],
                inputs.detector_partitions["validation"],
            )
            for variant in compatible["detector"]
        }
        sweep = []
        for dvariant, cvariant, score, combined in product(
            compatible["detector"], compatible["classifier"], (0.25, 0.4, 0.55), (0.25, 0.5)
        ):
            thresholds = Thresholds(score, combined, 0.5, 0.1)
            temperature = snapshots["classifier"]["contract"]["variants"][cvariant]["calibration"][
                "temperature"
            ]
            metrics = run_joint(
                validation, raw[dvariant], classifiers[cvariant], thresholds, temperature
            )
            entry = {
                "detectorVariant": dvariant,
                "classifierVariant": cvariant,
                "thresholds": asdict(thresholds),
                "metrics": _summary(metrics),
            }
            sweep.append(entry)
            print(
                json.dumps(
                    {
                        "validationSweep": len(sweep),
                        "detector": dvariant,
                        "classifier": cvariant,
                        "thresholds": asdict(thresholds),
                        "exactCardMultisetAccuracy": metrics["exactCardMultisetAccuracy"],
                        "detectorRecall": metrics["detectorRecall"],
                    }
                ),
                flush=True,
            )
        selected = select_experimental_entry(sweep)
        bindings = {
            kind: {
                "contractSha256": snapshot["contractSha256"],
                "modelHashes": {
                    variant: value["modelSha256"]
                    for variant, value in snapshot["contract"]["variants"].items()
                },
            }
            for kind, snapshot in snapshots.items()
        }
        selection = {
            "kind": EVIDENCE_MODE,
            "partition": "validation",
            "productionHoldout": False,
            "bindings": bindings,
            "selected": selected,
            "sweep": sweep,
        }
        selection_bytes = canonical(selection)
        # Freeze the validation choice before opening upstream test image bytes.
        secure_write_bytes_immutable(
            report_path.parent, "selection.experimental.json", selection_bytes
        )
        thresholds = Thresholds(**selected["thresholds"])
        inputs = build_two_stage_inputs(
            document, splits.parent, evidence_mode=EVIDENCE_MODE, include_experimental_test=True
        )
        test = snapshot_scenes(
            document["partitions"]["test"], splits.parent, staging / "experimental-test"
        )
        test_raw, detector_metrics, classifier_metrics = {}, {}, {}
        for variant, contract in snapshots["detector"]["contract"]["variants"].items():
            test_raw[variant] = _infer_detector(
                snapshots["detector"]["paths"][variant],
                contract,
                inputs.detector_partitions["test"],
            )
            detector_metrics[variant] = measure_detector_predictions(
                inputs.detector_partitions["test"],
                test_raw[variant],
                score_threshold=thresholds.detector_score,
                iou_threshold=0.5,
            )
        for variant, contract in snapshots["classifier"]["contract"]["variants"].items():
            scores = apply_temperature(
                _infer_classifier(
                    snapshots["classifier"]["paths"][variant], inputs.classifier_partitions["test"]
                ),
                contract["calibration"]["temperature"],
            )
            classifier_metrics[variant] = measure_classifier_predictions(
                inputs.classifier_partitions["test"], scores
            )
        dvariant, cvariant = selected["detectorVariant"], selected["classifierVariant"]
        temperature = snapshots["classifier"]["contract"]["variants"][cvariant]["calibration"][
            "temperature"
        ]
        test_joint = run_joint(
            test, test_raw[dvariant], classifiers[cvariant], thresholds, temperature
        )
        candidate_metrics = {
            kind: snapshot["metrics"] for kind, snapshot in snapshots.items()
        }
        report = {
            "schemaVersion": 1,
            "kind": EVIDENCE_MODE,
            "releaseApproved": False,
            "deckDisjoint": False,
            "productionHoldout": False,
            "visualDeckCount": 1,
            "warning": (
                "One visual deck, upstream partitions; no independent deck generalization "
                "or Android device latency evidence. Review every card."
            ),
            "sourceManifestSha256": document["sourceManifestSha256"],
            "upstreamSplitSha256": document["upstreamSplitSha256"],
            "selectionSha256": digest(selection_bytes),
            "bindings": bindings,
            "sceneCounts": {key: len(value) for key, value in document["partitions"].items()},
            "cropCounts": {key: len(value) for key, value in inputs.classifier_partitions.items()},
            "selected": selected,
            "compatibleVariants": compatible,
            "trainingAndValidation": candidate_metrics,
            "experimentalTest": {
                "detector": detector_metrics,
                "classifier": classifier_metrics,
                "joint": test_joint,
            },
            "deviceBenchmark": None,
        }
        payload = canonical(report)
        secure_write_bytes_immutable(report_path.parent, report_path.name, payload)
        models, contracts = {}, {}
        for kind, variant in (("detector", dvariant), ("classifier", cvariant)):
            declared = snapshots[kind]["contract"]["variants"][variant]
            model = secure_read_bytes(snapshots[kind]["paths"][variant])
            if digest(model) != declared["modelSha256"]:
                raise ValueError("experiment model changed after evaluation")
            models[f"card_{kind}.tflite"] = model
            contracts[kind] = {
                **declared,
                "modelSizeBytes": len(model),
                "filename": f"card_{kind}.tflite",
                "variant": variant,
            }

        def row(joint, top1):
            return {
                **{
                    k: joint[k]
                    for k in (
                        "sceneCount",
                        "detectorRecall",
                        "detectorFalsePositiveShare",
                        "exactCardMultisetAccuracy",
                        "exactHandTotalAccuracy",
                    )
                },
                "classifierTop1Accuracy": top1,
            }

        metadata = {
            "schemaVersion": 1,
            "kind": EVIDENCE_MODE,
            "releaseApproved": False,
            "labels": list(CARD_LABELS),
            "maximumCardCount": 10,
            "confidenceCombination": "min(detector,calibratedClassifier)",
            "spatialOrder": "top-left-bottom-right",
            "boxPolicy": NATIVE_BOX_POLICY,
            "resizePolicy": RESIZE_POLICY,
            "nmsPolicy": NMS_POLICY,
            "thresholds": selected["thresholds"],
            **contracts,
            "bindings": bindings,
            "sourceManifestSha256": document["sourceManifestSha256"],
            "upstreamSplitSha256": document["upstreamSplitSha256"],
            "experimentReportSha256": digest(payload),
            "evidence": {
                "trainSceneCount": len(document["partitions"]["train"]),
                "validationSceneCount": len(validation),
                "experimentalTestSceneCount": len(test),
                "visualDeckCount": 1,
                "deckDisjoint": False,
                "productionHoldout": False,
            },
            "metrics": {
                "validation": row(
                    selected["metrics"],
                    candidate_metrics["classifier"]["variants"][cvariant]["top1Accuracy"],
                ),
                "experimentalTest": row(test_joint, classifier_metrics[cvariant]["top1Accuracy"]),
            },
        }
        publish_experimental_bundle(
            destination, {**models, "card_recognition.release.json": canonical(metadata)}
        )
        print(json.dumps({"bundle": str(destination), "metrics": metadata["metrics"]}), flush=True)
        return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--detector", type=Path, required=True)
    parser.add_argument("--classifier", type=Path, required=True)
    parser.add_argument("--splits", type=Path, default=Path("datasets/normalized/splits.json"))
    parser.add_argument("--sources", type=Path, default=Path("datasets/sources.json"))
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args(argv)
    evaluate_experiment(
        args.detector, args.classifier, args.splits, args.sources, args.report, args.destination
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
