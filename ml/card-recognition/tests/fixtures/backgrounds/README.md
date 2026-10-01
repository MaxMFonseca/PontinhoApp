# Synthetic background fixtures

The compositor tests intentionally do not include downloaded photographs. They create
procedural tabletop pixels at runtime so no unreviewed or ambiguously licensed background can
enter the repository. Production generation uses the same deterministic procedural background
family and records its generator identifier, seed, and CC0 dedication in every scene manifest.

Adding a photographic fixture here requires the source URL, archive checksum, version/date,
deck-independent source identity, and verified CC0 or public-domain evidence before the pixels
are committed.
