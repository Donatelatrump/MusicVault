# Proveedores, descargas y privacidad

Implementación: 8 de octubre de 2026. No se ejecutaron pruebas, lint ni benchmarks por instrucción explícita del propietario. Las pruebas nuevas se entregan para una ejecución posterior.

## Extractor y errores

`ProviderMaintenance` conserva la comprobación estable cada 24 horas y añade estado/versión y comprobación manual. La lectura/extracción comparte un bloqueo de lectura; una actualización espera un bloqueo exclusivo. El nuevo binario debe ejecutar `--version` antes de confirmar la actualización. Se copia el binario anterior y se restauran binario y metadatos del wrapper si la actualización falla; el fallo automático tiene una espera de 30 minutos y no reinicia descargas. La comprobación de versión comprueba integridad de ejecución, no compatibilidad con todos los cambios futuros de YouTube.

`ProviderErrors` clasifica conexión, autenticación, rate limit, contenido ausente, extractor y enlaces expirados. La cancelación se propaga y no representa un fallo recuperable ni activa fallback. La exportación de diagnóstico recoge clases de fallos y números de razón de parada, no la salida cruda del proveedor.

## Audio y límites Android

AudioTagger aplica la misma finalización a descargas nuevas y promociones de caché. Conserva audio MP3/AAC/M4A sin recodificación; WebM/Opus se convierte a AAC 192 kbit/s en M4A para tags MP4 y portada. MP3 usa ID3v2.3. La portada se incorpora si sigue accesible; su ausencia no descarta el audio. La pérdida de acceso a una carpeta SAF deja disponible la copia privada de una descarga nueva, con evento local de recuperación. Los documentos parciales se borran cuando falla su escritura.

DownloadWorker usa las razones de parada existentes de WorkManager 2.10.0. Timeout de foreground o de ejecución deja una descarga fallida recuperable desde la UI, cancela los procesos del extractor/FFmpeg y evita un ciclo automático de reanudaciones que vuelva a consumir el límite. Pérdida de red o carga mantiene la espera. Wi-Fi se comprueba por transporte físico, también cuando el fallback de WorkManager en API26–27 solo permite expresar UNMETERED.

Se comparó UIDT de Android14+ con la cola WorkManager persistida, compatible desde API26. UIDT requiere otro JobService, permisos de ejecución iniciada por usuario, notificaciones del JobScheduler y reconciliación propia; migrar solo la llamada de planificación duplicaría propietarios y no solucionaría los lotes que nacen de sincronización automática. Esta entrega conserva WorkManager y hace observable/recuperable su límite. **No implementa UIDT ni afirma eliminar las cuotas target36.** El timeout reducido, la cuota real y las notificaciones deben validarse en dispositivo en una sesión autorizada para pruebas.

Fuentes: [WorkManager](https://developer.android.com/jetpack/androidx/releases/work), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt), [timeout foreground](https://developer.android.com/develop/background-work/services/fgs/timeout).

## Diagnóstico y ListenBrainz

ProviderToolsScreen permite guardar diagnóstico JSON a un documento SAF elegido explícitamente. El registro local está limitado a 100 eventos, reside en noBackupFilesDir y puede borrarse. El informe contiene versión, API Android, extractor y eventos técnicos; no enumera biblioteca, cuentas, cookies, rutas ni URLs firmadas. No transmite telemetría ni usa ACRA.

La captura JankStats está desactivada inicialmente y requiere activar «Medir fluidez localmente». Solo se recogen frames mientras MainActivity está visible, se detiene al pausar la actividad y se liberan las referencias al destruirla. Se acumulan cantidad de frames, frames lentos, duración UI total/máxima/media por un conjunto fijo de destinos (sin títulos, consultas ni identificadores). El almacenamiento ocurre al pausar/desactivar, no en cada frame. El borrado de eventos borra también agregados y el último registro de cierre inesperado. La captura habilitada no implica envío automático ni constituye evidencia de un benchmark ejecutado.

LocalCrashRecorder encadena el handler de Android y almacena únicamente clase de excepción y hasta 12 símbolos del código de la app; excluye mensajes, nombres de thread, contenido del proveedor y rutas. El documento SAF optativo puede incluir ese registro.

ListenBrainz requiere introducir y validar un token y aceptar el texto visible de compartir historial. El token se cifra con AES-GCM y clave Android Keystore; token y SQLite de cola residen en noBackupFilesDir. Solo se encola al alcanzar media canción o cuatro minutos, usando tiempo real en reproducción y sin otorgar crédito a saltos hacia delante. Se deduplica por canción y momento de inicio; los registros enviados se retienen 30 días como marcas de deduplicación. Reintentos tras una respuesta perdida pueden reenviar la misma combinación de timestamp y metadata al servidor. La entrega no ofrece una garantía transaccional entre red y SQLite.

Una tarea conectada y una tarea periódica de 6 horas envían la cola offline sin depender de dejar abierta la pantalla. Rate limit preserva los registros pendientes. Una credencial caducada desactiva envío y pide reconectar. Cambiar cuenta o desconectar borra la cola anterior para no compartir historial con otra identidad. No se envía playing_now ni se integra Last.fm.

La API pública RelatedArtists obtiene artistas relacionados y canciones de sus listas top como una radio de metadatos; no prepara URLs de audio ni inicia descargas durante recomendaciones. La disponibilidad de esos endpoints públicos de Deezer depende del servicio y los errores se presentan como recuperables.

Fuente: [API ListenBrainz y umbral de escucha](https://listenbrainz.readthedocs.io/en/latest/users/api/core.html).

La preferencia explícita offline_mode impide comprobaciones del extractor, nuevas resoluciones de audio y subidas de ListenBrainz. Las descargas permanecen pendientes, sin perder su fila de cola, y usan espera exponencial de WorkManager desde un minuto; activar offline durante una transferencia la cancela y conserva su estado recuperable. La desactivación permite su reanudación programada.

Fuente JankStats: [captura, estados y lifecycle](https://developer.android.com/topic/performance/jankstats).
