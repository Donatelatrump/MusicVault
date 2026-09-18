# Music Vault v11 — actualización sin borrar datos

## Problema corregido

Algunas versiones antiguas podían dejar `musicvault.db` en versión 17 con una forma de `user_preferences` distinta a la que Room esperaba. En una instalación limpia la base se crea correctamente, pero al instalar el APK nuevo encima del viejo Room valida la base existente y la app podía cerrarse al arrancar.

## Cambios

- Base de datos: 17 → 18.
- `MIGRATION_16_17` ahora crea `user_preferences` con los `NOT NULL` correctos.
- Nueva `MIGRATION_17_18` de compatibilidad:
  - reconstruye `user_preferences` sin perder géneros/artistas guardados;
  - acepta columnas antiguas nulas o faltantes;
  - reconstruye únicamente el índice `tracks_fts` y sus triggers;
  - NO borra `tracks`, `download_records`, playlists, favoritos ni historial.
- `versionCode` 13 / `versionName` 0.5.2.

La actualización debe instalarse directamente encima del APK anterior, sin borrar datos ni desinstalar.
