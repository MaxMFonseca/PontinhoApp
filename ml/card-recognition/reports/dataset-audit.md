# Card-recognition dataset audit

**Audit date:** 2026-10-15
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

No isolated-card archive is admitted. The two reviewed classification candidates
remain excluded because their archive hashes and visual deck identities have not
been verified; their published item counts are observations, not usable counts.

## Policy note

The canonical [CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/)
explains the public-domain dedication and warns that Creative Commons does not
verify a work's copyright status. This engineering audit therefore fails closed;
it is not legal advice and it does not convert a platform license badge into
verified provenance.
