# MusicVault Android: documentación integral del proyecto

Fecha de revisión: 8 de octubre de 2026.

Revisión inicial: [hoja de ruta priorizada](MEJORAS_ANDROID_PRIORIZADAS.md). Estado posterior y límites de verificación: [implementación de la hoja de ruta](ROADMAP_ANDROID_IMPLEMENTADA.md).

Este documento describe exclusivamente la aplicación de `android/`, a partir del código presente en el espacio de trabajo, incluidos los cambios locales todavía sin commit. No describe Linux, Windows ni interfaces web. Las versiones indicadas son las configuradas en este proyecto; no representan una recomendación de actualizar dependencias.

## Índice

1. [Qué es y qué permite hacer](#1-qué-es-y-qué-permite-hacer)
2. [Tecnologías y para qué las usamos](#2-tecnologías-y-para-qué-las-usamos)
3. [Estructura y arquitectura](#3-estructura-y-arquitectura)
4. [Arranque y estado](#4-arranque-y-estado)
5. [Diseño y pantallas](#5-diseño-y-pantallas)
6. [Cómo se construyen las listas visuales](#6-cómo-se-construyen-las-listas-visuales)
7. [Datos y persistencia](#7-datos-y-persistencia)
8. [Búsqueda y catálogo](#8-búsqueda-y-catálogo)
9. [Recomendaciones](#9-recomendaciones)
10. [Reproducción y audio](#10-reproducción-y-audio)
11. [Cachés](#11-cachés)
12. [Descargas](#12-descargas)
13. [Biblioteca local y almacenamiento](#13-biblioteca-local-y-almacenamiento)
14. [Spotify y playlists de YouTube](#14-spotify-y-playlists-de-youtube)
15. [Letras](#15-letras)
16. [Respaldos y migración](#16-respaldos-y-migración)
17. [Integración con Android](#17-integración-con-android)
18. [Compilación y distribución](#18-compilación-y-distribución)
19. [Pruebas y diagnóstico](#19-pruebas-y-diagnóstico)
20. [Guía para modificar el proyecto](#20-guía-para-modificar-el-proyecto)
21. [Inventario de código](#21-inventario-de-código)
22. [Límites y diferencias con notas antiguas](#22-límites-y-diferencias-con-notas-antiguas)

## 1. Qué es y qué permite hacer

MusicVault es una aplicación Android nativa de biblioteca y reproducción musical. Combina archivos del dispositivo, metadatos de catálogos remotos, reproducción por streaming, descargas explícitas y colecciones guardadas en el teléfono.

Sus funciones principales son:

- Configurar géneros y artistas favoritos durante la primera ejecución.
- Mostrar recomendaciones de canciones y álbumes.
- Buscar canciones, álbumes y playlists de YouTube.
- Reproducir archivos locales, descargas y audio remoto.
- Controlar reproducción desde mini reproductor, pantalla completa, notificación y widget.
- Gestionar favoritos, playlists y álbumes personalizados.
- Importar metadatos de canciones y playlists de Spotify.
- Descargar audio, observar el progreso, cancelar y reintentar.
- Consultar letras normales y sincronizadas, y conservar letras para uso local.
- Ajustar apariencia, crossfade, ecualización, repetición y temporizador.
- Exportar/importar biblioteca y trasladar audio de una instalación de desarrollo a release.

Hay tres conceptos diferentes: **metadatos** describen una canción; **streaming** obtiene audio para escuchar y lo puede cachear temporalmente; **descarga** guarda audio como parte durable de la biblioteca. Importar una playlist no descarga sus canciones automáticamente.

## 2. Tecnologías y para qué las usamos

### 2.1 Lenguaje y construcción

| Tecnología | Versión/configuración | Uso real |
|---|---|---|
| Kotlin | 2.0.21 | Código de aplicación, modelos, servicios, adaptadores y lógica asíncrona. |
| Java/JVM | Toolchain 17 | Compilar Kotlin/Java y mantener compatibilidad de bytecode. |
| Android Gradle Plugin | 8.10.1 | Construir APK, integrar recursos, variantes, R8 y pruebas Android. |
| Gradle Wrapper | 8.11.1 | Ejecutar la construcción con una versión reproducible. |
| KSP | 2.0.21-1.0.28 | Generar implementaciones Room a partir de entidades y DAO. |
| Plugin Compose | 2.0.21 | Compilar funciones `@Composable`. |
| Kotlin BOM | 2.0.21, enforcedPlatform | Evitar que dependencias transitivas desalineen el runtime Kotlin. |
| Desugaring NIO | 2.0.3 | Compatibilidad de APIs Java usadas por el extractor con Android mínimo. |
| R8 y resource shrinking | Release/performance | Reducir y optimizar código y recursos del APK. |

La aplicación configura `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, `versionName = 0.5.11` y `versionCode = 22`. El mínimo corresponde a Android 8.0.

### 2.2 Interfaz, ciclo de vida y persistencia

| Dependencia | Versión | Para qué se usa |
|---|---|---|
| Compose BOM | 2024.12.01 | Alinear versiones de componentes Compose. |
| Compose UI, Material 3, icons-core | Gestionadas por BOM | Pantallas, tema, controles, diálogos, iconos y animaciones. |
| RecyclerView | 1.4.0 | Reciclar filas de listas largas y actualizarlas parcialmente. |
| Activity Compose | 1.10.0 | Hospedar Compose en `MainActivity`. |
| Core KTX | 1.15.0 | Utilidades Android y compatibilidad. |
| Core SplashScreen | 1.0.1 | Pantalla de arranque integrada con el sistema. |
| Lifecycle runtime/viewmodel/service | 2.8.7 | ViewModel, ámbitos de vida y observación de estado en UI. |
| DataStore Preferences | 1.1.3 | Propietario de ajustes AppPreferences; migra preferencias antiguas y mantiene fachada compatible. |
| Room runtime/ktx/compiler | 2.7.0 | SQLite, DAO reactivos, transacciones y migraciones. |
| WorkManager KTX | 2.10.0 | Descargas persistentes con restricciones, notificación y reintentos. |
| DocumentFile | 1.0.1 | Trabajar con carpetas elegidas mediante Storage Access Framework. |
| ProfileInstaller | 1.4.1 | Instalar el perfil ART existente; [benchmark opt-in](../benchmark/README.md) captura reglas reales para su actualización. |
| Coil Compose | 2.7.0 | Cargar portadas en Compose y Views, compartir cachés y limitar decodificación. |
| Coroutines y Flow | Runtime Kotlin/dependencias AndroidX | Trabajo fuera del hilo principal, cancelación y propagación reactiva. |

### 2.3 Audio y conectividad

| Dependencia/API | Versión | Para qué se usa |
|---|---|---|
| Media3 ExoPlayer | 1.10.1 | Decodificación y reproducción local/remota. |
| Media3 datasource/database | 1.10.1 | Fuentes de datos, caché de bytes y su índice. |
| Media3 session | 1.10.1 | Servicio y controles multimedia del sistema. |
| Media3 datasource-cronet | 1.10.1 | Conectar streaming con Cronet. |
| Cast framework | 22.0.0 | Sesión emisora para audio HTTP(S) accesible al receptor; la UI del reproductor es propia. |
| Play Services Cronet | 18.1.1 | Pila de red preferida para streaming, con alternativa HTTP si no está disponible. |
| NewPipeExtractor | 0.26.5 | Buscar YouTube, leer playlists y resolver URLs de audio. |
| OkHttp | 4.12.0 | Cliente del extractor y peticiones cancelables de letras. |
| youtubedl-android library | 0.18.1 | Ejecutar yt-dlp empaquetado, descargar y resolver como alternativa. |
| youtubedl-android ffmpeg | 0.18.1 | Procesar/converter audio durante adquisición. |
| AudioEffect/Equalizer/LoudnessEnhancer | Android | Efectos conectados a la sesión de audio. |
| MediaExtractor y MediaCodec | Android | Analizar el inicio de audio local para ajuste conservador de letras. |

Media3 está fijado en 1.10.1 por compatibilidad con la línea Kotlin/KSP usada en el proyecto. El comentario de Gradle explica el problema de metadatos Kotlin 2.2 con Media3 1.11.x.

### 2.4 Servicios de contenido

| Fuente | Qué aporta | Qué no debe confundirse |
|---|---|---|
| Deezer | Metadatos de canciones, artistas, álbumes y portadas. | Una coincidencia de catálogo no equivale a tener un archivo de audio local. |
| YouTube mediante NewPipe/yt-dlp | Búsqueda, playlists, coincidencias y resolución de audio; subtítulos cuando existen. | Una URL directa puede expirar. |
| MusicBrainz | Implementación alternativa de catálogo y metadatos. | Existe en el código, pero no forma parte del proveedor agregado predeterminado actual. |
| LRCLib | Letras normales y LRC sincronizado. | Una letra de otra edición puede tener tiempos incompatibles. |
| Spotify web | Metadatos para importar canciones/playlists mediante cliente de sesión. | No hay un reproductor Spotify SDK ni descarga directa de audio de Spotify. |

## 3. Estructura y arquitectura

El proyecto Gradle Android contiene un módulo `:app`, paquete y namespace `com.musicvault.app`. Es una arquitectura por responsabilidades con UI reactiva, ViewModel y repositorios; la creación de dependencias es manual, sin un contenedor Hilt/Dagger declarado.

```text
MainActivity / MusicVaultApplication
                │
                ▼
        MusicVaultApp (Compose)
                │ acciones y observación
                ▼
        MusicVaultViewModel
          ├── MusicRepository ── Room ── SQLite
          ├── CatalogRepository ── proveedores de metadatos
          ├── Providers ── NewPipe / yt-dlp / FFmpeg
          ├── PlayerController ── MediaController
          │                         │
          │                         ▼
          │                MusicPlaybackService
          │                   ├── ExoPlayer / MediaSession
          │                   └── StreamingCache / Cronet
          ├── DownloadQueue ── Room + WorkManager ── DownloadWorker
          ├── letras / Spotify / respaldos
          └── ConnectivityMonitor / preferencias / efectos
```

Las responsabilidades están agrupadas en `data`, `catalog`, `providers`, `player`, `download`, `library`, `lyrics`, `spotify`, `backup`, `network`, `permissions`, `widget` y `ui`.

El ViewModel concentra muchas decisiones de coordinación. `MusicVaultApp.kt` concentra una parte importante de las pantallas y diálogos. Los adaptadores nativos están separados para que las listas repetidas tengan su propio ciclo de creación, enlace y reciclado.

## 4. Arranque y estado

### 4.1 Application

`MusicVaultApplication` inicializa `Providers` y prepara `StreamingCache`. Implementa `ImageLoaderFactory`: Coil utiliza el mismo `ImageLoader` de `CoverImageCache` en todo el proceso, evitando cachés duplicadas entre componentes Compose y nativos.

### 4.2 Activity

`MainActivity` instala splash, activa edge-to-edge y crea la UI con `setContent { MusicVaultTheme { MusicVaultApp(...) } }`. La base se obtiene de forma diferida y el ViewModel se crea con su `Factory`.

Las fuentes se precargan en `Dispatchers.Default`; trabajo de proveedores se inicia en IO. Al volver a primer plano se revisan permisos y estado de reproducción.

### 4.3 Estado reactivo

El ViewModel utiliza `MutableStateFlow`, `StateFlow`, `combine`, `stateIn` y `viewModelScope`. La UI observa con `collectAsStateWithLifecycle`; las acciones de botones terminan en métodos del ViewModel.

Los DAO emiten `Flow`. El repositorio transforma entidades persistentes en modelos de UI. `distinctUntilChanged` reduce emisiones repetidas y `flowOn` desplaza transformaciones fuera del hilo principal cuando corresponde.

Estado estructural, progreso de reproducción y progreso de descargas tienen distintos ritmos de cambio. Separarlos evita recalcular recomendaciones o reconstruir listas en cada avance del reproductor.

## 5. Diseño y pantallas

### 5.1 Identidad visual

`ui/theme/Theme.kt` define un tema oscuro Material 3, superficies cálidas y acento latón. Los colores principales son:

| Token | Valor | Uso |
|---|---|---|
| Background | `#121110` | Fondo general. |
| Surface1 | `#1E1B18` | Tarjetas/superficies. |
| TextPrimary | `#F1EAD9` | Texto principal. |
| TextSecondary | `#B3A992` | Texto auxiliar. |
| Accent | `#E0A458` | Selección, acciones y foco. |
| Divider | `#322C25` | Separación entre superficies. |
| Favorite | `#C96A5E` | Favoritos. |

Space Grotesk se usa para títulos, Inter para lectura y Plex Mono para etiquetas técnicas. `VaultIcons.kt` contiene iconografía propia. Las filas nativas también crean controles y dibujos mediante `Canvas`, `Paint`, `Path` y `Drawable`.

### 5.2 Pantallas

| Área | Construcción y función |
|---|---|
| Onboarding | Compose; selección local de géneros/artistas y transición hacia recomendaciones. |
| Inicio | RecyclerView vertical con saludo, carruseles Compose y canciones nativas. |
| Búsqueda | Controles Compose y RecyclerView con canciones/álbumes/playlists. |
| Biblioteca | Controles/filtros y RecyclerView de canciones disponibles en biblioteca. |
| Colecciones | Listados y creación de playlists/álbumes personalizados. |
| Detalle de playlist/álbum | Cabecera y filas recicladas; acciones de reproducir, descargar y gestionar. |
| Mini reproductor | Compose; canción actual, portada y controles. |
| Reproductor completo | Compose; estilos vinilo/circular, progreso, letras y acciones. |
| Cola | Bottom sheet Compose con RecyclerView nativo. |
| Ajustes | Compose; preferencias de audio, diseño, almacenamiento y herramientas. |
| Descargas | Diálogo con información y acciones sobre la cola persistente. |
| Spotify | WebView de sesión y selección de canciones para importar. |

`PersistentSection` mantiene secciones principales montadas y controla su visibilidad. Al estar oculta una sección se evita enviar actualizaciones innecesarias a su RecyclerView. Los detalles y diálogos utilizan su propio estado y adaptadores.

## 6. Cómo se construyen las listas visuales

### 6.1 La combinación Compose + Views

Las listas verticales largas se implementan con **RecyclerView nativo dentro de Compose mediante `AndroidView`**. No se genera una nueva vista de canción por cada elemento del catálogo: se crean suficientes holders para las filas visibles y la caché, y se reutilizan al desplazar.

Compose se ocupa del marco de pantalla, controles y componentes visuales complejos. Las filas repetidas usan principalmente `LinearLayout`, `TextView`, `ImageView`, botones y `ProgressBar`, construidos en Kotlin.

Los carruseles horizontales de Inicio sí usan `LazyRow` Compose dentro de `ComposeView`. No debe interpretarse la migración de listas verticales como la eliminación de toda API lazy de Compose.

### 6.2 Recorrido desde los datos hasta la pantalla

```text
Room / proveedor remoto
        ↓
Repositorio convierte o combina datos
        ↓
ViewModel publica listas y estado
        ↓
Composable observa los datos de su pantalla
        ↓
AndroidView.factory crea RecyclerView y asigna adapter
        ↓
AndroidView.update entrega listas/estado al adapter existente
        ↓
submitList / AsyncListDiffer calcula diferencias
        ↓
onCreateViewHolder crea la estructura cuando hace falta
        ↓
onBindViewHolder coloca título, artista, portada y acciones
        ↓
RecyclerView coloca filas visibles y recicla las que salen
```

El adaptador se conserva con `remember` cuando corresponde. Recrearlo en cada recomposición perdería parte del beneficio del reciclado y podría alterar scroll o estado.

### 6.3 Identidad, contenido y tipos de fila

`DiffUtil.ItemCallback` distingue dos preguntas:

- `areItemsTheSame`: ¿es la misma canción/álbum/fila según su ID?
- `areContentsTheSame`: ¿cambió el contenido de ese elemento?

Los adaptadores activan `setHasStableIds(true)`. Los IDs derivan de identificadores de negocio con prefijos como `track:`, `album:` o `playlist:`. La búsqueda remota usa proveedor e ID para distinguir resultados de distintas fuentes.

`getItemViewType` diferencia cabeceras, canciones, álbumes y playlists. Cada tipo tiene un holder propio; así el RecyclerView reutiliza estructuras compatibles.

Un stable ID ayuda a preservar identidad, pero no reemplaza `DiffUtil` ni es una garantía matemática de ausencia de colisiones cuando se obtiene mediante hash.

### 6.4 Listado de Inicio

`HomeFeedAdapter` recibe recomendaciones, colecciones, selección, reproducción y conectividad. Construye una lista heterogénea `HomeFeedItem`:

1. `Greeting`.
2. `RecommendedTracks` si hay canciones.
3. `RecommendedAlbums` si hay álbumes.
4. `SongsHeader` si queda una lista de descubrimiento.
5. Un `TrackItem` por canción restante.

Las primeras ocho canciones se reservan para el carrusel; el bloque vertical usa `recommendations.drop(8)`, evitando repetirlas.

Las canciones verticales usan `NativeTrackHolder`. Saludo/cabeceras/carruseles usan `ComposeRowHolder`. Este configura `DisposeOnDetachedFromWindowOrReleasedFromPool` para gestionar correctamente la composición dentro del reciclado.

Las `LazyRow` tienen separación entre tarjetas y claves por elemento. Las tarjetas conectan con acciones del ViewModel y usan el cargador compartido de portadas.

### 6.5 Fila nativa de canción

`makeTrackRow` crea la jerarquía visual; `NativeTrackHolder` conserva referencias a portada, título, subtítulo, progreso, botón play y menú.

`bind` asigna la canción actual, los datos de acciones, disponibilidad y flujo de descarga. Los listeners consultan el elemento vinculado al holder para no ejecutar acciones sobre una canción que ocupó antes la misma vista.

El holder tiene una tarea de observación de progreso. Al cambiar de vínculo o reciclarse se limpia trabajo anterior y solicitudes de imagen según su implementación. Esto evita que aparezca la portada o el porcentaje de una canción anterior en una fila reutilizada.

### 6.6 Actualizaciones parciales mediante payload

Cuando solo cambia play/pause o la canción seleccionada se envía un payload, por ejemplo `FeedPayload.PLAYER_STATE` o `PAYLOAD_STATE`, con `notifyItemChanged`.

`onBindViewHolder(holder, position, payloads)` actualiza únicamente el estado necesario, sin volver a cargar título y portada. Inicio y búsqueda mantienen índices ID → posición para localizar filas afectadas. Biblioteca actualiza las filas de la selección anterior y nueva.

También hay comprobaciones por referencia/contenido antes de enviar listas. Una emisión del estado del reproductor no tiene por qué volver a ejecutar un diff sobre todas las canciones.

### 6.7 Búsqueda

`RemoteResultsAdapter` maneja `RemoteResultItem.HeaderItem`, `AlbumItem`, `TrackItem` y `PlaylistItem`. Cada resultado tiene un identificador estable con tipo y proveedor.

`updateItems` cambia contenido; `updateState` cambia descargados, porcentajes, selección y play/pause. El índice `positionByLocalId` relaciona filas remotas con IDs locales del formato `proveedor:recording:id`.

Las acciones incluyen cola, álbum, descargar, cancelar y eliminar. Las filas usan miniaturas acotadas y texto truncado para estabilizar medición.

El listener de scroll informa la ventana visible cuando RecyclerView queda en `SCROLL_STATE_IDLE`. El ViewModel usa esa ventana para preparar posibles reproducciones sin lanzar trabajo por cada píxel desplazado.

### 6.8 Biblioteca, álbumes y playlists

| Adaptador | Función |
|---|---|
| `LibraryFeedAdapter` | Canciones de biblioteca, selección y progreso por fila. |
| `AlbumFeedAdapter` | Tarjetas de colecciones representadas por playlists, con cabecera propia. |
| `LocalAlbumDetailAdapter` | Detalle de álbum local con cabecera y canciones. |
| `PlaylistDetailAdapter` | Cabecera Compose y canciones nativas; `AsyncListDiffer` y offsets para cabecera. |
| `PlaylistTrackPickerAdapter` | Selección de canciones para colecciones; payload de checkbox. |
| `SpotifyImportTrackPickerAdapter` | Selección de canciones de Spotify; payload de checkbox. |
| `YoutubePlaylistTracksAdapter` | Canciones de la vista previa de playlist de YouTube. |
| `PlaybackQueueAdapter` | Cola, elemento actual, mover y eliminar. |
| `SyncedLyricsAdapter` | Texto sincronizado, estilo activo y modo completo/seguimiento. |

El detalle de playlist vincula adaptador/estado al ID de la colección y coordina el primer layout para evitar una cabecera Compose momentáneamente de altura cero. La cabecera y las filas no necesitan reconstruirse juntas en cada cambio de descarga.

La cola usa una clave que incorpora ID e índice; esto permite representar más de una aparición de la misma canción.

### 6.9 Configuración del RecyclerView

Las pantallas ajustan `LinearLayoutManager`, prefetch, caché de holders y animaciones. Algunos valores presentes en el código:

| Lista | Prefetch inicial | Caché de vistas |
|---|---:|---:|
| Inicio | 8 | 12 |
| Búsqueda | 8 | 12 |
| Biblioteca | 10 | 12 |
| Colecciones (`PlaylistScreen`) | 8 | 10 |
| Detalle local / playlist | 6 | 10 |
| Detalle de álbum remoto | 10 | 16 |
| Letras | 10 | 16 |
| Cola | 8 | 12 |

Son cantidades de elementos/vistas, no tamaños en MB. El prefetch anticipa creación/enlace; la caché conserva vistas cercanas. Aumentarlos consume memoria y no acelera por sí mismo las peticiones de red.

Se desactivan animadores en listas sensibles para evitar parpadeo o animaciones de cambio por cada tick. Los selectores tienen altura delimitada para que su lista recicle dentro del diálogo.

### 6.10 Letras como lista visual

`SyncedLyricsAdapter` guarda `LyricLine(timeMs, text)`. La línea activa cambia color, tamaño, opacidad, escala y peso. En API 31+ puede aplicar `RenderEffect` para desenfocar líneas alejadas.

En modo de seguimiento se muestran líneas desde la activa; en modo completo se conserva toda la letra. La UI calcula posición a partir del estado del reproductor y centra la línea mediante `LinearLayoutManager`.

### 6.11 Listas pequeñas

Ajustes y selectores reducidos usan `Column` con `verticalScroll` cuando es apropiado. Ese patrón crea sus hijos; se reserva para contenido acotado. Las listas largas necesitan la virtualización de RecyclerView o una estructura lazy.

## 7. Datos y persistencia

Room mantiene una base de versión **20**, con migraciones explícitas desde las primeras versiones y `exportSchema = true`. Los JSON versionados viven en `app/schemas/`; 19 se exportó antes del cambio y 18 es una fixture reconstruida documentada. La migración 19→20 conserva biblioteca/cola y reconstruye únicamente FTS con unicode61, además de persistir la restricción de carga de descargas.

| Tabla | Propósito |
|---|---|
| `artists` | Artistas identificados por ID. |
| `albums` | Álbumes, artista, año y portada. |
| `tracks` | Metadatos, audioUri, descarga, origen y marca de retirada. |
| `tracks_fts` | Índice FTS4 de título, artista y álbum. |
| `playlists` | Nombre, descripción y portada de playlists. |
| `playlist_tracks` | Relación playlist/canción con posición. |
| `album_tracks` | Relación álbum/canción con posición. |
| `play_history` | Reproducciones, fecha y proporción escuchada. |
| `favorites` | IDs favoritos y fecha. |
| `search_history` | Consultas recientes. |
| `download_records` | Referencia/ruta guardada y datos de registro. |
| `download_queue` | Tareas persistentes, generación, estado y progreso. |
| `catalog_search_cache` | Resultados serializados y fecha de búsqueda. |
| `user_preferences` | Géneros/artistas y estado del onboarding. |

`TrackEntity` es persistente; `Track` es el modelo que circula hacia UI/reproducción. `CatalogTrack` representa un resultado de un proveedor. Convertir entre ellos conserva origen e ID para resolver audio más adelante.

La búsqueda local crea una expresión FTS por tokens con prefijos. FTS4 usa unicode61 con remove_diacritics=1 para búsquedas con/sin acentos. Triggers mantienen sincronizado el índice. Índices adicionales aceleran filtros por descarga, retirada y orden.

`userRemoved` evita que el escáner vuelva a insertar visualmente un audio que el usuario retiró pero cuyo archivo no pudo eliminarse.

Las asociaciones guardan `position`; el orden de playlist depende de esa columna. La clave compuesta impide repetir el mismo track en una playlist persistida. Los DAO y repositorio utilizan transacciones para agrupar operaciones relacionadas.

Los metadatos de catálogo se insertan con `IGNORE` cuando corresponde: un resultado remoto no debe sobrescribir `audioUri` ni `isDownloaded` de una descarga existente.

`AppPreferences` centraliza ajustes en DataStore Preferences y migra `musicvault.settings`, manteniendo una fachada SharedPreferences para consumidores existentes. Sesiones, credenciales y programación SAF tienen almacenes privados separados.

## 8. Búsqueda y catálogo

`CatalogProvider` define la interfaz de metadatos. `MetadataCatalogProvider` agrega por defecto Deezer y YouTube; la lista de fallback está vacía. `MusicBrainzCatalogProvider` está disponible como alternativa.

El flujo de una búsqueda es:

1. La UI entrega la consulta a `submitSearch`.
2. Se cancela trabajo anterior y se identifica la generación actual.
3. Se publica una respuesta rápida del catálogo cuando existe.
4. Continúa refinamiento mezclando fuentes y ranking.
5. Resultados válidos se convierten/persisten según el camino usado.
6. El ViewModel publica datos que `RemoteResultsAdapter` convierte en filas.

`SearchScorer` normaliza, compara y ordena coincidencias. La prioridad de fuentes puede cambiar con el tipo de consulta: contenido como ASMR/podcast/audiolibro necesita YouTube antes que un catálogo musical convencional.

`YouTubeMetadataProvider` intenta NewPipe primero y utiliza yt-dlp si no obtiene resultados útiles o hay un fallo real. Una cancelación debe propagarse; no se debe interpretar como motivo para arrancar un fallback costoso.

Las playlists se consultan al abrir su pestaña. Canciones, álbumes y playlists no requieren ejecutar siempre el mismo trabajo.

`CatalogRepository` conserva respuestas por consulta normalizada/límites, serializadas en JSON. Una entrada de búsqueda tiene TTL de 30 minutos y hay limpieza de entradas antiguas. Mantiene también una LRU de tracks de álbum. Las recomendaciones reutilizan entradas recientes del catálogo.

## 9. Recomendaciones

Las recomendaciones son reglas locales que combinan preferencias, metadatos obtenidos, favoritos, búsquedas recientes y biblioteca; no hay un modelo de aprendizaje automático empaquetado. El historial alimenta recomendaciones mediante RecommendationRanker: finalizaciones aportan evidencia positiva, saltos explícitos negativa y la antigüedad reduce su peso.

El ViewModel obtiene candidatos según artistas/géneros, elimina duplicados y puntúa. Entre los pesos visibles en la implementación están artista preferido `+100`, favorito `+40` y álbum descargado `+30` para canciones, además de coincidencias de texto.

Se distribuyen candidatos entre artistas antes de completar por puntuación para evitar que un solo artista domine toda la pantalla. `HomeRecommendationCache` guarda una instantánea asociada al perfil para mostrar Inicio antes de repetir trabajo remoto.

Durante onboarding se prepara el catálogo y portadas antes de revelar Inicio. La UI coordina datos disponibles, commit del adaptador y layout. La animación tiene esperas delimitadas; las notas históricas contienen tiempos de versiones anteriores, por lo que el código actual es la referencia.

## 10. Reproducción y audio

### 10.1 Selección y resolución

Al seleccionar una canción se actualiza el mini reproductor y se prioriza esa petición. Según disponibilidad se utiliza archivo local, caché completa o resolución remota.

Para un resultado de catálogo musical puede buscarse la coincidencia de YouTube por título/artista. Para un resultado YouTube se conserva el `sourceId` del video. `Providers.resolveStream` intenta NewPipe para obtener una URL progresiva de audio y usa yt-dlp como alternativa cuando corresponde.

La URL obtenida se convierte en un `MediaItem`; el servicio consume bytes mediante la fuente de datos configurada. Resolver una URL y preparar audio en ExoPlayer son fases distintas y tienen tiempos de diagnóstico separados.

### 10.2 Servicio y controlador

`MusicPlaybackService` hereda de `MediaSessionService` y mantiene ExoPlayer/MediaSession. Su vida no depende de una fila ni de que el reproductor completo esté abierto.

`PlayerController` se conecta mediante `MediaController`, expone estados y ejecuta play/pause, seek, siguiente, anterior y operaciones de cola. El ViewModel coordina preparación de canciones siguientes y recuperación de URLs cuando es necesario.

La sesión recibe eventos multimedia. Hay manejo explícito para evitar que Next sin siguiente reinicie la canción y para que Previous vuelva al inicio cuando se ha avanzado suficiente.

### 10.3 Crossfade, repetición y temporizador

El servicio usa un reproductor secundario y una rampa de volumen para solapar el final/inicio de canciones. El relevo debe conservar cola, posición y sesión; los eventos durante el fade no deben restablecer bruscamente el volumen.

El ajuste de crossfade se guarda entre 0 y 12 segundos. `PlaybackPolicy` y `PlaybackOptions` centralizan política de repetición y temporizador. El temporizador utilizado se administra mediante PlaybackOptions/MusicPlaybackService; se retiró la utilidad SleepTimer sin consumidores.

### 10.4 Persistencia

`PlaybackSnapshot` serializa cola completa, índice, posición, repetición y orden aleatorio para restauración. El servicio guarda cambios relevantes y, mientras reproduce, actualiza periódicamente la instantánea. Al regresar a la app se vuelve a sincronizar el estado con la sesión.

Persistir la posición no equivale a garantizar que una URL firmada siga válida tras reiniciar; una canción remota puede necesitar resolverse otra vez.

### 10.5 Efectos y visualización

`AudioSessionBridge` comunica el ID de sesión. `AudioEffects`, `NativeEqualizer` y `EqualizerPreset` aplican/configuran efectos del sistema. La disponibilidad efectiva depende del dispositivo.

Los estilos vinilo y circular son componentes gráficos de la UI. Una animación visual no debe interpretarse automáticamente como medición precisa del espectro del audio.

## 11. Cachés

| Caché | Ubicación/vida | Propósito |
|---|---|---|
| Portadas Coil | RAM y `cacheDir/musicvault_cover_cache` | Reducir peticiones y decodificación repetidas. |
| Resultados de búsqueda | Room; TTL 30 minutos | Reutilizar consultas. |
| Tracks de álbum | LRU RAM del repositorio | Reabrir álbumes con menos peticiones. |
| Recomendaciones Inicio | Preferencias/instantánea | Mostrar contenido previo al arrancar. |
| Coincidencia hacia YouTube | RAM; 1 hora | Evitar repetir búsqueda título/artista → video. |
| URL de stream | RAM; 10 minutos | Reutilizar URL directa mientras sea válida. |
| Bytes de streaming | Media3 SimpleCache; TTL 24 horas | Reutilizar audio recibido. |

El límite predeterminado de audio es 1 GiB; ajustes permiten 256, 512, 1024 o 2048 MB. `AdjustableCacheEvictor` permite cambiar límite. La clave estable de audio es `musicvault-stream:trackId`, evitando que cada URL firmada cree una entrada distinta.

Coil ajusta memoria al máximo del runtime: 10%, 15% o 20%. El disco se configura en 32, 64 o 128 MiB según espacio disponible. Las filas piden imágenes pequeñas; los carruseles solicitan tamaños mayores.

Una caché completa puede reproducirse sin red y, bajo las condiciones de calidad correspondientes, promocionarse a archivo guardado. Una caché parcial no garantiza una canción completa. Borrar streaming cache no es borrar descargas.

La URI `https://cache.musicvault.invalid/...` es una referencia sintética interna para leer bytes completos de caché, no un servidor de MusicVault.

Se prepara una ventana pequeña de candidatos al quedar Inicio/Búsqueda estables. El trabajo especulativo es secuencial y cede prioridad al toque del usuario. Si se toca exactamente la canción en preparación, se aprovecha el trabajo en curso. Existe prefetch de prefijo para otros caminos, pero el calentamiento de pantalla no debe confundirse con descargar todas las canciones visibles.

## 12. Descargas

### 12.1 Cola durable

`DownloadQueue.enqueueAll` guarda todo el lote en una transacción antes de programar tareas. Evita perder el resto de una playlist si el proceso termina durante el encolado.

Cada entrada conserva ID de canción, UUID de generación, snapshot de metadatos, URL de origen, calidad, preferencia de red, estado, progreso e intentos. La generación impide que una tarea vieja complete una petición reemplazada/cancelada.

Estados principales: `queued`, `running`, `waiting`, `complete`, `failed` y `cancelled`. `queued/running/waiting` se consideran activos.

### 12.2 WorkManager y worker

Se crea trabajo único con restricción de espacio disponible y conectividad. Para solo Wi-Fi se solicita transporte Wi-Fi en API compatibles y red no medida en el camino antiguo. El backoff es exponencial.

`DownloadWorker` ejecuta adquisición en IO y se declara foreground con notificación. Reduce escrituras de progreso mediante un intervalo de aproximadamente 750 ms, salvo progreso final. Vigila pérdida de Wi-Fi durante la transferencia y cancela/reintenta cuando corresponde.

Al finalizar, una transacción actualiza canción, registro de descarga y cola. Las letras se intentan guardar después como operación opcional: su fallo no invalida audio descargado.

`DownloadQueue.reconcile` repara el intervalo entre guardar una entrada y programar su trabajo. WorkManager conserva sus tareas entre reinicios, sujeto a restricciones del sistema; tras un force-stop hace falta volver a abrir la aplicación.

### 12.3 Obtención y destino

`Providers.init` inicia mantenimiento de yt-dlp en segundo plano. `updateYtDlpIfNeeded` utiliza `updateYoutubeDL(STABLE)`, conserva la fecha de una comprobación exitosa y evita repetirla dentro de 24 horas; un fallo conserva disponible el runtime previo. No hay control manual visible de actualización.

`Providers.download` coordina yt-dlp/FFmpeg, cancelación, nombres únicos, calidad y almacenamiento. Las calidades declaradas son `BEST` y `SAVER`.

La promoción de caché no debe sustituir una descarga en mejor calidad por audio de streaming de menor calidad. Los destinos se gestionan con MediaStore, SAF o el camino de archivos aplicable a la versión de Android.

`DownloadEvents` propaga progreso/resultados; la base persistente permite reconstruir el estado al volver a abrir. La ejecución durable se encuentra en Queue/Worker; se retiró la fachada DownloadManager sin consumidores.

La adquisición actual está implementada en Providers/NewPipe/yt-dlp. Se retiró el contrato AudioSourceProvider y su placeholder FutureVideoCatalogProvider sin consumidores.

## 13. Biblioteca local y almacenamiento

`LocalAudioScanner` consulta MediaStore, convierte audio accesible en entidades y mantiene identificadores locales. Reconcilia lo existente y respeta elementos retirados por el usuario.

Una canción puede tener `content://`, `file://` o ruta local según origen. El acceso real depende de permisos, grants y existencia del archivo; tener una URI almacenada no garantiza acceso para siempre.

`StoragePermissions` decide permisos por versión. La Activity solicita los pendientes después del onboarding y evita insistir indefinidamente ante denegaciones. Al regresar de ajustes revisa de nuevo el estado.

El usuario puede elegir una carpeta mediante SAF; se conserva la referencia y el permiso persistente cuando corresponde. `DocumentFile` permite escribir allí sin asumir que siempre es una ruta tradicional.

Retirar una canción de biblioteca, quitar una relación de playlist y borrar físicamente audio son operaciones distintas. Los métodos del repositorio y los flujos de permisos conservan esa diferencia.

## 14. Spotify y playlists de YouTube

### 14.1 Spotify

`SpotifyLinks` identifica enlaces de track/playlist. La UI inspecciona enlaces compartidos mediante el portapapeles cuando puede leerlo y ofrece importación.

La conexión abre un WebView del sitio de Spotify y obtiene una sesión `sp_dc`. `SpotifySessionStore` normaliza y guarda la cookie cifrada con AES/GCM y clave Android Keystore no exportable. Desconectar limpia el almacenamiento de sesión.

`SpotifySongMirrorClient` adapta lógica de SongMirror/SpotifyScraper: intercambio de sesión por token, lectura web/API y recuperación de metadatos. Es un cliente integrado en Kotlin; no se ejecuta un backend SongMirror propio.

La playlist se presenta con selección de canciones. Confirmar guarda metadatos y enlaces ordenados en Room, preservando descargas existentes. El ID de Spotify permite detectar importaciones previas.

La reproducción posterior resuelve audio mediante el camino de MusicVault; la cookie no convierte el proyecto en un cliente de audio Spotify.

### 14.2 YouTube

La pestaña Playlists consulta NewPipe. Tocar una playlist abre la vista previa; se recorren continuaciones hasta un máximo de 500 elementos para UI/importación.

Tocar una canción usa el reproductor habitual. `Agregar a MusicVault` crea playlist y metadatos sin descargar audio. Se preserva `sourceId` para streaming y futuras descargas.

## 15. Letras

`LyricsProvider` representa letras con `Lyrics(plain, synced, origin)` y líneas `LyricLine(timeMs, text)`. Consulta LRCLib, parsea timestamps LRC, múltiples marcas de tiempo y offsets, y valida resultados.

Hay filtros de calidad para evitar entradas mínimas o incoherentes: al menos cinco líneas sincronizadas o texto normal suficiente; también se verifica compatibilidad de duración y metadatos. La confianza de la sincronización se revisa frente a cobertura, huecos y duración.

`LyricsRequest` utiliza un OkHttp compartido con esperas acotadas y cancela la llamada cuando se cancela la coroutine. `lyricsAttempt` propaga cancelación/interrupción en lugar de convertirlas en resultado vacío.

También se pueden obtener subtítulos del video YouTube. `YouTubeCaptionParser` convierte eventos a líneas; la procedencia del video permite evaluar los tiempos como parte de esa misma línea temporal.

`LyricsLocalStore` conserva letras localmente y trabaja con referencias del audio. `DownloadLyrics` guarda letras durante descarga y ofrece coordinación para ese flujo. Se generan representaciones LRC cuando hay sincronización.

`VocalActivityDetector` analiza solo audio local con MediaExtractor/MediaCodec. Puede retrasar letras que empiezan demasiado pronto si detecta un inicio acústico posterior. Es una heurística conservadora, con límites de tiempo y desplazamiento; no separa voz profesionalmente ni sube audio a un servicio.

El reproductor calcula la posición visual desde snapshots de progreso y un anclaje temporal local, para que la letra siga play, pause y seek sin exigir una emisión global por cada frame.

## 16. Respaldos y migración

### 16.1 Respaldo de biblioteca

`BackupCodec` genera JSON `format = musicvault-backup`, versión 1. Contiene canciones, playlists, enlaces, favoritos y una selección de ajustes.

Es un respaldo de metadatos: no transporta automáticamente archivos de audio, cookies ni grants de almacenamiento. Filtra referencias para que el documento sea portable y valida formato, versiones, límites y valores antes de restaurar. El tamaño máximo del JSON es 20 MiB y se limita el número de filas.

`LibraryBackupStore` obtiene la instantánea y restaura con transacciones/deduplicación, preservando audio ya descargado. Los ajustes exportables incluyen estilo, diseño, crossfade, calidad, Wi-Fi y límite de caché.

### 16.2 Migración a release con audio

`MigrationArchive` empaqueta biblioteca y archivos en un archivo de migración y valida sus entradas. `ReleaseMigrationStore` exporta audio legible y permite importarlo en `com.musicvault.app`.

La importación usa un directorio de preparación, valida metadatos/audio, mueve a almacenamiento privado y genera URIs mediante FileProvider. Una descarga ya existente en release tiene prioridad frente al audio importado.

Este flujo es necesario porque debug/performance usan otro applicationId: sus bases, preferencias y almacenamiento privado son independientes de release.

## 17. Integración con Android

| Permiso/componente | Uso |
|---|---|
| `INTERNET` | Catálogo, resolución, portadas, letras y descarga. |
| `ACCESS_NETWORK_STATE` | Conectividad y restricciones de red. |
| `READ_MEDIA_AUDIO` | Leer audio compartido en Android moderno. |
| `READ_EXTERNAL_STORAGE` hasta API 32 | Compatibilidad de lectura antigua. |
| `WRITE_EXTERNAL_STORAGE` hasta API 28 | Escritura pública en versiones antiguas. |
| `FOREGROUND_SERVICE` y `MEDIA_PLAYBACK` | Reproducción con servicio multimedia. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Descarga foreground con WorkManager. |
| `POST_NOTIFICATIONS` | Notificaciones en versiones que lo requieren. |
| `WAKE_LOCK` | Operación multimedia/trabajo según componentes. |
| `FileProvider` | Compartir referencias de archivos mediante content URIs. |
| `MusicWidgetProvider` | Widget con estado y acciones anterior/play/siguiente. |
| `ConnectivityMonitor` | Observar disponibilidad de red. |

El servicio multimedia está exportado con filtro MediaSessionService. El FileProvider es no exportado y permite grants por URI. El receiver del widget es no exportado. El manifest habilita backup del sistema con allowlist de preferencias portables y exclusión de sesiones, grants, bases y audio; esto es distinto del respaldo JSON explícito de biblioteca.

El widget usa `RemoteViews` y XML de layout, porque vive en el launcher. Su ciclo no es el de una pantalla Compose.

## 18. Compilación y distribución

### 18.1 Requisitos

JDK 17, SDK Android con plataforma 36, herramientas de Android y acceso a repositorios para resolver dependencias. La ubicación local del SDK se configura en el entorno o `local.properties`; no debe copiarse una ruta personal como requisito universal.

Repositorios declarados: Google, Maven Central, Gradle Plugin Portal y JitPack. Gradle tiene caché y construcción paralela; configuration cache está deshabilitada.

### 18.2 Variantes

| Variante | applicationId | Características |
|---|---|---|
| debug | `com.musicvault.app.debug` | Debuggable, sufijo `-debug`. |
| release | `com.musicvault.app` | Minificación y shrinking; firma de distribución debe configurarse. |
| performance | `com.musicvault.app.debug` | Deriva de release, optimizada, no debuggable, firma debug, sufijo `-performance`. |

`musicvault.localReleaseSigning=true` habilita firma release con la clave debug para comprobación local. No es una configuración de firma de producción.

Se generan APK ARM64 (`arm64-v8a`), ARM32 (`armeabi-v7a`) y universal de esas dos arquitecturas. El universal no incluye x86/x86_64.

### 18.3 Comandos desde android/

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:assemblePerformance
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease :app:verifyReleaseDownloadRules
```

Release local firmada y APK de pruebas matching:

```bash
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:verifyReleaseDownloadRules -Pmusicvault.testBuildType=release -Pmusicvault.localReleaseSigning=true --no-daemon
```

Salida debug universal: `app/build/outputs/apk/debug/app-universal-debug.apk`. Otras variantes se encuentran bajo `app/build/outputs/apk/`.

`verifyReleaseDownloadRules` revisa seeds de R8 y constructores ZIP usados por reflexión al preparar el runtime yt-dlp. Las reglas ProGuard preservan además componentes relevantes de Room, Media3, NewPipe/Rhino y wrappers nativos.

`useLegacyPackaging` y `extractNativeLibs` forman parte del empaquetado de bibliotecas nativas. `baseline-prof.txt` aporta un perfil de arranque; no implica que se hayan medido todas las rutas de uso.

## 19. Pruebas y diagnóstico

### 19.1 Pruebas existentes

CI está definido en `.github/workflows/android-quality.yml`; instrumentación se activa manualmente. El módulo [benchmark](../benchmark/README.md) es opt-in y genera Baseline Profiles reales para revisión. Ningún test, lint o benchmark se ejecutó en esta implementación por instrucción expresa del propietario. La presencia de LeakCanary en debug o de herramientas de medición no constituye un resultado de diagnóstico.

| Grupo | Qué verifica |
|---|---|
| LyricsProvider / YouTubeCaptionParser | Parseo, calidad y confianza de tiempos. |
| LyricsRequest / DownloadLyrics | Cancelación y coordinación/persistencia de letras. |
| SpotifyLinks | Interpretación de enlaces. |
| NewPipeStreamResolver | Comportamiento de resolución y condiciones cubiertas por sus casos. |
| AdjustableCacheEvictor | Límite/evicción de caché. |
| PlaybackPolicy / PlaybackSnapshot | Política y serialización/restauración de reproducción. |
| BackupCodec / MigrationArchive | Validación y formato de respaldos/archivo de audio. |
| DownloadPersistence (instrumentación) | Persistencia, migración y estados de cola. |
| FavoritesPersistence (instrumentación) | Favoritos y relaciones durables. |
| PlaybackPersistence (instrumentación) | Conservación del estado de reproducción. |
| ReleaseDownloadSmoke (instrumentación) | Inicialización ZIP/runtime y smoke optativo de descarga real. |

JUnit 4.13.2 y `org.json` de test soportan pruebas JVM. AndroidX Test Runner 1.6.2 y ext-junit 1.2.1 soportan instrumentación.

Este documento enumera pruebas presentes; **no afirma que se hayan ejecutado ni aprobado durante esta revisión documental**.

### 19.2 Comprobación en dispositivo

La guía [release-checks.md](release-checks.md) explica cómo instalar actualización compatible e instrumentación sin desinstalar la aplicación del usuario. Una descarga real requiere pasar el argumento `musicvault.smokeUrl`; sin él ese caso se omite.

Hay que comprobar actualización con biblioteca previa, recuperación de cola, pérdida de Wi-Fi, calidad de descarga tras streaming, importación repetida de backups y separación entre caché y audio descargado.

### 19.3 Logs de rendimiento

```bash
adb shell setprop log.tag.MusicVaultStreamPerf V
adb shell setprop log.tag.MusicVaultYouTubeSearch V
adb logcat -v time MusicVaultStreamPerf:V MusicVaultYouTubeSearch:V '*:S'
```

Etiquetas/fases útiles: `newpipe-match`, `newpipe-stream-url`, `resolver=newpipe`, `resolver=newpipe-fallback-ytdlp`, `ytdlp-stream-url`, `stream-cache-hit`, `media3 READY`, `request-to-audio` y warmup de pantalla.

Esos tiempos permiten separar búsqueda de coincidencia, extracción de URL y preparación de reproducción. Release elimina varias llamadas de Log mediante R8, así que no todos los mensajes están disponibles en todo APK.

## 20. Guía para modificar el proyecto

| Cambio deseado | Archivos/puntos de entrada |
|---|---|
| Colores/fuentes | `ui/theme/Theme.kt`; revisar también estilos de Views nativas. |
| Fila de canción local | `ui/FastFeedAdapters.kt`, `NativeTrackHolder`, `makeTrackRow`. |
| Resultado de búsqueda | `ui/search/RemoteResultsAdapter.kt` y construcción de items en `MusicVaultApp.kt`. |
| Nueva pantalla/acción | `MusicVaultApp.kt`, `MusicVaultViewModel.kt`, estado de navegación. |
| Nuevo proveedor de metadatos | `catalog/CatalogProvider.kt`, implementación y `MetadataCatalogProvider`. |
| Ranking | `catalog/SearchScorer.kt`; recomendaciones en ViewModel. |
| Resolución de audio | `providers/NewPipeStreamResolver.kt` y `Providers.kt`. |
| Control de playback | `player/PlayerController.kt` y `MusicPlaybackService.kt`. |
| Caché | `StreamingCache.kt`, `AdjustableCacheEvictor.kt`, `CoverImageCache.kt`. |
| Descarga durable | `DownloadQueue.kt`, `DownloadWorker.kt`, entidad/DAO. |
| Nueva columna persistente | `Entities.kt`, DAO y migración explícita en `MusicDatabase.kt`. |
| Letras | `LyricsProvider.kt`, `LyricsLocalStore.kt`, ViewModel y `SyncedLyricsAdapter`. |
| Respaldo | `BackupCodec.kt`, `LibraryBackupStore.kt`, validación y versionado. |

Para extender una lista, conservar IDs, tipos de vista, lógica de payload y limpieza del holder. Para añadir campos persistentes, actualizar lectura/escritura y migración. Para tocar catálogo, mantener separación entre metadata y audio descargado. Para añadir trabajo remoto, conservar propagación de cancelación.

## Incorporaciones de la hoja de ruta

Los controles de [LibraryManagementScreen](../app/src/main/java/com/musicvault/app/ui/LibraryManagementScreen.kt) permiten M3U, almacenamiento recuperable, duplicados, auto descargas y respaldo programado/cifrado. La implementación completa, restricciones SAF/archivos externos y validaciones pendientes se registran en [ROADMAP_ANDROID_IMPLEMENTADA.md](ROADMAP_ANDROID_IMPLEMENTADA.md).

AppContainer/coordinadores separan dependencias y operaciones. El reproductor incorpora controles de velocidad/silencios, ReplayGain y nivelación RMS local, edición/desfase de letras, catálogo MediaLibraryService y ListenBrainz opt-in. Cast requiere URLs accesibles al receptor. No hay app Wear autónoma ni auditoría TalkBack/medición gapless completa; consultar los límites documentados antes de presentar esos caminos como certificados.

## 21. Inventario de código

Todos los enlaces de esta sección son relativos al documento y apuntan a código Android real. La responsabilidad es orientativa; los flujos cruzan varios archivos.

### Entrada de aplicación

| Archivo | Responsabilidad |
|---|---|
| [MainActivity.kt](../app/src/main/java/com/musicvault/app/MainActivity.kt) | Entrada de actividad, splash, Compose, permisos y regreso a primer plano. |
| [MusicVaultApplication.kt](../app/src/main/java/com/musicvault/app/MusicVaultApplication.kt) | Inicialización de proceso y cargador Coil compartido. |

### backup

| Archivo | Responsabilidad |
|---|---|
| [BackupCodec.kt](../app/src/main/java/com/musicvault/app/backup/BackupCodec.kt) | Formato JSON portable, filtrado y validación de biblioteca. |
| [LibraryBackupStore.kt](../app/src/main/java/com/musicvault/app/backup/LibraryBackupStore.kt) | Lectura de instantánea y restauración de biblioteca. |
| [MigrationArchive.kt](../app/src/main/java/com/musicvault/app/backup/MigrationArchive.kt) | Archivo de migración con metadata y audio; validación de entradas. |
| [ReleaseMigrationStore.kt](../app/src/main/java/com/musicvault/app/backup/ReleaseMigrationStore.kt) | Traslado de audio/biblioteca a instalación release. |

### catalog

| Archivo | Responsabilidad |
|---|---|
| [CatalogModels.kt](../app/src/main/java/com/musicvault/app/catalog/CatalogModels.kt) | Modelos inmutables de resultados remotos y tipos de contenido. |
| [CatalogProvider.kt](../app/src/main/java/com/musicvault/app/catalog/CatalogProvider.kt) | Contrato común de búsqueda y lectura de catálogo. |
| [CatalogRepository.kt](../app/src/main/java/com/musicvault/app/catalog/CatalogRepository.kt) | Persistencia/caché de búsqueda, conversión y tracks de álbum. |
| [CoverArt.kt](../app/src/main/java/com/musicvault/app/catalog/CoverArt.kt) | Utilidades de referencia/obtención de portada. |
| [CoverImageCache.kt](../app/src/main/java/com/musicvault/app/catalog/CoverImageCache.kt) | ImageLoader, memoria/disco y precarga de imágenes. |
| [DeezerCatalogProvider.kt](../app/src/main/java/com/musicvault/app/catalog/DeezerCatalogProvider.kt) | Cliente de metadatos Deezer. |
| [MetadataCatalogProvider.kt](../app/src/main/java/com/musicvault/app/catalog/MetadataCatalogProvider.kt) | Agregación y prioridad de proveedores. |
| [MusicBrainzCatalogProvider.kt](../app/src/main/java/com/musicvault/app/catalog/MusicBrainzCatalogProvider.kt) | Proveedor alternativo MusicBrainz. |
| [SearchScorer.kt](../app/src/main/java/com/musicvault/app/catalog/SearchScorer.kt) | Normalización, comparación, deduplicación y ranking de resultados. |
| [YouTubeMetadataProvider.kt](../app/src/main/java/com/musicvault/app/catalog/YouTubeMetadataProvider.kt) | Búsqueda y metadatos YouTube con extracción y fallback. |

### data

| Archivo | Responsabilidad |
|---|---|
| [Daos.kt](../app/src/main/java/com/musicvault/app/data/Daos.kt) | Consultas Room y contratos de acceso por tabla. |
| [DownloadQueueEntity.kt](../app/src/main/java/com/musicvault/app/data/DownloadQueueEntity.kt) | Entidad de cola y DAO de tareas persistentes. |
| [Entities.kt](../app/src/main/java/com/musicvault/app/data/Entities.kt) | Esquema de entidades Room y sus índices. |
| [Models.kt](../app/src/main/java/com/musicvault/app/data/Models.kt) | Modelos Track, Album, Playlist para aplicación/UI. |
| [MusicDatabase.kt](../app/src/main/java/com/musicvault/app/data/MusicDatabase.kt) | Singleton Room, versión y migraciones. |
| [Repository.kt](../app/src/main/java/com/musicvault/app/data/Repository.kt) | Operaciones de biblioteca, playlists, favoritos e historial. |

### download

| Archivo | Responsabilidad |
|---|---|
| [DownloadEvents.kt](../app/src/main/java/com/musicvault/app/download/DownloadEvents.kt) | Eventos/broadcasts de avance, cancelación y resultado. |
| [DownloadMetadata.kt](../app/src/main/java/com/musicvault/app/download/DownloadMetadata.kt) | Snapshot y combinación de metadatos al completar descarga. |
| [DownloadPreferences.kt](../app/src/main/java/com/musicvault/app/download/DownloadPreferences.kt) | Calidad, Wi-Fi y límite de caché. |
| [DownloadQueue.kt](../app/src/main/java/com/musicvault/app/download/DownloadQueue.kt) | Encolado persistente, programación, cancelación y reconciliación. |
| [DownloadWorker.kt](../app/src/main/java/com/musicvault/app/download/DownloadWorker.kt) | Ejecución foreground, restricciones, reintentos y persistencia final. |

### library

| Archivo | Responsabilidad |
|---|---|
| [LocalAudioScanner.kt](../app/src/main/java/com/musicvault/app/library/LocalAudioScanner.kt) | Exploración/reconciliación de audio de MediaStore. |

### lyrics

| Archivo | Responsabilidad |
|---|---|
| [DownloadLyrics.kt](../app/src/main/java/com/musicvault/app/lyrics/DownloadLyrics.kt) | Coordinación de letras para audio descargado. |
| [LyricsLocalStore.kt](../app/src/main/java/com/musicvault/app/lyrics/LyricsLocalStore.kt) | Lectura/escritura local de letras. |
| [LyricsProvider.kt](../app/src/main/java/com/musicvault/app/lyrics/LyricsProvider.kt) | LRCLib, parseo LRC y validación de calidad/sincronización. |
| [LyricsRequest.kt](../app/src/main/java/com/musicvault/app/lyrics/LyricsRequest.kt) | HTTP cancelable y manejo de fallos de letras. |
| [VocalActivityDetector.kt](../app/src/main/java/com/musicvault/app/lyrics/VocalActivityDetector.kt) | Heurística local de inicio acústico para retrasar letras tempranas. |
| [YouTubeCaptionParser.kt](../app/src/main/java/com/musicvault/app/lyrics/YouTubeCaptionParser.kt) | Conversión de subtítulos YouTube a líneas temporizadas. |

### network

| Archivo | Responsabilidad |
|---|---|
| [ConnectivityMonitor.kt](../app/src/main/java/com/musicvault/app/network/ConnectivityMonitor.kt) | Observación de conectividad del sistema. |

### permissions

| Archivo | Responsabilidad |
|---|---|
| [StoragePermissions.kt](../app/src/main/java/com/musicvault/app/permissions/StoragePermissions.kt) | Selección y evaluación de permisos por versión Android. |

### player

| Archivo | Responsabilidad |
|---|---|
| [AdjustableCacheEvictor.kt](../app/src/main/java/com/musicvault/app/player/AdjustableCacheEvictor.kt) | Control ajustable de evicción de bytes de streaming. |
| [AudioEffects.kt](../app/src/main/java/com/musicvault/app/player/AudioEffects.kt) | Efectos del sistema y configuración sobre sesión de audio. |
| [AudioSessionBridge.kt](../app/src/main/java/com/musicvault/app/player/AudioSessionBridge.kt) | Puente de ID de sesión entre servicio y efectos. |
| [EqualizerPreset.kt](../app/src/main/java/com/musicvault/app/player/EqualizerPreset.kt) | Presets y valores del ecualizador. |
| [MusicPlaybackService.kt](../app/src/main/java/com/musicvault/app/player/MusicPlaybackService.kt) | Servicio MediaSession, ExoPlayer, crossfade y persistencia. |
| [NativeEqualizer.kt](../app/src/main/java/com/musicvault/app/player/NativeEqualizer.kt) | Wrapper de ecualizador nativo. |
| [PlaybackOptions.kt](../app/src/main/java/com/musicvault/app/player/PlaybackOptions.kt) | Estado/control compartido de repetición y temporizador. |
| [PlaybackPolicy.kt](../app/src/main/java/com/musicvault/app/player/PlaybackPolicy.kt) | Reglas de reproducción y repetición. |
| [PlaybackSnapshot.kt](../app/src/main/java/com/musicvault/app/player/PlaybackSnapshot.kt) | Serialización de cola, índice, posición y política restaurables. |
| [PlayerController.kt](../app/src/main/java/com/musicvault/app/player/PlayerController.kt) | MediaController, estados, progreso y operaciones de cola. |
| [StreamingCache.kt](../app/src/main/java/com/musicvault/app/player/StreamingCache.kt) | SimpleCache, red Cronet/alternativa, TTL y exportación de bytes completos. |

### providers

| Archivo | Responsabilidad |
|---|---|
| [NewPipeStreamResolver.kt](../app/src/main/java/com/musicvault/app/providers/NewPipeStreamResolver.kt) | Extracción NewPipe, cliente HTTP, búsqueda/playlists y resolución. |
| [Providers.kt](../app/src/main/java/com/musicvault/app/providers/Providers.kt) | Coordinación de streaming/descargas, alternativas, almacenamiento y cancelación. |

### spotify

| Archivo | Responsabilidad |
|---|---|
| [SpotifyLinks.kt](../app/src/main/java/com/musicvault/app/spotify/SpotifyLinks.kt) | Reconocimiento y normalización de enlaces Spotify. |
| [SpotifySessionStore.kt](../app/src/main/java/com/musicvault/app/spotify/SpotifySessionStore.kt) | Cookie cifrada AES/GCM con Android Keystore. |
| [SpotifySongMirrorClient.kt](../app/src/main/java/com/musicvault/app/spotify/SpotifySongMirrorClient.kt) | Cliente integrado de importación de metadatos Spotify. |

### ui

| Archivo | Responsabilidad |
|---|---|
| [DownloadsDialog.kt](../app/src/main/java/com/musicvault/app/ui/DownloadsDialog.kt) | UI de gestión de tareas de descarga. |
| [FastFeedAdapters.kt](../app/src/main/java/com/musicvault/app/ui/FastFeedAdapters.kt) | Feeds, detalles, holders nativos y selectores reciclados. |
| [HomeRecommendationCache.kt](../app/src/main/java/com/musicvault/app/ui/HomeRecommendationCache.kt) | Instantánea de recomendaciones ligada al perfil. |
| [LibraryToolsSettings.kt](../app/src/main/java/com/musicvault/app/ui/LibraryToolsSettings.kt) | Controles de backup/migración y herramientas de biblioteca. |
| [MusicVaultApp.kt](../app/src/main/java/com/musicvault/app/ui/MusicVaultApp.kt) | Composición de pantallas, navegación por estado, reproductor y diálogos. |
| [MusicVaultViewModel.kt](../app/src/main/java/com/musicvault/app/ui/MusicVaultViewModel.kt) | Coordinación central de repositorios, reproducción, búsqueda y acciones. |
| [NativeUtilityAdapters.kt](../app/src/main/java/com/musicvault/app/ui/NativeUtilityAdapters.kt) | Listas nativas de letras sincronizadas y cola de reproducción. |
| [SettingsScreen.kt](../app/src/main/java/com/musicvault/app/ui/SettingsScreen.kt) | Ajustes visuales, audio y preferencias generales. |

### ui/onboarding

| Archivo | Responsabilidad |
|---|---|
| [OnboardingScreen.kt](../app/src/main/java/com/musicvault/app/ui/onboarding/OnboardingScreen.kt) | Selección inicial de gustos y sus transiciones. |

### ui/search

| Archivo | Responsabilidad |
|---|---|
| [RemoteResultsAdapter.kt](../app/src/main/java/com/musicvault/app/ui/search/RemoteResultsAdapter.kt) | Filas nativas de búsqueda con diff y payloads de estado. |
| [YoutubePlaylistTracksAdapter.kt](../app/src/main/java/com/musicvault/app/ui/search/YoutubePlaylistTracksAdapter.kt) | Tracks de vista previa de playlist YouTube. |

### ui/theme

| Archivo | Responsabilidad |
|---|---|
| [Theme.kt](../app/src/main/java/com/musicvault/app/ui/theme/Theme.kt) | Paleta, fuentes y tipografía Material 3. |
| [VaultIcons.kt](../app/src/main/java/com/musicvault/app/ui/theme/VaultIcons.kt) | Vectores/iconos de identidad de la aplicación. |

### widget

| Archivo | Responsabilidad |
|---|---|
| [MusicWidgetProvider.kt](../app/src/main/java/com/musicvault/app/widget/MusicWidgetProvider.kt) | Widget RemoteViews y acciones multimedia. |

### Recursos y configuración

| Ruta | Para qué sirve |
|---|---|
| `app/src/main/AndroidManifest.xml` | Permisos, Application, Activity, servicios, provider y widget. |
| `app/src/main/res/font/` | Fuentes integradas. |
| `app/src/main/res/drawable/` | Iconografía y recursos gráficos. |
| `app/src/main/res/mipmap*/` | Iconos de launcher y adaptativos. |
| `app/src/main/res/layout/widget_music.xml` | Layout operativo del widget. |
| `app/src/main/res/layout/widget_preview.xml` | Vista previa del widget. |
| `app/src/main/res/xml/music_widget_info.xml` | Configuración del proveedor de widget. |
| `app/src/main/res/xml/file_paths.xml` | Rutas compartibles por FileProvider. |
| `app/src/main/res/values/styles.xml` | Tema de ventana y splash del sistema. |
| `app/src/main/assets/THIRD_PARTY_NOTICES.md` | Avisos/licencias de las adaptaciones Spotify. |
| `app/src/main/baseline-prof.txt` | Perfil ART de arranque. |
| `app/proguard-rules.pro` | Conservación/optimización de código release. |
| `app/test-proguard-rules.pro` | Reglas para APK de instrumentación. |
| `compose-stability.conf` | Declaración de estabilidad de List/Set/Map para Compose. |
| `app/build.gradle.kts` | SDK, variantes, dependencias, splits y verificaciones. |
| `build.gradle.kts` | Versiones de plugins. |
| `settings.gradle.kts` | Módulos y repositorios. |
| `gradle.properties` | Memoria Gradle, AndroidX, caché y paralelismo. |
| `gradle/wrapper/gradle-wrapper.properties` | Distribución Gradle fijada. |
| `docs/release-checks.md` | Procedimiento de verificación release en dispositivo. |
| `MUSICVAULT_PROJECT_NOTES.md` | Compilación de notas históricas; contrastar con código. |

## 22. Límites y diferencias con notas antiguas

- El README del catálogo describe MusicBrainz como fuente actual, pero el proveedor agregado predeterminado del código usa Deezer y YouTube.
- Algunas notas antiguas hablan de LazyColumn en onboarding o de una eliminación general de listas lazy. El código actual conserva `LazyRow` para carruseles de Inicio y utiliza RecyclerView para listas verticales largas.
- La caché de portadas no tiene un porcentaje fijo universal: ajusta RAM y disco según recursos disponibles.
- El proyecto incluye un proveedor futuro placeholder, pero la resolución/descarga YouTube real se implementa en otros módulos.
- Las etiquetas V22/V23/V24/V25 de notas históricas no deben sustituir `versionCode` y `versionName` de Gradle.
- Funciones que dependen de sitios externos pueden fallar si cambian extracción, sesión, respuestas o disponibilidad. El código dispone de alternativas en varios caminos, sin garantizar resultados para todo contenido.
- Los perfiles, diffs, reciclado y warmup reducen trabajo, pero este documento no aporta benchmarks nuevos ni garantiza latencia en todos los dispositivos.
- Los avisos de terceros forman parte del proyecto. NewPipeExtractor está identificado en las notas como GPL-3.0-or-later; las adaptaciones Spotify tienen avisos MIT en assets. Este inventario no sustituye una auditoría completa de licencias transitivas.

La documentación corresponde al código local de la fecha indicada. Cuando se cambie esquema Room, versiones Gradle, proveedores, cachés o adaptadores, deben actualizarse las secciones relacionadas.
