# MusicVault portable para Windows x64

Adaptación de la versión Linux 0.8.0, sincronizada con las mejoras de experiencia de Android. Conserva sus pantallas, colores, fuentes y recursos Qt, biblioteca, favoritos, playlists, búsqueda, descargas, Spotify/SongMirror, letras, cola, ecualizador, crossfade, temporizador, mini reproductor y bandeja.

## Abrir

1. Extrae **todo** `MusicVault-Windows-x64.zip` en una carpeta con permiso de escritura, por ejemplo Documentos o una memoria USB. No lo ejecutes dentro del ZIP ni en Archivos de programa.
2. Abre **`MusicVault.exe`**. Mantén el ejecutable junto a las carpetas del paquete. El paquete contiene Python, Qt/WebEngine, libmpv, FFmpeg, yt-dlp y Node; no necesita instalación ni permisos de administrador.
3. `Diagnostico.cmd` comprueba las dependencias. Si el arranque falla, consulta `Data/startup-error.log`.

Destino: Windows 10/11 de 64 bits (x64). Las búsquedas, portadas remotas, Spotify y descargas necesitan Internet. La reproducción de archivos locales funciona sin conexión.

## Portabilidad

La biblioteca, preferencias, descargas predeterminadas y cachés se guardan en `Data/`, junto al lanzador. Cierra MusicVault antes de mover **la carpeta completa**. Al abrirla se actualizan las rutas internas de la base de datos, incluso cuando cambia la letra de la unidad USB. Los archivos importados desde otras carpetas y las descargas configuradas fuera de `Data/` siguen dependiendo de su ubicación externa; para transportarlos, guárdalos dentro de `Data/` antes de importarlos.

`python\python.exe launch.py --data-dir "D:\Mi biblioteca"` permite elegir otro directorio. No copies una biblioteca mientras la app está abierta. Las bases de Linux no se migran automáticamente: importa los archivos de audio en Windows.

La sesión Spotify es un archivo local sensible dentro de Data. Desconecta la cuenta antes de compartir la carpeta. El navegador integrado usa un perfil temporal. No se guarda la contraseña.

Las teclas multimedia se registran con Windows para reproducir/pausar, detener y cambiar de pista, también desde la bandeja; otro programa puede tenerlas reservadas. MPRIS/D-Bus es exclusivo de Linux y se sustituye por esta integración. No se incluye panel SMTC en la pantalla de bloqueo.

## Desarrollo y reconstrucción

El código completo está en `windows/musicvault/`; no depende de `linux/`. El lanzador nativo ya está incluido. Para recompilarlo, instala MinGW-w64 y ejecuta `python packaging/build_launcher.py`.

Para ensamblar el paquete, usa una máquina con Python, pip, 7-Zip e Internet:

```
python packaging/build_portable.py
```

Produce `dist/MusicVault-Windows-x64/` y el ZIP del mismo nombre. El ensamblado funciona en Linux y Windows usando el paquete embebido oficial de Python y ruedas binarias Windows. Las versiones y SHA256 quedan en `packaging/dependencies.lock.json`; las reconstrucciones verifican esos hashes. No descarga nada durante el arranque de la app.

Referencias: [Python embebido](https://docs.python.org/3.12/using/windows.html#the-embeddable-package), [libmpv para Windows](https://mpv.io/installation/).

## Verificación

Consulta `VERIFICATION.md` para los resultados y límites de las pruebas realizadas. El diagnóstico no comprueba sonido audible ni la disponibilidad de servicios externos.
