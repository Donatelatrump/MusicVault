# MusicVault v12 — playlist picker

- Replaced the create-playlist Compose LazyColumn with a fixed-height native RecyclerView picker.
- Stable ids, native row recycling, item prefetch, cache pool, no item animator.
- Added local search by song title or artist inside the playlist dialog.
- Selection survives search/filter changes.
- New playlists use the first selected track cover immediately.
- Adding the first song later also fills an empty playlist cover without overwriting imported covers.
- Playlist delete affordance is now a white trash-can icon.
- versionCode 14 / versionName 0.5.3.
