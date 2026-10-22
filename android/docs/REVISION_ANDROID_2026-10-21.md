# Revisión Android — 21 de octubre de 2026

## Fallos encontrados y corregidos

1. **Compatibilidad de cancelación de descargas (Android 8–11).** `DownloadWorker` consultaba `stopReason`, anotado para API 31, sin comprobar la versión. Ahora lo consulta desde Android 12 y usa un motivo neutro en versiones anteriores. Lint detectó doce errores relacionados con este acceso.
2. **Descargas automáticas después de limpiar audio.** Una fila histórica `complete` impedía descargar una canción que la biblioteca ya marcaba como no descargada. Ahora solo una tarea activa impide programarla de nuevo.
3. **Reserva de cuota automática.** El filtro reservaba espacio para todas las canciones elegibles antes de limitar la lista a 50. Ahora la selección se detiene al reunir 50 y reserva únicamente sus bytes estimados.
4. **Declaración de búsqueda por voz.** Faltaba `android.media.action.MEDIA_PLAY_FROM_SEARCH` en el servicio multimedia anunciado para Android Auto. Se añadió al filtro del servicio, que ya procesa búsquedas mediante Media3.

## Validación

- 63 pruebas unitarias: sin errores, fallos ni omisiones; repetidas después de las correcciones finales.
- Lint release final: **0 errores, 174 advertencias y 4 sugerencias**. Informe completo en `app/build/reports/lint-results-release.html`. Predominan sugerencias de Kotlin, dependencias, recursos e internacionalización. Los avisos de contexto estático revisados en `AppContainer` y `AppPreferences` guardan `applicationContext`; los listeners táctiles revisados devuelven `false` y delegan el clic a la vista.
- 6 pruebas instrumentadas en HONOR WDY-LX3, serie `AXLKCP3B11408575`: todas pasaron. Cubren migraciones 17/18/19 → 20, variantes históricas de esquema, conservación de biblioteca y cola, búsqueda Unicode, protección frente a workers antiguos y arranque de Python/yt-dlp. Se ejecutaron en el paquete debug separado.
- La descarga desde un proveedor remoto, Cast y Android Auto conectado a un automóvil no se probaron de extremo a extremo.

La revisión se concentra en migraciones, persistencia, descargas, reproducción y los diagnósticos de lint; no demuestra ausencia de fallos en todos los flujos.

## Release e instalación

- `assembleRelease`, `verifyReleaseDownloadRules`, `lintRelease` y `testDebugUnitTest`: `BUILD SUCCESSFUL` en la ejecución final.
- Versión 0.6.3, código 26, APK universal ARM32/ARM64: `app/build/outputs/apk/release/app-universal-release.apk`.
- Firma local de desarrollo (`CN=Android Debug`), verificada mediante `apksigner`; esquema v2 válido. No es una nueva clave de distribución.
- SHA-256 del APK: `0e2caca1a6a360a44f0a1187e9a04ae179faeccabf214446c508ee691079e8ca`.
- Instalación en `AXLKCP3B11408575` mediante `adb install --no-incremental -r`: `Success`, sin desinstalar ni borrar datos.
- Arranque frío de la release: `Status: ok`, 1610 ms; proceso 3368 vivo y sin mensajes de nivel error en la consulta de logcat posterior. El dispositivo estaba bloqueado; no se recorrieron visualmente sus pantallas.
