# Verificación local — 2026-09-08

## Corrección de avisos 0.7.1

- Los fallos DNS y las interrupciones de conexión se traducen a mensajes breves en español, también al leer registros antiguos. El aviso permite reintentar y abrir los detalles técnicos completos; la barra de estado deja de mostrar la traza cortada.
- La prueba de descarga real del clip público `jNQXAC9IVRw` se ejecutó fuera del sandbox, en un directorio temporal: 332342 bytes, 19,032 segundos, dos reproducciones locales y cero descargas adicionales. La resolución de YouTube funcionó fuera del entorno restringido. Esto acredita esta prueba puntual; no garantiza disponibilidad permanente de la red ni de todos los videos.

## Escritorio 0.7.0

- Navegación lateral, accesos a colecciones, búsqueda superior, portadas y transporte inferior con controles centrados. Paleta original conservada. Revisión visual de Inicio, Biblioteca, Favoritos, Álbumes, Buscar y Descargas a 1440 × 900; Inicio y Biblioteca también a 920 × 680, con biblioteca temporal de 40 pistas y portadas de prueba.
- Suite: **25 pruebas aprobadas y una omitida** en modo offscreen. La prueba omitida de MPRIS se ejecutó por separado sobre bus privado y pasó: **26 pruebas aprobadas en total**. Se usan audio nulo, WAV de prueba y proveedores simulados.
- Regresiones adicionales: 2.000 canciones solicitan menos de 25 portadas al mostrar la tabla; desplazar al final solicita las portadas correspondientes; se conservan selección y progreso; diez consumidores de una misma portada producen una sola solicitud; búsqueda global y accesos de playlists funcionan; el temporizador del vinilo se detiene al ocultarlo y tras pausar.
- Comparación sintética local de actualización de 10.000 pistas, tres pasadas con Qt offscreen: mediana **856,0 ms** recreando las seis celdas por canción con el algoritmo anterior; **149,1 ms** con el modelo nuevo. Ambos incluyen comprobación local de archivos y procesamiento de eventos; las portadas de red, consultas SQLite y el audio quedan fuera de esta medición. No representa una aceleración de toda la aplicación. El script reproducible está en `tests/benchmark_render.py`.
- La generación visual con 12ui no estuvo disponible porque la herramienta solicitó conexión de cuenta. La implementación Qt se verificó mediante capturas de la aplicación real.

## Corrección 0.6.1

- Diagnóstico de la biblioteca existente mediante una copia temporal: 15 registros de descarga fallidos por HTTP 403 y cero archivos locales. yt-dlp del sistema era 2026.03.17 y no detectaba un runtime JavaScript.
- Se incorporó yt-dlp oficial 2026.08.19 con checksum SHA-256 verificado y EJS. Se detectó Node 24.19.0 instalado por nvm. Las pruebas también verifican la detección de nvm cuando Node no aparece en PATH.
- Descarga real del clip público `jNQXAC9IVRw`: 332342 bytes, duración 19.032 s, nueve actualizaciones de progreso. Se reprodujo el archivo local dos veces desde una ficha remota antigua: cero solicitudes adicionales de descarga. Todo se realizó en un directorio temporal, sin modificar la biblioteca del usuario. El primer video de prueba, `BaW_jenozKc`, ya no estaba disponible y se descartó.
- Suite completa: **23 pruebas aprobadas**, incluida MPRIS sobre bus privado. Después de ajustar la ubicación de la caché y los botones de Descargas se volvieron a ejecutar las ocho pruebas de servicios/interfaz, todas aprobadas.
- Nuevas regresiones: las descargas pendientes/fallidas no cuentan como archivos locales, se recuperan rutas desde registros completos, una ficha antigua no borra rutas válidas y un error exige reintento explícito. Descargar, detener y reproducir de nuevo reutiliza el archivo guardado.
- Revisión visual mediante capturas: inicio con estante de seis tarjetas, biblioteca a 920 × 680 y tornamesa con brazo, fader y controles circulares. Se usan las fuentes originales Inter, Space Grotesk e IBM Plex Mono y la paleta Android.
- Se generaron el `.deb` amd64 y el `.tar.gz` 0.6.1, con fuentes y motor de descarga incluidos. Se comprueba el arranque desde ambos paquetes extraídos y el diagnóstico de dependencias.

## Verificación inicial 0.6.0

Entorno: Ubuntu 26.04.1 LTS, Linux x86_64, Python del sistema 3.14.4, Qt 6.10.2 y libmpv ABI 2.

- 15 pruebas de la suite pasaron con un bus D-Bus privado, incluyendo la prueba MPRIS. Después se añadieron y ejecutaron tres pruebas específicas de búsqueda YouTube, continuación relacionada y descargas compartidas: las tres pasaron. Total: 18 pruebas verificadas.
- Se decodificaron archivos WAV generados con libmpv real y salida de audio `null`. Se verificaron pausa, seek, final de pista, crossfade, ecualizador, temporizador y edición de cola.
- La prueba MPRIS llamó al servicio desde `gdbus`: introspección, metadatos, pausa, reproducción, volumen, seek y stop.
- La prueba de interfaz recorrió las secciones y abrió colecciones, cola, reproductor y ajustes. Se inspeccionaron capturas de la ventana y del reproductor, incluida la ventana a 920 × 620.
- Se probaron cancelación y limpieza de procesos de descarga, publicación de audio validado, caché, alternativa sin conexión, importaciones y persistencia. Las respuestas de proveedores y las descargas externas de esas pruebas fueron simuladas.
- Una consulta de lectura real a Deezer (`Daft Punk`, límite 1) devolvió un resultado. No se realizó una descarga de música externa de extremo a extremo ni una comprobación de todos los proveedores en vivo.
- `./linux/run.sh --doctor` encontró todas las dependencias y confirmó Linux x86_64.
- Se generaron `linux/dist/musicvault_0.6.0_amd64.deb` y `linux/dist/MusicVault-0.6.0-linux-x86_64.tar.gz`. Se extrajeron en `/tmp/musicvault-package-check` y se ejecutó `--smoke-test` con cada lanzador, con salida exitosa.
- Se validó el archivo `.desktop` con `desktop-file-validate` y se comprobó el diff con `git diff --check`.

Las advertencias de las pruebas D-Bus corresponden a deprecaciones de PyGObject con Python 3.14. No se probaron altavoces físicos, instalación mediante APT, Wayland/X11 interactivo ni otras distribuciones. Los paquetes dependen de las bibliotecas del sistema; el tarball no es autocontenido.
