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

The environment smoke test builds a single CSV-annotated image in a temporary
directory using the real Model Maker `DataLoader`. It never downloads a dataset
or model weights:

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

Every run is assembled in a hidden sibling staging directory below ignored
`checkpoints/`. Before staging begins, the command validates non-empty audited
train and validation records, canonical labels, boxes, image bytes, dimensions,
hashes, and the safe run ID. Model Maker receives class first-occurrences in
the exact `CARD_LABELS` order, and both loaders must expose the same canonical
1–52 label map before a model is created. PNG inputs from the synthetic
compositor are deterministically converted to staged JPEGs because Model Maker
0.4.3 accepts JPEG object-detection inputs only.

After fitting, early stopping restores its best in-memory weights. Those exact
weights are atomically saved and reloaded as the sole candidate
`selected.weights.h5`, then evaluated for aggregate AP and all 52 per-class AP
values. The run also records the resolved configuration, Git commit, SHA-256 of
`requirements.lock`, SHA-256 of the input manifest, loss history, metrics, and
the selected checkpoint SHA-256. Only after every check succeeds is the whole
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
