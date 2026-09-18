# MusicVault — entrega sincronizada Android / Linux / Windows

## Versiones incluidas

- Android: 0.5.5 (`versionCode 16`)
- Linux: 0.8.0
- Windows: 0.8.0

## Paridad llevada desde Android a escritorio

- Onboarding Géneros → Artistas con catálogo local y transición sin depender de red.
- Recomendaciones balanceadas entre todos los artistas elegidos, caché y resultados tardíos no bloqueantes.
- Pantalla opaca de preparación durante la primera carga.
- Buscar separado en Canciones / Álbumes con trabajos independientes.
- Corrección de álbumes Deezer (`tracks.data`) y caché LRU de detalle.
- Creación/importación de playlists con selector optimizado, búsqueda por canción/artista y portada automática.
- Eliminación de playlists con icono de bote de basura.
- Barras lineales delgadas para progreso de descargas.
- Optimización de listas grandes mediante vistas/modelos nativos Qt.
- Linux adopta FTS5, filtros SQL, firmas de escaneo incremental y caché de disponibilidad usados por Windows.

## Verificación de esta entrega

- `compileall` sin errores en fuentes, empaquetado y tests Python de Linux y Windows.
- Smoke de catálogo local de artistas, normalización de acentos y round-robin: OK.
- Smoke de migración SQLite y FTS5 desde esquema antiguo: OK en Linux y Windows.
- Paquetes Linux generados: `.deb` amd64 y `.tar.gz` x86_64 0.8.0.
- El árbol Android no contiene `LazyColumn` y conserva la revisión 0.5.5 actual.

## Límites del entorno de verificación

- No fue posible ejecutar la suite GUI Qt completa aquí porque PyQt6 no está instalado y el entorno no permite descargarlo.
- No fue posible reconstruir el launcher nativo de Windows con MinGW; las fuentes/manifiesto son 0.8.0 y el launcher existente conserva su formato PE x64 con metadata textual actualizada.
- No fue posible recompilar Android porque Gradle 8.11.1 necesita descargarse desde `services.gradle.org`, inaccesible desde este entorno.
