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

Float16/int8 variants remain training/export experiments. Joint selection compares
only pairs executable by the packaged MediaPipe CPU image runtime: FLOAT32 inputs
require uniform RGB normalization; UINT8 requires Python's normalized/quantized
values to equal the native raw pixels for all 256 values in each channel. Signed
INT8 inputs/outputs and non-equivalent UINT8 contracts are ineligible. This allows
the trainer's UINT8 classifier export with a nonunit scale such as 1/255 when it
is equivalent, without assuming every learned quantization is compatible. The
same eligibility check runs before final evaluation, at promotion and on Android.
Each classifier
uses its own model-bound validation transform:
`softmax(log(max(raw_probability, 1e-12)) / temperature)`.
Integer output is normalized through the shared T4 scale-aware boundary first.
Combined confidence is `min(detector_score, calibrated_classifier_score)`.

Android capture decoding preserves original pixels for crops. It rejects images
above 8192 pixels per axis or the smaller of 16,777,216 pixels and the process
heap budget divided by 16, rather than downsampling the capture. At most two
original ARGB buffers coexist; temporary native images are resized to the model
input dimensions. These memory bounds are not a physical-device benchmark.

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

The runtime/evaluator geometry contract is `mediapipe-integer-interior-1px-v1`.
MediaPipe truncates/clamps normalized float32 origins and extents independently
to its 320×320 analysis grid before returning Java bounds. The Python joint
evaluator mirrors that conversion (including the original-pixel crop math).
A visible detector box, including the 80%-of-threshold uncertainty band, whose
native bounds reach or cross the one-pixel guard causes whole-capture abstention
before NMS/classification: left/top must be >1 and right/bottom <319. The guard
covers right/bottom information lost by adding two independently truncated terms;
pixels 2..318 are interior. It does not shrink or discard physical cards to
produce a partial hand. Near-edge interior cards may still carry review warnings.
The left/top one-pixel margin is deliberately conservative: native clamping
already exposes a lost negative origin at zero. The right/bottom guard is needed
because independently truncated origins and extents can conceal an edge at 320.

Selection, final report, holdout claim/receipt and release all bind `boxPolicy`;
Android rejects releases without this exact policy. Older evidence must not be
relabelled or reused: selection/evaluation must follow the new contract without
re-consuming an already used holdout. This is a conservative safety contract,
not a new measured quality claim. `IncompleteCards` requires recapture guidance.

### Deterministic pre-model resize

`resizePolicy` version 1 requires `point-bilinear`, `half-pixel` coordinates,
`clamp` edges, `half-up-once` rounding, `RGB8`, `opaque-only` alpha, no antialias,
detector size 320×320 and classifier size 224×224. These exact parameters are checked
by Android and bound to candidate validation/calibration, selection, final report,
holdout claim/receipt and release. Missing/old evidence is rejected before use;
calibration measured with Pillow's prior resampler cannot be carried forward.

For an output coordinate d, source axis length S and destination length D, use
`n = clamp((2*d+1)*S-D, 0, (S-1)*2*D)`, then `lo=n//(2*D)`,
`hi=min(lo+1,S-1)` and fractional numerator `w=n%(2*D)`. Combine all four
neighbors per encoded RGB channel using integer weights and denominator
`(2*width)*(2*height)`. Round only the final result with
`(weightedSum+denominator//2)//denominator`. This includes no-op and upscale;
there is no Pillow/Skia filtering, separate intermediate rounding, gamma
conversion, antialias kernel widening or alpha composition. Nonopaque input
pixels anywhere are rejected, including unsampled pixels: Android's premultiplied
storage cannot preserve arbitrary straight-alpha RGB bytes losslessly.

Resizing occurs before tensor normalization/quantization in both evaluators and
both Android runners. The classifier producer uses it for training input,
representative quantization input, validation and temperature calibration too.
Detector training augmentation remains Model Maker's own process; its validation
inference and joint selection use the production resize. Original capture pixels
are still retained for crop extraction. Android allocates only two source rows
and a model-size result, not another full capture, and always owns the result.

`tests/fixtures/android-release/model-resize-golden.json` is one shared set of 14
numerical cases, generated independently with exact rational BigInt arithmetic
before the Python/Kotlin implementations. It includes hand-checkable half-up
ties, small integer/noninteger downsampling, upscale and no-op, plus each model
size under integer/noninteger downsampling, upscale and no-op. Literal RGB samples
and whole-output RGB byte fingerprints check every pixel on both platforms.
These are synthetic numerical vectors, not fabricated card assets or accuracy
evidence. New holdout quality, native device latency and memory remain unmeasured;
previously consumed holdouts must never be reused to qualify the new policy.

Physical-device execution and the manual acceptance checklist remain pending;
this tooling and the Android unit tests do not establish device/model quality.

### Deterministic native-grid NMS

`nmsPolicy` version 1 requires the 320×320 native integer detector grid. Native
box conversion and the conservative `boxPolicy` checks happen first; NMS happens
before any mapping to original-image coordinates. Suppression is strictly `>`:
`intersection * denominator > union * numerator`. Exact equality preserves both
boxes. The selected JSON IoU threshold is converted binary64 → binary32 and then
represented as that binary32 value's exact rational, not a rounded decimal or a
floating-point IoU. This conversion is explicit in Python and the Android verifier.

Scores are binary32. Priority is descending score, then integer left/top/right/bottom,
then original index. Results are ordered by top/left/bottom/right/index. Nonpositive
area, out-of-grid or off-grid coordinates are rejected. Areas are at most 102400 and
union at most 204800; Python unbounded integers and Android BigInteger prevent
cross-product overflow, including denominators as large as 2^149. The release
threshold range remains [1e-12, 1]; subnormal helper tests are additional arithmetic
defenses, not newly permitted release configurations.

The exact policy fields bind candidate contracts/validation evidence, selection,
final report, holdout claim/receipt and release; Android rejects absent or changed
policies. Earlier evidence must be requalified, never relabelled or rerun on an
already-consumed holdout. Standalone detector validation's ground-truth matching
IoU is a different metric, not this application NMS; joint selection/evaluation
always calls the shared-policy NMS implementation.

`tests/fixtures/android-release/native-nms-golden.json` supplies shared numerical
vectors: reviewer exact-IoU .5, adjacent binary32 thresholds, one-grid-pixel changes
on both sides, score/coordinate/index ties, spatial order, invalid boxes and large
cross-products. Both real helpers and the orchestration paths consume these cases;
joint tests cover 320×320, 1000×500 and 3000×2000 original images. They demonstrate
arithmetic agreement only, not recognition accuracy or physical-device timing.

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
