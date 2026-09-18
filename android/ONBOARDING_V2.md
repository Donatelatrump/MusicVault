# MusicVault onboarding v2

Nuevo flujo de primera ejecución:

1. Pantalla completa de géneros: selección obligatoria de al menos un género, con más variantes.
2. Pantalla completa de artistas: las sugerencias cambian según los géneros elegidos; se pueden seleccionar varios o añadir uno manualmente.
3. Handoff animado: al confirmar artistas se guardan las preferencias de inmediato, Home se monta detrás y el ViewModel empieza a buscar/reordenar recomendaciones.
4. La animación permanece un mínimo de ~2.3 s y espera hasta ~3.5 s adicionales por recomendaciones reales antes de liberar la pantalla; nunca bloquea indefinidamente si no hay red.
5. Los permisos de Android se solicitan después del handoff, no encima del onboarding.

También se restauró el APK universal ARM práctico: Gradle genera arm64-v8a, armeabi-v7a y un `app-universal-*` que combina solo esas dos arquitecturas (sin x86/x86_64 de FFmpeg).

Compilar:

```bash
./gradlew :app:compileDebugKotlin -q --console=plain
./gradlew assembleDebug
```

APK universal esperado:

`app/build/outputs/apk/debug/app-universal-debug.apk`
