# MusicVault Android

Versión de esta implementación: 0.6.0 (versionCode 23). Kotlin/Compose, Room y Media3; Android 8+ (API 26), target/compile SDK 36.

Documentación integral, dependencias y construcción de listas visuales: [PROYECTO_ANDROID_COMPLETO.md](docs/PROYECTO_ANDROID_COMPLETO.md). Funciones incorporadas y límites: [ROADMAP_ANDROID_IMPLEMENTADA.md](docs/ROADMAP_ANDROID_IMPLEMENTADA.md).

## Construir

Requiere JDK 17, Android SDK 36 y acceso a los repositorios de dependencias. Configura `ANDROID_HOME` o `local.properties` con tu SDK.

```bash
./gradlew :app:assembleRelease -Pmusicvault.localReleaseSigning=true -x :app:lintVitalAnalyzeRelease -x :app:lintVitalReportRelease -x :app:lintVitalRelease --console=plain
```

El APK universal ARM32/ARM64 queda en `app/build/outputs/apk/release/app-universal-release.apk`. La propiedad `musicvault.localReleaseSigning=true` usa la clave local de desarrollo para instalar esta release optimizada; para distribuir otra firma, configura la clave del propietario. Esta orden excluye lint y no ejecuta tests ni benchmarks.

CI y el módulo opcional `-Pmusicvault.benchmark=true` tienen instrucciones propias. Las pruebas añadidas están escritas pero no se ejecutaron en esta sesión, por petición del propietario.

## Código y licencia

El código propio de Android se distribuye bajo **GPL-3.0-or-later**, elección autorizada por el propietario el 8 de octubre de 2026. Puedes distribuir/modificar bajo GPL versión 3 o posterior. El texto está en [LICENSE](LICENSE); los avisos de terceros se conservan en [THIRD_PARTY_NOTICES.md](app/src/main/assets/THIRD_PARTY_NOTICES.md) y dentro del APK. Las dependencias mantienen sus licencias originales.

El árbol `android/` (fuentes, recursos, Gradle wrapper, configuración de compilación, esquemas y documentación) constituye las fuentes correspondientes de MusicVault Android. Las versiones exactas y repositorios de terceros constan en los archivos Gradle y avisos. NewPipeExtractor se obtiene de su repositorio upstream y no se modifica su código en este árbol. Este cambio de licencia aplica a Android; no cambia las otras plataformas.
