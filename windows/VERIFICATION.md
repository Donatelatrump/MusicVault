# Verificación Windows 0.10.0 — 2026-11-07

Pruebas en Windows x64 con Python embebido 3.12.10 y Qt 6.10.2:

- 24 pruebas dirigidas de actualizaciones, JAMS, perfiles, favoritos, carátulas,
  géneros y retratos. Las de audio real se ejecutan por separado.
- Políticas de actualizaciones: filtrar Android/borradores/prereleases, elegir
  Windows por manifiesto y código creciente, restricciones de URL, tamaño y SHA-256,
  descarga cancelada/corrupta, reutilización de caché y revisión manual tras Ahora no.
- Perfiles reales de Arctic Monkeys: 50 canciones, 33 álbumes, búsqueda de 505 y
  detalle del álbum The Car con diez canciones.
- Catálogo Android incorporado con 2,234 géneros combinados y páginas remotas de artistas.
- Fotos y carátulas descargadas y renderizadas; pruebas del círculo de reproducción
  y corazón relleno, y Favoritos de pistas remotas sin descarga.
- Pruebas reales de dos cuentas temporales Firebase para JAMS, amigos y controles.
  La sincronización acústica con teléfonos físicos sigue pendiente.

El SDK Firebase distribuido se compila en un archivo JavaScript y se ejecuta con
el Node incluido. No se distribuyen Data, sesiones ni identidades de desarrollo.
Las pruebas de despliegue finales de esta release se registran en sus notas de GitHub.

## Registro histórico de 0.8.0 — 2026-09-18

- `python -m compileall` completó sin errores para `windows/musicvault`, empaquetado, pruebas y `launch.py`.
- Smoke SQLite desde esquema antiguo: migración de columnas/FTS5, búsqueda, portada automática y lectura de biblioteca verificados usando el código Windows.
- Smoke de catálogo con proveedores simulados: búsqueda de canciones/álbumes y detalle Deezer con `tracks.data` verificados.
- El lanzador `MusicVault.exe` incluido sigue siendo el lanzador nativo x64 del proyecto y se actualizó su metadata textual de 0.7.1 a 0.8.0; para una reconstrucción reproducible completa se debe ejecutar `packaging/build_launcher.py` con MinGW-w64 en una máquina que lo tenga instalado.
- No se pudo repetir aquí la suite Qt/Wine porque este contenedor no incluye PyQt6, Wine ni MinGW. Las comprobaciones Windows 0.7.x documentadas abajo se conservan como historial de la base portable.

# Verificación de la adaptación Windows

- Se conserva el código de pantallas y widgets de Linux 0.7.1, sus fuentes y recursos gráficos. Solo se adaptan textos de plataforma en window.py.
- Pruebas heredadas ejecutadas en Linux sobre el código Windows: 50 aprobadas. Incluyen audio libmpv real con salida nula, cola, crossfade, búsqueda, biblioteca, interfaz y conectores simulados.
- Pruebas adicionales: 2 aprobadas (traslado de rutas Windows y lectura de salida de procesos hasta EOF).
- Las pruebas Linux no acreditan audio físico, teclas globales, Spotify WebEngine ni funcionamiento de proveedores en un equipo Windows real.
- La cancelación usa taskkill para incluir los procesos secundarios FFmpeg. Las rutas de datos se migran dentro de una transacción SQLite al mover Data; los archivos externos no se trasladan.
- No se implementa MPRIS en Windows; se incluyen teclas multimedia globales y bandeja. No hay integración SMTC de pantalla de bloqueo.

## Binarios Windows ejecutados con Wine

Prueba con el Python embebido del paquete, usando `packaging/check_windows.py`:

- Importaciones de Qt y Qt WebEngine: correctas.
- Ventana original renderizada: correcta; captura en `docs/windows-preview.png` en el árbol de desarrollo.
- Decodificación de WAV con libmpv Windows y avance de reproducción: correcta, salida de audio nula.
- Inicialización del registro de teclas multimedia: ejecutada sin excepción (no se simularon pulsaciones físicas).
- Descarga simulada mediante un proceso Windows, lectura de progreso y publicación de audio: correcta.
- Cancelación de un proceso Windows con taskkill: correcta.

Resultado: ambos grupos de comprobaciones Windows aprobados, código de salida 0.
No se probó inicio de sesión real en Spotify, descarga real de YouTube, teclas físicas ni sonido en un dispositivo Windows. Wine es una comprobación adicional, no certificación de compatibilidad con Windows real.

## Lanzador nativo MusicVault.exe

- Compilado con MinGW-w64 como PE32+ Windows GUI x64, con icono, información de versión y manifiesto `asInvoker`.
- Solo enlaza DLL del sistema Windows: KERNEL32, msvcrt, SHELL32 y USER32.
- `packaging/check_launcher.py` aprobado bajo Wine: `--version`, `--doctor`, apertura/cierre real de la ventana con captura, directorio de trabajo independiente y `--data-dir` con espacios y acentos.
- El .exe requiere las carpetas del ZIP portable junto a él; no es un instalador ni un binario que contenga todas las dependencias.
# Verificación de Windows 0.9.7 — 2026-09-24

- Se incorporaron el streaming, la caché temporal y las playlists de YouTube de Linux 0.9.7, junto con los cambios de búsqueda, importación, Ajustes y controles; se conservaron el runtime portable, las teclas multimedia y la cancelación de procesos de Windows.
- `compileall` pasó para `musicvault`, el lanzador y el empaquetado.
- Diez pruebas dirigidas pasaron con Python 3.12 embebido en Windows: reproducción, rutas portables, procesos, resolución de streaming, caché y promoción de audio. Una de ellas comprobó que reproducir una pista remota usa streaming y no inicia una descarga.
- `packaging/check_windows.py` pasó: Qt/WebEngine, ventana, libmpv con WAV, teclas multimedia, publicación de descarga simulada y cancelación del proceso.
- El lanzador nativo ejecutó `--smoke-test` con código de salida 0 y generó una captura. El diagnóstico `--doctor` encontró todas las dependencias y la ventana normal quedó abierta con la biblioteca copiada desde la carpeta anterior.
- Prueba real de YouTube: la búsqueda devolvió una pista, yt-dlp obtuvo una URL de audio HTTPS y libmpv reprodujo ese stream con salida de audio nula; la posición avanzó 7,53 segundos. Esto confirma decodificación y avance, no sonido audible en los altavoces.
- El ZIP `MusicVault-Windows-x64-0.9.7.zip` se generó sin `Data/`. No se probó una sesión real de Spotify ni el sonido audible en un dispositivo físico; YouTube sigue dependiendo de la disponibilidad del servicio.

# Reconstrucción para Beta 1.5 — 2026-11-01

- Lanzador x64 recompilado con MinGW-w64; metadatos corregidos a 0.9.7.
- `packaging/check_windows.py` y `packaging/check_launcher.py` aprobados con Wine: GUI, Qt/WebEngine, diagnóstico, WAV/libmpv con salida nula, descarga simulada, cancelación y rutas con acentos.
- Instalador NSIS por usuario; conserva Data al desinstalar.
- `packaging/check_installer.py` aprobado con Wine: instalación silenciosa, SHA256 de todos los archivos instalados, versión/diagnóstico del EXE instalado, captura de la GUI, desinstalación y conservación de un archivo testigo en Data.

## Corrección de ventana auxiliar — 2026-11-01

- El indicador library_badge se incorpora al layout de cabecera; ya no es un QLabel sin padre que Qt muestra como ventana independiente.
- Regresión Qt offscreen aprobada al navegar Inicio/Buscar/Biblioteca y redimensionar.
- Wine: check_windows.py aprobado con comprobación de que el indicador no es una ventana superior; enumeración Win32 confirma una sola ventana de aplicación por proceso.

## Actualizador pequeño y búsqueda/carátulas — 2026-11-01

- Actualizador NSIS de aplicación: descubre la instalación, comprueba procesos de esa carpeta, valida manifiesto/SHA256 y aplica cambios con copia de reversión; no incluye runtimes ni Data.
- Prueba Wine sobre instalación previa: detecta proceso activo sin modificar archivos, aplicación automática del parche, hashes de payload y runtimes, SQLite de datos conservado, rechazo de payload alterado y arranque de GUI actualizada.
- Nuevas búsquedas: Deezer y YouTube, como las fuentes activas Android; se conservan lectores de colecciones antiguas. Carátulas con fallback grande/miniatura estable, reparación de imagen inválida y reintentos limitados.

- NewPipeExtractor 0.26.5 idéntico al artefacto fijado en Android, con runtime Java privado Windows. Health, búsqueda y playlists reales ejecutados con Wine: códigos 0; búsqueda 2,94 s y playlists 2,61 s en esta ejecución.
- Búsqueda general real: 56 resultados; primeras 12 filas YouTube con carátula, sin fallos de imagen pendientes. Orden YouTube→Deezer para todas las consultas. 51 pruebas sin ventanas/MPRIS y 3 regresiones Qt aprobaron en Linux.
- Cadena de proveedor actualizada: NewPipe resolvió un stream real en 4,94 s; libmpv reprodujo 9,23 s con salida nula y cero errores.
