# MusicVault v0.5.0

## Integración v0.5.0
- Settings screen with persistent crossfade (0–12 s).
- Crossfade control removed from Now Playing.
- Native Android Equalizer wrapper connected to the Media3 audio session.
- Favorites persisted in Room.
- Media3 queue exposed to UI.
- Queue item reordering and removal.

Base: MusicVault v0.4.2 UX/custom albums.
# MusicVault 0.3.3

Reproductor musical local para Android, diseñado con una experiencia tipo Spotify pero orientado a **descargar el audio para reproducirlo localmente**.

## Estado de esta versión

- Jetpack Compose + Material 3
- Room para persistencia
- Media3 + MediaSessionService para reproducción en segundo plano
- Escaneo de archivos de audio locales
- Búsqueda de metadata mediante MusicBrainz
- Portadas mediante Cover Art Archive
- Proveedor de descarga aislado en `providers/Providers.kt`
- Barra de progreso de descarga por canción
- Estado persistente de canciones descargadas
- Mini-player
- Playlists y detalle de playlist
- Actualización automática del binario yt-dlp antes de descargar (máximo una vez cada 24 h)
- FFmpeg inicializado únicamente cuando se utiliza el proveedor

## Compilar

Requisitos:

- Android Studio reciente
- JDK 17
- Android SDK Platform 35
- Android Build Tools 34+

Desde PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

"sdk.dir=$($env:LOCALAPPDATA -replace '\\','/')/Android/Sdk" | Out-File -Encoding ascii local.properties

.\gradlew.bat assembleDebug
```

APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Metadata

MusicVault consulta MusicBrainz sin API key. El cliente usa un User-Agent identificable y serializa las solicitudes para respetar el límite de MusicBrainz de aproximadamente una solicitud por segundo.

La portada se obtiene desde Cover Art Archive usando el MBID del release o release-group.

Las búsquedas tienen un debounce de 350 ms para evitar lanzar una petición por cada tecla y se cancela la búsqueda anterior cuando el usuario continúa escribiendo.

## Proveedor de descarga

Toda la integración de descarga está centralizada en:

```text
app/src/main/java/com/musicvault/app/providers/Providers.kt
```

La interfaz pública es deliberadamente pequeña:

```kotlin
Providers.download(
    name = "Nombre de canción",
    author = "Autor",
    onProgress = { percent -> ... }
)
```

Esto permite reemplazar el backend de descarga posteriormente sin modificar la interfaz de MusicVault.

La integración actual utiliza `youtubedl-android` + FFmpeg. El binario yt-dlp que viene dentro de la librería puede actualizarse desde la propia librería; MusicVault intenta actualizarlo al canal estable como máximo una vez cada 24 horas antes de la primera descarga posterior al intervalo.

Si la actualización temporalmente no está disponible, se conserva el binario incluido como fallback y el error de descarga incluye la versión detectada.

## Flujo de descarga

```text
Usuario toca Descargar
        ↓
MusicVaultViewModel
        ↓
Providers.download(nombre, autor)
        ↓
Inicialización diferida de yt-dlp + FFmpeg
        ↓
Actualización estable de yt-dlp si corresponde
        ↓
ytsearch1:nombre autor
        ↓
Extracción de audio MP3
        ↓
Callback de progreso
        ↓
Detección del archivo generado
        ↓
FileProvider URI
        ↓
Room: audioUri + isDownloaded
        ↓
Botón ↓ cambia a ✓
```

## Progreso

`downloadProgress` utiliza `Map<String, Int>` y la misma clave que utiliza cada `Track`. El progreso se muestra debajo del título y se elimina cuando termina o falla la descarga.

## Estructura importante

```text
app/src/main/java/com/musicvault/app/
├── catalog/
│   ├── MusicBrainzCatalogProvider.kt
│   ├── CoverArt.kt
│   └── CatalogRepository.kt
├── data/
│   ├── MusicDatabase.kt
│   ├── Daos.kt
│   ├── Entities.kt
│   └── Repository.kt
├── providers/
│   └── Providers.kt
├── player/
│   ├── MusicPlaybackService.kt
│   └── PlayerController.kt
├── library/
│   └── LocalAudioScanner.kt
└── ui/
    ├── MusicVaultApp.kt
    └── MusicVaultViewModel.kt
```

## Nota sobre la dependencia yt-dlp

La versión `0.18.1` de `youtubedl-android` sigue siendo la versión publicada del wrapper, pero ese wrapper permite actualizar el ejecutable yt-dlp desde la aplicación. Por eso MusicVault no intenta resolver el warning de antigüedad cambiando arbitrariamente la versión de la dependencia: actualiza el binario embebido mediante `updateYoutubeDL()`.

## Privacidad

MusicVault está diseñado para uso local. La app no necesita una cuenta de MusicBrainz para consultar metadata. La búsqueda de metadata y la operación del proveedor de descarga requieren conexión de red.

## Metadata 0.3.6
MusicVault usa un sistema de fallback para metadata: MusicBrainz + Cover Art Archive, iTunes Search y Deezer. Si una fuente falla o no devuelve resultados, se prueba automáticamente la siguiente. La UI no depende de un único proveedor.

El proveedor de descargas (`providers/Providers.kt`) se mantiene separado de metadata.


## MusicVault 0.3.7

### Búsqueda y álbumes remotos
- Tracks y álbumes se consultan en carreras independientes; la UI muestra cada categoría en cuanto está disponible.
- iTunes, Deezer y MusicBrainz implementan `albumTracks()` para abrir un álbum remoto y listar sus canciones.
- Los álbumes de Search ahora son clickeables y tienen pantalla de detalle con portada, artista y tracks.
- Las canciones de un álbum reutilizan el mismo flujo de reproducción/descarga que Search.
- Los IDs locales descargados se precalculan como `HashSet` para evitar un `firstOrNull` por fila durante recomposiciones.
- Las miniaturas de resultados usan artwork de tamaño intermedio (iTunes 300x300; Deezer `cover_medium`) en lugar de las variantes XL.

### Arquitectura de metadata
`MetadataCatalogProvider.searchAll()` mantiene dos carreras paralelas: una para tracks y otra para álbumes. `CatalogRepository` expone además `tracksForAlbum(album)`, seleccionando el proveedor que originó el álbum.

### Nota
El proveedor de descargas (`providers/Providers.kt`) no fue reemplazado.

## Cambios de biblioteca local y nombres de descargas — 0.3.8

- Las descargas ya no se conservan con el ID interno de la fuente. Después de terminar yt-dlp, MusicVault renombra el archivo usando el título de la canción, por ejemplo `Blinding Lights.mp3`.
- Los nombres se limpian de caracteres no válidos para archivos y, si ya existe un archivo con el mismo título, se crea `Titulo (2).mp3`, `Titulo (3).mp3`, etc. para no sobrescribir música existente.
- La URI que se guarda en Room apunta al archivo ya renombrado, por lo que la canción descargada conserva su referencia después de reiniciar la aplicación.
- El escáner de música del dispositivo solo considera música con duración de **1:30 (90 segundos) o más** y `MediaStore.IS_MUSIC != 0`.
- Al volver a escanear, se reemplazan únicamente las entradas creadas por el escáner (`local:*`). Las canciones descargadas/provenientes del proveedor no se borran del catálogo.


## MusicVault 0.4.0

### Reproductor a pantalla completa
- Tocar el mini-player abre el reproductor completo.
- Portada grande con animación suave al reproducir/pausar.
- Play/Pause, anterior y siguiente.
- Barra de posición arrastrable con seek.
- Tiempo actual y duración.
- La conexión de MediaController sigue siendo no bloqueante.

### Colecciones creadas por el usuario
- Se pueden crear playlists seleccionando canciones locales descargadas.
- Se pueden crear álbumes locales seleccionando canciones.
- Los álbumes personalizados se almacenan en Room y se pueden abrir para reproducir sus canciones.
- Se agregó la tabla `album_tracks` con migración Room 2 -> 3.

### Catálogo inicial
Se eliminó el seed automático de canciones ficticias. Una instalación nueva empieza vacía y se llena mediante descargas o el escáner de música local.

### Rendimiento
- El ticker de reproducción actualiza el progreso cada 250 ms para una interacción más fluida.
- El seek solo se envía a Media3 al terminar de arrastrar el slider.
- Las animaciones usan Compose y no realizan trabajo de I/O en el hilo de UI.

> Nota: 60 FPS es el objetivo de la UI; la tasa real depende de la pantalla y del dispositivo Android.

## v0.4.1 — Colecciones, descarga al vuelo y reproducción inteligente

- El escaneo local solo admite audio marcado como música con duración mínima de 90 segundos.
- Se ignoran nombres compuestos únicamente por números/fechas y archivos con prefijo `AUD`.
- Las canciones usan menú contextual `⋮` para acciones de colección.
- Añadir una canción no descargada a una playlist o álbum dispara la descarga en segundo plano y, cuando termina, la canción se asigna automáticamente.
- Pulsar Play sobre una canción remota/no descargada inicia la descarga inmediatamente y comienza la reproducción cuando el archivo queda listo.
- El reproductor mantiene búsqueda temporal, anterior/siguiente, shuffle y transición de volumen configurable.
- Shuffle inteligente preselecciona/pre-descarga la siguiente canción cuando existe una fuente remota y utiliza exclusivamente la biblioteca descargada cuando no hay conexión.
- Dentro de un álbum se priorizan las canciones restantes del álbum; al terminar, con conexión, se busca una pista relacionada y se descarga para continuar la cola.
- El estado de progreso del reproductor se actualiza con una cadencia de 60 Hz para que el seek y las animaciones de la pantalla completa sean fluidos. La tasa visual real depende del dispositivo.

## v0.4.2 — UX y colecciones personalizadas

- Eliminados de la interfaz los mensajes de estado de descarga/preparación; el progreso se comunica mediante los indicadores visuales de cada canción.
- Búsqueda remota con debounce de 1.5 segundos desde la última tecla.
- Los álbumes mostrados y seleccionables desde las acciones de canciones son exclusivamente álbumes personalizados (`album:custom:*`). Los álbumes externos de metadata no se mezclan con las colecciones del usuario.
- La reproducción desde un álbum personalizado mantiene auto-siguiente y respeta el orden de las pistas; shuffle selecciona entre las pistas restantes del álbum.
- Los resultados remotos anteriores se limpian al comenzar una nueva consulta para evitar mostrar resultados obsoletos durante el debounce.
