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
- Owner-approved offline card recognition for up to ten visible, non-overlapping standard cards, with mandatory review/correction before save and manual scoring fallback.
- Localized, offline Privacy and licenses details covering local data, camera processing, model provenance, and AGPL source obligations.

The release source set contains the exact FP32 model tested and accepted by the
owner for the first release. Production metadata records this basis as
`OWNER_APPROVED_V1`; it does not claim a measured real-world accuracy rate.
The public dataset evidence and its limitations remain documented, and users
must review recognized cards before a score can be saved.

## Not implemented in this phase

- Advertising, consent management, Google Mobile Ads integration, billing, promotional-code entitlement, and Remove Ads.
- Backup export, restore validation, or database replacement.
- Release signing, store listing assets, Play Console declarations, or publication.
- A public source repository URL; the in-app legal screen intentionally retains the owner placeholder until that repository exists.

Unsigned release APK/AAB generation and strict offline/model/native-alignment
checks are implemented. Signing, public-source hosting, advertising, backup,
and store-publication work remain follow-up phases.
