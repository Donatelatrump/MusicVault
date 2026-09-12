# MusicVault Android — shared layouts

## App shell

Source: `MusicVaultApp.kt:228:553`.

The root `Scaffold` uses a compact top header, animated status/clipboard notice, four persistent main sections, a mini-player above bottom navigation, full-screen detail overlays, and an animated full player. Main sections remain mounted for performance.

## Bottom navigation

Source: `MusicVaultApp.kt:3295:3374`.

```kotlin
@Composable
private fun BottomBar(section: AppSection, onSelect: (AppSection) -> Unit) {
    Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceBase)) {
        HorizontalDivider(color = VaultColors.Divider, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            BottomBarItem(AppSection.HOME, "INICIO", section, onSelect) { Icon(Icons.Default.Home, null) }
            BottomBarItem(AppSection.SEARCH, "BUSCAR", section, onSelect) { Icon(Icons.Default.Search, null) }
            BottomBarItem(AppSection.LIBRARY, "BIBLIOTECA", section, onSelect) { Icon(VaultIcons.LibraryMusic, null) }
            BottomBarItem(AppSection.PLAYLISTS, "ÁLBUMES", section, onSelect) { Icon(VaultIcons.QueueMusic, null) }
        }
    }
}
```

## Mini player

Source: `MusicVaultApp.kt:1693:1791`. Full-width surface above the navigation, 2 dp brass progress, 44 dp artwork, title/artist, time, favorite, and brass circular play/pause control.
