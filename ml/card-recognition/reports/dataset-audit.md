# Card-recognition dataset audit

**Audit date:** 2026-10-15
**Decision:** `BLOCKED_FOR_RELEASE`

## Stop/go result

No dataset has yet satisfied every admission requirement at the same time: an
explicit CC0/public-domain statement from the publisher, an immutable source
revision, a SHA-256 computed from the downloaded archive before extraction,
annotation provenance, physical-deck grouping, and recorded human approval.
Consequently, `datasets/sources.json` intentionally contains no source entries.

| Release requirement | Audited and reserved | Minimum | Result |
|---|---:|---:|---|
| Real, public photographs held out from training | 0 | 200 | Blocked |
| Unseen physical deck groups in that holdout | 0 | 10 | Blocked |

Training may continue only as an internal experiment. Synthetic images and
isolated-card crops cannot satisfy the final holdout. No model produced from the
current manifest is eligible for promotion to the Android app.

## Candidate-source audit

The links below are direct publisher/source records inspected during the audit.
An `Excluded` result means no bytes from that candidate are admitted by the
manifest; it does not dispute the publisher's stated license.

| Candidate | Publisher evidence | Content observed | Admission result |
|---|---|---|---|
| Playing Cards Object Detection, dataset version 1 | [Roboflow source record](https://universe.roboflow.com/skypea/playing-cards-ow27d-9pnmd) labels it Public Domain and links the [CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/). | 10,100 synthetically generated scenes, 52 classes, bounding-box annotations. No real-photo holdout. | **Excluded.** The export archive was not acquired and hashed, no immutable export checksum is recorded, and physical-deck provenance is not documented. Even if admitted later, it contributes 0 release-holdout photos. |
| Cards Image Dataset—Classification, version 2 | [Kaggle source record](https://www.kaggle.com/datasets/gpiosenka/cards-image-datasetclassification) declares CC0 Public Domain; the Kaggle metadata inspected on 2026-10-15 identifies version 2, updated 2022-11-17. | 8,154 isolated/cropped images across 52 cards plus a joker; class labels only, no tabletop bounding boxes. | **Excluded.** The 431,789,634-byte archive was not acquired and hashed, deck-design identities are not documented, and isolated crops are not eligible real holdout scenes. |
| Playing Cards Labelized Dataset, version 2 | [Kaggle source record](https://www.kaggle.com/datasets/hugopaigneau/playing-cards-dataset) declares CC0 Public Domain; the Kaggle metadata inspected on 2026-10-15 identifies version 2, updated 2020-02-08. | Generated 1080×1080 card-hand composites with XML boxes for 52 classes; source card frames came from videos of cards. | **Excluded.** The 1,912,775,663-byte archive was not acquired and hashed, the published scenes are generated rather than real tabletop acceptance photos, and distinct physical deck groups are not documented. |
| Teodor Kostadinov “Real” dataset | The author's [README at immutable commit `6da0072`](https://github.com/TeogopK/Playing-Cards-Object-Detection/blob/6da007263c51cf5a44a3c06ea840030ff72ce678/README.md) says all included datasets are CC0 and says the 100 real images were photographed and labeled by the author. | 100 real images, bounding boxes, only 13 classes, apparently one capture source/deck; augmented derivatives are also present elsewhere in the repository. | **Excluded.** It is below the 200-photo minimum, cannot evidence 10 deck groups, does not cover 52 classes, has no separate license/dedication file, and no reviewed archive SHA-256 is recorded. |
| Photographic dataset: playing cards | The [versioned arXiv record](https://arxiv.org/abs/1701.07354v1) identifies a photographic dataset and its authors, but the record does not state CC0 or public-domain terms for the dataset. | Real playing-card scenes intended for image-processing research. | **Excluded.** No qualifying CC0/public-domain dedication was verified, and no archive revision, checksum, 52-class annotation set, or deck grouping was established. |

## Gaps before a releasable holdout exists

1. A person must review and approve the publisher's source-specific CC0 or
   public-domain evidence; a generic open-source or commercially usable license
   is not enough.
2. Each approved archive must be pinned to an immutable version, downloaded,
   hashed before extraction, and entered in `sources.json` with its actual
   SHA-256. The revision must be a full Git commit or a platform-specific
   numeric dataset version, never a branch or mutable tag.
3. Real photographs must be audited for fully visible, non-overlapping cards,
   52-class annotation provenance, and physical deck identity.
4. At least 200 qualifying real photos across at least 10 physical deck groups
   must be reserved exclusively for the final holdout. No source crop,
   augmentation, or deck design from that reserve may enter training or
   validation.

## Policy note

The canonical [CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/)
explains the public-domain dedication and warns that Creative Commons does not
verify a work's copyright status. This engineering audit therefore fails closed;
it is not legal advice and it does not convert a platform license badge into
verified provenance.
