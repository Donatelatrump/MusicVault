# MusicVault recommendations v5

Fixes a regression introduced in v4 where the onboarding recommendation batch was wrapped in one global timeout. If one selected artist was slow, `awaitAll()` was cancelled and the already-finished results for every other selected artist were discarded, leaving Home with generic cached recommendations.

Changes:
- Per-artist timeouts instead of one timeout around the whole batch.
- A failed artist can no longer erase successful artist buckets.
- Short merged-provider fallback only for the artist/category that failed.
- Artist onboarding buckets are strict: off-topic provider hits are not substituted for the selected artist.
- Selected-artist results remain round-robin balanced across the Home feed.
- If the artist song batch fails completely, the normal Home discovery pass is allowed to retry instead of considering the profile already fetched.
- Artist matching now ignores accents and punctuation, improving Tiësto/Tiesto, ROSALÍA/Rosalia, etc.
- Preference storage now preserves names containing commas (for example `Tyler, The Creator` and `Earth, Wind & Fire`) while still reading the old comma-separated format.
- Keeps the v4 search speed and album endpoint fixes.
