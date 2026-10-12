# Card-recognition dataset audit

**Audit date:** 2026-10-16
**Decision:** `BLOCKED_FOR_RELEASE`

## Stop/go result

Poker Cards Cxcvz was admitted for an attributed CC BY 4.0 experiment at the
immutable Hugging Face revision
`921de60dffb6a0ebf7cd88140e38b00ce845ed32`. Its deterministic archive SHA-256
is `7b236c9caacd2a39dcba9d7f4453a75ac5d2460b35dafaa9bb357c70b6394782`.
Normalization accepted 1,285 photographed scenes and 5,574 annotations with no
rejections. The three upstream partitions contain 964 train, 193 validation,
and 128 test scenes. They are admitted under `DETECTOR_SCENE` and
`CLASSIFIER_CROP` for two-stage experiments and share the immutable visual-deck
identity `libreyolo-poker-cards-cxcvz-deck-v1`. This is sufficient for an
experiment, but not for production.

| Release requirement | Audited and reserved | Minimum | Result |
|---|---:|---:|---|
| Real, public photographs frozen exclusively for final holdout | 0 | 200 | Blocked |
| Unseen visual deck designs in that holdout | 0 | 10 | Blocked |

The upstream 128-scene test partition is not counted as final holdout evidence:
it uses the same visual deck as the admitted train and validation partitions.
Treating its partition name as proof of an unseen deck would leak visual deck
identity across the acceptance boundary.

Training may continue only as an experiment. The resulting model may be bundled
only in a clearly labelled debug build; it is not eligible for production
promotion.

## Candidate-source audit

The links below are direct publisher/source records inspected during the audit.
An `Excluded` result means no bytes from that candidate are admitted by the
manifest; it does not dispute the publisher's stated license.

| Candidate | Publisher evidence | Content observed | Admission result |
|---|---|---|---|
| Poker Cards Cxcvz, immutable revision `921de60dffb6a0ebf7cd88140e38b00ce845ed32` | The [source README](https://huggingface.co/datasets/LibreYOLO/poker-cards-cxcvz/blob/921de60dffb6a0ebf7cd88140e38b00ce845ed32/README.md) states CC BY 4.0; the frozen archive hashes to `7b236c9caacd2a39dcba9d7f4453a75ac5d2460b35dafaa9bb357c70b6394782`. | 1,285 real scenes and 5,574 boxes across all 52 classes after the visually audited class-index repair; one documented visual deck identity. | **Admitted for experiment only.** Exact usable counts are 964 train, 193 validation, and 128 upstream test scenes. All three partitions use `DETECTOR_SCENE` and `CLASSIFIER_CROP`; none uses `FINAL_SCENE_HOLDOUT`. |
| Playing Cards Object Detection, Augmented Startups version 4 | The [public Roboflow version-4 page](https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4) shows Augmented Startups and a Public Domain badge. The authorized export's `data.yaml` identifies that same URL, version `4`, and `license: Public Domain`; `README.dataset.txt` independently says `License: Public Domain`. | The registered YOLOv8 ZIP is 2,170,532,362 bytes, has SHA-256 `461d1392bb70a0cd2fdb3b4d7c682e0544792b33e64e9fe79e0084e03bc19a74`, 48,478 ZIP members, and 24,233 paired synthetic scenes: 21,203 train, 2,020 validation, and 1,010 test. `README.roboflow.txt` says 24,240 images, so the exact archive inventory is the controlling count; its declared preprocessing and augmentation recipe is recorded below. | **Registered for debug-only single-stage experiment; excluded from release holdout.** The receipt is committed at `ml/card-recognition/yolo/datasets/playing-cards-ow27d-v4.source.json`; raw archive/extraction are ignored. It contributes 0 qualifying real-photo holdout scenes. |
| Cards Image Dataset—Classification, version 2 | [Kaggle source record](https://www.kaggle.com/datasets/gpiosenka/cards-image-datasetclassification) declares CC0 Public Domain; the Kaggle metadata inspected on 2026-10-15 identifies version 2, updated 2022-11-17. | 8,154 isolated/cropped images across 52 cards plus a joker; class labels only, no tabletop bounding boxes. | **Excluded.** The 431,789,634-byte archive was not acquired and hashed, deck-design identities are not documented, and isolated crops are not eligible real holdout scenes. |
| Playing Cards Labelized Dataset, version 2 | [Kaggle source record](https://www.kaggle.com/datasets/hugopaigneau/playing-cards-dataset) declares CC0 Public Domain; the Kaggle metadata inspected on 2026-10-15 identifies version 2, updated 2020-02-08. | Generated 1080×1080 card-hand composites with XML boxes for 52 classes; source card frames came from videos of cards. | **Excluded.** The 1,912,775,663-byte archive was not acquired and hashed, the published scenes are generated rather than real tabletop acceptance photos, and distinct physical deck groups are not documented. |
| Teodor Kostadinov “Real” dataset | The author's [README at immutable commit `6da0072`](https://github.com/TeogopK/Playing-Cards-Object-Detection/blob/6da007263c51cf5a44a3c06ea840030ff72ce678/README.md) says all included datasets are CC0 and says the 100 real images were photographed and labeled by the author. | 100 real images, bounding boxes, only 13 classes, apparently one capture source/deck; augmented derivatives are also present elsewhere in the repository. | **Excluded.** It is below the 200-photo minimum, cannot evidence 10 deck groups, does not cover 52 classes, has no separate license/dedication file, and no reviewed archive SHA-256 is recorded. |
| Photographic dataset: playing cards | The [versioned arXiv record](https://arxiv.org/abs/1701.07354v1) identifies a photographic dataset and its authors, but the record does not state CC0 or public-domain terms for the dataset. | Real playing-card scenes intended for image-processing research. | **Excluded.** No qualifying CC0/public-domain dedication was verified, and no archive revision, checksum, 52-class annotation set, or deck grouping was established. |

## Registered Roboflow v4 export provenance

The stable public dataset/version URL is
[`https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4`](https://universe.roboflow.com/augmented-startups/playing-cards-ow27d/dataset/4).
The authorized download URL is not recorded: it is an ephemeral,
secret-bearing authenticated endpoint, unsuitable for source control or a
provenance receipt. The tracked receipt records the stable public URL instead.

The ignored `README.roboflow.txt` declares a 2023-03-07 19:04 GMT Roboflow
YOLOv8 export. Its declared preprocessing is Auto-Orient with EXIF-orientation
stripping and Stretch resize to 640x640. It declares three augmented versions
per source image: 50% horizontal flip, 50% vertical flip, equal-probability
90-degree choices (none, clockwise, counter-clockwise, upside-down), 0--15%
random crop, -10 to +10 degree random rotation, -2 to +2 degree horizontal and
vertical shear, -25 to +25% brightness, -15 to +15% exposure, Gaussian blur up
to 1.75 px, and salt-and-pepper noise on 2% of pixels. It does not declare
grayscale, hue/saturation, or cutout augmentation, so this audit does not infer
any of them.

That README declares 24,240 images. The receipt-bound archive inventory finds
24,233 image/label pairs: 21,203 train + 2,020 validation + 1,010 test =
24,233. The seven-image discrepancy has no inferred explanation; immutable
archive contents, rather than the provider-declared count, control the
registered evidence.

## Gaps before a releasable holdout exists

1. A person must review and approve the publisher's source-specific CC0 or
   public-domain evidence; a generic open-source or commercially usable license
   is not enough.
2. Each approved archive must be pinned to an immutable version, downloaded,
   hashed before extraction, and entered in `sources.json` with its actual
   SHA-256. The revision must be a full Git commit or a platform-specific
   numeric dataset version, never a branch or mutable tag.
3. Real photographs must be audited for fully visible, non-overlapping cards,
   52-class annotation provenance, and immutable visual deck identity.
   `visualDeckId` and `deckGroupId` must map one-to-one across archive
   partitions, so neither ID can be split to masquerade as a new deck.
4. Audit-only sources may remain explicitly roleless while the manifest is
   `BLOCKED_FOR_RELEASE`; they contribute no training or holdout evidence. A
   ready manifest requires every source to have a nonempty, evidence-backed
   role.
5. At least 200 qualifying real photos across at least 10 unseen visual deck designs
   must be reserved exclusively for the final holdout. No source crop,
   augmentation, or deck design from that reserve may enter training or
   validation.

No isolated-card archive is admitted. The registered Roboflow v4 archive is a
synthetic single-stage detector candidate only and contributes no real-photo
holdout evidence. The two reviewed classification candidates remain excluded
because their archive hashes and visual deck identities have not been verified;
their published item counts are observations, not usable counts.

## Policy note

The canonical [CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/)
explains the public-domain dedication and warns that Creative Commons does not
verify a work's copyright status. This engineering audit therefore fails closed;
it is not legal advice and it does not convert a platform license badge into
verified provenance.
