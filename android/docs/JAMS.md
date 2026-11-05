# JAMS y UID por instalación

La release 0.6.15 (versionCode 38) añade una identidad aleatoria por instalación y salas compartidas mediante Firebase Realtime Database.

## Identidad

`InstallationIdentity` crea un UUID aleatorio y lo guarda mediante escritura atómica en `noBackupFilesDir`. Se conserva al actualizar y reiniciar la aplicación. No se deriva de Android ID, IMEI, correo, nombre, número de teléfono ni identificadores de hardware. Otra instalación genera otro UUID. El archivo no se transfiere con los respaldos de preferencias.

Firebase Authentication inicia sesión de forma anónima y proporciona un UID de autorización independiente. La base solo vincula ese UID con el UUID de instalación; las reglas permiten leer/escribir ese vínculo únicamente a su propietario. No se incorpora Firebase Analytics ni un formulario de datos personales. Esto identifica instalaciones, no demuestra la identidad de personas. Firebase y los proveedores de audio procesan las conexiones conforme a sus propias políticas.

Proyecto: `musicvault-jams-donatelatrump`; base Realtime Database en `us-central1`. La configuración cliente de Android se descarga a archivos `google-services.json` ignorados por Git. No se incluye una cuenta de servicio ni credenciales administrativas en la app.

## Uso

1. Abrir JAMS desde la cabecera y crear una sala o introducir su código.
2. Compartir la invitación mediante el botón de compartir. El código aleatorio completo funciona tanto en la misma red como en redes distintas; siempre requiere Internet.
3. Volver a Buscar o Biblioteca y tocar canciones para añadirlas a la cola de la sala. Cada entrada contiene un ID de video de YouTube y sus metadatos, no archivos locales ni enlaces temporales de audio.
4. Cualquier miembro puede seleccionar una canción, pausar, reanudar, avanzar, retroceder o mover la posición. Las notificaciones y auriculares también envían las órdenes a la sala. El creador puede cerrar la sala y quitar canciones no activas; todos pueden salir.

Todos reproducen el mismo video, pero cada teléfono resuelve y descarga su propio audio. El estado compartido contiene canción, posición, reproducción/pausa, momento efectivo y una revisión creciente. Las transacciones ordenan cambios simultáneos. Se estima la hora del servidor, se programan órdenes con 1,5 segundos de margen y se corrigen desvíos superiores a 350 ms cada 250 ms, extrapolando las muestras del reproductor. En JAMS se usa velocidad 1× y se desactiva omitir silencios para que las preferencias individuales no separen a los participantes.

La sincronización es aproximada: no se promete reproducción idéntica a nivel de muestra. La latencia, Bluetooth, restricciones de batería, carga del extractor y buffering pueden causar diferencias. Un teléfono que termina de preparar tarde adelanta su posición hasta la línea temporal compartida. Si pierde Internet, pausa su audio y se alinea al reconectar. Un participante en línea elegido de manera determinista avanza automáticamente al terminar una canción; cualquier miembro puede avanzar manualmente. El creador no necesita permanecer conectado para que los demás controlen la reproducción.

Las salas no se pueden enumerar y se necesita conocer el código para unirse. Las reglas validan pertenencia, contenido de la cola y las revisiones de reproducción. La invitación concede acceso a la sala; compartirla implica permitir sus controles compartidos. Las salas caducan a las 24 horas; la caducidad restringe el acceso, no equivale a una tarea automática de borrado de registros antiguos. Cerrar la sala elimina sus datos. La interfaz limita la cola a 100 canciones.

## Configuración reproducible

La integración oficial de Firebase se instaló y se autorizó con el inicio de sesión de su CLI. Los servicios y reglas se configuraron con las habilidades oficiales `firebase-basics` y `firebase-auth-basics`.

```bash
npx -y firebase-tools@latest login --interactive
npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:905019359968:android:58b255f7f72849f22a4685 \
  --project musicvault-jams-donatelatrump --out android/app/google-services.json
npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:905019359968:android:8a2fc3b664311bba2a4685 \
  --project musicvault-jams-donatelatrump --out android/app/src/debug/google-services.json
cd firebase
npx -y firebase-tools@latest deploy --only auth,database --project musicvault-jams-donatelatrump
```

Los APKs distribuidos deben incluir la configuración release y conservar la misma firma que 0.6.14. Incrementar siempre versionCode para que el actualizador los ofrezca. La release de GitHub debe incluir `musicvault-android-update.json`, generado con `android/tools/create_update_manifest.py`, además de sus APKs.

## Validación

- Pruebas unitarias de la línea temporal: pausa, reanudación, entrada tardía, tolerancia al desfase, duración e invitaciones.
- `npm ci` y `npm test` desde `firebase` (Java 21 o posterior) ejecutan cinco pruebas de reglas en un proyecto de emulador, sin servicios reales.
- `node firebase/test/jams-live.mjs --live` verifica dos cuentas anónimas temporales contra el proyecto configurado: cola compartida y play/pause/seek desde ambos participantes. El script elimina la sala y las cuentas de prueba al finalizar.
- La prueba de UID por instalación se incluye en Android instrumentation; usa directorios temporales y no altera el UID real del teléfono.
- Las pruebas de base de datos no sustituyen una medición acústica con dos teléfonos físicos. Para esa validación, actualizar ambos a la misma release, unirse a una sala, probar controles desde los dos y repetir en redes distintas.

Referencias: [autenticación anónima](https://firebase.google.com/docs/auth/android/anonymous-auth), [hora y presencia del servidor](https://firebase.google.com/docs/database/android/offline-capabilities), [reglas de Realtime Database](https://firebase.google.com/docs/database/security).
