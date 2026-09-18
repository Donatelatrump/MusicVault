# Providers

## Linux / Windows 0.8.0

Las versiones de escritorio usan `linux/musicvault/services.py` y `windows/musicvault/services.py`: Deezer, iTunes y MusicBrainz para metadatos; búsqueda explícita de YouTube mediante yt-dlp; LRCLib para letras. Las operaciones se ejecutan fuera del hilo de la interfaz.

El proveedor de descarga recibe un `Track`, la carpeta destino, una señal de cancelación y una función de progreso. Ejecuta yt-dlp/FFmpeg como procesos con argumentos separados, valida el audio en un directorio temporal y publica el archivo al terminar. Devuelve un `Track` con ruta local absoluta. Las solicitudes duplicadas comparten trabajo y pueden tener varios consumidores.

Desde Linux 0.6.1 se incluye yt-dlp oficial con EJS y checksum verificado. Se detecta Node.js ≥22 o Deno ≥2.3 incluso en un lanzamiento desde el escritorio con nvm. FFmpeg se instala mediante el sistema. Los errores de descarga no se presentan como archivos de la biblioteca y el reintento es explícito. Véase [linux/README.md](linux/README.md).

## Android (contrato original)

Este archivo documenta el contrato que debe implementar cualquier proveedor de audio.

## Entrada

La aplicación llama:

```kotlin
Providers.download(
    name = "Nombre de la canción",
    author = "Nombre del artista"
)
```

El proveedor recibe exactamente:

- `name`
- `author`
- `onProgress` para reportar el porcentaje de descarga.

## Salida

### Éxito

```kotlin
ProviderResult.Success(
    audioUri = "...",
    fileName = "cancion.mp3",
    mimeType = "audio/mpeg"
)
```

### No disponible

```kotlin
ProviderResult.Unavailable("Motivo")
```

### Error

```kotlin
ProviderResult.Error("Motivo")
```

## Implementación actual

`Providers.kt` contiene un adaptador basado en `youtubedl-android` + FFmpeg. El binario yt-dlp puede actualizarse desde la propia librería mediante el canal estable, con una comprobación como máximo cada 24 horas.

## Punto de sustitución

Modificar únicamente:

```text
android/app/src/main/java/com/musicvault/app/providers/Providers.kt
```

La UI y el ViewModel no deben depender de las clases internas del proveedor.

## Contrato de archivos

El proveedor devuelve un `content://` URI generado mediante `FileProvider`. MusicVault guarda ese URI en Room como `audioUri` y marca `isDownloaded = true` cuando el archivo ya fue localizado y validado.

## Nota legal/técnica

El proveedor debe utilizarse respetando las condiciones de servicio, derechos de autor y permisos aplicables al contenido y al servicio de origen.
