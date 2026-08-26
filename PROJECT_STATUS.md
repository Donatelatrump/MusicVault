# MusicVault v0.5.0

## Integración v0.5.0
- Settings screen with persistent crossfade (0–12 s).
- Crossfade control removed from Now Playing.
- Native Android Equalizer wrapper connected to the Media3 audio session.
- Favorites persisted in Room.
- Media3 queue exposed to UI.
- Queue item reordering and removal.

Base: MusicVault v0.4.2 UX/custom albums.
# MusicVault 0.4.1

## Incluido
- Media3 MediaSessionService y reproducción en segundo plano.
- Reproductor de pantalla completa con seek, anterior/siguiente, shuffle y crossfade de transición.
- Descargas cancelables mediante el proveedor configurado en `providers/Providers.kt`.
- Consumidores múltiples de una misma descarga: reproducir, añadir a playlist y añadir a álbum pueden esperar el mismo trabajo sin duplicar la descarga.
- Menú contextual de tres puntos en las canciones.
- Play sobre contenido no descargado inicia la descarga automáticamente y reproduce al terminar.
- Playlists y álbumes personalizados persistentes con Room.
- Filtro local: mínimo 90 s, ignora `AUD*`, nombres numéricos y fechas.
- Shuffle inteligente con precarga/descarga preventiva y fallback offline a la biblioteca.
- Prioridad de canciones restantes del álbum y continuación relacionada al finalizar un álbum con Internet.

## Verificación local realizada
- Se revisaron todos los archivos Kotlin y no se detectaron errores de parser/sintaxis (`expecting`, `Unexpected tokens`, `unclosed`, etc.).
- `assembleDebug` no se ejecutó en el entorno de empaquetado porque Gradle 9.3.0 no está disponible localmente y el entorno no puede descargar `services.gradle.org`.

## Verificación recomendada en Windows
```powershell
.\gradlew.bat clean
.\gradlew.bat assembleDebug
```


## Performance/cache pass
- Search results are cached in Room for 30 minutes and restored without a network request.
- Cover art uses a shared Coil memory+disk cache and is prefetched after search.
- Search list images request 180px thumbnails; album detail uses 360px.
- Search debounce is 350ms.
- Remote result rows no longer create one download-progress Flow collector each, reducing Compose work while scrolling.
- Launcher icon regenerated from the supplied MusicVault logo at mdpi through xxxhdpi.
