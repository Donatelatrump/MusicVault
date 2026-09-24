# MusicVault portable para Windows x64

Adaptación de la versión Linux 0.9.7, sincronizada con el modelo de reproducción de Android. Los resultados remotos se reproducen mediante streaming sin descargarlos primero. Incluye caché temporal de 24 horas o hasta 1 GB, preparación de las canciones visibles y reutilización de la caché al descargar. La búsqueda reúne canciones, álbumes y playlists de YouTube; las playlists se pueden guardar sin descargar sus pistas. Descargar sigue siendo una acción explícita.

La interfaz incorpora los cambios de Linux 0.9.7: Ajustes rediseñados, importación de carpetas desde Ajustes, Spotify administrado desde Ajustes y enlaces detectados automáticamente, búsqueda remota solo al pulsar Enter o Buscar, controles de reproducción y navegación actualizados. Conserva biblioteca, favoritos, letras, cola, crossfade, temporizador, mini reproductor y bandeja. El ecualizador se retiró en esta versión.

## Abrir

1. Extrae **todo** `MusicVault-Windows-x64-0.9.7.zip` en una carpeta con permiso de escritura, por ejemplo Documentos o una memoria USB. No lo ejecutes dentro del ZIP ni en Archivos de programa.
2. Abre **`MusicVault.exe`**. Mantén el ejecutable junto a las carpetas del paquete. El paquete contiene Python, Qt/WebEngine, libmpv, FFmpeg, yt-dlp y Node; no necesita instalación ni permisos de administrador.
3. `Diagnostico.cmd` comprueba las dependencias. Si el arranque falla, consulta `Data/startup-error.log`.

Destino: Windows 10/11 de 64 bits (x64). Las búsquedas, portadas remotas, streaming, Spotify y descargas necesitan Internet. La reproducción de archivos locales funciona sin conexión.

## Portabilidad

La biblioteca, preferencias, descargas predeterminadas y cachés se guardan en `Data/`, junto al lanzador. Cierra MusicVault antes de mover **la carpeta completa**. Al abrirla se actualizan las rutas internas de la base de datos, incluso cuando cambia la letra de la unidad USB. Los archivos importados desde otras carpetas y las descargas configuradas fuera de `Data/` siguen dependiendo de su ubicación externa; para transportarlos, guárdalos dentro de `Data/` antes de importarlos.

Al actualizar desde una carpeta portable anterior, cierra la versión antigua y copia su carpeta `Data/` junto al nuevo `MusicVault.exe`. El ZIP de distribución excluye `Data/` para no incluir bibliotecas ni sesiones privadas.

`python\python.exe launch.py --data-dir "D:\Mi biblioteca"` permite elegir otro directorio. No copies una biblioteca mientras la app está abierta. Las bases de Linux no se migran automáticamente: importa los archivos de audio en Windows.

La sesión Spotify es un archivo local sensible dentro de Data. Desconecta la cuenta antes de compartir la carpeta. El navegador integrado usa un perfil temporal. No se guarda la contraseña.

Las teclas multimedia se registran con Windows para reproducir/pausar, detener y cambiar de pista, también desde la bandeja; otro programa puede tenerlas reservadas. MPRIS/D-Bus es exclusivo de Linux y se sustituye por esta integración. No se incluye panel SMTC en la pantalla de bloqueo.

## Desarrollo y reconstrucción

El código completo está en `windows/musicvault/`; no depende de `linux/`. El lanzador nativo ya está incluido. Para recompilarlo, instala MinGW-w64 y ejecuta `python packaging/build_launcher.py`.

Para ensamblar el paquete, usa una máquina con Python, pip, 7-Zip (o el extractor `7zr`) e Internet:

```
python packaging/build_portable.py
```

Produce `dist/MusicVault-Windows-x64-0.9.7/` y el ZIP del mismo nombre. El ensamblado funciona en Linux y Windows usando el paquete embebido oficial de Python y ruedas binarias Windows. Las versiones y SHA256 quedan en `packaging/dependencies.lock.json`; las reconstrucciones verifican esos hashes. No descarga nada durante el arranque de la app.

Referencias: [Python embebido](https://docs.python.org/3.12/using/windows.html#the-embeddable-package), [libmpv para Windows](https://mpv.io/installation/).

## Verificación

Consulta `VERIFICATION.md` para los resultados y límites de las pruebas realizadas. El diagnóstico no comprueba sonido audible ni la disponibilidad de servicios externos.
