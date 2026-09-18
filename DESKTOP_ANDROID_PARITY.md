# MusicVault desktop 0.8.0 — sincronización con Android 0.5.5

Esta revisión porta a **Linux** y **Windows** las mejoras de UX/rendimiento que se fueron incorporando en Android, adaptándolas a Qt en vez de copiar literalmente componentes Compose.

## Primera ejecución y recomendaciones

- Onboarding en dos pasos: **Géneros → Artistas**.
- Catálogo de artistas por género incluido localmente; cambiar de pantalla no depende de red.
- Las sugerencias de artistas se preparan mientras todavía se eligen géneros.
- Lista de artistas basada en vista/modelo Qt con filas uniformes y filtrado local.
- Preferencias guardadas en `settings` sin destruir el esquema de la biblioteca.
- Recomendaciones por artista balanceadas round-robin para evitar que un solo artista monopolice Inicio.
- Si un artista tarda o falla, no borra los resultados ya obtenidos de los demás; los resultados tardíos pueden incorporarse después del primer handoff.
- Caché local de recomendaciones y refresco de red como máximo una vez por semana en lanzamientos normales.
- En la primera configuración, Home carga detrás de un overlay **100 % opaco**. El overlay espera a datos y primeras portadas, con timeout de seguridad.

## Búsqueda

- Selector segmentado **Canciones | Álbumes**.
- Canciones y álbumes se consultan en trabajos independientes; una respuesta lenta no bloquea la otra.
- Deezer es el camino rápido; iTunes/MusicBrainz quedan como fallback para canciones.
- Álbumes usan búsqueda específica de álbumes y se validan al abrirse, no antes del primer paint.
- Deezer album detail consume `tracks.data`, igual que la corrección Android v4.
- Resultados de búsqueda y álbumes conservan caché offline.

## Playlists e importación

- Nuevo selector de canciones con `QListView` y `QAbstractListModel`: no crea un widget por canción.
- Buscador local por **canción o artista**.
- La selección persiste al filtrar.
- El mismo selector se usa al revisar playlists de Spotify/SongMirror.
- La carátula de la primera canción seleccionada se usa como portada cuando la colección no trae una propia.
- Si una playlist vacía recibe después su primera canción con carátula, se completa automáticamente.
- Botón dedicado de **bote de basura blanco** para borrar la colección actual; borrar la playlist no borra los audios.

## Álbumes y listas grandes

- El detalle de álbum mantiene una `QTableView` nativa con viewport acotado; ya no expande todas las filas para delegar todo el scroll al contenedor exterior.
- Portadas se cargan solo para filas visibles mediante el modelo existente.
- Los últimos **12 álbumes remotos** se cachean en memoria para reaperturas instantáneas.
- Abrir otro álbum descarta las respuestas del álbum anterior mediante una generación de búsqueda.
- El selector de playlists y la pantalla de descargas usan filas uniformes y scroll por píxel.
- Linux ahora usa el mismo índice FTS5, filtros SQL y firmas de escaneo incremental que la versión Windows para evitar deserializar/releer toda una biblioteca grande en cada filtro o rescan.

## Descargas

- El estado de descarga de las canciones incorpora la misma **barra lineal delgada** usada en Android.
- La pantalla Descargas también dibuja una barra lineal en trabajos activos, sin insertar widgets por fila.
- El progreso persistido se agrupa para no escribir SQLite en cada pequeño cambio de porcentaje.

## Compatibilidad de datos

La revisión de escritorio añade columnas/índices auxiliares de búsqueda y escaneo cuando faltan, además de preferencias/cachés. La migración es in-place: no borra `tracks`, `downloads`, `collections`, `members`, favoritos ni historial. La portada de una colección solo se completa automáticamente cuando estaba vacía.

## Versiones

- Android incluido en este árbol: **0.5.5 / versionCode 16**.
- Linux: **0.8.0**.
- Windows: **0.8.0**.
