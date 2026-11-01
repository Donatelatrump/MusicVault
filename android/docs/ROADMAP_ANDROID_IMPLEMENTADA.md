# MusicVault Android: implementación y límites verificables

Estado del código: 8 de octubre de 2026. Complementa la [revisión inicial](MEJORAS_ANDROID_PRIORIZADAS.md) y la [documentación integral](PROYECTO_ANDROID_COMPLETO.md). Describe código presente; **no convierte pruebas escritas o herramientas añadidas en verificaciones ejecutadas**. El propietario excluyó expresamente tests, lint y benchmarks durante esta sesión. La construcción e instalación de release se registran por separado cuando terminen.

## Biblioteca y recuperación

Room utiliza esquema 20 y exportación KSP. La migración19→20 reconstruye exclusivamente el índice FTS4 con `unicode61/remove_diacritics=1`, repuebla canciones y restaura triggers; añade `download_queue.requiresCharging` con valor inicial 0. No elimina favoritos, colecciones, historial, descargas ni cola. No hay fallback destructivo. El esquema19 se exportó con sus fuentes originales antes del cambio; 18 es una fixture estructural reconstruida y documentada, no una supuesta exportación histórica. Las pruebas MigrationTestHelper cubren 18/19→20 y dos variantes 17 (preferencias nullable e índice FTS experimental). Están escritas, no ejecutadas. La conservación y búsqueda en Android real siguen requiriendo evidencia propia.

Ajustes → Administrar biblioteca permite importar/exportar M3U/M3U8 UTF-8. Las entradas accesibles se deduplican; se informan archivos ausentes o sin permiso. Las rutas relativas necesitan una carpeta de música SAF autorizada; `..` se rechaza. No se descarga audio remoto implícitamente. Las URIs exportadas no llevan los permisos a otro dispositivo.

La gestión de almacenamiento informa tamaño por colección y audio compartido, reconoce aliases mediante rutas de descarga y detecta duplicados por SHA-256 del contenido. La retirada por lote mueve **archivos privados propios** a una papelera persistente, conserva la biblioteca y ofrece deshacer. Vaciarla libera espacio y es irreversible. Archivos externos/MediaStore o compartidos con una colección conservada se reportan como omitidos; no se simula una eliminación que requiera autorización de otro proveedor. Una descarga nueva tiene prioridad frente al deshacer.

## Respaldos

Las reglas de Auto Backup/transferencia permiten únicamente el archivo de preferencias portables seleccionado. Bases Room/WorkManager, audio, cookies, credenciales, grants, WebView y estados efímeros no forman parte de esa allowlist. DataStore migra los ajustes de ese archivo al restaurar; Spotify vuelve desconectado. La política XML está implementada; cloud backup y traslado real entre dispositivos no se han probado durante esta sesión.

El respaldo explícito portable JSON incluye metadatos, colecciones, orden, favoritos y ajustes admitidos, con validación previa y transacciones. La contraseña opcional usa PBKDF2-HMAC-SHA256 (210 000 iteraciones, salt aleatorio) y AES-GCM; permite recuperar el archivo en otro dispositivo usando la misma contraseña. Keystore protege solamente la copia local de la contraseña necesaria para la programación. Esa clave local no es la clave portable del archivo. Perder la contraseña impide descifrarlo.

La programación SAF admite intervalo 1–30 días, rotación 1–30 copias, ejecución manual y desactivación. Solo rota nombres generados por MusicVault tras guardar la copia nueva. Se comprueba el grant persistente y se informa su pérdida. **Los respaldos programados/JSON no incluyen audio.**

El paquete debug→release existente sí transporta audio. Ahora muestra bytes/archivos procesados y fases de metadatos, permite cancelación durante la copia, verifica hashes y limita el audio total a 4 GiB. La restauración confirma biblioteca y referencias de audio en una transacción; un fallo/cancelación revierte cambios pendientes y limpia archivos preparados. No sustituye audio existente accesible. Los controles están en Administrar biblioteca según el applicationId.

## Descargas y proveedores

La auto descarga permite elegir playlists/favoritos, revisar altas/bajas, cancelar pendientes automáticos obsoletos, deduplicar con DownloadQueue y limitar el total estimado de audio. Se revisa cada 6 horas, requiere Wi-Fi real y carga, y mantiene esas condiciones en la tarea durable. La cuota usa una reserva conservadora a 320 kbps y los bytes existentes; no es una cuota exacta medida por contenido remoto antes de descargar. El modo offline omite ejecución automática conservando la programación.

La cola conserva generaciones, estado y restricciones ante reinicios. La infraestructura sigue basada en WorkManager; no se afirma haber sustituido los lotes por UserInitiatedDataTransfer. Hay errores accionables y mantenimiento observable/manual del extractor; los límites de tiempo del sistema y la recuperación requieren comprobación en dispositivo. La política del servicio multimedia sigue separada del servicio foreground de descargas.

Las descargas nuevas y promoción de caché incorporan tags/portada mediante el camino común AudioTagger/FFmpeg para contenedores compatibles. La lectura desde reproductores externos y fallos de codec/conversión no se han comprobado aquí.

## Reproducción, letras y recomendaciones

PlaybackQueueState conserva cola completa, índice, posición, repetición y shuffle para restaurar estado y resolver referencias de nuevo. Velocidad y saltar silencios se integran con Media3; la posición de letras sigue la velocidad real. El modo sin crossfade utiliza la preparación de cola de ExoPlayer; no hay una medición que certifique gapless en todos los codecs y archivos con padding/delay.

La normalización prioriza tags ReplayGain (ID3, MP4 y Vorbis). Cuando no existen, puede analizar RMS de audio local y atenuar a un objetivo conservador, sin amplificar ni prometer medición EBU R128/LUFS. No se presenta LoudnessEnhancer como normalización científica.

Las letras se obtienen automáticamente; se retiraron el editor manual y el ajuste de desfase por canción. Historial, finalizaciones y saltos explícitos alimentan recomendaciones con decaimiento temporal; detenerse/error/muerte del proceso no se interpreta automáticamente como rechazo. Existen pruebas deterministas del ranker y la cola serializada, además de instrumentación del ViewModel (back stack/restauración) y persistencia de DownloadQueue (generaciones/condiciones/cancelación). No se ejecutaron.

Radio y artistas relacionados usan catálogo remoto con disponibilidad dependiente del proveedor. ListenBrainz es opt-in, protege el token con Keystore, acumula escuchas reales en una cola local y deduplica por canción/inicio; desconectar borra credencial/cola. No hay envío de historial por defecto. Last.fm es una alternativa de la hoja de ruta, no una segunda integración implementada.

## Arquitectura, accesibilidad y dispositivos

AppContainer proporciona dependencias; coordinadores extraídos separan búsqueda, descargas, biblioteca y reproducción. SettingsScreen abre herramientas independientes. La navegación conserva un historial de secciones en SavedStateHandle, identidades de detalles y estado del reproductor; usa PredictiveBackHandler para confirmar Atrás al terminar el gesto y cancelar sin navegar; la interacción predictiva/recreación y TalkBack requieren validación real.

Se han añadido semánticas y controles accesibles en Compose/Views. No se afirma que exista una auditoría TalkBack o evidencia a fuente 200% para todas las pantallas.

MediaLibraryService ofrece catálogo local navegable para hosts Android Auto y controles externos. Su existencia no prueba validación con un host real ni equivale a una aplicación Wear autónoma. **No hay módulo Wear compañero instalado.** Cast integra sesión emisora y controles con URLs HTTP(S) accesibles al receptor; archivos content/file del teléfono no se envían al receptor mediante un servidor local inexistente. Compatibilidad con codecs/URLs de proveedores requiere dispositivo Cast.

AppPreferences es un único propietario DataStore con fachada compatible con consumidores anteriores SharedPreferences. Las preferencias de sesión, programación y credenciales continúan separadas. Los controles, herramientas, menús accesibles y textos estáticos extraídos usan recursos en español y traducciones en inglés (257 entradas por idioma). La selección sigue el idioma del sistema. Quedan mensajes dinámicos y errores heredados en español; no se presenta la extracción como una traducción exhaustiva de todos los mensajes técnicos. El script `tools/externalize_ui_strings.py` conserva IDs estables y un diccionario de traducciones para ampliar cobertura.

## Calidad, diagnóstico y limpieza

`.github/workflows/android-quality.yml` configura JDK 17, SDK 36, unit tests y lint con publicación de reportes; instrumentación es una opción manual en un job aparte y no utiliza smoke externo por defecto. Añadir el workflow no afirma que GitHub Actions lo haya ejecutado. No necesita cookies/token del usuario ni publica bases/audio.

LeakCanary se limita a debug. JankStats permite captura local opt-in de frames por estado de pantalla; no es un resultado de benchmark. LocalCrashRecorder conserva tipo y símbolos de stack de la app, sin mensajes de excepción, y mantiene el handler original. El diagnóstico es local, acotado y exportado por SAF a solicitud del usuario, con redacción de credenciales/URLs; no hay telemetría automática ni ACRA enviando datos por defecto.

El [módulo benchmark](../benchmark/README.md) solo se incluye con `-Pmusicvault.benchmark=true`. Contiene Macrobenchmark real de arranque frío, comparación sin AOT/con baseline requerido, FrameTiming de arranque/scroll y generación real de Baseline Profiles. Usa la variante performance optimizada y un manifest profileable exclusivo. No se ejecutó, no reemplazó el perfil distribuido y no hay resultados numéricos ni afirmación de mejora.

Se retiraron SleepTimer, DemoCatalog, DownloadManager y la interfaz/placeholder AudioSourceProvider/FutureVideoCatalogProvider después de comprobar que no tienen consumidores en main/test/androidTest. El temporizador utilizado por PlaybackOptions/MusicPlaybackService permanece. Navigation Compose y Media3 UI no tenían imports de producción; las decisiones de dependencias y construcción final están en app/build.gradle.kts. No se ha medido una diferencia de tamaño atribuible a esta limpieza.

## Actualización controlada de dependencias

La matriz base conservada es Kotlin/compiler 2.0.21, KSP 2.0.21-1.0.28, AGP 8.10.1/Gradle 8.11.1/JDK 17, Compose BOM 2024.12.01, Room 2.7.0, WorkManager 2.10.0 y Media3 compatible fijado en app/build.gradle.kts. Cast framework se fija en 22.0.0, compatible con el compiler Kotlin actual; versiones con metadatos Kotlin 2.2 requieren actualizar primero ese grupo. Las incorporaciones (Room testing, DataStore, JankStats, debug LeakCanary, Cast y módulo benchmark opcional) se agrupan por función. No se declara una actualización general a versiones más recientes sin resolver sus requisitos de metadatos.

| Grupo | Orden y comprobación necesaria antes de ampliar versiones |
|---|---|
| Kotlin/KSP/compiler Compose | Actualizar conjuntamente versiones compatibles y revisar metadata de dependencias transitivas. |
| AGP/Gradle/JDK/SDK | Respetar matriz soportada y construir release R8/firma/ABI antes de adoptar nuevas herramientas. |
| Compose BOM/Activity/Lifecycle | Verificar navegación, Views híbridas, accesibilidad, fuente grande y restauración. |
| Room | Exportar antes/después y ejecutar migraciones sobre fixtures soportadas sin fallback destructivo. |
| WorkManager | Comprobar stop reasons, foreground timeout, cuotas y Wi-Fi/carga en Android real. |
| Media3/Cronet/Cast | Coordinar Kotlin compatible y verificar local/streaming/cola/crossfade/perfiles/receptor. |
| Proveedores/NewPipe/yt-dlp | Mantener fixtures offline y comprobar extracciones con autorización; no exigir servicios externos en cada CI. |

La licencia de proyecto elegida por el propietario es GPL-3.0-or-later; conservar notices y ofrecer el código correspondiente al distribuir. Esta elección no resuelve por sí sola términos de plataformas ni aceptación en Google Play. No se publicó en una tienda durante esta sesión.

## Construcción de esta release

`assembleRelease` finalizó correctamente el 8 de octubre de 2026: versión 0.6.0 / versionCode 23, R8 y resource shrinking activos, firma local de desarrollo, APK universal ARM32/ARM64 en `dist/MusicVault-Android-0.6.0-release.apk`. También se entrega `dist/MusicVault-Android-0.6.0-source.zip`.

No se ejecutaron tests, lint, benchmarks ni apertura/smoke de la app. Se excluyeron explícitamente las tareas lintVital de la construcción. La instalación del APK 0.6.0 se completó en `192.168.1.13:44221` mediante `adb install -r --no-incremental` (resultado `Success`), conservando los datos. El primer destino `192.168.1.13:45345` no era accesible.


## Corrección de Inicio (0.6.1)

«Álbumes para ti» valida el tipo de lanzamiento (álbum o EP), al menos dos pistas y una lista de canciones disponible antes de recomendarlo. Descarta singles y filas de catálogo incompletas. Reconoce tanto los identificadores Deezer `release` como `release-group` al abrir el detalle, conserva el orden de las pistas del proveedor y precarga las canciones verificadas. Renueva la caché de recomendaciones para evitar tarjetas antiguas incorrectas.

El botón de regreso de las colecciones utiliza una flecha real, superficie del tema, borde discreto y área táctil de 48 dp; sustituye el icono de pista anterior del detalle local. No se ejecutaron pruebas de interfaz o proveedores para esta corrección.

El 9 de octubre de 2026, `assembleRelease` terminó con `BUILD SUCCESSFUL` para 0.6.1 / versionCode 24. El APK universal con firma local se instaló mediante ADB en `192.168.1.13:44221` con resultado `Success`, sin desinstalar ni borrar datos. No se abrió la app ni se ejecutaron tests, lint o benchmarks.

## Menú de canciones de Inicio (0.6.2)

Se sustituyó `android.R.drawable.ic_menu_more` en el menú compartido de canciones por un vector propio de tres puntos horizontales, con superficie oscura, esquinas de 16 dp, borde discreto y respuesta al toque. Mantiene un área táctil de 48 dp y una descripción accesible localizada. El cambio incluye las tarjetas «Para ti» de Inicio y los demás lugares que usan ese menú compartido.

## Playlist y DJ de Inicio (0.6.3)

La creación de playlists deja de superponer el desplazamiento del diálogo con el RecyclerView: la lista ocupa la altura disponible mediante `weight(1f)` y mantiene su propio desplazamiento. Nombre, buscador, selección y botones permanecen fuera de ese desplazamiento.

Inicio incluye «DJ para ti», con iniciar, otra mezcla y detener. `DjRecommendationEngine` calcula afinidad por canción/artista usando el historial local, proporción realmente escuchada, repeticiones y saltos explícitos. Las señales pierden la mitad de su peso cada 21 días. Descargar una canción no añade afinidad. Consulta radio de Deezer para hasta dos artistas semilla, combina esos resultados con metadatos conocidos, prioriza descubrimiento, penaliza escuchas de las últimas seis horas e intercala artistas. Excluye canciones ya elegidas durante la sesión, incluso metadatos duplicados por título/artista. El historial permanece local; las consultas de catálogo envían nombres de artistas, no el historial completo.

El registro de escuchas incluye avances automáticos y streaming: conserva metadatos sin guardar URLs temporales como audio local, crea una escucha después de tres segundos efectivamente reproducidos, acumula tiempo de reproducción (adelantar no cuenta como escuchar) y actualiza la proporción por fila de historial. Se reconoce una nueva reproducción al cambiar la época de transición del controlador, incluyendo repeticiones. Los saltos desde controles de la app y cambios de canción mediante controles externos se registran como señal negativa si la proporción escuchada es menor a 0,35.

DJ añade un sucesor a la cola a demanda, resuelve streaming sin descargar permanentemente y vuelve a ordenar candidatos con el historial actualizado. Cambiar manualmente de canción o iniciar una playlist desactiva DJ; detener pausa la reproducción. Funciona sin conexión con archivos disponibles. La sesión DJ no se restaura como modo activo después de cerrar el proceso; se conserva el historial y el respaldo ordinario de la cola. No se ejecutaron pruebas de interfaz, algoritmos o proveedores en esta sesión.

Release 0.6.3 / versionCode 26: `assembleRelease` finalizó con `BUILD SUCCESSFUL`. El APK definitivo se instaló en `192.168.1.13:44221` mediante `adb install --no-incremental -r`, con resultado `Success`, conservando los datos. No se ejecutaron tests, lint, benchmarks ni apertura de la app.
