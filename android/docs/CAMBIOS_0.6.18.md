# Cambios de MusicVault Android 0.6.18

Fecha: 6 de noviembre de 2026.
Versión: `0.6.18`, código de versión Android: `41`.
Commit de implementación: `641e7424d1b7586ced7d1d2de9659fb646bc464b`.
Release: https://github.com/Donatelatrump/MusicVault/releases/tag/android-v0.6.18

## JAMS: eliminación de canciones

- Todos los participantes pueden borrar canciones de la cola compartida, incluidas las añadidas por otros usuarios o por el anfitrión.
- Se eliminaron las restricciones de anfitrión en la interfaz y el controlador.
- Se actualizaron y desplegaron las reglas de Firebase Realtime Database para permitir esta operación a miembros de salas vigentes; los usuarios externos siguen sin poder borrar canciones.
- Al borrar la canción actual, su eliminación y el cambio del estado compartido de reproducción se guardan en una operación atómica. Se selecciona la siguiente canción disponible o se detiene la reproducción si la cola queda vacía.

## JAMS: favoritos y playlists locales

- Cada canción incluye un botón Me gusta para guardarla o retirarla de los favoritos locales.
- El estado del botón refleja los favoritos del usuario.
- Cuando existen playlists locales, un botón adicional permite elegir a cuál añadir la canción.
- Estas acciones guardan referencias locales; no descargan automáticamente el audio ni modifican los favoritos de otros participantes.

## Perfiles de artistas

- Se añadió el acceso Artistas desde la pantalla de búsqueda.
- Se pueden buscar artistas y abrir un perfil con fotografía, canciones destacadas y álbumes.
- Dentro del perfil se pueden buscar títulos; los resultados se filtran por identificador de artista.
- Los álbumes permiten consultar sus pistas. Más álbumes carga páginas adicionales del catálogo.
- Las canciones se pueden reproducir o solicitar para descarga mediante los flujos existentes de la app.
- Los perfiles utilizan los endpoints públicos de metadatos de Deezer; la obtención del audio sigue usando los proveedores de MusicVault.
- Se incluyen estados de carga, error y reintento, y textos en español e inglés para los controles nuevos.

## Notificación de reproducción

- Se añadieron comandos de sesión Anterior y Siguiente para JAMS mediante las preferencias de botones de Media3.
- Su disponibilidad se calcula a partir de la cola compartida y la canción seleccionada, en vez de depender de la cola local de una sola pista preparada.
- Los comandos de la notificación se envían al controlador de la sala y dejan de usarse al salir de JAMS.

## Calidad de audio incluida en esta actualización

Esta release también incorpora el trabajo previo de calidad que todavía no se había publicado tras retirar la 0.6.17:

- Ajustes independientes para streaming y descargas: modo ligero, máxima calidad y Beta.
- Beta consulta Audius sin cuenta ni claves y acepta originales FLAC/WAV/AIFF descargables, sin condiciones de acceso, con coincidencia normalizada de título y artista.
- Se comprueba la cabecera real del archivo; para WAV se comprueba que el formato sea PCM o punto flotante.
- Si no existe una opción válida o la consulta falla, se utiliza la fuente habitual en máxima calidad. Beta no garantiza disponer de una versión lossless de cada canción.
- Las cachés se separan por calidad; la reproducción Beta distingue la URL de origen para evitar mezclar bytes de formatos o proveedores diferentes.
- Las descargas no reutilizan el audio de la caché de streaming. El etiquetado conserva los formatos originales compatibles sin convertirlos a AAC.
- Los archivos ya descargados se conservan y no se reemplazan automáticamente.

Detalles: [AUDIO_QUALITY.md](AUDIO_QUALITY.md).

## Validación realizada

- Compilación optimizada release completada correctamente.
- 106 pruebas unitarias Android aprobadas.
- Lint de debug y comprobaciones vitales de release completados sin errores; existen advertencias pendientes.
- Verificación de reglas de descarga release aprobada.
- 14 pruebas de reglas Firebase aprobadas en emulador, incluidas las de eliminación por invitados y cambio atómico al borrar la canción actual.
- Prueba real con dos usuarios anónimos de Firebase aprobada: un invitado eliminó canciones del anfitrión, incluida la actual, y se comprobó el estado compartido resultante. También se verificaron cola, controles compartidos y replicación de revisiones.
- Consultas reales de búsqueda de artistas, canciones destacadas, álbumes y títulos dentro de un artista respondieron correctamente.
- En el trabajo previo de Audius se comprobó acceso anónimo a un original WAV PCM de 16 bits, estéreo, 48 kHz.
- Firmas de los APK ARM64 y ARM32 verificadas, conservando el certificado utilizado en las versiones anteriores.
- Archivos publicados en GitHub comprobados por tamaño y SHA-256 frente al manifiesto de actualización.

## Publicación

La release contiene:

- `app-arm64-v8a-release.apk`.
- `app-armeabi-v7a-release.apk`.
- `musicvault-android-update.json`, con versión, URLs, tamaños y sumas SHA-256.

La actualización se puede buscar desde Ajustes → Actualizaciones. Las reglas de Firebase necesarias para el borrado compartido ya están desplegadas.

## Validación pendiente

No había un teléfono conectado durante esta entrega. Queda pendiente comprobar en dispositivo:

- La presentación y navegación de los perfiles de artistas.
- Los botones de favoritos, playlists y eliminación desde la cuenta de un invitado.
- La disponibilidad y funcionamiento de Anterior/Siguiente en la notificación y pantalla de bloqueo.
- La reproducción completa de originales Audius y la sincronización acústica entre teléfonos.

Las pruebas automatizadas y las consultas de servicios no sustituyen estas comprobaciones visuales y de reproducción en hardware real.
