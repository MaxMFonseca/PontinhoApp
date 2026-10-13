# Pontinho single-stage card detector — debug candidate

Status: **DEBUG_ONLY / NOT PRODUCTION APPROVED**

This YOLOv8n detector recognizes the 52 standard playing cards in one offline
LiteRT pass. It is packaged only in the debug build. Manual review and manual
score entry remain mandatory fallbacks.

## Candidate

- Run: `playing-cards-v4-yolov8n-20261016`
- Training: 22 completed epochs, stopped with explicit user approval for the
  first debug candidate; best epoch 21
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
chosen for this first debug build because its complete validation had passed.

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

## Android evidence and remaining gate

The debug asset hash matches the evaluated model. Android unit tests, debug
androidTest compilation, offline dependency verification, debug/release builds
and the manual-only release-APK check pass. The licensed fixture test invokes
the actual LiteRT engine, but it was not executed because no Android device or
emulator was connected.

Production approval requires the unchecked physical-phone matrix in
`docs/manual-testing/2026-10-14-card-scanner-checklist.md`, including accuracy
and latency measurements. No 90%, 95%, 97%, Play Store, or production-readiness
claim is made from the public dataset result.
