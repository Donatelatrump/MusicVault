# MusicVault Android — page dependency trees

## App shell and Home

- `ui/MusicVaultApp.kt:228:945`
  - `ui/FastFeedAdapters.kt:1:320`
  - `ui/theme/Theme.kt`
  - `ui/theme/VaultIcons.kt`
  - `ui/MusicVaultViewModel.kt`
  - `data/Track.kt`, `data/Album.kt`, `data/Playlist.kt`

## Search

- `ui/MusicVaultApp.kt:946:1270`
  - `ui/search/RemoteResultsAdapter.kt`
  - `ui/theme/Theme.kt`
  - `ui/theme/VaultIcons.kt`

## Library and playlists

- `ui/MusicVaultApp.kt:1271:1693`
  - `ui/FastFeedAdapters.kt:321:820`
  - `ui/theme/Theme.kt`

## Player and settings

- `ui/MusicVaultApp.kt:1694:3288`
  - `ui/theme/Theme.kt`
  - `ui/theme/VaultIcons.kt`
