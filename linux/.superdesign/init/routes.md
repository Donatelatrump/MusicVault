# MusicVault Android — navigation map

Single-activity Compose app; navigation is state-driven rather than URL-driven.

- `HOME` → `HomeScreen`: greeting, quick picks, recommended albums, discovery tracks.
- `SEARCH` → `SearchScreen`: one unified query field for text or Spotify URLs, albums and songs results.
- `LIBRARY` → `LibraryScreen`: downloaded/local music.
- `PLAYLISTS` → `PlaylistScreen`: user and imported playlists.
- Playlist overlay → `PlaylistDetail`: cover, metadata, play/shuffle/download-all, track list.
- Album overlays → `LocalAlbumDetail` and `RemoteAlbumDetail`.
- Full-player overlay → `FullPlayerScreen`: cover/vinyl, transport, progress, lyrics, queue and player styles.
- Settings overlay → `SettingsScreen`: playback, equalizer, storage, Spotify connection and appearance.
- Dialogs → Spotify login and playlist import review.

Root layout: `MusicVaultApp.kt:228:553`.
