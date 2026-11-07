# MusicVault 0.10.0 para Windows x64

**Instalador completo:** abre `MusicVault-Windows-x64-0.10.0-Setup.exe`.
**Portable:** extrae completo `MusicVault-Windows-x64-0.10.0.zip` y abre `MusicVault.exe`.
Ambos incluyen Python, Qt/WebEngine, libmpv, FFmpeg, yt-dlp, Node.js, Firebase y
NewPipeExtractor con Java privado. No necesitas instalar esas dependencias aparte.
Destino: Windows 10/11 x64, instalación por usuario sin permisos de administrador.

La versión 0.10.0 incorpora JAMS con salas visuales, amigos e invitaciones; perfiles
de artista con canciones y álbumes; más de 2,200 géneros con artistas paginados y
fotos; carátulas de alta resolución; Favoritos inmediatos por streaming; corazón
rojo relleno y reproducción circular. DJ para ti está en Inicio.

## Actualizaciones

MusicVault consulta las releases estables de GitHub al arrancar y al volver al
primer plano, con un máximo de una comprobación exitosa cada 24 horas.
**Ajustes → Actualizaciones → Buscar actualizaciones** permite comprobar manualmente
y activar o desactivar la revisión automática. «Ahora no» silencia esa versión;
la actualización permanece disponible en Ajustes.

Al pulsar Actualizar se descarga el instalador completo y se comprueba su tamaño
y SHA-256. Al aceptar la instalación, MusicVault cierra y abre el instalador en
la misma carpeta. **Data conserva biblioteca y preferencias.** La sesión anónima
JAMS se conserva al actualizar en la misma ruta; se almacena fuera de Data, en
`%LOCALAPPDATA%/MusicVault/installations/`. La distribución excluye esos datos
privados. No se incluyen credenciales de GitHub.

Para actualizar manualmente, cierra la aplicación y elige su carpeta actual en
el instalador. Para el portable, conserva Data al extraer los archivos nuevos.
Consulta `UPDATES.md` para reconstrucción y publicación de futuras actualizaciones.

## Notas históricas de 0.9.7

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

La sesión Spotify se almacena como archivo local sin cifrar dentro de Data. Desconecta la cuenta antes de compartir la carpeta o moverla a otro equipo. El navegador integrado usa un perfil temporal. No se guarda la contraseña.

Las teclas multimedia se registran con Windows para reproducir/pausar, detener y cambiar de pista, también desde la bandeja; otro programa puede tenerlas reservadas. MPRIS/D-Bus es exclusivo de Linux y se sustituye por esta integración. No se incluye panel SMTC en la pantalla de bloqueo.

## Desarrollo y reconstrucción

También se distribuye `MusicVault-Windows-x64-0.9.7-Setup.exe`, un instalador por usuario para Windows x64. Instala en `%LOCALAPPDATA%\Programs\MusicVault`, crea accesos en el menú Inicio y no requiere permisos de administrador. La desinstalación conserva `Data/` con la biblioteca y las preferencias.

Para generar el instalador después del portable, instala NSIS y ejecuta desde `windows/packaging`: `makensis installer.nsi`. Produce `dist/MusicVault-Windows-x64-0.9.7-Setup.exe`.

El código completo está en `windows/musicvault/`; no depende de `linux/`. El lanzador nativo ya está incluido. Para recompilarlo, instala MinGW-w64 y ejecuta `python packaging/build_launcher.py`.

Para ensamblar el paquete, usa una máquina con Python, pip, 7-Zip (o el extractor `7zr`) e Internet:

```
python packaging/build_portable.py
```

Produce `dist/MusicVault-Windows-x64-0.9.7/` y el ZIP del mismo nombre. El ensamblado funciona en Linux y Windows usando el paquete embebido oficial de Python y ruedas binarias Windows. Las versiones y SHA256 quedan en `packaging/dependencies.lock.json`; las reconstrucciones verifican esos hashes. No descarga nada durante el arranque de la app.

Referencias: [Python embebido](https://docs.python.org/3.12/using/windows.html#the-embeddable-package), [libmpv para Windows](https://mpv.io/installation/).

## Verificación

Consulta `VERIFICATION.md` para los resultados y límites de las pruebas realizadas. El diagnóstico no comprueba sonido audible ni la disponibilidad de servicios externos.

## Actualización pequeña para instalaciones existentes

Este parche incorpora NewPipeExtractor 0.26.5, igual que Android, con un Java privado incluido. Las búsquedas generales muestran YouTube primero y Deezer después; yt-dlp queda como respaldo. No necesitas instalar Java por separado. El paquete de actualización incorpora estas dependencias nuevas y reutiliza las demás.

Descarga `MusicVault-Windows-x64-0.9.7-Update1.exe` y ejecútalo. Detecta la instalación del usuario y aplica el parche sin volver a descargar Python, Qt, libmpv, FFmpeg, Node ni yt-dlp; después abre MusicVault. Si está abierto, usa **Salir** en el icono de la bandeja y pulsa Reintentar. Conserva `Data/`.

Para la versión portable, coloca el actualizador junto a `MusicVault.exe` o selecciona la carpeta del paquete cuando se solicite. Requiere la base Windows 0.9.7 con las mismas dependencias; para versiones anteriores utiliza el instalador completo. El actualizador valida los hashes antes de escribir y conserva originales para revertir si falla la copia.

Reconstrucción: genera primero el portable y ejecuta `python packaging/build_update.py` con NSIS disponible en PATH.

## JAMS compartidas, amigos e identidad de instalacion

Disponible desde **JAMS / Amigos**. Consulta [la implementacion y verificacion](../DESKTOP_JAMS.md) para compatibilidad con Android, dependencias y limites. Desde esta carpeta ejecuta `python packaging/setup_jams.py` para instalar el cliente Firebase fijado.

El onboarding incluye el catalogo completo de generos de Android (2,234 opciones combinadas), buscador local y artistas por genero con paginas de 20 resultados. Las fotos y las paginas consultadas se guardan en cache; la seleccion se conserva al filtrar y cargar mas artistas.

Los nombres de artista de la tabla y del reproductor abren su perfil: foto, canciones destacadas, busqueda de canciones, albumes paginados y pistas de cada album. Desde el perfil puedes reproducir, descargar, anadir a cola y administrar Me gusta. Tambien esta disponible en el menu contextual de las canciones.
