# Providers

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
app/src/main/java/com/musicvault/app/providers/Providers.kt
```

La UI y el ViewModel no deben depender de las clases internas del proveedor.

## Contrato de archivos

El proveedor devuelve un `content://` URI generado mediante `FileProvider`. MusicVault guarda ese URI en Room como `audioUri` y marca `isDownloaded = true` cuando el archivo ya fue localizado y validado.

## Nota legal/técnica

El proveedor debe utilizarse respetando las condiciones de servicio, derechos de autor y permisos aplicables al contenido y al servicio de origen.
