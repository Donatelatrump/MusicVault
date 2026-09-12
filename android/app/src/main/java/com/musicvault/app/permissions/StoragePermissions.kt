package com.musicvault.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Centraliza la decisión de qué permiso de almacenamiento aplica
 * según el nivel de SDK y verifica si está otorgado.
 *
 * Android distingue dos momentos:
 *
 * - Lectura (escanear audio del dispositivo): siempre se necesita un permiso
 *   de audio (READ_MEDIA_AUDIO en API 33+, READ_EXTERNAL_STORAGE en API <33).
 *
 * - Escritura pública (guardar en Downloads/Music via MediaStore): sólo
 *   requiere WRITE_EXTERNAL_STORAGE hasta API 28 (Android 9). Desde API 29
 *   las descargas que la app hace van a getExternalFilesDir() o a una URI
 *   SAF, ambos libres de permisos en tiempo de ejecución. Si en el futuro
 *   se quiere guardar en MediaStore.Downloads/Music en API 29+, ya no se
 *   necesita WRITE_EXTERNAL_STORAGE pero sí la inserción por MediaStore.
 */
object StoragePermissions {

    /**
     * Modo de almacenamiento activo. Sirve como capa única de compatibilidad
     * para que el resto del código no tenga que ramificar por Build.VERSION
     * cada vez que necesita una ruta o un check de permisos.
     */
    enum class Mode {
        /** API 28 o inferior: requiere WRITE_EXTERNAL_STORAGE en runtime. */
        LEGACY_WRITE_REQUIRED,

        /** API 29-32: scoped storage activo. La app escribe en su carpeta privada
         *  y/o usa SAF; no se necesita ningún permiso runtime de almacenamiento. */
        SCOPED_STORAGE_NO_PERMISSION,

        /** API 33+: igual que SCOPED_STORAGE pero con permisos de lectura
         *  granulares (READ_MEDIA_AUDIO). */
        GRANULAR_MEDIA_PERMISSIONS,
    }

    /** Resuelve el [Mode] según el Build.VERSION actual. */
    fun currentMode(): Mode = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            Mode.GRANULAR_MEDIA_PERMISSIONS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            Mode.SCOPED_STORAGE_NO_PERMISSION
        else -> Mode.LEGACY_WRITE_REQUIRED
    }

    /**
     * Permiso necesario para que [android.provider.MediaStore] pueda insertar
     * archivos en colecciones públicas (Downloads, Music, etc.) en
     * dispositivos antiguos. En API 29+ no es necesario declararlo; el
     * MediaStore gestiona el scoped storage y `getExternalFilesDir()` +
     * SAF funcionan sin permiso runtime.
     */
    fun writeLegacyStoragePermissions(): Array<String> = when (currentMode()) {
        Mode.GRANULAR_MEDIA_PERMISSIONS,
        Mode.SCOPED_STORAGE_NO_PERMISSION -> emptyArray()
        Mode.LEGACY_WRITE_REQUIRED -> arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /**
     * Permiso que la UI ya solicita para escanear audio local.
     * Lo exponemos aquí para que MainActivity pueda verificarlo en el mismo
     * punto que el de escritura y evitar lanzar dos diálogos separados.
     */
    fun readAudioPermission(): String = when (currentMode()) {
        Mode.GRANULAR_MEDIA_PERMISSIONS -> Manifest.permission.READ_MEDIA_AUDIO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    /**
     * Conjunto de permisos que MainActivity debe solicitar al usuario en el
     * arranque si falta alguno. Combina lectura (escanear biblioteca) y
     * escritura legacy (guardar en almacenamiento público en API ≤ 28).
     */
    fun requiredAtStartup(): Array<String> {
        val perms = mutableListOf<String>()
        perms += readAudioPermission()
        perms += writeLegacyStoragePermissions()
        return perms.toTypedArray()
    }

    /**
     * Devuelve los permisos que faltan. Si la lista está vacía, no hay que
     * mostrar el diálogo del sistema (Android recordará la denegación o el
     * usuario ya concedió).
     */
    fun missing(context: Context): Array<String> =
        requiredAtStartup().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    /**
     * ¿El usuario otorgó los permisos legacy necesarios para escribir en
     * almacenamiento público? En API 29+ siempre retorna true porque el
     * scoped storage gestiona las inserciones a MediaStore sin permiso.
     */
    fun canWriteLegacy(context: Context): Boolean {
        val required = writeLegacyStoragePermissions()
        if (required.isEmpty()) return true
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * ¿El código puede apoyarse en [android.provider.MediaStore] sin
     * pedir permisos adicionales? En API 29+ sí (scoped storage); en
     * API ≤28 también, pero hay que tener WRITE_EXTERNAL_STORAGE.
     */
    fun canUseMediaStore(context: Context): Boolean = when (currentMode()) {
        Mode.GRANULAR_MEDIA_PERMISSIONS,
        Mode.SCOPED_STORAGE_NO_PERMISSION -> true
        Mode.LEGACY_WRITE_REQUIRED -> canWriteLegacy(context)
    }

    /**
     * Mensaje corto, dependiente de la versión, para mostrar al usuario
     * cuando [canWriteLegacy] retorna false. Apunta siempre al camino real:
     * Ajustes del sistema en API antiguas; nunca en API 29+ donde no hay
     * permiso que conceder.
     */
    fun explainWhyCannotWrite(context: Context): String? = when {
        canWriteLegacy(context) -> null
        currentMode() == Mode.LEGACY_WRITE_REQUIRED ->
            "Sin permiso de almacenamiento. Ve a Ajustes del sistema → Apps → MusicVault → Permisos y habilita el permiso de almacenamiento."
        else ->
            "No se puede escribir en el almacenamiento del dispositivo. Reintenta o reinicia la app."
    }
}