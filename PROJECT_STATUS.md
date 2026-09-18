# MusicVault 0.8.0 — paridad Android en Linux y Windows

- Onboarding Géneros → Artistas con catálogo local y lista virtualizada.
- Recomendaciones por artistas elegidos, intercaladas round-robin y cacheadas.
- Pantalla opaca de preparación hasta que Inicio tiene datos y primeras portadas.
- Buscar separa Canciones/Álbumes y publica cada tipo de resultado independientemente.
- Playlist picker con búsqueda local, selección persistente al filtrar, portada automática y eliminación mediante bote de basura.
- Detalle de álbum acotado a un viewport nativo, caché LRU de 12 álbumes y descarte de respuestas viejas.
- Descargas con barra lineal delgada unificada.
- Linux adopta además el índice FTS5 y las firmas de escaneo que Windows ya utilizaba para bibliotecas grandes.
- Las bases de datos existentes se migran in-place añadiendo columnas/índices auxiliares; no se eliminan pistas, playlists, favoritos, historial ni descargas.

# MusicVault 0.7.1 — Errores de conexión

Avisos DNS legibles, reintento desde el aviso y detalles técnicos expandibles. Se traducen también los errores de descarga guardados por versiones anteriores. Descarga y reproducción local verificadas con un clip público de prueba fuera del sandbox. Paquetes actualizados en `linux/dist/`.

# MusicVault 0.7.0 — Linux desktop

Interfaz de escritorio con barra lateral, playlists accesibles, búsqueda superior y controles de reproducción inferiores. Se conserva la paleta carbón/ámbar/marfil. Tabla virtual, portadas bajo demanda con caché y solicitudes compartidas, actualizaciones de descarga incrementales y animación detenida cuando no se ve.

Ejecución: `./linux/run.sh`. Paquetes: `linux/dist/musicvault_0.7.0_amd64.deb` y `linux/dist/MusicVault-0.7.0-linux-x86_64.tar.gz`. [Pruebas y medición de rendimiento](linux/VERIFICATION.md).

Las secciones siguientes conservan el historial de versiones anteriores.

# MusicVault v0.5.0

## Integración v0.5.0
- Settings screen with persistent crossfade (0–12 s).
- Crossfade control removed from Now Playing.
- Native Android Equalizer wrapper connected to the Media3 audio session.
- Favorites persisted in Room.
- Media3 queue exposed to UI.
- Queue item reordering and removal.

Base: MusicVault v0.4.2 UX/custom albums.
# MusicVault 0.6.1 — Linux x86_64

Corrección de descargas: biblioteca limitada a archivos presentes, errores visibles y reintento explícito, conservación y recuperación de rutas locales. yt-dlp oficial actualizado con EJS y detección de Node/nvm o Deno. Prueba real: descarga y dos reproducciones locales sin volver a descargar.

Diseño ajustado a Android: fuentes originales, navegación inferior, portadas, filas de canción y tornamesa HV-01 con brazo. Los paquetes actuales son `musicvault_0.6.1_amd64.deb` y `MusicVault-0.6.1-linux-x86_64.tar.gz`. Véase [verificación](linux/VERIFICATION.md).

La aplicación de escritorio se encuentra en `linux/musicvault/` y se ejecuta con `./linux/run.sh`.
Qt 6 sustituye la UI Android, libmpv el motor Media3, SQLite la persistencia Room y MPRIS los controles multimedia del sistema.
El empaquetador `linux/packaging/build.py` genera un `.deb` amd64 y un `.tar.gz` reubicable con dependencias del sistema.

La guía actual está en [linux/README.md](linux/README.md); las diferencias de plataforma están en [linux/MIGRATION.md](linux/MIGRATION.md).
Las notas siguientes corresponden a las versiones Android anteriores.

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
cd android
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
