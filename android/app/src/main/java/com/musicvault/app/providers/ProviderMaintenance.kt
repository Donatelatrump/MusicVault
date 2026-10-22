package com.musicvault.app.providers

import android.content.Context
import com.musicvault.app.diagnostics.LocalDiagnostics
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

data class ExtractorUpdateState(val version: String? = null, val checking: Boolean = false,
    val lastCheck: Long = 0, val message: String = "Comprobación automática cada 24 horas")

/** Shared gate for downloads AND metadata. Updating never replaces a running extractor. */
object ProviderMaintenance {
    private val gate = ReentrantReadWriteLock(true)
    private val checkLock = Mutex()
    private val mutableState = MutableStateFlow(ExtractorUpdateState())
    val state = mutableState.asStateFlow()

    fun execute(request: YoutubeDLRequest, processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse {
        val read = gate.readLock()
        read.lockInterruptibly()
        try { return YoutubeDL.getInstance().execute(request, processId, false, callback) }
        finally { read.unlock() }
    }

    suspend fun check(context: Context, force: Boolean = true): ExtractorUpdateState = withContext(Dispatchers.IO) {
        checkLock.withLock {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("musicvault_provider", Context.MODE_PRIVATE)
            val last = prefs.getLong("last_ytdlp_update_check", 0)
            val now = System.currentTimeMillis()
            if (com.musicvault.app.settings.AppPreferences.prefs(app).getBoolean("offline_mode", false)) {
                mutableState.value = ExtractorUpdateState(YoutubeDL.getInstance().versionName(app), false, last,
                    "Modo sin conexión activo. Desactívalo en Ajustes para comprobar actualizaciones.")
                return@withLock mutableState.value
            }
            if (!Providers.isInitialized()) Providers.init(app)
            Providers.ensureYoutubeReady()
            val oldVersion = YoutubeDL.getInstance().versionName(app)
            mutableState.value = ExtractorUpdateState(oldVersion, false, last)
            if (!force && now - last in 0 until 86_400_000L) return@withLock mutableState.value
            val lastFailure = prefs.getLong("last_ytdlp_update_failure", 0)
            if (!force && now - lastFailure in 0 until 1_800_000L) return@withLock mutableState.value.copy(message = "La última comprobación falló; puedes reintentar manualmente").also { mutableState.value = it }
            mutableState.value = mutableState.value.copy(checking = true, message = "Esperando extracciones activas…")
            try {
                runInterruptible {
                    val write = gate.writeLock()
                    write.lockInterruptibly()
                    try {
                        if (com.musicvault.app.settings.AppPreferences.prefs(app).getBoolean("offline_mode", false)) {
                            mutableState.value = mutableState.value.copy(checking = false, message = "Modo sin conexión activo. Desactívalo en Ajustes para comprobar actualizaciones.")
                            return@runInterruptible
                        }
                        val binary = File(app.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
                        val recovery = File(app.noBackupFilesDir, "youtubedl-android/yt-dlp/musicvault-recovery")
                        val wrapperPrefs = app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                        val previousVersion = wrapperPrefs.getString("dlpVersion", null)
                        val previousVersionName = wrapperPrefs.getString("dlpVersionName", null)
                        if (binary.isFile) binary.copyTo(recovery, overwrite = true)
                        try {
                            mutableState.value = mutableState.value.copy(message = "Comprobando yt-dlp estable…")
                            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                            val probe = YoutubeDLRequest("https://www.youtube.com").apply { addOption("--version") }
                            check(execute(probe).out.trim().isNotBlank()) { "Extractor update validation failed" }
                            prefs.edit().putLong("last_ytdlp_update_check", now).remove("last_ytdlp_update_failure").commit()
                            mutableState.value = ExtractorUpdateState(YoutubeDL.getInstance().versionName(app), false, now, "Extractor actualizado y listo")
                            recovery.delete()
                            LocalDiagnostics.record(app, "extractor_update", "ok")
                        } catch (failure: Exception) {
                            // Keep the actual previously working binary, even if the wrapper's update failed midway.
                            if (recovery.isFile) recovery.copyTo(binary, overwrite = true)
                            wrapperPrefs.edit().putString("dlpVersion", previousVersion).putString("dlpVersionName", previousVersionName).commit()
                            prefs.edit().putLong("last_ytdlp_update_failure", now).commit()
                            throw failure
                        }
                    } finally { write.unlock() }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(checking = false, message = "Comprobación cancelada; conserva el extractor anterior")
                throw cancelled
            } catch (failure: Exception) {
                val classified = ProviderErrors.classify(failure)
                mutableState.value = ExtractorUpdateState(oldVersion, false, last,
                    "No se pudo actualizar. Conserva el extractor anterior. ${classified.message}")
                LocalDiagnostics.record(app, "extractor_update", classified.kind.name)
            }
            mutableState.value
        }
    }
}
