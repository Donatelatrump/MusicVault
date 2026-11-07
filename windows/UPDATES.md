# Actualizaciones Windows

La aplicación consulta releases públicas de `Donatelatrump/MusicVault`. No depende
del marcador global «Latest», que también se usa para Android. Lee únicamente
`musicvault-windows-update.json` en releases publicadas, estables y no borradores.
El manifiesto debe indicar `schemaVersion: 1`, `packageName: com.musicvault.windows`,
un `versionCode` mayor al instalado y un instalador `x86_64` presente en esa release.

Ejemplo de publicación:

```
python packaging/build_launcher.py --output build/launcher/MusicVault.exe
python packaging/build_portable.py
makensis packaging/installer.nsi
python packaging/create_update_manifest.py --tag windows-v0.10.0 --installer dist/MusicVault-Windows-x64-0.10.0-Setup.exe --notes RELEASE_0.10.0.md
```

Publicar juntos el instalador, el portable ZIP, sus sumas SHA-256 y el manifiesto.
Para versiones posteriores actualizar también los recursos de versión del lanzador
y reconstruirlo. `versionCode` es independiente de Android: 0.10.0 usa 1000.

El cliente solo ofrece instaladores HTTPS del repositorio configurado. Comprueba el
SHA-256 y tamaño publicados al descargar y otra vez antes de instalar. Estos controles
acreditan integridad respecto a GitHub; no son una firma Authenticode.

La revisión automática espera 24 horas después de un éxito y al menos un minuto tras
un fallo. «Ahora no» persiste el código rechazado. La comprobación manual omite ese
silencio y permite reintentar errores. La versión disponible se conserva en Ajustes
al reiniciar. La descarga puede cancelarse y nunca ejecuta archivos incompletos.

Un trabajador de PowerShell con política limitada a ese proceso espera la salida de
la aplicación y su lanzador antes de abrir el instalador. El instalador comprueba que
la instalación de destino esté cerrada y conserva `Data/`. La sesión JAMS se conserva
al actualizar en la misma ruta. No se cambia la política global de PowerShell.

La comprobación y las descargas usan la API pública sin tokens. Solo quienes tengan
permisos de publicación en GitHub pueden publicar nuevas releases. Esta aplicación
no incluye herramientas ni permisos para publicar.

Para pruebas de instalación aisladas: ejecutar el instalador con
`/S /NOINTEGRATION /D=C:\ruta\de\prueba`. El destino debe ser el último argumento.
Ese modo no registra desinstalador ni accesos en el perfil del usuario.

JAMS se distribuye con su SDK Firebase compilado en `bridge.bundle.mjs`: incluye
las bibliotecas necesarias y sus avisos de licencia, sin requerir npm al ejecutarse.
Las dependencias y hashes están en `packaging/dependencies.lock.json` y el lock npm.
