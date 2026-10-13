# Pontinho development status

## Implemented in the manual offline core

- Native Android application shell for `com.maxmfonseca.pontinho`.
- Offline match creation with reusable player identities, editable names, removal from the setup draft, and accessible drag/reorder alternatives.
- Manual scoring with direct wildcard selection, explicit zero, `Bateu a rodada`, automatic winner inference, dealer rotation, and the strict greater-than-100 finish rule.
- Between-round player addition with zero starting total, historical gray-minus nonparticipation, and next-round eligibility.
- In-context historical score correction, including reopening and resuming a later round when required.
- Room persistence for active and completed matches, result acknowledgement, daily history, wins, and accumulated penalty summaries.
- Match results that rank lower penalties first and distinguish winners from the highest penalty.
- Brazilian Portuguese fallback and complete US English resources for every implemented surface.
- Light/dark themes, reduced-motion preference, large-text layouts, localized screen-reader semantics, and non-drag reorder actions.
- Offline Learn to Play, Settings, Language, Appearance and Accessibility, and About surfaces.

The debug build is ready for hands-on testing of manual scoring and the
experimental offline single-stage scanner. Its FP32 model is measured against
the public dataset and remains debug-only pending physical-phone acceptance.

## Not implemented in this phase

- Production-approved card recognition. Camera/photo/review flows exist in
  debug, but physical-deck accuracy and device latency are still unmeasured.
- Advertising, consent management, Google Mobile Ads integration, billing, promotional-code entitlement, and Remove Ads.
- Backup export, restore validation, or database replacement.
- Release signing, store listing assets, Play Console declarations, or publication.

The scanner model is intentionally absent from the release APK. Advertising,
backup and store-publication work remain follow-up plans.
