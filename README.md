# MusicVault

Versiones organizadas por plataforma:

- **[Android](android/README.md)**: aplicación Kotlin/Jetpack Compose, módulo `app/`, Gradle y configuración de compilación dentro de `android/`. Abre esa carpeta en Android Studio.
- **[Linux](linux/README.md)**: aplicación Python/Qt, pruebas, empaquetador y paquetes generados dentro de `linux/`.
- **[Windows](windows/README.md)**: aplicación portable Qt para Windows x64, con lanzador nativo `MusicVault.exe`. Descarga el ZIP completo en [Releases](https://github.com/Donatelatrump/MusicVault/releases), extráelo y abre el ejecutable.
- [iOS](ios/MusicVault/README.md): implementación conservada en `ios/`.

## Android

Desde la raíz del repositorio:

```bash
cd android
./gradlew assembleDebug
```

APK generado en `android/app/build/outputs/apk/debug/`.

## Linux

Desde la raíz del repositorio:

```bash
./linux/run.sh --doctor
./linux/run.sh
```

Para generar e instalar el paquete Debian/Ubuntu:

```bash
python3 linux/packaging/build.py
sudo apt install ./linux/dist/musicvault_0.7.1_amd64.deb
```

El empaquetador también genera `linux/dist/MusicVault-0.7.1-linux-x86_64.tar.gz`. Consulta las dependencias y pruebas en la [guía de Linux](linux/README.md).

Los recursos de marca compartidos están en `branding/`. El historial del proyecto y la documentación de proveedores siguen en [PROJECT_STATUS.md](PROJECT_STATUS.md) y [PROVIDERS.md](PROVIDERS.md).
