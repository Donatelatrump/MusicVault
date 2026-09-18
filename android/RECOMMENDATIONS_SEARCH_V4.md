# MusicVault — recommendations/search v4

## Fixed

- First-run handoff now builds the selected-artist feed first instead of blocking on genre enrichment.
- Home artwork is prefetched at the exact sizes/cache keys used by the first recommendation rails before onboarding disappears.
- The normal discovery collector now uses the same canonical taste-profile signature as onboarding, preventing the same artist batch from being fetched twice after preferences are saved.
- Search publishes songs and albums independently instead of making the fast result wait for both categories.
- Slow multi-provider refinement only runs when the primary result is empty/suspiciously small.
- Deezer album search now uses the dedicated `/search/album` endpoint.
- Deezer album detail parsing now reads `tracks.data`, fixing albums that previously looked empty/unreachable.
- Removed album reachability probing from first-paint search; album tracks are validated when the album is opened.
- Empty-result messages now use wrap-content height and are no longer clipped to a 42dp row.
- Deezer connection/read timeouts were reduced so a dead endpoint cannot stall the UI for 12–15 seconds.

## Expected behavior

After finishing artist selection, the loading animation remains on top while the selected artists, recommended albums, first Home adapter state and the first artwork are prepared. Genre enrichment can continue afterward without blocking the reveal. Normal searches should show Deezer song/album results as soon as each category returns; for an artist query such as `marshmello`, the Albumes tab should receive actual album rows instead of an empty/clipped message.
