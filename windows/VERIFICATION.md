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
