# Verificación local — 2026-09-19 (0.9.7)

## Comprobaciones realizadas en este entorno

- `python3 -m compileall -q musicvault tests packaging` terminó correctamente después de integrar streaming, búsqueda de playlists y warmup.
- `python3 packaging/build.py` generó correctamente `dist/MusicVault-0.9.7-linux-x86_64.tar.gz` y `dist/musicvault_0.9.7_amd64.deb`; `dpkg-deb --info` reporta versión 0.9.7/amd64.
- Se inspeccionó el tar generado y contiene `musicvault/streaming.py`, `musicvault/window.py` y el resto de la aplicación actualizada.
- El ZIP fuente se valida con `zip -T` antes de entregarse.

## Limitación de este entorno

El contenedor usado para preparar esta versión no tiene PyQt6, libmpv, Mutagen, DBus ni PyGObject instalados. Por ello no se pudo ejecutar aquí el smoke test gráfico ni la suite pytest que importa Qt. `./run.sh --doctor` sí confirma que el lanzador detecta correctamente las dependencias ausentes y que están disponibles FFmpeg, FFprobe, el yt-dlp incluido y Node.js 22.

La validación funcional de UI/audio debe hacerse en una instalación Linux con las dependencias descritas en `README.md`.

## UI 0.9.7

- El control Play/Pausa del mini-reproductor usa máscara elíptica además del estilo Qt, por lo que conserva forma circular incluso con temas que ignoran el `border-radius`.
- Se eliminó el texto redundante `TU BIBLIOTECA` y `Descargas` de la navegación principal.
- El botón `+` de crear playlist/álbum se movió al encabezado `TUS COLECCIONES`.

- Ajustes usa tarjetas compactas por sección; se eliminó completamente el ecualizador y sus filtros persistentes.

## Importación 0.9.7

- No hay botones de “Importar música” en sidebar/header.
- Spotify se administra desde Ajustes → Cuenta.
- La importación manual de playlist de Spotify se eliminó; permanece el autodetector de enlaces copiados.
- Ajustes → Biblioteca y almacenamiento incluye “Música local → Importar carpeta”.
