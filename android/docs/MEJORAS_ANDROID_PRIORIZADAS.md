# MusicVault Android: revisión y hoja de ruta priorizada

Revisión: 8 de octubre de 2026. Complementa [la documentación del proyecto](PROYECTO_ANDROID_COMPLETO.md). Se contrastaron propuestas con el código local y documentación oficial. Este documento conserva la revisión inicial anterior a la implementación. El estado posterior, funciones incorporadas y verificaciones pendientes se describe en [ROADMAP_ANDROID_IMPLEMENTADA.md](ROADMAP_ANDROID_IMPLEMENTADA.md); las observaciones de las tablas siguientes corresponden al punto de partida.

## Observaciones verificadas y matices

| Propuesta/afirmación | Resultado de la revisión |
|---|---|
| Actualizar yt-dlp sin publicar APK | **Ya existe**. `Providers.init` lanza mantenimiento y `updateYtDlpIfNeeded` llama `updateYoutubeDL(STABLE)`. Comprobación persistida de 24 h tras éxito; el arranque se protege una vez por proceso. Falta control manual, estado visible y recuperación observable de fallos. |
| No se pueden probar migraciones con exportSchema=false | **Hay pruebas manuales**. `DownloadPersistenceTest` simula 18→19 y verifica datos. Faltan esquemas versionados y cobertura histórica con MigrationTestHelper. |
| FTS puede fallar con acentos | **Confirmado**: `@Fts4` y SQL de creación no especifican unicode61. En SQLite del equipo, cancion/corazon/mexico no encuentran canción/corazón/México; unicode61 con remove_diacritics=1 sí. Falta verificar ese cambio en Android real. |
| Backup no tiene exclusiones | **Confirmado**: allowBackup=true sin fullBackupContent/dataExtractionRules. Definir ambas reglas y política de restauración. Los directorios cacheDir/noBackupFilesDir ya tienen exclusión del sistema; no hay que tratar todo dato llamado caché como si viviera allí. |
| No hay manejo de Atrás | **Existe BackHandler** con prioridad para reproductor, ajustes, detalles y vuelta a Inicio; algunos estados usan rememberSaveable. Falta back stack completo y validación predictiva/restauración de proceso. |
| Todo lo señalado como código muerto puede borrarse | Los símbolos SleepTimer, DemoCatalog y DownloadManager no tienen consumidores de producción identificados en la búsqueda. FutureVideoCatalogProvider solo es referenciado por esa fachada. Navigation Compose y Media3 UI no tienen usos importados identificados. Confirmar construcción y diff del APK antes de retirar; R8 puede eliminar parte ya. |
| No hay accesibilidad | **Existe parcialmente**: descripción de menú por canción, botones Atrás/Eliminar y estado offline. Los TextView aportan texto automáticamente; Canvas requiere semántica en su View host. Falta auditoría TalkBack, estado de reproducción, foco, acciones, texto grande y objetivos táctiles. |
| No hay pruebas de proveedores/cola | Hay NewPipeStreamResolverTest, DownloadMetadataTest y pruebas de persistencia/cancelación de DAO. Falta cobertura directa de Providers, planificación de DownloadQueue, coordinación ViewModel y ranking de recomendaciones. |
| Snapshot solo conserva canción y posición | **Confirmado** en PlaybackSnapshot. No serializa la cola completa, índice y orden aleatorio. |
| Las recomendaciones usan play_history | La escritura del historial existe, pero el combine principal de recomendaciones no recibe play_history. La documentación general anterior era demasiado amplia: añadir esa señal sería una mejora nueva. |
| Tags y portada embebidos en descargas | No se identificaron opciones de embebido/metadata en la solicitud actual de Providers.download. Verificar formatos y caminos de promoción antes de diseñarlo. |
| Compose BOM tiene casi un año | El BOM 2024.12.01 tiene aproximadamente 22 meses en la fecha de revisión. La edad es contexto; los problemas medidos y la compatibilidad definen prioridad. |

## Distribución, proveedores y licencia

Los términos de YouTube restringen scraping y descargas salvo autorización contemplada por el servicio o permiso escrito. Las directrices de Spotify también restringen scraping y usos no autorizados. Es una incompatibilidad que debe evaluarse para los caminos concretos de la app y las autorizaciones disponibles. Fuentes: [YouTube](https://www.youtube.com/static?template=terms), [Spotify](https://www.spotify.com/legal/user-guidelines/).

Google Play prohíbe aplicaciones que inducen infracción de copyright, con ejemplos de streaming/descarga no autorizados. La implementación actual presenta un riesgo de distribución; no se dispone de una revisión de Play que permita afirmar un rechazo formal de este APK. La licencia GPL y la política de Play son cuestiones distintas. Fuente: [propiedad intelectual en Google Play](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en).

NewPipeExtractor 0.26.5 incluye GPLv3. Si se distribuye la aplicación como una obra combinada con esa biblioteca, deben cumplirse las condiciones GPL aplicables al conjunto y entrega del código fuente correspondiente. El uso privado sin distribución no obliga por sí solo a publicar el proyecto para todo el público. Conservar avisos de otras dependencias y decidir explícitamente la licencia/distribución con el propietario; no añadir una LICENSE automáticamente como parte de una limpieza técnica. Fuente: [licencia de NewPipeExtractor 0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE).

Alternativas que requieren decisión:

- Mantener integraciones actuales y preparar distribución compatible con sus licencias, además de resolver permisos de servicios/contenido.
- Diseñar una variante para publicación que use contenido local o fuentes/integraciones autorizadas. Desactivar una pantalla no basta si sigue ejecutándose el camino incompatible.
- Mantener uso personal mientras se decide distribución. Esto no elimina posibles restricciones contractuales de los proveedores.

La actualización remota de yt-dlp existe y está soportada por su wrapper. No debe asumirse que toda actualización remota está aprobada por Play por ser Python: revisar el comportamiento y la política de código descargado para el canal elegido. Fuentes: [youtubedl-android](https://github.com/yausername/youtubedl-android), [política de dispositivos y red](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en).

## P0: proteger biblioteca y hacer recuperables los fallos

### A. Esquemas Room y búsqueda en español

Archivos: Entities.kt, MusicDatabase.kt, app/build.gradle.kts, nuevos esquemas y pruebas androidTest.

1. Activar exportSchema, configurar ruta KSP, versionar JSON y añadir room-testing de la línea compatible del proyecto.
2. Exportar el esquema actual antes de cambiarlo. Los esquemas históricos no aparecen por activar exportación: recuperarlos de código histórico reproducible o crear fixtures documentados de versiones soportadas.
3. Añadir pruebas MigrationTestHelper que validen estructura y datos de versiones disponibles, especialmente variantes antiguas del esquema 17.
4. Cambiar anotación FTS y SQL de migración coherentemente; crear una migración nueva 19→20 que reconstruya índice, triggers y datos.
5. Validar búsquedas con y sin acentos, Unicode y coincidencias por prefijo en instalaciones nuevas y actualizadas.

Aceptación: conservar favoritos, playlists, orden, descargas y cola; canción/cancion y México/mexico deben producir coincidencias previstas en Android. No usar fallback destructivo para resolver migraciones.

Fuentes: [migraciones Room](https://developer.android.com/training/data-storage/room/migrating-db-versions), [FTS3/FTS4 y unicode61](https://www.sqlite.org/fts3.html). Los ejemplos actuales de documentación pueden corresponder a otra versión de Room: adaptar APIs a la dependencia elegida.

### B. Política explícita de backup del sistema

Archivos: AndroidManifest.xml, nuevos XML de reglas, SpotifySessionStore y flujos de restauración.

Propuesta inicial: permitir únicamente preferencias portables seleccionadas; excluir sesión Spotify, WebView autenticado, estado de proveedores, referencias efímeras, bases MusicVault/WorkManager y audio privado pesado. Mantener el respaldo explícito de biblioteca como camino de recuperación hasta diseñar reconciliación de URIs/audio.

La política final debe identificar preferencias que mezclan ajustes portables con grants, snapshots y cachés. Excluir un archivo completo puede perder preferencias útiles; incluirlo completo puede restaurar referencias rotas.

Aceptación: en otro dispositivo se muestra Spotify desconectado, sin tareas fantasma ni biblioteca que declare descargas inaccesibles. Probar backup cloud y transferencia entre dispositivos, no solo XML válido. Definir fullBackupContent para versiones antiguas y dataExtractionRules para las nuevas. Fuente: [Auto Backup Android](https://developer.android.com/identity/data/autobackup).

### C. Errores de proveedores y actualización observable

Archivos: Providers.kt, SpotifySongMirrorClient.kt, CatalogRepository.kt, coordinadores/UI.

Clasificar offline, sesión caducada, rate limit, contenido ausente, extracción rota, URL expirada y cancelación. La UI debe proponer acciones específicas: reconectar, reintentar, esperar o actualizar extractor. Cancelar una acción no debe generar un error ni lanzar un fallback.

Extender el mecanismo de actualización existente con estado/versión, comprobación manual, coordinación con extracciones y reintentos acotados. No reiniciar trabajos sin límite ni sustituir una versión funcional sin estrategia de recuperación.

Aceptación: inyectar cada clase de fallo sin depender de que un servicio externo se rompa; comprobar mensaje, acción y que no se pierde biblioteca/audio local. Logs exportables deben ocultar cookies y URLs firmadas.

## P1: descargas modernas, accesibilidad y verificaciones continuas

### D. Descargas y límites del sistema

Android 15 limita dataSync en background a seis horas acumuladas en 24 horas; no son seis horas por canción. La interacción que devuelve la app a foreground afecta el contador. Fuente: [timeouts foreground](https://developer.android.com/develop/background-work/services/fgs/timeout).

El servicio actual es SystemForegroundService de WorkManager, no un servicio propio al que baste añadir onTimeout en DownloadWorker. WorkManager 2.10.0 ya incluyó un arreglo de parada por timeout y una razón de parada correspondiente. Probar el comportamiento real y revisar versiones compatibles antes de reemplazar infraestructura. Fuente: [release notes WorkManager](https://developer.android.com/jetpack/androidx/releases/work).

Comparar UserInitiatedDataTransfer para lotes largos iniciados por el usuario con WorkManager para sincronización diferida; mantener compatibilidad minSdk26. Validar además las cuotas de trabajos aplicables a target36.

Aceptación: simular timeout reducido en dispositivo de prueba, comprobar cancelación del extractor, estado recuperable y reconciliación; no dejar notificaciones/tareas fantasma ni exceder Wi-Fi-only. No modificar el servicio multimedia para arreglar el de descargas.

### E. Accesibilidad

Auditar Inicio, Búsqueda, Biblioteca, cola, letras y selectores con TalkBack. Exponer nombres, rol, disponibilidad, selección/reproducción y acciones; mantener decoraciones fuera del foco y evitar anuncios de progreso en cada tick.

Probar fuente al 200%, pantalla estrecha, orden de foco y controles de al menos 48 dp. Diferenciar dimensiones del dibujo y del área táctil. Probar Views y Compose, incluyendo holders reciclados con otro elemento.

Aceptación: reproducir, pausar, añadir favorito, cancelar descarga y navegar por cola solo con TalkBack; ninguna acción debe quedar únicamente como icono sin nombre.

### F. CI, pruebas y diagnóstico privado

Añadir CI con JDK/SDK compatibles, testDebugUnitTest y lintDebug; publicar resultados sin secretos. Instrumentación requiere emulador/dispositivo y un trabajo aparte. No hacer depender la suite cotidiana de YouTube/Spotify.

Priorizar pruebas de cancelación, generación obsoleta, reintentos, no duplicación, conservación de audioUri, resultados de búsqueda de generaciones anteriores y ranking determinista.

Añadir exportación optativa de diagnóstico local y redacción de datos sensibles. Evaluar ACRA si se necesita reporte; evitar telemetría por defecto sin una decisión explícita.

LeakCanary solo en debug ayuda a investigar referencias retenidas. Medir repetidos ciclos de abrir/cerrar detalle, scroll y cambio de canción antes de atribuir una fuga a ComposeView.

Macrobenchmark debe medir arranque, scroll y navegación sobre build optimizada y dispositivo estable; verificar efectividad del baseline profile. JankStats requiere integrar métricas con estados de pantalla y una política de captura/exportación.

## P2: arquitectura, limpieza y actualización controlada

### G. División gradual

1. Crear un AppContainer simple que proporcione base, repositorios, proveedores y controlador.
2. Extraer búsqueda, descargas, biblioteca y reproducción a coordinadores con responsabilidades y ciclos claros.
3. Separar pantallas/diálogos de MusicVaultApp.kt conservando estado y listeners.
4. Evaluar ViewModels por destino cuando exista una navegación definida; no crear cuatro propietarios de la misma sesión multimedia.
5. Añadir back stack/restauración o fortalecer la navegación actual; probar Atrás predictivo, cierre de overlays y recreación del proceso.

Aceptación: el resultado observable y rendimiento se conservan, y cada extracción permite probar una responsabilidad. Evitar una refactorización masiva simultánea con cambio de esquema y reproductor.

### H. Limpieza de código y dependencias

Retirar candidatos sin consumidores tras verificar usos en main/test/androidTest y comportamiento de release. Incluir fachadas/placeholders en la misma evaluación. Comparar tamaño de APK y dependencias resueltas: menos código fuente no garantiza menor APK cuando R8 ya lo elimina.

### I. Actualización de plataforma

Registrar matriz Kotlin, KSP, Compose compiler/BOM, AGP/Gradle, Room y Media3. Actualizar por grupos realmente acoplados, con pruebas de release/R8, extracción, audio, UI y migraciones. No todos deben actualizarse juntos obligatoriamente; el plan depende de requisitos de metadatos y compatibilidad.

Mantener una base de mediciones antes/después. DataStore y localización pueden realizarse en fases propias; revisar localización antes si se quiere ampliar público.

## Funciones nuevas, orden sugerido

Estas funciones amplían el producto y necesitan diseño propio; no son correcciones implementadas por esta hoja de ruta.

| Orden | Área | Función | Condiciones/aceptación |
|---|---|---|---|
| 1 | Reproducción | Restauración completa de cola | Persistir items, índice, posición y política; resolver URLs de nuevo; manejar audio desaparecido. |
| 1 | Letras | Desfase manual por canción | Guardar offset y permitir restablecer; combinar claramente con offset LRC/ajuste automático. |
| 1 | Biblioteca | Importar/exportar M3U/M3U8 | Resolver rutas/URIs con permisos; UTF-8, duplicados y archivos ausentes. |
| 1 | Reproducción | Gapless | Verificar soporte actual ExoPlayer con cola preparada y archivos con padding/delay; medir sin crossfade antes de añadir motor nuevo. |
| 2 | Archivos | Tags y portada embebida | Formatos ID3/MP4; coherencia entre descarga nueva y promoción de caché; validar desde otro reproductor. |
| 2 | Biblioteca | Gestión de almacenamiento | Tamaño por colección y audio compartido, borrado en lote recuperable y detección de duplicados por contenido. |
| 2 | Reproducción | Velocidad y saltar silencios | Integración con Media3; estado persistente y letras sincronizadas con posición real del audio. |
| 2 | Audio | Normalización | Elegir ReplayGain o medición loudness, límites y prevención de clipping; LoudnessEnhancer no equivale por sí solo a normalización. |
| 2 | Recomendaciones | Historial, saltos y antigüedad | Señal negativa y decaimiento; no confundir fin del proceso/error con rechazo del usuario. Tests deterministas. |
| 2 | Producto | Modo offline explícito | Evitar peticiones especulativas, mostrar disponibilidad local/cache completa y mensajes consistentes. |
| 3 | Descargas | Auto descarga playlists/favoritos | Wi-Fi y cargando, deduplicación, cuota, altas/bajas y explicación del comportamiento. |
| 3 | Letras | Editor básico | Texto/LRC, guardado local y validación de tiempos; no sobrescribir cambios con resultados remotos. |
| 3 | Backup | Programado a SAF, cifrado opcional | Grant revocable, rotación y claves recuperables; cifrado solo con Keystore no sirve como backup portable. |
| 3 | Backup | Progreso de restauración | Contar unidades reales y definir cancelación/transacciones, especialmente en paquetes con audio. |
| 3 | Recomendaciones | Radio y artistas relacionados | Separar recomendación de preparación de audio; revisar disponibilidad de API de Deezer. |
| 3 | Recomendaciones | ListenBrainz/Last.fm | Opt-in, credenciales protegidas, cola offline y deduplicación de scrobbles. |
| 4 | Dispositivos | Android Auto | MediaLibraryService, catálogo navegable y restricciones del host. |
| 4 | Dispositivos | Wear | Definir app compañera/control remoto/autónoma; MediaLibraryService no crea por sí solo una app Wear. |
| 4 | Dispositivos | Chromecast | Sesión Cast, URLs accesibles al receptor y cambio de dispositivo; las URLs/URIs locales necesitan otra estrategia. |
| Según público | UI | Localización | Extraer strings de Compose y Views, plurales, formatos y etiquetas accesibles. |
| Baja | Persistencia | Migrar preferencias a DataStore | Migración idempotente y un único propietario de estado; preservar lectura del servicio. |

## Primer bloque concreto recomendado

Empezar con esquemas/migraciones/FTS y reglas de backup. Son tareas comprobables, reducen riesgo sobre datos existentes y no requieren dividir primero toda la arquitectura. Continuar con errores/actualización observable y descargas bajo límites Android. Resolver destino de distribución/licencia con el propietario antes de publicar.

Criterios de cierre del bloque inicial:

- Instalación nueva y actualización 19→20 conservan datos y búsquedas sin acentos.
- Esquemas versionados y pruebas de migración disponibles en el repositorio.
- Restauración en dispositivo distinto no importa sesión ni referencias inválidas.
- testDebugUnitTest y lintDebug pasan; instrumentación de migración y restauración tiene evidencia propia.
- Documentación refleja comportamiento final y no presenta tareas pendientes como implementadas.
