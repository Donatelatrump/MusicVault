# MusicVault v13 — album detail performance

- Remote/recommended album detail no longer renders every song as a Compose row.
- The song list now uses the same native RecyclerView pipeline as Search: stable IDs, DiffUtil/payload updates, fixed rows, view recycling, prefetch, and no item animator.
- Playback/download progress updates only rebind affected native rows instead of recomposing the whole album.
- Album artwork decode target reduced for the detail header.
- Remote album requests are cancellable; stale responses from a previously opened album are ignored.
- The last 12 remote album track lists are cached in memory so reopening an album is instant and avoids unnecessary network work.
- Version: 0.5.4 (versionCode 15).
