# Pontinho single-stage card detector — first release model

Status: **OWNER_APPROVED_V1**

This YOLOv8n detector recognizes the 52 standard playing cards in one offline
LiteRT pass. The exact evaluated FP32 bytes are packaged in debug and release.
Manual review and manual score entry remain mandatory fallbacks.

## Candidate

- Run: `playing-cards-v4-yolov8n-20261016`
- Training: 22 completed epochs, stopped with explicit user approval; best epoch 21
- Best checkpoint SHA-256:
  `695e860b6dc323ce12f582da2827ce2ed63b4a7279e5267474c5535a433c4712`
- Export: FP32 TFLite, input `(1,640,640,3)`, raw output `(1,56,8400)`
- Model SHA-256:
  `01034db88037605250fd3d78b49ee791a7711b218305e44ebe4ff3c02e2ad277`
- Model size: 12,838,331 bytes
- Confidence / IoU: `0.65` / `0.30`
- Maximum accepted cards: 10

INT8 was exported and structurally inspected, but its full validation was
stopped because it was taking hours. It was not selected or packaged. FP32 was
chosen for the first release because its complete public-dataset validation had
passed and the owner approved its behavior in the app.

## Measured public-dataset evidence

The dataset is the registered Roboflow Playing Cards v4 YOLOv8 archive. These
images are public/synthetic dataset evidence, not photographs from the owner's
phone and not proof across unseen physical deck designs.

- Validation: 2,020 scenes / 8,080 cards; precision, recall, exact detection,
  exact multiset and exact total all `1.0`; 0 false positives, 0 false
  negatives and 0 abstentions.
- One-use test holdout: 1,010 scenes / 4,040 cards; the same five metrics are
  `1.0`; 0 false positives, 0 false negatives and 0 abstentions.
- Holdout receipt SHA-256:
  `d0c3013d220413e2631d66c745bfcabee7194ae620c1cc62620eb4a4d33f5a4c`

Every validation/test scene contains exactly four non-overlapping cards. The
dataset therefore does not establish real-phone accuracy for 1, 3, 5, 8 or 10
cards, difficult lighting, blur, glare, perspective, partial cards, or unseen
deck artwork.

## Approval basis and Android evidence

The owner performed in-app acceptance testing and approved this exact model for
the first release. The machine-readable receipt is
`ml/card-recognition/reports/release/owner-acceptance-v1.json`. That decision is
an owner acceptance, not a substitute for a real-world accuracy benchmark.

Automated checks bind the main-source, APK, and AAB model bytes and production
metadata; reject corrupt or mismatched assets; keep camera hardware optional;
and verify offline dependencies plus 16 KB zip/ELF alignment. The historical
`NOT_APPROVED` benchmark report remains preserved because its stronger
200-photo/10-unseen-deck matrix was not completed. No measured real-phone
accuracy percentage or cross-deck benchmark claim is made from the public
dataset result.
