# JAMS y UID por instalación

La release 0.6.16 (versionCode 39) añade salas visuales, amigos, enlaces de invitación y códigos de seis dígitos sobre la identidad anónima y Firebase Realtime Database introducidos en 0.6.15.

## Identidad

`InstallationIdentity` crea un UUID aleatorio y lo guarda mediante escritura atómica en `noBackupFilesDir`. Se conserva al actualizar y reiniciar la aplicación. No se deriva de Android ID, IMEI, correo, nombre, número de teléfono ni identificadores de hardware. Otra instalación genera otro UUID. El archivo no se transfiere con los respaldos de preferencias.

Firebase Authentication inicia sesión de forma anónima y proporciona un UID de autorización independiente. La base solo vincula ese UID con el UUID de instalación; las reglas permiten leer/escribir ese vínculo únicamente a su propietario. No se incorpora Firebase Analytics ni un formulario de datos personales. Esto identifica instalaciones, no demuestra la identidad de personas. Firebase y los proveedores de audio procesan las conexiones conforme a sus propias políticas.

Proyecto: `musicvault-jams-donatelatrump`; base Realtime Database en `us-central1`. La configuración cliente de Android se descarga a archivos `google-services.json` ignorados por Git. No se incluye una cuenta de servicio ni credenciales administrativas en la app.

## Uso

1. Abrir JAMS desde la cabecera y crear una sala o introducir su código.
2. Compartir la invitación mediante el botón de compartir. El enlace HTTPS abre la app y permite entrar automáticamente. También se muestra un código aleatorio de seis dígitos, copiable, que se puede escribir al unirse. Ambos funcionan en redes distintas; siempre requieren Internet. Los códigos no se reutilizan mientras su sala está vigente.
3. Volver a Buscar o Biblioteca y tocar una canción para prepararla y reproducirla para todos. La acción explícita «Agregar a cola» solo la añade. Las filas muestran preparación, confirmación o error. Cada entrada contiene un ID de video de YouTube y sus metadatos, no archivos locales ni enlaces temporales de audio.
4. Cualquier miembro puede seleccionar una canción, pausar, reanudar, avanzar, retroceder o mover la posición. Las notificaciones y auriculares también envían las órdenes a la sala. El creador puede cerrar la sala y quitar canciones no activas; todos pueden salir.

Todos reproducen el mismo video, pero cada teléfono resuelve y descarga su propio audio. El estado compartido contiene canción, posición, reproducción/pausa, momento efectivo y una revisión creciente. Las transacciones ordenan cambios simultáneos. El reloj del servidor se ancla a tiempo monotónico. Los controles responden localmente y sus transacciones se envían en orden, sin el margen artificial anterior de 1,5 segundos. Las confirmaciones antiguas no revierten pulsaciones recientes. Al seleccionar una canción, la línea temporal espera a que los participantes preparen el audio; un líder publica el inicio con 180 ms de margen. Tras 12 segundos se permite avanzar a quienes estén listos. La alineación consulta la posición real cada 100 ms; corrige desvíos pequeños con hasta ±3 % de velocidad y reserva los saltos para diferencias grandes o desplazamientos explícitos. No repite saltos durante buffering. Se desactiva omitir silencios en JAMS para mantener la línea temporal compartida.

La sincronización es aproximada: no se promete reproducción idéntica a nivel de muestra. La latencia, Bluetooth, restricciones de batería, carga del extractor y buffering pueden causar diferencias. Un teléfono que termina de preparar tarde adelanta su posición hasta la línea temporal compartida. Si pierde Internet, pausa su audio y se alinea al reconectar. Un participante en línea elegido de manera determinista avanza automáticamente al terminar una canción; cualquier miembro puede avanzar manualmente. El creador no necesita permanecer conectado para que los demás controlen la reproducción.

Las salas y el directorio de códigos no se pueden enumerar. Resolver un código corto requiere una consulta privada autenticada, con intervalo mínimo de dos segundos y permiso de lectura de diez segundos. Las reglas validan pertenencia, contenido de la cola y las revisiones de reproducción. La invitación concede acceso a la sala; compartirla implica permitir sus controles compartidos. Las salas caducan a las 24 horas; la caducidad restringe el acceso, no equivale a una tarea automática de borrado de registros antiguos. Cerrar la sala elimina sus datos. La interfaz limita la cola a 100 canciones.

La pantalla muestra los participantes mediante avatares anónimos y permite volver directamente a Biblioteca o Buscar desde la barra inferior. Los amigos se agregan por invitación o desde la sala, con aceptación del destinatario; luego pueden recibir invitaciones de sala. No hay un directorio público de usuarios.

Los enlaces HTTPS de Firebase Hosting incluyen la asociación Android verificada para la firma de distribución. Si la app no está instalada, la página permite abrirla o descargarla desde GitHub Releases.

## Configuración reproducible

La integración oficial de Firebase se instaló y se autorizó con el inicio de sesión de su CLI. Los servicios y reglas se configuraron con las habilidades oficiales `firebase-basics` y `firebase-auth-basics`.

```bash
npx -y firebase-tools@latest login --interactive
npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:905019359968:android:58b255f7f72849f22a4685 \
  --project musicvault-jams-donatelatrump --out android/app/google-services.json
npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:905019359968:android:8a2fc3b664311bba2a4685 \
  --project musicvault-jams-donatelatrump --out android/app/src/debug/google-services.json
cd firebase
npx -y firebase-tools@latest deploy --only database,hosting --project musicvault-jams-donatelatrump
```

Los APKs distribuidos deben incluir la configuración release y conservar la misma firma que 0.6.14. Incrementar siempre versionCode para que el actualizador los ofrezca. La release de GitHub debe incluir `musicvault-android-update.json`, generado con `android/tools/create_update_manifest.py`, además de sus APKs.

## Validación

- Pruebas unitarias de la línea temporal: pausa, reanudación, entrada tardía, tolerancia al desfase, duración e invitaciones.
- `npm ci` y `npm test` desde `firebase` (Java 21 o posterior) ejecutan doce pruebas de reglas en un proyecto de emulador, sin servicios reales.
- `node firebase/test/jams-live.mjs --live` verifica dos cuentas anónimas temporales contra el proyecto configurado: cola compartida y play/pause/seek desde ambos participantes. El script elimina la sala y las cuentas de prueba al finalizar.
- La prueba de UID por instalación se incluye en Android instrumentation; usa directorios temporales y no altera el UID real del teléfono.
- Las pruebas de base de datos no sustituyen una medición acústica con dos teléfonos físicos. Para esa validación, actualizar ambos a la misma release, unirse a una sala, probar controles desde los dos y repetir en redes distintas.

Referencias: [autenticación anónima](https://firebase.google.com/docs/auth/android/anonymous-auth), [hora y presencia del servidor](https://firebase.google.com/docs/database/android/offline-capabilities), [reglas de Realtime Database](https://firebase.google.com/docs/database/security).
