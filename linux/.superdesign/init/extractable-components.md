# Extractable components

The target is a native Jetpack Compose Android application. Superdesign's reusable Petite-Vue component extraction is not source-compatible with Compose, so no layout component is uploaded as an HTML `DraftComponent`. The source patterns below are supplied directly as generation context instead.

- `BottomBar` — layout; `MusicVaultApp.kt:3295:3374`; active section state.
- `MiniPlayer` — layout; `MusicVaultApp.kt:1693:1791`; track, playback and favorite state.
- `SpotifyClipboardNotice` — basic; `MusicVaultApp.kt:172:227`; track/playlist kind and actions.
- `AlbumCard` — basic; `MusicVaultApp.kt:3380:3431`; cover and optional download state.
- `TrackRow` — basic; `MusicVaultApp.kt:3469:3560`; playback and download state.
