# Card-recognition data and model notices

**Status:** The debug build contains an experimental model trained from the
source below. Release builds contain no model and keep the scanner disabled.

## Single-stage YOLO candidate

The planned replacement is a debug-only, offline 52-class detector trained
with Ultralytics YOLOv8n `8.4.177`. Pontinho's complete corresponding source is
distributed under AGPLv3 or later; the full text is in the repository
[`LICENSE`](../../LICENSE), with third-party and data provenance notices in
[`NOTICE.md`](../../NOTICE.md).

The intended source is Roboflow's public `playing-cards-ow27d` dataset, version
4. An authorized manual YOLOv8 download was registered on 2026-10-16 in
[`playing-cards-ow27d-v4.source.json`](../../ml/card-recognition/yolo/datasets/playing-cards-ow27d-v4.source.json).
The receipt binds the canonical Augmented Startups URL, version `4`, format
`yolov8`, 2,170,532,362 archive bytes, 48,478 ZIP members, and SHA-256
`461d1392bb70a0cd2fdb3b4d7c682e0544792b33e64e9fe79e0084e03bc19a74`.

The receipt intentionally records only the stable public dataset/version URL:
[`https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4`](https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4).
The authenticated export download URL is ephemeral and can be secret-bearing,
so it is intentionally omitted from the receipt, documentation, and source
control.

The archive's `data.yaml` identifies the same provider URL and records the
license as **Public Domain**; `README.dataset.txt` independently says
`License: Public Domain`, agreeing with the provider page's Public Domain
badge. Its 52 class names, in archive order, are `10C`, `10D`, `10H`, `10S`,
`2C`, `2D`, `2H`, `2S`, `3C`, `3D`, `3H`, `3S`, `4C`, `4D`, `4H`, `4S`, `5C`,
`5D`, `5H`, `5S`, `6C`, `6D`, `6H`, `6S`, `7C`, `7D`, `7H`, `7S`, `8C`, `8D`,
`8H`, `8S`, `9C`, `9D`, `9H`, `9S`, `AC`, `AD`, `AH`, `AS`, `JC`, `JD`, `JH`,
`JS`, `KC`, `KD`, `KH`, `KS`, `QC`, `QD`, `QH`, and `QS`.

The extracted export has 21,203 train, 2,020 validation, and 1,010 test
image/label pairs. It is a synthetically generated detector dataset and has no
production approval or final-holdout role.

### Provider-declared export recipe and count reconciliation

`README.roboflow.txt` says that Roboflow exported the dataset on 2023-03-07 at
19:04 GMT in YOLOv8 format. It declares Auto-Orient preprocessing with
EXIF-orientation stripping and a Stretch resize to 640x640. It also declares
three augmented versions of each source image: 50% horizontal flip, 50%
vertical flip, equal-probability 90-degree choices (none, clockwise,
counter-clockwise, upside-down), random 0--15% crop, random -10 to +10 degree
rotation, random -2 to +2 degree horizontal and vertical shear, random -25 to
+25% brightness, random -15 to +15% exposure, Gaussian blur up to 1.75 px,
and salt-and-pepper noise on 2% of pixels. The README does not declare
grayscale, hue/saturation, or cutout augmentation; none is inferred here.

The same provider README declares 24,240 images. The receipt-bound archive
inventory instead contains 24,233 paired images: 21,203 train + 2,020
validation + 1,010 test = 24,233. The seven-image difference has no inferred
cause; the exact immutable archive inventory controls this registration.

## Experimental model attribution

The debug-only `rf100-beta-20261015-int8` model was trained from **Poker Cards
Cxcvz** by LibreYOLO / Roboflow 100 / Augmented Startups, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The immutable source
revision is [`921de60dffb6a0ebf7cd88140e38b00ce845ed32`](https://huggingface.co/datasets/LibreYOLO/poker-cards-cxcvz/commit/921de60dffb6a0ebf7cd88140e38b00ce845ed32).
The downloaded archive SHA-256 is
`7b236c9caacd2a39dcba9d7f4453a75ac5d2460b35dafaa9bb357c70b6394782`.

This attribution does not imply production approval. All three upstream
partitions share the immutable visual-deck identity
`libreyolo-poker-cards-cxcvz-deck-v1`; therefore the 128-photo test partition is
not an unseen final holdout. The frozen release inventory remains at 0 of 200
required photographs and 0 of 10 required unseen deck designs.

`ml/card-recognition/datasets/sources.json` is the sole allow-list for material
that may enter the training pipeline. The 2026-10-15 beta audit admitted the
source above for attributed experimental use. See the
[dataset audit](../../ml/card-recognition/reports/dataset-audit.md) for the
candidate matrix and release blocker.

## Admission policy

- CC0 1.0, explicit public-domain material, and CC BY 4.0 with recorded
  attribution may be admitted. Attribution licenses require an explicit human
  approval flag and source-specific evidence.
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
- `archiveInput.localPath` names one immutable local ZIP/TAR snapshot and the
  downloaded archive is hashed before use; a mismatch is fatal. Normalization
  reads members directly without trusting a mutable extracted directory.
- `adapter.kind` must be `COCO_JSON_SCENES`, `ISOLATED_CARD_FOLDERS`, or
  `YOLO_SCENES`. The manifest explicitly binds the annotation member
  when applicable, image prefix, and complete source-label-to-`CARD_LABELS`
  map. Unsafe members, archive links, unmapped labels, and other formats fail
  closed.
- Source version is typed as either a full Git commit SHA tied to the source
  repository or a numeric platform dataset-version ID tied to its exact
  canonical provider route: Kaggle `/datasets/{owner}/{slug}` or Roboflow
  `/{workspace}/{project}` on Universe. Shortened identifiers, malformed routes,
  branch names, and mutable tags are rejected.
- Annotation status, requested attribution, and physical deck group are
  mandatory even when attribution is not legally required. Unannotated real
  photos may be recorded as `UNANNOTATED`, but contribute zero
  `holdoutEligiblePhotoCount` until evaluation-ready boxes exist.
- Every source explicitly declares `taskRoles` from `DETECTOR_SCENE`,
  `CLASSIFIER_CROP`, and `FINAL_SCENE_HOLDOUT`, plus an immutable
  `visualDeckId`. An empty role list is permitted only while the audit is
  `BLOCKED_FOR_RELEASE`; it records inventory but contributes no training or
  holdout evidence. `visualDeckId` and `deckGroupId` form a one-to-one
  identity: one visual deck may span archive partitions only when both IDs
  remain the same, and a final-holdout visual deck may not occur in a training
  role.
- `DETECTOR_SCENE` and `FINAL_SCENE_HOLDOUT` require usable real scenes with
  scene-adapter bounding boxes. `CLASSIFIER_CROP` requires either usable
  class-labelled isolated cards or auditable crops from boxed real scenes.
- `READY_FOR_TWO_STAGE_EXPERIMENT` is rejected if any source has no eligible
  role, or unless the manifest proves at least 200 final-holdout photos across
  ten unseen visual deck designs. The current audited manifest remains
  `BLOCKED_FOR_RELEASE`.

When a source is eventually admitted, its required courtesy or legal
attribution will be added here verbatim before any model can be promoted.

## Model release and dependency notice

The Android release application currently contains no card-recognition model
or third-party card imagery. The experimental model is isolated to the debug
source set. A future production model may be promoted only when the strict
manifest parser accepts every source and the release receipt cryptographically
binds that manifest, the frozen split and holdout, the selected exported model,
and its validation report. Each declared source archive must also exist at its
canonical path and match the manifest checksum, and a human-review receipt must
match the trust-root hash configured in `config/release-gate.yaml`. Empty
manifests, missing archives or audit receipts, platform license badges without
source-specific evidence, and merely permissive licenses fail closed.

For every real train, validation, or test image, that receipt must identify a
safe `sourceArchiveMember` path inside the exact hashed source archive, its
`sourceArchiveMemberSha256`,
the canonical image checksum and dimensions, and the human real-photo
classification. It also binds the complete normalized dataset digest and exact
split-manifest digest. The currently pinned receipt is deliberately
`NOT_APPROVED` and cannot authorize a model.

The development toolchain uses the exact versions resolved in
`ml/card-recognition/requirements.lock`, including TensorFlow, TensorFlow Lite
Model Maker, TensorFlow Lite Support, NumPy, OpenCV, Pillow, PyYAML, and
pycocotools. Those packages are development dependencies, not evidence that a
dataset or trained model may be redistributed. Their upstream license notices
must be reviewed and included as applicable before an approved binary is
shipped. The Android runtime is expected to use MediaPipe Tasks; its upstream
notice must likewise be included when that dependency and an approved model are
present in a release.
