package com.musicvault.app.backup

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ScheduledBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BackupSchedule.enabled(applicationContext)) return Result.success()
        val prefs = BackupSchedule.prefs(applicationContext)
        var partial: DocumentFile? = null
        try {
            val uri = Uri.parse(prefs.getString("tree", null) ?: error("Selecciona la carpeta de respaldo."))
            val grant = applicationContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            check(grant) { "Se perdió el permiso de la carpeta. Selecciónala de nuevo." }
            val folder = DocumentFile.fromTreeUri(applicationContext, uri) ?: error("Carpeta no disponible.")
            check(folder.canWrite()) { "La carpeta de respaldo no permite escritura." }
            val password = BackupSchedule.password(applicationContext)
            val filename = "MusicVault-${System.currentTimeMillis()}.mvbackup"
            val file = folder.createFile("application/json", filename) ?: error("No se pudo crear el respaldo.")
            partial = file
            try { LibraryBackupStore.export(applicationContext, file.uri, password) } finally { password?.fill('\u0000') }
            partial = null
            // Rotate only our files after the new document was successfully written.
            folder.listFiles().filter { it.name?.matches(Regex("MusicVault-\\d+\\.mvbackup(?:\\.json)?")) == true }
                .sortedByDescending { it.name }.drop(prefs.getInt("keep", 5)).forEach { check(it.delete()) { "No se pudo rotar un respaldo antiguo." } }
            prefs.edit().putLong("last_success", System.currentTimeMillis()).remove("error").apply()
            return Result.success()
        } catch (cancelled: CancellationException) { runCatching { partial?.delete() }; throw cancelled }
        catch (failure: Exception) {
            runCatching { partial?.delete() }
            prefs.edit().putString("error", failure.message ?: "No se pudo guardar el respaldo.").apply()
            return if (failure is SecurityException || runAttemptCount >= 2) Result.failure() else Result.retry()
        }
    }
}

object BackupSchedule {
    private const val WORK = "musicvault-scheduled-backup"
    private const val KEY = "musicvault-backup-password"
    fun prefs(context: Context) = context.getSharedPreferences("musicvault.scheduled-backup", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun configure(context: Context, tree: Uri, password: CharArray?, keep: Int = 5, intervalDays: Long = 1) {
        require(keep in 1..30 && intervalDays in 1..30)
        val stored = password?.takeIf { it.isNotEmpty() }?.let { value ->
            require(value.size >= 8) { "La contraseña debe tener al menos 8 caracteres." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, localKey())
            Base64.encodeToString(cipher.iv + cipher.doFinal(String(value).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        prefs(context).edit().putString("tree", tree.toString()).putString("password", stored)
            .putInt("keep", keep).putLong("interval_days", intervalDays).putBoolean("enabled", true).remove("error").apply()
        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun disable(context: Context) { prefs(context).edit().putBoolean("enabled", false).remove("password").apply(); WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScheduledBackupWorker>().build())
    }
    internal fun password(context: Context): CharArray? {
        val stored = prefs(context).getString("password", null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP); require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).toCharArray()
    }
    private fun localKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
