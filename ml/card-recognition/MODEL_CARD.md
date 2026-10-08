# Pontinho two-stage card recognition model card

**Release status: NOT_APPROVED / BLOCKED_TWO_STAGE_INPUTS.**

No reviewed two-stage candidate, selected thresholds, final quality metrics or
Android latency measurements exist. No two-stage Android bundle has been
published. Generated regression images and native trainer smoke tests establish
software behavior only.

## Current evidence and blockers

The [source audit](reports/dataset-audit.md) admits 1,285 Poker Cards Cxcvz scenes
and 5,574 annotations under CC BY 4.0 for experimentation, with one visual deck
design. Its 128 upstream test scenes share that design and are not an unseen-deck
holdout. The final reserve remains **0 qualifying photos / 0 unseen decks**.

The reviewed input and validation manifests, reviewed detector/classifier
candidates and physical-device benchmark are absent. The configuration-pinned
source-audit receipt is still NOT_APPROVED. Real accuracy, model hashes/sizes,
calibration quality and weak-deck performance are unmeasured. See
[two-stage evaluation status](reports/release/two-stage-evaluation-status.json),
[detector status](reports/release/detector-training-status.json), and
[classifier status](reports/release/classifier-training-status.json).

## Intended use and limitations

The optional scanner assists offline Pontinho scoring for one through ten
complete, visible, non-overlapping standard cards. A one-class detector runs at
320 × 320; a 52-class classifier receives original-resolution padded crops at
224 × 224. Physically distinct equal cards remain separate observations.
Printed jokers, stacks, overlaps and incomplete cards are unsupported. Unknown
deck artwork, glare, blur, severe perspective and shadow may require review.

Empty recognition requires retry. More than ten surviving detections requires
split capture without truncation. Every scan remains reviewable; only user
confirmation transfers points. Manual scoring remains available. The old
single-stage experimental model is not a fallback.

## Calibration and validation selection

The Python API `select_two_stage_thresholds` consumes T3/T4 candidates, a
validation manifest, explicit `Thresholds` candidates and an immutable output.
The manifest is schema 1 with `partition: "validation"` and `records` equal to
the full canonical validation partition. Candidate projections are matched by
task role, source image hashes and deck identities. Negative scenes contribute
detector false positives; exact hand accuracy uses supported 1–10-card scenes.

Both float16/int8 detector and classifier variants are compared. Each classifier
uses its own model-bound validation transform:
`softmax(log(max(raw_probability, 1e-12)) / temperature)`.
Integer output is normalized through the shared T4 scale-aware boundary first.
Combined confidence is `min(detector_score, calibrated_classifier_score)`.

The T3 adapter converts native y/x boxes to normalized x/y bounds. Joint inference
maps these to original pixels and pads by the selected margin (0–0.25 of box
width/height). Class-independent NMS suppresses IoU strictly above the threshold.
Review order is top, left, bottom, right. Incomplete boxes and low confidence
cause abstention. The deterministic sweep prioritizes the joint hard envelope,
then exact multiset/total accuracy, recall and false-positive share. It binds
both candidate contracts and exact model hashes and opens no holdout images.

## One-use final evaluation

`evaluate_two_stage` consumes that immutable selection and the existing
`pontinho-frozen-holdout` manifest. It requires 200 unique real photographs,
ten unseen decks, all 52 labels and every supported count bucket 1–10.
Candidate training, validation and frozen split identities are checked.

An exclusive durable claim consumes the photo-set identity before inference.
Changing candidates, output names, photo order or annotations cannot retry the
same photo set in the same canonical evidence directory. Native failure also
consumes it. Preserve the evidence directory; copying the dataset must never be
used to circumvent the one-use protocol.

The immutable report records detector precision/recall and FP/(TP+FP), per-card
precision/recall, exact physical multiset accuracy, abstention, and Pontinho
totals for all 26 coringa contexts. Exact-total credit requires every context to
match. Every metric is grouped by deck and card count.

## Promotion and atomic bundle

`promote_two_stage_release(ReleaseInputs(...), destination)` requires:

- recall ≥0.97, FP/(TP+FP) ≤0.01, exact multiset ≥0.90 and exact total ≥0.95,
  overall and in every supported count bucket;
- the required frozen inventory and exact candidate/selection/report/receipt
  hashes, plus recomputation from recorded one-use inference; promotion never
  repeats final holdout inference;
- source/license/archive-member evidence under the existing configuration-pinned
  human source-audit trust root;
- a model/report-bound physical representative mid-range Android benchmark,
  cold start, memory, and at least three warm end-to-end samples per 1–10 bucket.
  Every bucket median must be ≤2,000 ms; median and p95 are recomputed.

Publication creates one new immutable directory containing
`card_detector.tflite`, `card_classifier.tflite` and
`card_recognition.release.json`, using held-directory staging and Linux
`renameat2(RENAME_NOREPLACE)`. Existing directories and symlinks are rejected.
Individual assets are never replaced. Android packaging must consume the complete
bundle. Metadata binds tensor contracts, calibration, labels, thresholds,
source evidence and measured quality.

Android integration, actual device execution and the manual acceptance checklist
are subsequent tasks and have not been performed by this tooling.

## Reproduction

From `ml/card-recognition`, using only the locked Linux CPython 3.9 environment:

```text
python -m pontinho_cards.train_detector --config config/detector.yaml --inputs datasets/two-stage-inputs.json --run-id reviewed-two-stage-detector-20261015
python -m pontinho_cards.train_classifier --config config/classifier.yaml --inputs datasets/two-stage-inputs.json --run-id reviewed-two-stage-classifier-20261015
python -m pontinho_cards.evaluate_two_stage select --detector artifacts/detector-candidates/reviewed-two-stage-detector-20261015 --classifier artifacts/classifier-candidates/reviewed-two-stage-classifier-20261015 --validation datasets/two-stage-validation.json --output artifacts/two-stage-threshold-selection.json
# Only after passing validation and freezing the complete audited holdout:
python -m pontinho_cards.evaluate_two_stage evaluate --detector artifacts/detector-candidates/reviewed-two-stage-detector-20261015 --classifier artifacts/classifier-candidates/reviewed-two-stage-classifier-20261015 --validation datasets/two-stage-validation.json --selection artifacts/two-stage-threshold-selection.json --holdout datasets/final-holdout.json --output reports/two-stage-evaluation.json
python -m pytest tests -q
python -m ruff check src tests
```

Optional `--grid` JSON contains `thresholds`, a list of objects with
`detector_score`, `combined_score`, `iou` and `crop_margin`. Without it the
CLI uses its explicit bounded grid. The reviewed selection command currently
exits nonzero because the validation manifest is absent, creating no selection,
holdout receipt or Android assets.

Inference stays offline. Dependencies remain pinned in `requirements.lock`;
their licenses must accompany an approved distribution. The app must not
upload, retain or log photos.
