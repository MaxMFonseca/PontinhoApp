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

All commands run from this directory. Future tools are invoked as
`python -m pontinho_cards.<command>`. The fixed `seed` in
`config/model.yaml` is the sole seed used by split, synthesis, and training.

Only CC0 or public-domain inputs may enter the pipeline. Keep raw downloads,
derived data, checkpoints, artifacts, caches, and local reports out of Git.
