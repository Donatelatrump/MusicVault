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
