# MusicVault — documentación consolidada

> Archivo generado al consolidar todos los documentos Markdown del proyecto V25.1. Cada sección conserva la ruta del archivo original.


---

## Archivo original: `README.md`

# MusicVault 🎵

MusicVault es un reproductor musical local para Android enfocado en crear una biblioteca personal de música sin depender de servicios de streaming.

La aplicación permite organizar, reproducir y gestionar música almacenada en el dispositivo mediante una interfaz moderna inspirada en reproductores actuales, manteniendo los archivos bajo control del usuario.

---

## Características principales

- Reproducción de música local.
- Reproductor en segundo plano mediante Media3.
- Cola de reproducción editable.
- Mini reproductor y reproductor a pantalla completa.
- Favoritos persistentes.
- Playlists y álbumes personalizados.
- Escaneo automático de archivos de audio del dispositivo.
- Búsqueda de información musical.
- Portadas automáticas.
- Ecualizador nativo de Android.
- Crossfade configurable.
- Descarga de canciones con progreso individual.
- Cuenta de Spotify protegida con Android Keystore e importación de canciones y playlists mediante el lector integrado de SongMirror.
- Detección de enlaces de Spotify copiados mientras MusicVault está en primer plano.

---

## Tecnologías utilizadas

MusicVault está desarrollado utilizando tecnologías modernas del ecosistema Android:

- Kotlin
- Jetpack Compose
- Material 3
- Room Database
- AndroidX Media3
- MediaSessionService
- Coroutines
- FFmpeg
- yt-dlp mediante youtubedl-android

---

# Arquitectura del proyecto


app/src/main/java/com/musicvault/app/

├── catalog/
│ ├── MusicBrainzCatalogProvider.kt
│ ├── CoverArt.kt
│ └── CatalogRepository.kt
│
├── data/
│ ├── MusicDatabase.kt
│ ├── Daos.kt
│ ├── Entities.kt
│ └── Repository.kt
│
├── providers/
│ └── Providers.kt
│
├── player/
│ ├── MusicPlaybackService.kt
│ └── PlayerController.kt
│
├── library/
│ └── LocalAudioScanner.kt
│
└── ui/
├── MusicVaultApp.kt
└── MusicVaultViewModel.kt


---

# Metadata musical

MusicVault utiliza diferentes fuentes para completar información de canciones y álbumes:

- **MusicBrainz** para información musical.
- **Cover Art Archive** para portadas.
- **iTunes Search** y **Deezer** como fuentes alternativas.

El sistema utiliza un modelo de proveedores con fallback para evitar depender de una única fuente.

Si un proveedor no devuelve resultados, automáticamente se intenta con otra fuente disponible.

---

# Descargas

El sistema de descarga está separado del resto de la aplicación mediante:


providers/Providers.kt


Esto permite reemplazar el proveedor de descarga sin modificar la lógica principal del reproductor.

Flujo general:


Usuario solicita descarga
↓
MusicVaultViewModel
↓
Provider de descarga
↓
yt-dlp + FFmpeg
↓
Archivo local
↓
Registro en Room
↓
Disponible en biblioteca


---

# Reproductor

MusicVault utiliza AndroidX Media3 para manejar:

- Reproducción en segundo plano.
- Controles multimedia del sistema.
- Cola de reproducción.
- Seek.
- Shuffle.
- Reproducción siguiente/anterior.
- Estado persistente del reproductor.

---

# Versión 0.5.0

Cambios principales:

- Nueva pantalla de ajustes.
- Crossfade configurable y persistente.
- Eliminación del control de crossfade del reproductor principal.
- Ecualizador nativo conectado a la sesión de audio Media3.
- Favoritos almacenados mediante Room.
- Cola de reproducción visible desde la interfaz.
- Reordenamiento y eliminación de canciones en la cola.

---

# Compilar el proyecto

## Requisitos

- Android Studio reciente.
- JDK 17.
- Android SDK Platform 35.
- Android Build Tools 34 o superior.

Abre esta carpeta `android/` en Android Studio. Para compilar desde la raíz del repositorio:

```bash
cd android
./gradlew assembleDebug
```


---

## Archivo original: `ALBUM_DETAIL_V13.md`

# MusicVault v13 — album detail performance

- Remote/recommended album detail no longer renders every song as a Compose row.
- The song list now uses the same native RecyclerView pipeline as Search: stable IDs, DiffUtil/payload updates, fixed rows, view recycling, prefetch, and no item animator.
- Playback/download progress updates only rebind affected native rows instead of recomposing the whole album.
- Album artwork decode target reduced for the detail header.
- Remote album requests are cancellable; stale responses from a previously opened album are ignored.
- The last 12 remote album track lists are cached in memory so reopening an album is instant and avoids unnecessary network work.
- Version: 0.5.4 (versionCode 15).


---

## Archivo original: `ONBOARDING_V10_SCROLL_ANDROID.md`

# MusicVault v10 — artist scrolling + Android compatibility

## Artist picker performance
- Replaced the LazyColumn-of-Rows pattern with a native `LazyVerticalGrid`.
- Each artist has its own stable key and reusable `contentType`.
- Artist cells now use a fixed 64dp height, which avoids repeated measuring during fast flings.
- Removed per-card color animations while scrolling/selection changes.
- The title/search controls stay outside the scrolling grid, so only the artist cells participate in a fling.
- Reduced card visual complexity while preserving the MusicVault visual language.

## Android compatibility
- `compileSdk`: 35 -> 36
- `targetSdk`: 35 -> 36
- Android Gradle Plugin: 8.7.3 -> 8.10.1
- Gradle wrapper: 9.3.0 -> 8.11.1 (officially compatible with AGP 8.10)
- Disabled Gradle configuration cache because this project previously hit a corrupted AGP service cache.

API 36 is the current required target for new/updated phone apps submitted to Google Play as of Aug 31, 2026, and avoids treating MusicVault as an old-target app on current Android versions without opting into Android 17/API 37 behavior changes yet.


---

## Archivo original: `ONBOARDING_V2.md`

# MusicVault onboarding v2

Nuevo flujo de primera ejecución:

1. Pantalla completa de géneros: selección obligatoria de al menos un género, con más variantes.
2. Pantalla completa de artistas: las sugerencias cambian según los géneros elegidos; se pueden seleccionar varios o añadir uno manualmente.
3. Handoff animado: al confirmar artistas se guardan las preferencias de inmediato, Home se monta detrás y el ViewModel empieza a buscar/reordenar recomendaciones.
4. La animación permanece un mínimo de ~2.3 s y espera hasta ~3.5 s adicionales por recomendaciones reales antes de liberar la pantalla; nunca bloquea indefinidamente si no hay red.
5. Los permisos de Android se solicitan después del handoff, no encima del onboarding.

También se restauró el APK universal ARM práctico: Gradle genera arm64-v8a, armeabi-v7a y un `app-universal-*` que combina solo esas dos arquitecturas (sin x86/x86_64 de FFmpeg).

Compilar:

```bash
./gradlew :app:compileDebugKotlin -q --console=plain
./gradlew assembleDebug
```

APK universal esperado:

`app/build/outputs/apk/debug/app-universal-debug.apk`


---

## Archivo original: `ONBOARDING_V9_LOCAL_ARTISTS.md`

# Onboarding v9 — catálogo local de artistas

- El listado de artistas por género es local y no depende de red.
- Las sugerencias para la pantalla 2 se calculan mientras el usuario todavía está en la pantalla de géneros.
- La pantalla de artistas usa LazyColumn, por lo que solo compone las tarjetas visibles y no las 36 de golpe.
- Se redujo la duración de la transición entre Géneros -> Artistas para que se perciba inmediata.
- Las búsquedas reales de canciones/álbumes siguen ocurriendo únicamente después de que el usuario confirma sus artistas.


---

## Archivo original: `PERFORMANCE_DOWNLOAD_UI_V14.md`

# MusicVault v14 — descarga unificada + listas optimizadas

- Búsqueda: reemplazado el spinner circular de puntos por la misma barra horizontal delgada de progreso usada en las filas nativas de Inicio/Biblioteca.
- Estados de descarga Compose heredados: unificados a barras lineales delgadas, determinadas cuando existe porcentaje e indeterminadas para descargas por lote.
- Eliminado el uso de `LazyColumn` del código de la app.
- Importación de Spotify: lista de canciones migrada a `RecyclerView` nativo de altura fija y reciclado real.
- Letras sincronizadas: migradas a `RecyclerView` nativo, conservando foco de estrofa, opacidad/escala/desenfoque y auto-scroll.
- Cola de reproducción: migrada a `RecyclerView` nativo.
- Ajustes y selectores pequeños: usan `Column + verticalScroll`, evitando crear otra lista lazy para colecciones reducidas.
- Version: 0.5.5 (versionCode 16).


---

## Archivo original: `PERFORMANCE_NOTES.md`

# MusicVault 0.5.1 - scroll performance pass

This build applies a deeper Jetpack Compose performance pass focused on search-result scrolling.

## Changes

- Compose BOM remains **2024.12.01** (Compose 1.7.x). A planned bump to the 2025.11.01 line was *not* applied in this build.
- Enabled Compose stability configuration for Kotlin `List`, `Set`, and `Map`.
- Marked immutable UI/catalog models with `@Immutable` so Compose can skip unchanged rows more aggressively.
- Search screen no longer receives the entire `MusicUiState`; it receives only the search data it needs.
- Search is a native **RecyclerView** (`RemoteResultsAdapter`), not a Compose `LazyColumn`; row virtualization and caching come from RecyclerView (`setItemViewCacheSize`, recycled-view pools, prefetch). A `LazyLayoutCacheWindow` therefore does **not** apply here.
- Kept stable keys and explicit `contentType` values for albums/tracks.
- Reduced thumbnail decode target to 56px for 50–52dp result thumbnails.
- Reduced cover memory cache from 20% to 15% to reduce GC pressure on lower-memory devices.
- Album result rows no longer draw a gradient underneath a fully opaque cover.
- Album titles are single-line/ellipsized to keep row measurement stable.
- Search debounce remains exactly 1.5 seconds.
- Download progress remains visible; it is observed only by the search screen rather than the application root.

## Validation

The archive was checked for ZIP integrity and Kotlin source files were checked for balanced braces/parentheses and package/import ordering.

A full Gradle build could not be executed in this environment because the Gradle 9.3.0 distribution and Android dependencies are not cached locally and external Gradle downloads are unavailable here.


---

## Archivo original: `PLAYLIST_CREATE_BUTTON_FIX.md`

# Fix: botón Crear playlist visible

- El botón no había sido eliminado: la lista de canciones de 304 dp + buscador hacía que el contenido del diálogo superara la altura visible en algunos dispositivos.
- `VaultModalDialog` ahora queda limitado al alto real disponible.
- El encabezado y la barra inferior de acciones permanecen fijos.
- Solo el contenido central del modal se desplaza cuando falta espacio (por pantalla pequeña o teclado/IME).
- Se conserva el `RecyclerView` nativo del selector y su búsqueda/selección.


---

## Archivo original: `PLAYLIST_IMPORT_RECOVERY_V15.md`

# Playlist import/download recovery V15

- Playlist rows that are still present but not downloaded no longer report that they disappeared from the playlist.
- Long-pressing a pending/non-downloaded track inside a playlist opens the option to remove it from that playlist.
- Downloaded tracks expose a separate `Quitar de esta playlist` action, distinct from deleting the downloaded file.
- Playlist playback keeps the complete playlist visible while only playable/downloaded tracks are sent to the player queue.
- Large playlist downloads are bounded to 2 concurrent tracks instead of starting every track at once.
- Transient download failures are retried up to 3 times with a short backoff; permanently unavailable media is not repeatedly retried.
- Intermediate retry errors are suppressed and the user receives one batch summary at the end.
- Tracks whose `isDownloaded` flag is inconsistent with an empty URI are treated as pending rather than as playable.


---

## Archivo original: `PLAYLIST_PICKER_V12.md`

# MusicVault v12 — playlist picker

- Replaced the create-playlist Compose LazyColumn with a fixed-height native RecyclerView picker.
- Stable ids, native row recycling, item prefetch, cache pool, no item animator.
- Added local search by song title or artist inside the playlist dialog.
- Selection survives search/filter changes.
- New playlists use the first selected track cover immediately.
- Adding the first song later also fills an empty playlist cover without overwriting imported covers.
- Playlist delete affordance is now a white trash-can icon.
- versionCode 14 / versionName 0.5.3.


---

## Archivo original: `PLAYLIST_SCROLL_OPEN_FIX_V16.md`

# Playlist detail scroll fix — V16

- Playlist detail now always opens at the header/top (RecyclerView position 0).
- The reset is keyed to `playlist.id`, so switching/opening a playlist cannot inherit another playlist's scroll anchor.
- RecyclerView hierarchy-state restoration is disabled for this transient detail view to prevent reopening near the previous/bottom position.
- The reset runs only once per playlist opening; normal user scrolling and download/playback UI updates do not force the list back to the top.


---

## Archivo original: `RECOMMENDATIONS_SEARCH_V3.md`

# MusicVault — recomendaciones y búsqueda v3

Cambios principales:

- El onboarding precarga recomendaciones inmediatamente al confirmar artistas.
- Las consultas de artistas/géneros se ejecutan en paralelo durante la animación.
- Las canciones y álbumes de artistas seleccionados se intercalan round-robin para que el límite del feed no quede monopolizado por un solo artista.
- La animación espera al lote completo y a que Inicio tenga canciones + álbumes (con timeout offline).
- Inicio, Buscar, Biblioteca y Álbumes permanecen montados y sus adapters pueden precalentarse bajo la animación.
- Buscar ahora muestra un selector segmentado `Canciones | Álbumes` solamente después de enviar una búsqueda, justo debajo del buscador, con conteos por tipo.
- Se corrigió el layout anterior de los tabs, donde ambos elementos usaban `fillMaxWidth()` dentro del mismo Row.

Validación local sugerida:

```bash
./gradlew :app:compileDebugKotlin -q --console=plain 2>&1 | tail -40
./gradlew clean assembleDebug
```


---

## Archivo original: `RECOMMENDATIONS_SEARCH_V4.md`

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


---

## Archivo original: `RECOMMENDATIONS_V5_FIX.md`

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


---

## Archivo original: `RECOMMENDATIONS_V6_VISUAL_HANDOFF.md`

# MusicVault v6 — visual onboarding handoff

- The recommendation animation now remains above the app until Home has the final recommendation data **and** the Home RecyclerView has committed those rows and reached a real pre-draw.
- Home artwork is pre-warmed with the exact Coil cache keys/sizes used by the UI before the handoff: up to 8 track covers and 6 album covers.
- Artwork warmup has a longer bounded window (6.5 s) so normal mobile networks can finish without revealing a half-painted Home.
- The final overlay fade has only a tiny 120 ms frame cushion instead of an arbitrary long delay.
- There is still an 18 s safety timeout so an offline/broken image host cannot trap a new user forever.
- Existing Home content can satisfy the visual-ready callback even if no new DiffUtil update is needed.

The important distinction is that this waits for the content visible in the initial Home experience, not every off-screen recommendation in the full feed. Waiting for dozens of off-screen covers would only make onboarding slower without improving the first frame the user sees.


---

## Archivo original: `SEARCH_V7_FIX.md`

# Search v7 fix

- Song search no longer races Deezer against yt-dlp for ordinary music queries.
  A good Deezer result is returned immediately; YouTube is only used as a fallback.
- This fixes the case where albums appeared quickly but songs timed out and ended in
  "No encontramos canciones" even though Deezer had already answered.
- Merged track search also stops after a healthy primary-provider page instead of
  blocking on secondary enrichment.
- Search song rows now have a fixed 84dp height and a fixed 48dp action column, so
  Play/Pause buttons form a straight aligned column regardless of metadata length.
- The optional description is limited to one line in search rows to preserve that
  deterministic row height.


---

## Archivo original: `SEARCH_YOUTUBE_FALLBACK_V17.md`

# Search YouTube fallback v17

Correcciones del flujo Buscar:

- Deezer conserva la ruta rápida para búsquedas musicales normales.
- Consultas de contenido como `ASMR`, podcast, audiolibro, entrevista, charla o conferencia priorizan YouTube.
- Si Deezer no devuelve canciones útiles, YouTube se ejecuta antes de mostrar "No encontramos canciones".
- La primera página de YouTube se limita a 12 resultados para reducir la latencia de yt-dlp.
- YouTube tiene un timeout propio, mayor que el de las APIs HTTP rápidas.
- El buscador garantiza que el runtime de yt-dlp haya terminado de inicializarse antes de ejecutar la búsqueda.
- Se reintenta una vez un fallo/resultado vacío de yt-dlp.
- Los resultados vacíos de YouTube ya no se guardan en memoria como caché permanente de la sesión.

La búsqueda normal que obtiene buenos resultados de Deezer no espera a YouTube.


---

## Archivo original: `STREAMING_CACHE_V18.md`

# MusicVault Android — Streaming + caché temporal V18

## Reproducción
- Tocar cualquier canción reproduce: archivo local si existe; streaming si no está descargada.
- Las URLs directas de streaming se resuelven bajo demanda con yt-dlp y no se persisten en Room.
- Media3/ExoPlayer usa una clave de caché estable basada en Track.id.

## Caché temporal
- Máximo: 1 GiB (LRU).
- TTL: 24 horas desde el último uso.
- Las canciones completamente cacheadas pueden reproducirse sin volver a resolver la URL remota.
- El caché no aparece como descarga offline ni como archivo de Biblioteca.

## Promoción a descarga offline
- Si el caché está completo, se copia directamente al destino permanente.
- Si el caché está parcial, CacheWriter reutiliza los rangos existentes y obtiene únicamente los bytes faltantes antes de materializar el archivo.
- Si la ruta cache-first falla por URL/CDN, se conserva el downloader yt-dlp existente como fallback.

## UI de canciones
- Se elimina el botón Play por canción.
- Toda la fila/tarjeta es el target de reproducción.
- El menú de tres puntos está disponible para canciones locales y online.
- Online: Descargar / Cancelar descarga.
- Local: Eliminar canción descargada.
- Agregar a playlist/álbum ya no descarga silenciosamente la canción.
- Agregar a cola una canción online usa streaming, no descarga permanente.

## Verificación realizada
- Revisión estática de los puntos de entrada de Search, Home, Library y Playlist.
- Comprobación sintáctica Kotlin sin errores de parser en los archivos modificados.
- La compilación Gradle completa no pudo ejecutarse en este entorno porque no está disponible la distribución Gradle 8.11.1 y el entorno no puede obtenerla de services.gradle.org.


---

## Archivo original: `UI_REVIEW_FIXES.md`

# MusicVault — revisión de UI / primer inicio

Cambios aplicados:

- Rediseño completo del onboarding para que siga la identidad visual `Vault`.
- Pantalla segura para edge-to-edge, desplazable y usable en pantallas pequeñas.
- Jerarquía visual clara: marca, mensaje principal, géneros, artistas y una sola acción primaria.
- Chips seleccionables para géneros y artistas, entrada manual de artistas y opción de omitir.
- Corregido el texto `Saltizar` y eliminada la pantalla de bienvenida sin contenido interactivo.
- Corregido el flujo de primera ejecución: completar u omitir ahora persiste el estado y realmente cierra el onboarding.
- Evitado el parpadeo del onboarding en usuarios existentes mientras Room determina el estado inicial.
- Los permisos de almacenamiento/audio ya no aparecen encima de la bienvenida; se solicitan al entrar a la app.
- Las preferencias elegidas en onboarding ahora participan en el feed de recomendaciones/búsqueda de catálogo.
- El APK directo queda configurado como universal (`splits.abi` desactivado), de acuerdo con el objetivo de generar un solo APK.

## Comprobación recomendada

```bash
./gradlew :app:compileDebugKotlin -q --console=plain
./gradlew assembleDebug
```

APK esperada con la configuración universal:

```text
app/build/outputs/apk/debug/app-debug.apk
```


---

## Archivo original: `UI_V8_PLAYLIST_DIALOGS.md`

# Music Vault v8 — onboarding opaque + playlist modals

- El handoff inicial ya no usa alpha/fade sobre Inicio: la pantalla de carga es 100% opaca y desaparece solamente cuando Home está visualmente listo.
- `RecommendationLoadingScreen` usa un fondo sólido `VaultColors.Background`.
- El modal de crear playlist se rediseñó con el lenguaje visual Vault: encabezado con icono, superficie oscura, borde, campo de nombre, contador de selección, seleccionar todas/ninguna, portadas y CTA claro.
- El modal de revisión al importar una playlist de Spotify usa la misma familia visual, mostrando portada, conteo, selección masiva y filas con artwork.
- Las tarjetas de playlists ahora tienen un botón de opciones; las playlists normales se pueden eliminar con confirmación. Favoritos permanece protegido.
- La confirmación de eliminación aclara que borrar una playlist no elimina las canciones descargadas de la biblioteca.


---

## Archivo original: `UPDATE_COMPAT_V11.md`

# Music Vault v11 — actualización sin borrar datos

## Problema corregido

Algunas versiones antiguas podían dejar `musicvault.db` en versión 17 con una forma de `user_preferences` distinta a la que Room esperaba. En una instalación limpia la base se crea correctamente, pero al instalar el APK nuevo encima del viejo Room valida la base existente y la app podía cerrarse al arrancar.

## Cambios

- Base de datos: 17 → 18.
- `MIGRATION_16_17` ahora crea `user_preferences` con los `NOT NULL` correctos.
- Nueva `MIGRATION_17_18` de compatibilidad:
  - reconstruye `user_preferences` sin perder géneros/artistas guardados;
  - acepta columnas antiguas nulas o faltantes;
  - reconstruye únicamente el índice `tracks_fts` y sus triggers;
  - NO borra `tracks`, `download_records`, playlists, favoritos ni historial.
- `versionCode` 13 / `versionName` 0.5.2.

La actualización debe instalarse directamente encima del APK anterior, sin borrar datos ni desinstalar.


---

## Archivo original: `V19_1_KOTLIN_COMPAT_FIX.md`

# MusicVault V19.1 — Kotlin/KSP compatibility fix

The V19 build used Media3 1.11.1. Media3 1.11.x is compiled with Kotlin 2.2 metadata, while this project intentionally remains on Kotlin 2.0.21 and KSP 2.0.21-1.0.28. That mismatch caused `:app:kspDebugKotlin` to fail with `binary version 2.2.0, expected 2.0.0`.

Fixes:
- Pin all Media3 modules to 1.10.1.
- Keep Google Play Services Cronet 18.1.1.
- Enforce Kotlin BOM 2.0.21 so transitive dependencies cannot silently upgrade only the stdlib.
- Keep all V19 streaming optimizations and behavior changes.

Recommended first rebuild after replacing V19:

```bash
./gradlew --stop
./gradlew clean assembleDebug --refresh-dependencies
```

The old Kotlin 2.2.10 jar may remain physically inside `~/.gradle/caches`, which is harmless; the fixed dependency graph should no longer select it for the app compile/KSP classpath.


---

## Archivo original: `V19_STREAMING_REVIEW.md`

# MusicVault V19 — revisión de streaming

## Objetivo
Reducir el tiempo desde que se solicita una canción online hasta que empieza el audio, sin romper el modo offline, la caché temporal de 24 h, las playlists grandes, Next/Previous ni el crossfade.

## Cambios aplicados

### 1. Media3 actualizado
- Media3 1.5.1 -> 1.10.1 en todos los módulos usados.
- Se añadió `media3-datasource-cronet`.
- Todos los módulos Media3 usan exactamente la misma versión.

### 2. Red de streaming
- Preferencia por Cronet de Google Play Services.
- Fallback automático a `DefaultHttpDataSource` cuando Cronet no está disponible.
- Un único `HttpDataSource.Factory` compartido para no reconstruir la pila en cada pista.
- Timeouts de conexión/lectura explícitos.

### 3. Warm-up al iniciar la app
`MusicVaultApplication` precalienta en segundo plano:
- la base/índice de `SimpleCache`;
- la pila HTTP/Cronet;
- yt-dlp por separado.

El primer toque en Play ya no debe pagar esas inicializaciones si el warm-up terminó.

### 4. yt-dlp fast path
`resolveStream()` intenta primero una extracción reducida:
- `youtube:skip=hls,dash`
- `player_skip=webpage,configs`

Si esa ruta no produce una URL válida, inmediatamente se repite con el extractor normal. La optimización por tanto es oportunista y no reemplaza el camino robusto.

### 5. FFmpeg fuera del camino crítico
La inicialización de yt-dlp y FFmpeg quedó separada. Buscar/streaming sólo prepara yt-dlp. FFmpeg permanece disponible para operaciones que realmente lo necesiten, pero no bloquea el primer audio.

### 6. Caché corta de URLs firmadas
Las resoluciones directas se conservan en RAM durante 10 minutos y se identifican por la clave estable de la canción. No se guardan en Room ni se consideran URLs permanentes.

Ante un error del reproductor en un stream remoto:
1. se invalida la URL corta;
2. se vuelve a resolver;
3. se reemplaza sólo el MediaItem actual;
4. se conserva la posición y el resto de la cola.

Hay un throttle de 30 s por canción para impedir loops de reintentos.

### 7. Buffer de arranque ajustado
`DefaultLoadControl` se ajusta sólo para streaming remoto (los archivos locales conservan los defaults de Media3):
- mínimo: 10 s
- máximo: 30 s
- para iniciar: 500 ms
- después de un rebuffer: 1500 ms

El objetivo es arrancar rápido sin hacer demasiado frágil una conexión irregular.

### 8. Precarga de playlists grandes
No se resuelve una playlist completa. Sólo existe una ventana móvil:
- canción actual;
- siguiente;
- segunda siguiente.

Las siguientes se resuelven justo a tiempo y se precargan aproximadamente 192 KiB al `SimpleCache` antes de agregarlas al reproductor.

Importante: el prebuffer abre el recurso con longitud completa y detiene manualmente la lectura tras el prefijo. Esto evita que un prefijo de 192 KiB sea confundido con una canción 100 % cacheada.

### 9. Búsqueda especulativa controlada
Después de que los resultados de búsqueda permanecen estables 650 ms, MusicVault resuelve únicamente el primer resultado. No se resuelven todos los elementos visibles ni se hacen peticiones mientras el usuario sigue escribiendo.

### 10. Álbumes y Smart Next
Las rutas automáticas de álbumes remotos y Smart Next ya no convierten silenciosamente la siguiente canción en descarga offline. Resuelven streaming + pequeño prebuffer.

### 11. Playlists mixtas
`Play playlist` ya no filtra sólo archivos descargados. La cola lógica conserva canciones locales y online. Las online se resuelven sólo cuando entran en la ventana de look-ahead.

### 12. Caché temporal y descarga permanente
Se conserva el diseño V18:
- máximo 1 GiB;
- TTL de 24 h desde uso reciente;
- clave estable por canción;
- caché invisible para la biblioteca offline.

Al pulsar Descargar:
- caché completa -> se materializa como archivo offline sin descargar de nuevo;
- caché parcial -> `CacheWriter` reutiliza rangos presentes y solicita lo que falta;
- si el CDN/URL temporal falla -> fallback al downloader yt-dlp existente.

### 13. Métricas locales
Logcat usa la etiqueta `MusicVaultStreamPerf` para registrar:
- duración de resolución fast/fallback;
- tiempo de prebuffer;
- tiempo hasta `Media3 STATE_READY`;
- tiempo total `request-to-audio` para reproducciones iniciadas por la app;
- pila de red elegida (Cronet/fallback).

No se envía telemetría a ningún servidor.

## Puntos revisados
- Descarga offline continúa siendo explícita.
- Tocar una canción online no la guarda como descarga.
- Caché parcial no se presenta como caché completa.
- URL de GoogleVideo nunca se persiste como identidad estable.
- Cola no resuelve cientos de canciones de golpe.
- Next de álbum remoto usa streaming.
- Smart Next remoto usa streaming.
- Crossfade conserva el mismo servicio/reproductores y recibe pistas siguientes ya resueltas/prebufferizadas.
- Error de URL firmada puede recuperarse sin destruir la cola.
- Canciones locales evitan `SimpleCache` para no duplicar almacenamiento.
- Se mantiene el límite LRU de 1 GiB y TTL de 24 h.

## Validación disponible en este entorno
- Revisión estática de los archivos Kotlin modificados: sin errores de parser (`expecting`) ni declaraciones duplicadas detectadas.
- Revisión manual de APIs de Media3/Cronet usadas contra la documentación actual.
- Gradle wrapper no pudo ejecutar una compilación completa porque este entorno no puede resolver `services.gradle.org` para descargar Gradle 8.11.1.


## V19.1 build compatibility fix

- Project compiler remains Kotlin 2.0.21 with KSP 2.0.21-1.0.28.
- Media3 is pinned to 1.10.1 because Media3 1.11.x moved its Kotlin metadata to 2.2.x.
- Kotlin BOM is enforced at 2.0.21 to prevent transitive stdlib drift.
- Cronet 18.1.1 remains enabled; it is not the source of the Kotlin 2.2 metadata mismatch.
- Streaming fast-path, URL RAM cache, 24h byte cache, 500 ms startup buffer, queue look-ahead and prebuffer remain enabled.


---

## Archivo original: `V20_STREAMING_SEARCH_RECOVERY.md`

# MusicVault V20 — Streaming/Search recovery review

## UX de reproducción online
- Tocar una canción online actualiza inmediatamente `selectedTrack`, por lo que el mini-player muestra portada/título/artista antes de que termine la resolución de red.
- La canción anterior se pausa durante la preparación para evitar audio/UI desincronizados.
- `preparingTrackId` mantiene un spinner pequeño dentro del botón principal del mini-player hasta que Media3 informa que esa misma pista está reproduciéndose.
- Un toque posterior invalida una resolución anterior mediante `streamSelectionGeneration`.
- Buscar e Inicio tienen feedback de presión inmediato (scale/alpha); las recomendaciones Compose usan estado pressed animado y las filas RecyclerView usan una animación corta de touch.

## Resolver de streaming
- Se eliminó el fast-path agresivo `player_skip=webpage,configs`.
- El fast-path conservador solo usa `youtube:skip=hls,dash`.
- Cada intento está aislado: una excepción del fast-path no puede saltarse el fallback robusto.
- Orden de recuperación: resolución rápida -> resolución normal -> `--get-url` como último recurso.
- El parser busca una línea HTTP real en la salida en vez de asumir que la última línea siempre es la URL.
- youtubedl-android 0.18.1 ya añade internamente su QuickJS empaquetado con ruta absoluta; no se duplica `--js-runtimes` en las requests de la app.

## Búsqueda YouTube / ASMR
- Consultas como `asmr`, podcast, audiolibro, entrevista, charla y conferencia siguen priorizando YouTube.
- La primera ruta de búsqueda usa `ytsearchN + --flat-playlist + --print` con una fila tabulada por resultado, evitando depender de un JSON grande.
- Si esa salida no produce resultados, se conserva el parser JSON anterior como fallback.
- Resultados vacíos/transitorios no se guardan en caché.
- Los timeouts exteriores e interiores se alinearon para permitir que yt-dlp termine sin hacer lenta la ruta normal de Deezer.

## Compatibilidad preservada
- Media3 permanece en 1.10.1 para ser compatible con Kotlin/KSP 2.0.21.
- Se conserva Cronet + fallback HTTP, caché temporal de 24 h / 1 GB, promoción caché->descarga, cola mixta local/streaming, prebuffer y crossfade.
- No se vuelve a introducir una descarga permanente al simplemente reproducir una pista online.

## Validación disponible en este entorno
- Revisión estática de los archivos Kotlin modificados y búsqueda de flags antiguos/duplicados.
- El wrapper no puede completar una compilación aquí porque Gradle 8.11.1 no está almacenado y `services.gradle.org` no es resoluble desde este entorno.


---

## Archivo original: `V21_STREAMING_LATENCY_FIX.md`

# V21 — streaming latency warmup

Baseline measured on-device over ADB (slow ~0.5 Mb/s link):

- yt-dlp stream resolution: 16,061 ms
- Media3 READY: 573 ms
- request-to-audio: 16,717 ms

The bottleneck is therefore URL resolution, not Media3 buffering.

## Changes

1. Search warm window
   - As soon as remote search results are published, the first 4 tracks are considered.
   - Tracks 1-2 resolve their complete signed stream URL in the background.
   - Tracks 3-4 pre-resolve only Deezer/metadata -> YouTube watch URL.
   - At most 2 warm jobs run concurrently.

2. Home warm window
   - The same strategy is used for the first 4 non-local Home recommendations while Home is visible.
   - Warming stops being scheduled when leaving Home.

3. In-flight deduplication
   - A per-track Mutex prevents Search/Home warmup and a user tap from starting duplicate yt-dlp work.
   - If the user taps a track while it is already resolving, playback joins the existing work and waits only for the remainder.

4. Two-level RAM cache
   - Signed stream URLs remain cached for 10 minutes.
   - Stable YouTube match URLs are cached for 1 hour.
   - Nothing ephemeral is persisted in Room.

5. Cold-tap safety
   - A track that was not warmed keeps the original single yt-dlp `ytsearch1 + audio extraction` path.
   - We deliberately do not run an extra matching process first, because two yt-dlp startups could make a cold tap slower.

6. Improved diagnostics
   - `stream-cache-hit`
   - `youtube-match cache`
   - `youtube-match cold-bypass`
   - `prewarm-match ...ms`
   - `youtube-stream-url ...ms`
   - existing `resolve ...ms`, `ready-to-media3`, `media3 READY`, and `request-to-audio`

## Expected behavior

- If a top Search/Home result finishes warming before the tap, the expensive ~16 s resolver is removed from the tap path and startup should be dominated by Media3 (~0.5-1 s in the measured test).
- If the user taps while warmup is in progress, the tap joins that resolver instead of restarting it.
- If the user taps an unwarmed lower result, behavior remains no worse than V20.
- Existing 24-hour / 1 GB byte cache, offline downloads, cache promotion, Cronet, playlists, Next look-ahead, and crossfade are unchanged.

## Compatibility

- Kotlin/KSP remains 2.0.21.
- Media3 remains 1.10.1 to avoid the Kotlin 2.2 metadata incompatibility seen with Media3 1.11.x.


---

## Archivo original: `V22_NEWPIPE_STREAMING.md`

# V22 — NewPipe primary streaming resolver

## Why
V21 device logs showed ~18–20 seconds spent in yt-dlp URL extraction while Media3 reached READY in ~0.6–1.0 seconds. The player/buffer was not the bottleneck.

## Changes
- NewPipeExtractor 0.26.5 is now the primary YouTube search/stream resolver.
- yt-dlp remains as a compatibility fallback only when NewPipe cannot produce a direct progressive audio URL.
- Added an OkHttp-backed NewPipe `Downloader`.
- Added JitPack and NIO core-library desugaring required by NewPipeExtractor for minSdk 26.
- Search/Home speculative streaming work is single-flight (concurrency 1).
- Only the first visible candidate gets full stream resolution; the next two only pre-resolve a stable YouTube match.
- Tapping a song cancels Search/Home speculative warmups so the selected track has priority.
- Existing 10-minute signed-URL RAM cache, 1-hour Deezer→YouTube match cache, Media3/Cronet path, 24-hour/1 GB media cache and yt-dlp download fallback remain intact.

## Logcat timing tags
Use:

    adb shell setprop log.tag.MusicVaultStreamPerf V
    adb logcat -c
    adb logcat -v time MusicVaultStreamPerf:V '*:S'

Important lines:
- `newpipe-match ...ms`
- `newpipe-stream-url ...ms`
- `resolver=newpipe total=...ms`
- `resolver=newpipe-fallback-ytdlp`
- `ytdlp-stream-url ...ms`
- `ready-to-media3 ...ms`
- `media3 READY ...ms`
- `request-to-audio ...ms`

## Compatibility
- Kotlin/KSP stay on 2.0.21.
- Media3 stays on 1.10.1.
- minSdk remains 26.

## License note
NewPipeExtractor is GPL-3.0-or-later. If this app is redistributed, review and comply with the applicable GPL source/distribution obligations.


---

## Archivo original: `V23_NEWPIPE_CANCEL_SEARCH_FIX.md`

# MusicVault V23 — NewPipe cancellation + YouTube search recovery

## What the V22 device logs proved

- Successful NewPipe resolution: ~3.9–5.7 s.
- Media3 READY: ~0.6–2.0 s.
- yt-dlp fallback: ~15–24 s.
- The `newpipe-stream failed ... interrupted` events were caused by speculative warmup cancellation being swallowed as a normal extractor error, which then incorrectly launched yt-dlp.

## V23 fixes

### 1. Cancellation can no longer fall through to yt-dlp
`Providers.resolveStream()` now checks coroutine cancellation after NewPipe/OkHttp failures. If an interrupted NewPipe request belongs to a canceled warmup, the cancellation propagates immediately and the expensive yt-dlp fallback is not started.

### 2. Tapping the track currently being warmed keeps its work alive
The ViewModel records the track being speculatively warmed. If the user taps that same track, Search/Home does not cancel its warmup. The foreground resolver waits on the same per-track lock and consumes the cached URL when the warmup finishes rather than restarting extraction.

Unrelated warmups are still canceled so user intent keeps priority.

### 3. NewPipe is now also the primary YouTube SEARCH engine
`YouTubeMetadataProvider` now performs:

1. NewPipeExtractor search.
2. Map results directly to MusicVault catalog tracks.
3. yt-dlp search only if NewPipe returns no usable result or genuinely fails.

This removes yt-dlp startup from normal ASMR/YouTube search.

### 4. Deezer no longer prevents YouTube refinement
Fast search can still paint Deezer immediately for ordinary music queries. A merged refinement now always continues into YouTube in the background instead of stopping merely because Deezer returned 5+ plausible rows.

For ASMR/podcast/audiobook/talk queries, YouTube remains ordered before Deezer.

### 5. Search cancellation is safe too
If a user types a new query while a NewPipe search is in progress, the canceled search no longer turns into a yt-dlp fallback for the stale query.

## Useful logs

```bash
adb shell setprop log.tag.MusicVaultStreamPerf V
adb shell setprop log.tag.MusicVaultYouTubeSearch V
adb logcat -c
adb logcat -v time MusicVaultStreamPerf:V MusicVaultYouTubeSearch:V '*:S'
```

Expected successful search line:

```text
D/MusicVaultYouTubeSearch: newpipe-search 1xxxms query='ASMR' count=...
```

Expected streaming path:

```text
D/MusicVaultStreamPerf: newpipe-match ...ms
D/MusicVaultStreamPerf: newpipe-stream-url ...ms
D/MusicVaultStreamPerf: resolver=newpipe total=...ms
D/MusicVaultStreamPerf: media3 READY ...ms
D/MusicVaultStreamPerf: request-to-audio ...ms
```

A canceled warmup should NOT be followed by `resolver=newpipe-fallback-ytdlp` for that canceled work.


---

## Archivo original: `V24_SCREEN_IDLE_STREAM_WARMUP.md`

# V24 — Screen-idle streaming warmup

## Goal
Use the time the user spends reading Search/Home results to hide NewPipe extraction latency without running multiple extractors in parallel.

## Search behavior
- Wait ~650 ms after a result list update before warming anything, so the fast Deezer page can be replaced by the merged YouTube/NewPipe page without wasting an extraction.
- Warm a 4-track window sequentially.
- Phase 1: fully resolve row 1.
- Phase 2: cache Deezer→YouTube matches for rows 2–4.
- Phase 3: fully resolve rows 2–3 using the cached match.
- RecyclerView reports a new warm window only after scrolling becomes idle; after ~420 ms, warmup follows the currently visible rows plus a small look-ahead.
- Fast flings therefore cancel/reorder speculative work instead of resolving rows already off-screen.

## Home behavior
- After the recommendations remain visible for ~650 ms, prepare the first three likely tracks sequentially using the same phased strategy.

## Priority rules
- One speculative NewPipe extraction at a time.
- A user tap always beats unrelated warmup work.
- If the tapped track is exactly the track currently being warmed, keep that job alive so playback joins the existing per-track resolver/cache instead of restarting it.
- No speculative yt-dlp fallback is introduced by cancellation; V23 cancellation propagation remains intact.

## Deliberately unchanged
- Media3 1.10.1 / Kotlin + KSP 2.0.21.
- Cronet networking.
- 24 h / 1 GiB streaming cache.
- Cache-to-offline promotion.
- yt-dlp remains compatibility fallback only.
- No 192 KiB prefix prebuffer is added to screen warmup: on a ~0.5 Mb/s link that could cost several extra seconds and block preparation of the next visible row. Media3 is already ~0.6–1.5 s once the direct URL exists.

## New log lines
- `screen-warm-search ...`
- `screen-warm-ready ...`
- `screen-warm-match ...`

A successfully warmed tap should then show `stream-cache-hit` / a near-zero resolver step followed mostly by Media3 READY time.


---

## Archivo original: `V25_YOUTUBE_PLAYLIST_SEARCH.md`

# MusicVault V25 — YouTube playlists in Search

## Search UI
- Added a third segmented tab: `Canciones | Álbumes | Playlists`.
- Playlists are searched directly on YouTube through NewPipeExtractor; Deezer is not involved.
- Playlist rows show cover, title, uploader/channel and item count when available.
- The playlists query is lazy: it runs when the user opens the Playlists tab, so normal song searches keep their current fast path.

## Playlist preview/import
- Tapping a playlist opens an in-app preview.
- NewPipe follows playlist continuation pages (up to a safe 500-item UI/import cap).
- Tapping an entry streams it with the existing MusicVault playback path.
- `Agregar a MusicVault` stores the playlist and track metadata without downloading audio.
- Imported tracks keep their YouTube `sourceId`, so playback, 24 h streaming cache and explicit downloads continue to work normally.

## Keyboard behavior
- Pressing the trailing Search button hides the software keyboard and clears focus.
- Pressing the keyboard IME Search/Enter action does the same.
- Switching between Canciones / Álbumes / Playlists also clears focus so the keyboard does not reopen over results.

## Compatibility
- Keeps Kotlin/KSP 2.0.21 and Media3 1.10.1.
- Keeps NewPipeExtractor 0.26.5, Cronet, streaming cache, offline downloads and screen-idle stream warmup unchanged.


---

## Archivo original: `app/src/main/assets/THIRD_PARTY_NOTICES.md`

# Avisos de terceros

## SongMirror

El lector integrado de playlists de Spotify en
`spotify/SpotifySongMirrorClient.kt` adapta partes del backend de sesión web de
[SongMirror](https://github.com/ahnafnafee/songmirror).

Copyright (c) 2026 Ahnaf An Nafee.

## SpotifyScraper

El intercambio local de la cookie `sp_dc` por un token temporal adapta partes de
[SpotifyScraper](https://github.com/AliAkhtari78/SpotifyScraper), dependencia que
utiliza SongMirror para esta operación.

Copyright (c) 2025 Ali Akhtari.

Ambos componentes se distribuyen bajo la siguiente licencia MIT:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


---

## Archivo original: `app/src/main/java/com/musicvault/app/catalog/README.md`

# Catalog module

`MusicBrainzCatalogProvider` es la fuente de metadata remota.

No descarga audio.

## API pública

```kotlin
interface CatalogProvider {
    suspend fun searchTracks(query: String, limit: Int = 20): List<CatalogTrack>
    suspend fun searchArtists(query: String, limit: Int = 10): List<CatalogArtist>
    suspend fun searchAlbums(query: String, limit: Int = 10): List<CatalogAlbum>
}
```

## Fuente actual

MusicBrainz Web Service:

```text
https://musicbrainz.org/ws/2/
```

No se usa API key.

Se envía:

```text
User-Agent: MusicVault/0.3.0 (personal-local-app)
```

y el cliente espera entre peticiones para respetar el límite de MusicBrainz.

## Sustituir proveedor

Puedes crear otro:

```text
SpotifyCatalogProvider.kt
DeezerCatalogProvider.kt
LocalJsonCatalogProvider.kt
```

sin cambiar la UI, porque todos implementan `CatalogProvider`.


## V25.2 — Ajuste responsivo del contador de búsqueda
- Se redistribuyó el ancho de `Canciones | Álbumes | Playlists` para dar más espacio a las pestañas de texto largo.
- Se redujo el padding horizontal interno de cada segmento.
- El contador usa tipografía ligeramente más compacta y formato abreviado para valores >= 1000.
- Se fuerza una sola línea para evitar cortes/saltos de línea en pantallas angostas.
