# Card-recognition data and model notices

**Status:** No third-party card images, annotations, trained weights, or model
artifact are currently included in the repository or Android application.

`ml/card-recognition/datasets/sources.json` is the sole allow-list for material
that may enter the training pipeline. It is empty because the 2026-10-15 audit
did not find a candidate with all required source-specific evidence, immutable
archive identity, verified SHA-256, deck provenance, and human approval. See the
[dataset audit](../../ml/card-recognition/reports/dataset-audit.md) for the
candidate matrix and release blocker.

## Admission policy

- Only CC0 1.0 or an explicit public-domain mark/dedication is eligible.
- A general open-source, permissive, attribution, or commercial-use license is
  not a substitute for source-specific public-domain evidence.
- `license.evidenceUrl` and its quoted/paraphrased evidence text must identify
  the publisher's statement for that named source. A Creative Commons or SPDX
  deed URL is rejected in this field. The canonical
  [CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/) belongs in
  the separate `license.legalToolUrl` field: it explains the legal tool but does
  not by itself license a dataset.
- Evidence and source hostnames are compared after lowercase, trailing-dot, and
  IDNA normalization. If the publisher's source-specific evidence lives on a
  different host, that exact host must be recorded as
  `license.reviewedEvidenceHost`; generic license/deed hosts remain forbidden.
- A person must approve the evidence and record a note that points back to it.
- The downloaded archive is hashed before extraction; a mismatch is fatal.
- Source version is typed as either a full Git commit SHA tied to the source
  repository or a numeric platform dataset-version ID tied to its exact
  canonical provider route: Kaggle `/datasets/{owner}/{slug}` or Roboflow
  `/{workspace}/{project}` on Universe. Shortened identifiers, malformed routes,
  branch names, and mutable tags are rejected.
- Annotation status, requested attribution, and physical deck group are
  mandatory even when attribution is not legally required. Unannotated real
  photos may be recorded as `UNANNOTATED`, but contribute zero
  `holdoutEligiblePhotoCount` until evaluation-ready boxes exist.

When a source is eventually admitted, its required courtesy or legal
attribution will be added here verbatim before any model can be promoted.

## Model release and dependency notice

The Android application currently contains no card-recognition model or
third-party card imagery. A future model may be promoted only when the strict
manifest parser accepts every source and the release receipt cryptographically
binds that manifest, the frozen split and holdout, the selected exported model,
and its validation report. Empty manifests, platform license badges without
source-specific evidence, and merely permissive licenses fail closed.

The development toolchain uses the exact versions resolved in
`ml/card-recognition/requirements.lock`, including TensorFlow, TensorFlow Lite
Model Maker, TensorFlow Lite Support, NumPy, OpenCV, Pillow, PyYAML, and
pycocotools. Those packages are development dependencies, not evidence that a
dataset or trained model may be redistributed. Their upstream license notices
must be reviewed and included as applicable before an approved binary is
shipped. The Android runtime is expected to use MediaPipe Tasks; its upstream
notice must likewise be included when that dependency and an approved model are
present in a release.
