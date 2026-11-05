# Actualizaciones opcionales desde GitHub Releases

Android consulta las releases públicas de `Donatelatrump/MusicVault` al entrar en primer plano con conexión (máximo una comprobación exitosa cada 24 horas). Los errores permiten otro intento tras un minuto. Ajustes → Actualizaciones permite comprobar y actualizar manualmente. El modo sin conexión bloquea comprobaciones y descargas.

«Ahora no» guarda el versionCode rechazado y no vuelve a mostrar automáticamente esa versión. La actualización permanece en Ajustes incluso tras reiniciar la app. Una versión posterior puede mostrar un nuevo aviso. No se descarga el APK hasta pulsar Actualizar. Android solicita autorización de instalación y confirmación; rechazar cualquiera conserva la app instalada.

Las etiquetas de GitHub mezclan Windows y Android y no representan el versionName del APK. Por eso se requiere el asset `musicvault-android-update.json`. Las releases antiguas sin ese archivo se omiten. Borradores y prereleases también se omiten. Se elige el versionCode mayor para el mismo packageName y una ABI compatible; no se ofrecen APKs de producción a una instalación `.debug` ni versiones iguales/anteriores.

## Publicar una actualización

1. Aumentar `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Compilar los APKs release con la misma clave de firma que la versión distribuida. Conservar esa clave entre versiones.
3. Adjuntar `app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk` y opcionalmente `app-universal-release.apk` a una release estable antes de publicarla.
4. El workflow `.github/workflows/android-update-metadata.yml` genera y adjunta el JSON al publicarla. Si se adjuntaron los APKs después, ejecutar manualmente el workflow con la etiqueta de esa release.

También se puede generar el archivo localmente:

```bash
python3 android/tools/create_update_manifest.py --tag android-v0.7.0 \
  --apk arm64-v8a=android/app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  --apk armeabi-v7a=android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk \
  --apk universal=android/app/build/outputs/apk/release/app-universal-release.apk
```

El script lee packageName/versionCode/versionName directamente del APK con `aapt`, calcula tamaño/SHA-256 y construye los enlaces de la release. `--aapt` permite indicar la herramienta del SDK. Adjuntar el JSON generado a la misma release que los APKs. Para APKs con otros nombres o paquetes de prueba, generarlo manualmente con el mismo script.

La app descarga en su caché privada, verifica SHA-256, tamaño, packageName, versionCode y firma antes de abrir el instalador. No solicita permisos de instalación hasta que el usuario acepta descargar una actualización. No publica releases desde la app.

Referencias: [API de releases de GitHub](https://docs.github.com/en/rest/releases/releases) y [permisos de instalación de Android](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).
