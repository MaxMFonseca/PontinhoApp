# Pontinho card-recognition workspace

This directory contains the reproducible, offline training toolchain for the
Pontinho card detector. It does not include data, pretrained weights, or a
production artifact.

## Supported environment

Use 64-bit CPython 3.9 on Linux x86-64. TensorFlow Lite Model Maker 0.4.3,
its TensorFlow-compatible dependency set, and TFLite Support 0.4.4 provide the
EfficientDet-Lite0 training API and metadata tooling. Model Maker requires
`scann==1.2.6`, whose Linux wheels end at CPython 3.9, making 3.9 the newest
supported minor. MediaPipe is reserved for Android runtime integration, not
Python training. Linux is deliberately the supported training host because the
native TFLite Support stack is wheel-bound.

Create an isolated local environment, then install the fully pinned lock:

```bash
python3.9 -m venv .venv
./.venv/bin/python -m pip install --require-hashes -r requirements.lock
./.venv/bin/python -m pip install --no-deps -e .
./.venv/bin/python -c "import pontinho_cards"
```

The editable install makes the `src/` layout importable, so subsequent tools
are invoked as `./.venv/bin/python -m pontinho_cards.<command>`.

The environment smoke test imports the pinned runtime. The opt-in training
smoke below uses Model Maker's Pascal VOC adapter so empty negative scenes are
retained in the generated TFRecords instead of being dropped by CSV conversion.

```bash
./.venv/bin/python -m pytest tests/test_environment.py -q
./.venv/bin/python -m ruff check src tests
```

## Training

`config/model.yaml` fixes the EfficientDet-Lite0 family, canonical 52-label
order, image and batch sizes, seed, bounded epoch count, early stopping, and
each augmentation switch. Training consumes only the `train` and `validation`
members of the supplied split document. It neither opens
`datasets/final-holdout.json` nor emits `TEST` rows to Model Maker.

Run a reviewed job from this directory with:

```bash
./.venv/bin/python -m pontinho_cards.train \
  --config config/model.yaml \
  --splits datasets/splits.json \
  --run-id <safe-unique-id>
```

Approved synthetic scenes can be merged into `train` only by adding both
`--synthetic-manifest datasets/generated/training-manifest.json` and
`--synthetic-review datasets/generated/human-review.json`. The review receipt
must bind the exact manifest, deterministic generation configuration,
distribution report, and contact sheet hashes. Positive card-scene bundles
require a review sample of at least 100 scenes. Each generated image and record
is hash-checked, and every card crop must descend from an approved real
train-only parent. Canonical empty scenes are retained as true background
examples; validation and test remain byte-for-byte real partitions.
The CLI snapshots the base split, configuration, manifest, review, audit
artifacts, metadata, and generated images once through descriptor-rooted reads.
Authorization and release reconstruction operate only on those immutable bytes;
the staged training copy is materialized from them and live source paths are not
reopened.

Run metadata and every downstream candidate/promotion/release contract keep
three meanings separate: `splitManifestSha256` binds the frozen real split,
`trainingInputSha256` binds the exact real-plus-reviewed-synthetic training
document, and nullable `syntheticManifestSha256` binds the admitted synthetic
manifest. Synthetic runs also bind their review and generation-configuration
hashes. None of these fields is an alias for another.

Every run is assembled in a hidden sibling staging directory below ignored
`checkpoints/`. Before staging begins, the command validates non-empty audited
train and validation records, canonical labels, boxes, image bytes, dimensions,
hashes, and the safe run ID. Model Maker receives class first-occurrences in
the exact `CARD_LABELS` order, and both loaders must expose the same canonical
1–52 label map before a model is created. Canonical records are translated to
Pascal VOC annotations, including genuine zero-object training images. The
pipeline inspects Model Maker's emitted TFRecords and fails unless every
declared negative survived alongside positive examples.

## Source adapters and normalization

Every approved source entry names one immutable local archive snapshot and its
SHA-256 plus one explicit adapter: `COCO_JSON_SCENES` (annotation member, image
prefix, and source-label map) or `ISOLATED_CARD_FOLDERS` (image prefix and
folder-label map). ZIP and TAR members are read directly from the verified
snapshot; traversal, links, unknown formats, unmapped labels, invalid boxes,
and dimension mismatches fail closed. Source/deck identifiers and archive file
names are bounded portable safe names; archive members are safe relative POSIX
paths. Reads and writes are descriptor-rooted and reject symbolic-link/reparse
parents, so normalization cannot escape the declared roots. Normalized records carry
`sourceArchiveMember` and `sourceArchiveMemberSha256` provenance.
COCO inputs additionally require object-only arrays, typed unique category/image
IDs, unique annotation IDs when supplied, exact image/category references, and a
one-to-one image-to-archive-member mapping; orphan or ambiguous annotations are
never discarded silently.

```bash
python -m pontinho_cards.normalize --manifest datasets/sources.json \
  --archive-root datasets/source-archives --out datasets/normalized
```

`datasets/sources.json` remains empty until a real source completes the human
license review; these adapters do not constitute approval.

## Validation thresholds and promotion order

Create the validation envelope from the frozen split before inference:

```bash
python -m pontinho_cards.thresholds validation-manifest \
  --split datasets/splits.json --output datasets/validation-manifest.json
python -m pontinho_cards.thresholds --model <candidate.tflite> \
  --contract <candidate-contract.json> \
  --validation-manifest datasets/validation-manifest.json \
  --scores 0.3 0.4 0.5 0.6 0.7 --ious 0.3 0.4 0.5 0.6 0.7 \
  --output artifacts/threshold-selection.json
```

The required sequence is: generate candidates, create/select validation
thresholds, benchmark both immutable candidates on Android and write the
comparison, promote the selected candidate, run Stage 7 once on the frozen
holdout, then run Stage 8. For a synthetic run, pass the exact merged training
input, synthetic manifest, and review receipt to Stage 8 with
`--training-input`, `--synthetic-manifest`, and `--synthetic-review`. Stage 8
treats the receipt only as replay-prevention
evidence: it independently executes the exact snapshotted TFLite over securely
snapshotted holdout bytes and compares the complete recomputed metrics report.

After fitting, early stopping restores its best in-memory weights. Those exact
weights are atomically saved and reloaded as the sole candidate
`selected.weights.h5`, then evaluated for aggregate AP and all 52 per-class AP
values. The run also records the resolved configuration, Git commit, SHA-256 of
`requirements.lock`, the distinct frozen-split/training-input/synthetic evidence
hashes, loss history, metrics, and the selected checkpoint SHA-256. Only after
every check succeeds is the whole
staging directory atomically renamed to `checkpoints/<run-id>`. Any training,
metric, checkpoint, or publication failure removes staging and leaves no final
run directory.

The real dataset audit currently has no admitted sources, crops, or splits.
Therefore the command intentionally fails before creating a run: the current
repository has no split manifest, and an empty future manifest would fail with
`no audited training records`. It must not be described as a trained or
releasable model, and no checkpoint or metrics file is fabricated.

The opt-in integration test executes one genuine EfficientDet-Lite0 batch on
generated fixtures in CPU mode (and may fetch the pinned TF Hub feature-vector
weights on first use):

```bash
PONTINHO_RUN_MODEL_MAKER_SMOKE=1 \
  ./.venv/bin/python -m pytest tests/test_train_smoke.py -q
```

All commands run from this directory. Future tools are invoked as
`python -m pontinho_cards.<command>`. The fixed `seed` in
`config/model.yaml` is the sole seed used by split, synthesis, and training.

Only CC0 or public-domain inputs may enter the pipeline. Keep raw downloads,
derived data, checkpoints, artifacts, caches, and local reports out of Git.
