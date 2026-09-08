# Migración Android → Linux

La aplicación principal para Linux está en `linux/`. No se puede convertir un APK ARM en una aplicación nativa x86_64 cambiando `abiFilters`: Compose Android, Room/Context, MediaStore, MediaSessionService, SAF y los efectos Android requieren sustituciones.

| Android | Linux |
| --- | --- |
| Activity y Jetpack Compose | QApplication, ventana y widgets Qt 6 |
| Room | SQLite con WAL, transacciones y conexión por operación |
| MediaStore y permisos SAF | Selector nativo, rutas locales y escaneo de carpetas |
| Media3 / ExoPlayer | libmpv mediante su API C estable |
| Crossfade con reproductores móviles | Dos instancias de libmpv y rampa de volumen |
| Android Equalizer / BassBoost / Virtualizer | Filtros FFmpeg de libmpv: EQ, ganancia, amplitud estéreo y limitador |
| MediaSession / notificación / widget | MPRIS y bandeja del escritorio |
| youtubedl-android + FFmpeg Android | Procesos yt-dlp y FFmpeg de la distribución x86_64 |
| content:// y FileProvider | Archivos locales con rutas absolutas |
| Preferences | Ajustes SQLite |
| Corrutinas y Flows | Trabajos QThreadPool y señales Qt con actualización en el hilo de la UI |

Los modelos, servicios y pantallas se reimplementaron para Linux. Los catálogos y los conceptos de biblioteca se mantienen; no se comparte código de ejecución con Android o iOS. Esto permite instalar la aplicación sin SDK Android ni JVM, a costa de mantener una implementación adicional.

Adaptaciones que no son idénticas a la interfaz móvil:

- Navegación inferior, estantes de portadas y filas con título/artista como en Android; se conservan menús contextuales y arrastre de archivos para escritorio. Las fuentes Inter, Space Grotesk e IBM Plex Mono se empaquetan desde los recursos originales.
- MPRIS sustituye los controles del widget y la notificación móviles. La disponibilidad visual depende de GNOME/KDE u otro escritorio.
- La animación circular no representa muestras de audio; el vinilo conserva una velocidad visual ajustable.
- La continuación automática se basa en la biblioteca local. No implementa recomendaciones remotas automáticas ni el algoritmo de ranking completo de Android.
- Las portadas locales se extraen de etiquetas o de imágenes de la carpeta; no se enriquece automáticamente toda la biblioteca mediante consultas remotas.
- yt-dlp oficial se incluye con su componente EJS y se ejecuta con Python del sistema. La app detecta Node.js ≥22 o Deno ≥2.3, incluido Node instalado por nvm. FFmpeg sigue siendo una dependencia del sistema. No se reemplazan binarios del sistema.
- No hay migración automática de bases Room, playlists móviles ni URI `content://`; se importa la música copiada al equipo.

Los detalles de ejecución, distribución, datos y pruebas están en [README.md](README.md). No se afirma paridad visual exacta ni equivalencia del algoritmo de recomendaciones.
