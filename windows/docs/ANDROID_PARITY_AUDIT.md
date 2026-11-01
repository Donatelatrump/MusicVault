# Auditoría de funciones Android → Windows

Fecha: 2026-11-01. Resultado: **Windows no tiene paridad funcional completa con Android.**

Se revisó el código del árbol de trabajo sobre `003fe08`, incluidos los cambios locales de Android. Windows declara 0.9.7. La auditoría compara implementación y puntos de entrada de interfaz; no descompila ni certifica cada función del APK publicado. Los números de versión y compartir una release no acreditan paridad.

Estados: **Presente** = existe implementación y acceso desde la aplicación; **Parcial** = existe una función relacionada con diferencias relevantes; **Falta** = no se encontró un equivalente en el código de Windows; **Plataforma** = requiere una adaptación específica, no copiar APIs Android.

## Funciones presentes

| Función | Estado en Windows | Evidencia y límites |
|---|---|---|
| Biblioteca local y escaneo de carpetas | Presente | `library.py:scan`, `window.py:import_paths/rescan`; escaneo incremental, filtro de duración y carpetas configurables. SAF/MediaStore se sustituyen por rutas Windows. |
| Búsqueda local con Unicode, filtros por artista/álbum y orden | Presente | `database.py:tracks/library_facets`, `window.py:refresh_tracks`. |
| Favoritos, playlists y colecciones de álbumes | Presente | `database.py` permite crear, renombrar, borrar, añadir/quitar y ordenar miembros; interfaz en `window.py`. |
| Búsqueda remota de canciones, álbumes y playlists | Presente | `services.py:Catalog`, `streaming.py:youtube_search/youtube_playlist_search`; proveedores y criterios de selección no son idénticos. |
| Guardar playlists remotas sin descargar todo su audio | Presente | `window.py:save_remote_playlist`, selección de pistas con `TrackPickerDialog`. |
| Spotify: sesión e importación de enlaces de pistas/playlists | Presente | `spotify_login.py`, `songmirror_spotify.py`, `window.py:import_spotify_playlist/import_spotify_track`; no se validó una sesión real en esta auditoría. |
| Reproducción local y streaming sin descarga permanente previa | Presente | `player.py:play_index`, `window.py:ensure_playable`, `streaming.py:resolve`. |
| Pausa, búsqueda temporal, volumen, anterior/siguiente y shuffle | Presente | `player.py`, controles en `layout.py`. Los modos de repetición difieren; véase abajo. |
| Cola: añadir, quitar y reordenar | Presente | `player.py:append/remove/move`; ventana de cola en `window.py:open_queue`. |
| Crossfade y temporizador de sueño | Presente | Dos decks libmpv y `set_sleep`; implementación y rangos distintos de Android. |
| Descarga manual, progreso, cancelación y reintento | Presente | `window.py:start_download/cancel_download/retry_selected_download`; publicación atómica en `services.py:download`. |
| Letras automáticas, LRC local y resaltado sincronizado | Presente | `services.py:fetch_lyrics`, `window.py:update_full_player/playback_progress`; cobertura de fuentes distinta de Android. |
| Onboarding de gustos y estilos vinilo/circular | Presente | `experience_ui.py:OnboardingDialog`, `window.py:start_taste_experience`, `widgets.py`. No equivale al motor DJ de Android. |
| Controles multimedia y reproducción en segundo plano | Presente | `media_keys.py` y bandeja Qt; adaptación de escritorio, no equivalencia completa con MediaSession. |

## Diferencias y funciones pendientes

| Función Android | Estado en Windows | Diferencia comprobada |
|---|---|---|
| Restauración completa de reproducción | Parcial | Guarda IDs e índice de cola y ajustes de shuffle/repetición, pero no la posición. `Player.__init__` reinicia posición/duración a cero; `persist_queue` solo serializa `ids/index`. Android serializa pistas, posición y orden shuffle en `PlaybackQueueState.kt`. |
| Repetir una vez adicional / indefinidamente | Parcial | Android `PlaybackPolicy.kt` ofrece apagado, una repetición adicional y repetición indefinida de la pista. Windows ofrece `off/all/one`: repetir toda la cola o la pista indefinidamente; falta la repetición adicional única. |
| Escuchas reales, finalizaciones y saltos explícitos | Parcial | Windows crea historial al cargar y calcula `position/duration`; adelantar aumenta la finalización sin haber escuchado ese tiempo. No registra la señal de salto ni el tiempo efectivamente reproducido como Android. |
| Recomendaciones adaptadas al historial reciente | Parcial | Windows usa artistas elegidos, favoritos/biblioteca y caché de gustos; no tiene el ranker con decaimiento y señales de escucha de Android (`RecommendationRanker.kt`, `DjRecommendationEngine.kt`). |
| DJ para ti: iniciar, otra mezcla, detener y sucesor a demanda | Falta | No hay sesión/motor/interfaz DJ en Windows. `Player.related_successor` elige archivos locales por álbum/artista/favoritos; no sustituye al DJ de Android. |
| Radio remota y artistas relacionados | Falta | Android `RelatedArtists.kt` y `MusicVaultViewModel.startRadio/relatedArtists`; Windows solo tiene continuación local opcional. |
| Validación de álbumes recomendados | Parcial | Windows recomienda resultados de `search_albums` filtrando artista, sin verificar tipo álbum/EP, al menos dos pistas y detalle disponible como Android. |
| Velocidad de reproducción 0.5–2× | Falta | Android expone el ajuste en `PlaybackToolsScreen.kt` y lo aplica en el servicio. Windows no conecta un ajuste a la velocidad de libmpv. El control RPM en `widgets.py:set_rpm` cambia la presentación y el ajuste visual, no la velocidad de audio. |
| Saltar silencios | Falta | Android integra `skip_silence` con el reproductor; Windows no tiene control ni filtro equivalente. |
| Normalización ReplayGain / análisis local de nivel | Falta | Android `ReplayGain.kt`, `LocalLoudness.kt`, `MusicPlaybackService.kt`; no hay aplicación equivalente en el motor Windows. |
| Preparación de cola para reproducción continua sin crossfade | Parcial | Windows resuelve/prepara fuentes y hace crossfade, pero al EOF carga la siguiente pista con `loadfile replace`; no usa una cola nativa preparada equivalente a Media3. No se midió gapless en ninguna plataforma durante esta auditoría. |
| Modo sin conexión explícito | Parcial | Windows reproduce archivos locales y recupera resultados cacheados, pero no ofrece el interruptor que bloquea operaciones remotas en Android. |
| Caché de streaming configurable | Parcial | Windows fija 1 GiB y 24 h (`streaming.py` y Ajustes); Android permite 256/512/1024/2048 MiB. Ambos permiten limpiar y reutilizar audio temporal. |
| Calidad de descarga / ahorro de datos / solo Wi-Fi | Falta | Windows fija MP3 y `--audio-quality 0`; no tiene selector de calidad ni restricciones de red como `DownloadPreferences.kt`. |
| Descarga automática de playlists/favoritos con cuota | Falta | No hay equivalente a `AutoDownloadWorker.kt`: selección de fuentes, revisión periódica, cuota estimada y restricciones de red/carga. |
| Cola durable de descargas con recuperación automática | Parcial | Windows persiste registros y al arrancar cambia `queued/downloading` a `interrupted`; exige reintentar. No hay reanudación durable con generaciones y restricciones equivalente a WorkManager. |
| Tags, portada y letras junto al audio descargado | Parcial | Descarga normal usa `--embed-metadata`, sin el pipeline explícito de tags/portada de Android. Promoción de caché Windows copia el audio a MKA sin enriquecerlo con los metadatos/portada de la pista. No persiste automáticamente LRC junto a cada descarga como `DownloadLyrics.persist`. |
| Letras desde subtítulos YouTube y validación/alineación de sincronía | Parcial | Windows usa sidecar LRC/TXT y LRCLIB; no tiene `YouTubeCaptionParser`, `LyricsRequest` ni `VocalActivityDetector` de Android. |
| Importar/exportar playlists M3U/M3U8 | Falta | Android `M3uStore.kt` y `LibraryManagementScreen.kt`; Windows no implementa lector/exportador ni controles M3U. Sus importadores Spotify/SongMirror no sustituyen este formato. |
| Respaldo portable JSON y restauración desde la interfaz | Parcial | Windows solo exporta SQLite (`Database.export_backup` y Ajustes). No importa el respaldo JSON Android ni ofrece el flujo transaccional de restauración de Android. Copiar manualmente Data no equivale a ese flujo. |
| Respaldos con contraseña, programación y rotación | Falta | No hay equivalentes a `BackupEncryption.kt` y `ScheduledBackupWorker.kt`. |
| Almacenamiento por colección y duplicados por contenido | Falta | Windows no tiene el inventario por colección ni la comparación SHA256 de `LibraryStorage.kt`; deduplicar rutas/IDs al escanear no equivale a comparar contenido. |
| Papelera de audio, retirada por lote, deshacer y vaciado | Falta | Windows retira registros de biblioteca (`remove_track`), pero no mueve audio a una papelera persistente ni ofrece deshacer/liberar espacio equivalente. |
| ListenBrainz opt-in y cola de scrobbling | Falta | Android `scrobble/ListenBrainz.kt` y `ProviderToolsScreen.kt`; sin equivalente Windows. |
| Mantenimiento del extractor desde la app | Falta | Windows incluye yt-dlp fijado y diagnóstico de disponibilidad, sin la comprobación/actualización observable de `ProviderMaintenance.kt`. |
| Diagnóstico exportable y registro local de fallos con redacción | Parcial | Windows tiene `--doctor` y log de error de arranque; no tiene el centro de eventos/exportación y redacción de `LocalDiagnostics.kt`/`LocalCrashRecorder.kt`. |
| Chromecast | Falta | Android tiene emisor y controles para URLs HTTP(S) accesibles al receptor; no hay descubrimiento/sesión Cast en Windows. |
| Idiomas español e inglés según sistema | Parcial | Android tiene recursos en ambos idiomas, con mensajes heredados aún en español. Windows usa textos españoles y no dispone de un catálogo/traductor equivalente. |
| Diseño híbrido/clásico y navegación de regreso persistente | Parcial | Windows tiene un diseño Qt y selección de estilo de reproductor; no ofrece el selector de diseño Android ni un historial persistente equivalente a `sectionBackStack`/`navigateBack`. |
| Accesibilidad | Parcial / sin certificar | Windows tiene algunos nombres accesibles, teclas y tooltips; Android tiene semánticas propias. No se ejecutó auditoría de Narrator/TalkBack ni fuente grande; no se afirma equivalencia. |

## Elementos que requieren adaptación de plataforma

- Android Auto, MediaLibraryService, widget Android, búsqueda por voz y notificación/MediaSession no se trasladan copiando sus APIs. Windows necesita decidir equivalentes propios. Hay teclas multimedia/bandeja, pero no integración SMTC/pantalla de bloqueo equivalente.
- SAF, permisos persistentes, MediaStore, Keystore, Auto Backup y WorkManager son mecanismos Android. Su ausencia literal no es un fallo de Windows; sí deben cubrirse las funciones de producto que soportan, como programar descargas, restaurar y proteger sesiones.
- La migración debug→release con audio es específica de los paquetes Android. Windows ya admite trasladar Data; no importa el archivo de migración Android.
- No hay una app Wear compañera implementada en Android que pueda declararse pendiente de portar.
- Android conserva código de ecualizador/efectos y su aplicación en el ViewModel, pero la búsqueda de referencias no encontró controles actuales que llamen a sus setters fuera del ViewModel. No se cuenta automáticamente como función de interfaz vigente que Windows deba reproducir. Windows documenta la retirada del ecualizador.
- El editor manual y ajuste de desfase de letras fueron retirados del árbol Android actual; no se marcan como funciones pendientes de Windows.

## Comprobaciones ejecutadas

1. Inspección cruzada de código de producto, ajustes, motores, persistencia y pruebas de ambas plataformas. La documentación Android se usó como índice y se contrastó con código.
2. Suite Windows ejecutada con Python del sistema en Linux y Qt offscreen, excluyendo MPRIS y los tests de ventana: **42 aprobadas, 2 fallidas, 6.98 s**. No son 42 pruebas ejecutadas en Windows/Wine.
   - `test_catalog_fallback_cache_and_offline`: falta `notice` al devolver resultados cacheados en el camino actual. El fallo no demuestra que se pierdan esos resultados.
   - `test_youtube_metadata_and_source_specific_cache`: fixture del comando antiguo con objeto `entries` frente al camino actual `--dump-json` por filas. Requiere actualizar/contrastar la prueba; no demuestra por sí solo un fallo del proveedor real.
3. La ejecución que incluía tests de ventana quedó bloqueada en un flujo Qt y fue detenida. No se declara la suite completa aprobada ni se atribuye el bloqueo a un flujo concreto sin instrumentarlo.
4. Comprobaciones pequeñas con SQLite real y deck simulado confirmaron: restaurar una cola guardada a 42 s devuelve posición 0; buscar 90 s en una pista de 100 s escribe finalización 0.9 sin reproducir esos 90 s; `repeat=one` se mantiene indefinidamente. Son pruebas de estado/algoritmo, no pruebas de sonido.
5. Las pruebas Wine de empaquetado de la tarea anterior acreditaron arranque, WAV/libmpv con salida nula, descarga simulada, cancelación, instalación y desinstalación. No prueban paridad con Android ni todas las funciones remotas.

## Orden de trabajo propuesto

1. Recuperación y datos: posición/estado de cola, semántica de repetición, historial real y saltos, respaldo portable/restauración y M3U.
2. Descargas/almacenamiento: recuperación durable, calidad/red, descarga automática, tags/portada/LRC comunes para descarga y promoción de caché, inventario/duplicados/papelera.
3. Reproducción: offline explícito, velocidad, silencios, ReplayGain, caché configurable y continuidad de cola.
4. Descubrimiento e integraciones: ranker, DJ, radio/artistas, validación de álbumes, ListenBrainz, mantenimiento del extractor, Cast y diagnóstico exportable.
5. Interfaz y adaptación: navegación, idiomas, accesibilidad y controles multimedia nativos de Windows.

Esta auditoría no implementa ni publica esas funciones. No se modificaron los binarios ni la release como parte de la revisión.

## Correcciones posteriores durante esta revisión

Se corrigió la etiqueta sin padre que creaba una segunda ventana. Las búsquedas nuevas de Windows se alinearon con las fuentes activas Android (Deezer y YouTube); los lectores de álbumes antiguos se conservaron por compatibilidad. Se añadieron respaldo de portada grande de Deezer, reparación de caché de imagen inválida, normalización HTTPS, alternativa estable de miniatura YouTube y reintentos limitados para carátulas visibles. La comparación anterior describe el estado inicial, no atribuye paridad completa a este parche. Se añadió además un actualizador pequeño para la base Windows 0.9.7; reutiliza runtimes y conserva Data.

La suite sin ventanas/MPRIS pasó 48 pruebas después de actualizar dos fixtures a los caminos de búsqueda vigentes. Se añadieron y aprobaron pruebas Qt específicas de la ventana auxiliar y reintento de carátulas. La prueba adicional de miniatura YouTube se verificó después por separado con el conjunto de servicios.

Por indicación posterior del propietario se fijó YouTube antes de Deezer en todas las búsquedas generales. Se incorporó NewPipeExtractor 0.26.5 (mismo artefacto SHA256 del Android), con Java privado Windows, para búsquedas/playlists/resolución y yt-dlp de respaldo. Prueba Wine real de búsqueda y playlists aprobada; búsqueda general con 12 primeras filas YouTube y todas con carátula. Validación final de lógica: 51 aprobadas; regresiones Qt: 3 aprobadas. Esto corrige esta diferencia del proveedor, no todas las funciones pendientes de la matriz inicial.
