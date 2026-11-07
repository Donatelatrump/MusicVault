# JAMS de Android en Windows y Linux

Referencia revisada: `origin/feature/alternative-newpipe-release`, archivos
`android/app/src/main/java/com/musicvault/app/jams/`, `android/docs/JAMS.md`
y las reglas de Realtime Database de esa rama. `main` no contiene ese Android.
No se modificó ni se cambió la rama de la carpeta Android del usuario.

## Implementado en ambos escritorios

- Acceso **JAMS / Amigos**, independiente de la mezcla personal **Jam** existente.
- UUID aleatorio de instalación y sesión anónima de Firebase persistentes.
- Salas del mismo proyecto y protocolo v2 que Android: crear, entrar por código
  de seis dígitos, pegar invitaciones HTTPS o `musicvault://`, copiar enlace,
  salir, cerrar como creador y caducidad impuesta por las reglas existentes.
- Cola compartida con ID de vídeo y metadatos, límite de cien canciones,
  selección, eliminación, preparación individual y confirmación de audio listo.
- Seleccionar una canción en la aplicación la comparte; añadir a cola conserva
  la acción de solo añadir. Los archivos locales se buscan en YouTube para
  compartir una fuente accesible a todos; no se suben archivos del equipo.
- Pausa, reproducción, posición, anterior/siguiente y controles multimedia
  envían órdenes a la sala. Las revisiones se actualizan con transacciones.
- Barrera de preparación, inicio con margen de 180 ms, espera máxima de doce
  segundos, corrección de velocidad de hasta tres por ciento, corrección de
  desfases grandes y avance automático por participante líder.
- Presencia mediante `onDisconnect`, pausa al desconectarse y alineación al
  reconectar. Solicitudes de amistad por enlace o participante, aceptación,
  rechazo, eliminación de amigos e invitaciones a sala.

La identidad queda fuera de SQLite y de `Data`, en
`%LOCALAPPDATA%/MusicVault/installations/<identificador-de-ruta>/` en Windows,
o `~/.local/share/MusicVault/installations/<identificador-de-ruta>/` en Linux.
Actualizar en la misma carpeta conserva la identidad; instalar en otra ruta
genera otra identidad. Los archivos contienen la sesión anónima: no compartirlos.
No contienen claves administrativas ni identificadores de hardware.

## Dependencias y ejecución

Con Node.js 22 o posterior y npm instalados, ejecutar desde `windows/` o `linux/`:

```
python packaging/setup_jams.py
```

Instala el SDK Firebase fijado en `package-lock.json`, sin scripts de instalación.
Los constructores de distribución incluyen ese cliente; Linux verifica que esté
preparado antes de generar el paquete. Las dependencias npm son JavaScript y
pueden copiarse entre las dos distribuciones. El diagnóstico `--doctor` comprueba
el cliente Firebase y Node además de las dependencias anteriores.

En este equipo quedó preparado el lanzador `windows/MusicVault.exe`, con Python
embebido 3.12.10, las versiones Qt del requirements original, libmpv, FFmpeg,
Node 24.19.0 y NewPipeExtractor 0.26.5 con Java privado. Python embebido se descargó
y comprobó contra el SHA256 del lock existente. No se cambiaron paquetes globales.
No se generó un nuevo instalador NSIS ni una release pública.

## Verificación realizada

- Windows: 19 pruebas de mezcla DJ, interfaz e integración JAMS, incluida
  preparación y reproducción de un WAV con libmpv real y controles sin eco.
- Windows: cuatro pruebas de audio independientes: decodificación, pausa,
  posición, final de pista, crossfade y continuación.
- Código Linux: 18 pruebas ejecutadas en Windows, con audio nativo excluido.
  Esto **no certifica la ejecución nativa de Linux**, D-Bus ni su empaquetado.
- Dos clientes Node reales contra Firebase: autenticación, sala, código corto,
  cola, control desde invitado, amistad, invitación y persistencia de ambos UID
  al reiniciar. La prueba elimina sala, amistades y cuentas temporales.
- Ventana y diálogo JAMS renderizados con Qt y autenticación confirmada desde
  el puente de la interfaz. Capturas locales en `build/`.
- `--doctor`, compilación sintáctica de ambos árboles y `git diff --check`.

Prueba de protocolo reproducible (utiliza dos cuentas temporales reales):

```
python packaging/check_jams_live.py --live
```

## Límites y pendientes de paridad

La prueba de red utilizó dos clientes de escritorio con el protocolo Android;
no se midió sincronización acústica entre un teléfono físico y Windows/Linux.
Los enlaces se pegan dentro de la aplicación: falta registrar su apertura directa
desde el sistema operativo. Las invitaciones de sala se aceptan desde Amigos;
no hay notificaciones nativas del sistema para solicitudes e invitaciones.

La ventana de escritorio adapta ahora el diseño de `JamsScreen.kt`: mesa de
escucha con avatares anónimos, disco central, sala de espera con crear/unirse,
código copiable, menú de sala, controles de reproducción y cola con portadas,
Me gusta y acciones por canción. Amigos y solicitudes tienen su propia vista.
El UID de instalación está en Información, fuera de la pantalla principal.

No se afirma paridad completa con todo Android. Siguen pendientes de portar o
verificar las diferencias generales de la auditoría anterior, entre ellas
respaldo JSON Android y cifrado/programación de respaldos, automatización y
restricciones de descarga, importación/exportación M3U, normalización de volumen,
omitir silencios y fuentes/alineación de letras. El actualizador de APK es una
función específica de Android y no se incorporó como actualizador de escritorio.
`windows/docs/ANDROID_PARITY_AUDIT.md` es una auditoría histórica: sus filas de
DJ/Jam no reflejan el motor DJ añadido después ni las salas de esta revisión.
