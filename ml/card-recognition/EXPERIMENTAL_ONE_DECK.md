# Experimental one-deck card recognition

This is a debug-only experiment, not a production model release.

## Evidence

- Source: admitted Poker Cards Cxcvz / RF100 partitions, CC BY 4.0.
- Visual deck designs: 1.
- Scenes: 964 train, 193 validation, 128 upstream experimental test.
- Classifier crops: 4,181 train, 838 validation, 555 experimental test.
- Classes: the 52 standard playing cards; no printed joker class.
- Selected pair: float16 detector and float16 classifier.
- Selected thresholds: detector `0.55`, combined confidence `0.25`, IoU `0.5`, crop margin `0.1`.

| Metric | Validation | Experimental test |
|---|---:|---:|
| Detector recall | 73.39% | 80.72% |
| Detector false-positive share | 0.81% | 1.10% |
| Classifier top-1 per crop | 80.79% | 81.62% |
| Exact card multiset per scene | 35.75% | 37.50% |
| Exact hand total per scene | 37.31% | 42.19% |

These partitions come from one upstream dataset and one audited visual deck.
They do not establish generalization to decorative or previously unseen deck
designs. Every recognized card must remain editable and must be reviewed by the
user. The measured result is materially below the production gate and must not
be described as 9/10, 97%, production-ready, or Play Store-ready.

## Reproduce

Use the pinned project Python environment and run from `ml/card-recognition`:

```bash
export PYTHONPATH=src
python -m pontinho_cards.experimental_two_stage detector \
  --config config/detector-experimental.yaml \
  --run-id one-deck-detector-20261015
python -m pontinho_cards.experimental_two_stage classifier \
  --config config/classifier-experimental.yaml \
  --run-id one-deck-classifier-20261015
python -m pontinho_cards.evaluate_experimental \
  --detector artifacts/detector-candidates/one-deck-detector-20261015 \
  --classifier artifacts/classifier-candidates/one-deck-classifier-20261015 \
  --splits datasets/normalized/splits.json \
  --sources datasets/sources.json \
  --report artifacts/experimental-one-deck-report.json \
  --destination ../../app/src/debug/assets
```

The evaluator freezes threshold selection before opening upstream test image
bytes, validates candidate and source hashes, writes the full ignored report to
`artifacts/experimental-one-deck-report.json`, and atomically publishes the two
models plus `card_recognition.release.json` to the debug source set. The release
source set intentionally contains no recognition assets.

## Verified packaging

- Debug APK: both real TFLite models and experimental metadata are present.
- Release APK: no detector, classifier, or recognition metadata is present.
- API 37 emulator: metadata verification, detector creation/invocation, and
  classifier creation/invocation completed through MediaPipe CPU.

