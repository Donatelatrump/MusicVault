# MusicVault para iOS

Port nativo de MusicVault (Android/Jetpack Compose) a **SwiftUI**, manteniendo la misma
identidad visual (superficies negro-cálido, acento latón, etiquetas mono) y el mismo
modelo mental: **un solo motor de reproducción con dos interfaces visuales
intercambiables** (Tornamesa de vinilo ↔ Visualizador circular).

## Abrir y ejecutar

**Requisitos:** Xcode 15 o superior, iOS 16+.

```bash
cd ios/MusicVault
open MusicVault.xcodeproj   # Xcode abre el proyecto con esquema "MusicVault"
# ⌘R en un simulador o dispositivo
```

- El proyecto `MusicVault.xcodeproj` ya incluye los dos targets (`MusicVault` app +
  `MusicVaultWidget` extension) y el `UIBackgroundModes: audio` necesario para que la
  reproducción continúe en segundo plano / pantalla bloqueada / auriculares.
- No hay dependencias externas (todo es SwiftUI, AVFoundation, MediaPlayer, SQLite3
  del sistema y WidgetKit).

## Arquitectura — mapeo Android → iOS

| Android | iOS | Notas |
|---|---|---|
| `MusicVaultApp.kt` (2663 líneas) | `MusicVaultApp.swift` + `Views/*` | Tabs personalizadas, mini player, full player overlay |
| `MusicVaultViewModel` (combine de 27 flows) | `LibraryStore` (@MainActor ObservableObject) | Estado de biblioteca/playlists/favoritos/búsqueda |
| `PlayerController` (Media3/MediaController) | `PlayerEngine` (AVPlayer) | Cola, ticker 0.5 s, seek, now playing |
| `MusicPlaybackService` (MediaSession) | `MPRemoteCommandCenter` + `MPNowPlayingInfoCenter` | Lock screen, auriculares, interrupciones |
| `VinylDisc` (Compose Canvas) | `VinylDiscView` (TimelineView + Canvas) | **Port 1:1**: brazo con ley de cosenos, fader RPM 16–78 con detentes 33/45/78, selector de velocidad, llaves decorativas, LED |
| `CircularVisualizer` | `CircularVisualizerView` (TimelineView + Canvas) | **Port 1:1**: 144 barras espejadas, graves lentos/anchos + anillo de kick, medios, agudos rápidos/cortos |
| Room (Daos/Entities) | `Database.swift` (SQLite3, actor) | Esquema espejo: tracks, playlists, playlist_tracks, favorites, play_history, search_history, catalog_search_cache |
| `DeezerCatalogProvider` / `ItunesCatalogProvider` | `CatalogService` (URLSession) | Mismos endpoints públicos, merge + caché 30 min + **MusicBrainz enrichment** |
| `LyricsProvider` (LRCLib) | `LyricsService` | Mismos quality gates (≥5 líneas sincronizadas / ≥80 caracteres) |
| Descargas yt-dlp | `LocalImporter` (fileImporter) | Ver diferencias abajo |
| SharedPreferences `player_style` | `@AppStorage("player_style")` | Persistente entre lanzamientos |
| Widget (AppWidget) | **WidgetKit target** | **Implementado** (small/medium, controles) |
| Equalizer nativo | **AVAudioEngine + AVAudioUnitEQ** | **Implementado** (10 bandas, archivos locales) |
| Fuentes | **Space Grotesk/Inter/Plex Mono** | **Empaquetadas en Resources/Fonts** (fallback sistema) |

## Diferencias de plataforma (importantes)

1. **Descargas (yt-dlp):** yt-dlp-android no existe en iOS. El equivalente funcional es:
   - **Importar** archivos de audio desde la app Archivos (se copian a `Documents/Music`).
   - **Streaming de previews de 30 s** desde Deezer/iTunes — la búsqueda es totalmente
     reproducible sin descargar nada (las filas lo indican con `PREVIEW 30s`).
2. **Ecualizador nativo:** Android usa `android.media.Equalizer`. En iOS se implementa
   con **AVAudioEngine + AVAudioUnitEQ** (10 bandas paramétricas) — **funciona en archivos
   locales**; previews de 30 s usan AVPlayer sin EQ.
3. **Crossfade:** implementado con doble `AVPlayer` + ramp de volumen (0–12 s, settings).
4. **Fuentes:** Space Grotesk / Inter / Plex Mono **empaquetadas en `Resources/Fonts`** —
   se cargan dinámicamente; fallback a fuentes de sistema si no están.
5. **Widget:** **WidgetKit target** incluido — small/medium con portada, título, artista,
   play/pause/prev/next.
5. **MusicBrainz:** proveedor de metadatos (MBIDs, release-groups, Cover Art Archive) —
   enriquece resultados de Deezer/iTunes con IDs autoritarios y portadas.

## Estructura

```
ios/MusicVault/
├── MusicVault.xcodeproj/         # Proyecto Xcode (app + widget, ⌘R directo)
├── MusicVault/
│   ├── MusicVaultApp.swift       # @main, tabs, mini player, overlays, environment
│   ├── Theme.swift               # VaultColors, estilos, gradientes, custom fonts loader
│   ├── Models.swift              # Track/Playlist/CatalogTrack/Lyrics + helpers
│   ├── Database.swift            # SQLite3 actor (esquema espejo de Room)
│   ├── LibraryStore.swift        # Estado global (mirror del ViewModel) + EQ integration
│   ├── PlayerEngine.swift        # AVPlayer + shuffle/repeat/crossfade/queue/remote/now-playing
│   ├── EqualizerController.swift # AVAudioEngine + AVAudioUnitEQ (10-band EQ)
│   ├── CatalogService.swift      # Deezer + iTunes + MusicBrainz + caché
│   ├── LyricsService.swift       # LRCLib exact/search + parser LRC
│   ├── LocalImporter.swift       # fileImporter → Documents/Music (+duración AVAsset)
│   ├── Common.swift              # Covers, TrackRow, actions sheet, pickers, banners
│   ├── HomeView.swift            # Saludo, Para ti, Favoritos, Importadas
│   ├── SearchView.swift          # Debounce 350 ms, álbumes + tracks, recientes
│   ├── LibraryView.swift         # Favoritos + Importaciones + importar
│   ├── AlbumsView.swift          # Playlists + Álbumes personalizados + detalle + crear
│   ├── FullPlayerView.swift      # Metadatos, seek, transporte (shuffle/repeat), letras, cola
│   ├── VinylDiscView.swift       # Tornamesa completa + fader RPM
│   ├── CircularVisualizerView.swift
│   ├── MiniPlayerView.swift
│   ├── SettingsView.swift        # Estilo + RPM + Crossfade + EQ 10-band + Import + About
│   ├── MusicBrainzProvider.swift # MB metadata + Cover Art Archive
│   ├── Info.plist                # UIBackgroundModes: audio, URL scheme musicvault://
│   ├── MusicVault.entitlements   # App Sandbox + user-selected files + MusicKit
│   ├── Assets.xcassets/          # AppIcon + AccentColor
│   └── Preview Content/
│       └── Preview Assets.xcassets/
├── MusicVaultWidget/
│   ├── MusicVaultWidget.swift    # WidgetKit small/medium con controles
│   ├── Info.plist
│   └── Assets.xcassets/          # AccentColor + WidgetBackground
├── README.md
└── PARITY_REPORT.md              # Matriz exhaustiva de paridad Android ↔ iOS
```

## Notas de implementación

- **Sin writes de estado por frame:** igual que la versión Compose, ambos skins derivan
  la animación de `TimelineView` (rebase de ángulo al pausar/cambiar RPM; energía del
  visualizador por smoothstep) — invalidación de dibujo, no de recomposición.
- **Catálogo → favoritos/listas:** al favoritar o añadir un track del catálogo se
  materializa como fila en `tracks` (insert-if-absent), igual que los placeholders
  `provider:recording:` de Android.
- **Historial:** se registra un `play_history` por reproducción; ratio de completado
  cada 10 s vía `engine.onProgress` → `store.updateCompletion`.
- **Ecualizador:** `EqualizerController` (AVAudioEngine + AVAudioUnitEQ, 10 bandas
  32–16k Hz) — **solo archivos locales**; previews usan AVPlayer sin EQ.
- **MusicBrainz:** búsqueda de grabaciones + release-groups + Cover Art Archive
  (portadas 500px) — enriquece resultados de Deezer/iTunes con MBIDs autoritarios.
- **WidgetKit:** target `MusicVaultWidget` (small/medium) con portada, título, artista,
  play/pause/prev/next via `Link` → deep links `musicvault://`.
- El código no compila en Windows: revisar en Xcode/macOS. Las APIs usadas son estándar
  (SwiftUI 4/5, AVFoundation, MediaPlayer, SQLite3 del sistema).

## Diferencias de plataforma (resumen)

| Android | iOS | Mitigación |
|---|---|---|
| Descargas yt-dlp | Importar + previews 30 s | ✅ Funcional |
| Equalizer nativo | AVAudioEngine + AVAudioUnitEQ (local) | ✅ Implementado |
| Widget | WidgetKit target | ✅ Implementado |
| Fuentes Space Grotesk/Inter/Plex Mono | Empaquetadas en Resources/Fonts | ✅ Empaquetadas |
| MusicBrainz audio | Solo metadatos → enriquecimiento | Deezer/iTunes dan audio |

## Cómo compilar

```bash
cd ios/MusicVault
open MusicVault.xcodeproj   # Xcode 15+ abre directo
# ⌘R en simulador o dispositivo físico
```

`UIBackgroundModes: audio` ya está en `MusicVault/Info.plist` — la reproducción
continúa en segundo plano, pantalla bloqueada y Bluetooth automáticamente.