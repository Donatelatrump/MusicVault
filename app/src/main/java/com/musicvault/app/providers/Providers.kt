package com.musicvault.app.providers

import android.content.Context
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Single integration point for the future/actual download provider.
 *
 * Callers only need: download(name, author, onProgress).
 * The implementation can later be replaced without changing the UI layer.
 */
object Providers {
    private const val UPDATE_PREFS = "musicvault_provider"
    private const val LAST_UPDATE_CHECK = "last_ytdlp_update_check"
    private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    private val activeProcesses = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = appContext != null

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            initialized = true
        }
    }

    /**
     * Updates the embedded yt-dlp binary at most once every 24 hours.
     * youtubedl-android bundles a copy, but its README explicitly supports
     * updating that binary through updateYoutubeDL().
     */
    private fun updateYtDlpIfNeeded(context: Context): String? {
        val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(LAST_UPDATE_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck < UPDATE_INTERVAL_MS) {
            return YoutubeDL.getInstance().versionName(context)
        }

        return try {
            YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.STABLE)
            prefs.edit().putLong(LAST_UPDATE_CHECK, now).apply()
            YoutubeDL.getInstance().versionName(context)
        } catch (e: Exception) {
            // Do not make the whole provider unusable just because GitHub's
            // update endpoint is temporarily unavailable. The bundled binary
            // remains available as a fallback.
            null
        }
    }

    suspend fun download(
        name: String,
        author: String,
        onProgress: (Int) -> Unit = {},
        requestId: String? = null
    ): ProviderResult = withContext(Dispatchers.IO) {
        val context = appContext
            ?: return@withContext ProviderResult.Error("El proveedor no ha sido inicializado")

        try {
            ensureInitialized(context)
            val ytDlpVersion = updateYtDlpIfNeeded(context)

            val outputDir = File(
                context.getExternalFilesDir("Music") ?: context.filesDir,
                "downloads"
            ).apply { mkdirs() }

            val before = outputDir.listFiles()
                ?.filter { it.isFile }
                ?.mapTo(mutableSetOf()) { it.absolutePath }
                ?: emptySet()

            val outputTemplate = File(outputDir, "%(id)s.%(ext)s").absolutePath
            val query = "ytsearch1:${name.trim()} ${author.trim()}".trim()
            val request = YoutubeDLRequest(query)

            request.addOption("--no-playlist")
            request.addOption("--no-part")
            request.addOption("-f", "bestaudio/best")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("--no-mtime")
            request.addOption("-o", outputTemplate)

            val processId = "musicvault-${requestId ?: System.currentTimeMillis()}"
            requestId?.let { activeProcesses[it] = processId }
            val response = try {
                YoutubeDL.getInstance().execute(
                    request,
                    processId,
                    false
                ) { progress, _, _ ->
                    onProgress(progress.coerceIn(0f, 100f).toInt())
                }
            } finally {
                requestId?.let { activeProcesses.remove(it) }
            }

            val downloadedFile = outputDir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS &&
                        it.absolutePath !in before
                }
                ?.maxByOrNull(File::lastModified)
                ?: outputDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
                    ?.maxByOrNull(File::lastModified)

            if (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) {
                val detail = response.err.trim().takeIf { it.isNotBlank() }
                    ?: response.out.trim().takeIf { it.isNotBlank() }
                    ?: "El proveedor terminó sin generar un archivo de audio"
                return@withContext ProviderResult.Error(
                    "No se pudo encontrar el audio descargado${ytDlpVersion?.let { " (yt-dlp $it)" } ?: ""}: $detail"
                )
            }

            // yt-dlp initially writes the media using the source ID. Rename it
            // to a human-readable song title before exposing it to the rest of
            // the app. The database stores the URI of this final file, so the
            // download remains detectable after restarting MusicVault.
            val extension = downloadedFile.extension.ifBlank { "mp3" }.lowercase(Locale.ROOT)
            val safeTitle = sanitizeFileName(name).ifBlank { "MusicVault Track" }
            val finalFile = uniqueFile(outputDir, safeTitle, extension)

            if (downloadedFile.absolutePath != finalFile.absolutePath) {
                if (!downloadedFile.renameTo(finalFile)) {
                    return@withContext ProviderResult.Error(
                        "El audio se descargó, pero no se pudo renombrar a \"${finalFile.name}\""
                    )
                }
            }

            onProgress(100)
            val audioUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                finalFile
            ).toString()

            ProviderResult.Success(audioUri, finalFile.name, "audio/mpeg")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val version = runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()
            val suffix = version?.let { " (yt-dlp $it)" }.orEmpty()
            ProviderResult.Error(
                "No se pudo descargar$suffix: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    fun cancel(requestId: String) {
        val processId = activeProcesses.remove(requestId) ?: return
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ' ')
        .take(120)

    private fun uniqueFile(directory: File, title: String, extension: String): File {
        var candidate = File(directory, "$title.$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$title ($index).$extension")
            index++
        }
        return candidate
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"
    )
}

sealed interface ProviderResult {
    data class Success(
        val audioUri: String,
        val fileName: String,
        val mimeType: String? = null
    ) : ProviderResult

    data class Unavailable(val reason: String) : ProviderResult
    data class Error(val message: String) : ProviderResult
}
