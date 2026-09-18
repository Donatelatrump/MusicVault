# MusicVault — revisión de UI / primer inicio

Cambios aplicados:

- Rediseño completo del onboarding para que siga la identidad visual `Vault`.
- Pantalla segura para edge-to-edge, desplazable y usable en pantallas pequeñas.
- Jerarquía visual clara: marca, mensaje principal, géneros, artistas y una sola acción primaria.
- Chips seleccionables para géneros y artistas, entrada manual de artistas y opción de omitir.
- Corregido el texto `Saltizar` y eliminada la pantalla de bienvenida sin contenido interactivo.
- Corregido el flujo de primera ejecución: completar u omitir ahora persiste el estado y realmente cierra el onboarding.
- Evitado el parpadeo del onboarding en usuarios existentes mientras Room determina el estado inicial.
- Los permisos de almacenamiento/audio ya no aparecen encima de la bienvenida; se solicitan al entrar a la app.
- Las preferencias elegidas en onboarding ahora participan en el feed de recomendaciones/búsqueda de catálogo.
- El APK directo queda configurado como universal (`splits.abi` desactivado), de acuerdo con el objetivo de generar un solo APK.

## Comprobación recomendada

```bash
./gradlew :app:compileDebugKotlin -q --console=plain
./gradlew assembleDebug
```

APK esperada con la configuración universal:

```text
app/build/outputs/apk/debug/app-debug.apk
```
