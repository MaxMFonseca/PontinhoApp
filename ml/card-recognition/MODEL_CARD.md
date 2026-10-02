# Pontinho card detector model card

**Release status:** `NOT_APPROVED`

No card-detector model is approved or bundled with the Android application.
There is therefore no production model version, model size, SHA-256, measured
accuracy, or known weak-deck metric to report yet. The release gate deliberately
keeps both Android assets absent until every item below is backed by immutable
evidence.

## Current blockers

- The reviewed source allow-list is empty. No downloaded card-image archive has
  passed the source-specific CC0/public-domain, immutable revision, checksum,
  annotation-provenance, and human-approval checks.
- There is no frozen real-photo holdout containing at least 200 unique photos
  from at least 10 unseen physical deck designs with all 52 classes represented.
- There is no approved candidate model, validation-selected threshold evidence,
  one-use frozen-holdout validation receipt, or qualifying validation report.
- Consequently, exact hand-total accuracy has not been measured against the
  release holdout and cannot be claimed to meet the 95% minimum.

The machine-readable status is in
[`reports/release/status.json`](reports/release/status.json). Dataset gaps and
candidate-source findings are in
[`reports/dataset-audit.md`](reports/dataset-audit.md).

## Intended use

The future model is intended for fully offline assistance while entering a
Pontinho round: detect face-up, fully visible, non-overlapping cards laid on a
table, then propose the detected 52-card multiset and its score for human
confirmation. It is an input convenience, not an authoritative scorekeeper.

Only the standard 52 suited cards are in scope. The game uses a selected suited
card as the coringa; printed jokers are not part of the recognition vocabulary.

## Non-intended use and limitations

The model is not intended for overlapping hands, partially hidden or clipped
cards, card backs, printed jokers, novelty cards, video tracking, surveillance,
identity recognition, gambling decisions, or cloud inference. Predictions from
unknown deck artwork, severe glare, blur, shadows, extreme perspective, poor
lighting, damaged cards, or clutter may be wrong or should abstain.

Weak deck designs and conditions will be listed only after the frozen evaluation
produces measured per-deck, card-count, lighting, and abstention slices. None are
claimed today.

## Required user correction flow

Every scan must remain reviewable. The app must show the recognized cards and
computed total, allow cards to be added, removed, or corrected, and require the
user to confirm before points are saved. An abstention or scanner-unavailable
state must fall back to manual numeric entry without blocking the game.

## Data, privacy, and licensing

Inference is designed to remain on-device. A released scanner must not upload,
retain, or log table photos. Training data may enter the pipeline only through
the strict source manifest described in
[`docs/licenses/card-recognition.md`](../../docs/licenses/card-recognition.md).
No third-party training images, weights, or model binaries are currently stored
in this repository.

The training/export toolchain is Python with TensorFlow, TensorFlow Lite Model
Maker, TensorFlow Lite Support, NumPy, OpenCV, Pillow, PyYAML, and pycocotools.
Exact resolved versions are recorded in `requirements.lock`; their upstream
licenses must be reviewed before distribution. Android inference is expected to
use the MediaPipe Tasks runtime under its upstream license.

## Training and evaluation contract

Training may use approved real data plus procedurally composed, non-overlapping
tabletop scenes. Physical deck groups and every crop or augmented derivative
must remain disjoint across train, validation, and the frozen test partition.
The final holdout must be real-photo-only, cover all 52 classes, and remain
unseen during training, threshold selection, and model selection.

Promotion requires exact model, Stage 6 contract/comparison, source manifest,
frozen split/holdout, Stage 7 report/receipt, and dependency-provenance hashes.
The primary gate is at least 95% exact Pontinho hand-total accuracy across at
least 200 unique photos and 10 unseen deck designs. Exact card-multiset accuracy,
abstention rate, and all disclosed failure slices remain visible.

## Reproduction

From `ml/card-recognition` in the locked Linux CPython 3.9 environment:

```text
python -m pontinho_cards.normalize --manifest datasets/sources.json --out datasets/normalized
python -m pontinho_cards.split --input datasets/normalized --out datasets/splits.json
python -m pontinho_cards.synthetic --split datasets/splits.json --output datasets/generated
python -m pontinho_cards.train --config config/model.yaml --splits datasets/splits.json --run-id <id>
python -m pontinho_cards.export --checkpoint checkpoints/<id> --output artifacts/<version>
python -m pontinho_cards.evaluate --model artifacts/<version>/card_detector.tflite --test-manifest datasets/final-holdout.json --report reports/<version>.json
python -m pontinho_cards.release --model artifacts/<version>/card_detector.tflite --report reports/<version>.json --contract artifacts/<version>/model-contract.json
```

The final command must return non-zero and leave Android assets untouched while
the blockers above remain.
