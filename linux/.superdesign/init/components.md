# MusicVault Android — shared UI components

Source root: `/home/donatelatrump/Desktop/MusicVault/android/app/src/main/java/com/musicvault/app/ui/`.

The Android UI is Jetpack Compose with a few RecyclerView-backed feeds. The reusable visual primitives are co-located in `MusicVaultApp.kt`; for generation, the complete source is supplied directly through line-ranged context files so it remains the source of truth.

## SpotifyClipboardNotice

Source: `MusicVaultApp.kt:172:227`. Compact actionable notice for a valid Spotify track or playlist copied to the clipboard. It has an icon medallion, two-line explanation, Import action, and dismiss action.

```kotlin
@Composable
private fun SpotifyClipboardNotice(link: SpotifyLink, onImport: () -> Unit, onDismiss: () -> Unit) {
    val kind = if (link.kind == SpotifyLinkKind.PLAYLIST) "Playlist" else "Canción"
    Surface(
        color = VaultColors.Surface1,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, VaultColors.Accent.copy(alpha = 0.45f)),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(VaultColors.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(VaultIcons.LibraryMusic, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("$kind de Spotify detectada", color = VaultColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Enlace válido en el portapapeles", color = VaultColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
            TextButton(onClick = onImport) { Text("Importar", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Ignorar enlace") }
        }
    }
}
```

## AlbumCard / PlaylistCard / TrackRow

Sources: `MusicVaultApp.kt:3380:3560`. Shared catalog cards and track row patterns. Album artwork uses 10–12 dp corners, a 1 dp hairline, compact two-line metadata, and optional download/play controls. Complete implementations are supplied as direct source context.

## Theme and icons

Complete sources: `ui/theme/Theme.kt` and `ui/theme/VaultIcons.kt`.
