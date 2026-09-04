package com.musicvault.app.providers

import android.content.Context
import androidx.core.content.FileProvider
import com.musicvault.app.catalog.CatalogTrack
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    // User-selected download destination (SAF tree), shared with the settings UI.
    const val SETTINGS_PREFS = "musicvault.settings"
    const val DOWNLOAD_TREE_PREF = "download_tree_uri"

    private fun prefs(context: Context) =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private fun mimeFor(extension: String): String = when (extension) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    private val activeProcesses = ConcurrentHashMap<String, String>()
    // Three concurrent yt-dlp/FFmpeg jobs improve album download throughput
    // while keeping CPU/RAM usage reasonable on typical Android devices.
    private val downloadSlots = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(1, 3))
    private val updateLock = Any()
    private val updateStarted = AtomicBoolean(false)
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // Initialization and yt-dlp maintenance stay off the UI thread.
        // Both are also kept outside the critical path of individual downloads.
        if (updateStarted.compareAndSet(false, true)) {
            providerScope.launch {
                runCatching { ensureInitialized(app) }
                runCatching { synchronized(updateLock) { updateYtDlpIfNeeded(app) } }
            }
        }
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
        requestId: String? = null,
        sourceUrl: String? = null
    ): ProviderResult = downloadSlots.withPermit {
        withContext(Dispatchers.IO) {
            val context = appContext
                ?: return@withContext ProviderResult.Error("El proveedor no ha sido inicializado")

            try {
                ensureInitialized(context)
                val ytDlpVersion = runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()

                val externalMusic = context.getExternalFilesDir("Music")
                val rootDir = when {
                    externalMusic != null -> File(externalMusic, "downloads")
                    // Fallback al almacenamiento interno de la app. Garantiza que
                    // las descargas nunca fallen por una SD card ausente o
                    // desmontada (caso común en API ≤ 28 con SD como external).
                    else -> File(context.filesDir, "downloads")
                }.apply { mkdirs() }

                // Every request gets its own staging directory. This removes the
                // race where two simultaneous downloads scan the same directory
                // and one can accidentally claim the other one's output file.
                val requestKey = requestId?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    ?: System.nanoTime().toString()
                val stagingDir = File(rootDir, ".pending/$requestKey").apply { mkdirs() }
                val outputTemplate = File(stagingDir, "%(id)s.%(ext)s").absolutePath
                val directUrl = sourceUrl?.trim()?.takeIf { it.isNotBlank() }
                val query = directUrl ?: "ytsearch1:${name.trim()} ${author.trim()}".trim()
                val request = YoutubeDLRequest(query)

                request.addOption("--no-playlist")
                request.addOption("--no-part")
                // Keep the provider's native audio container instead of forcing
                // an FFmpeg MP3 transcode. This removes the long post-download
                // CPU phase and preserves the source audio without an extra
                // lossy conversion. Media3 can play the common m4a/opus/webm
                // audio containers directly.
                request.addOption("-f", "bestaudio/best")
                request.addOption("--no-mtime")
                request.addOption("-N", "8")
                request.addOption("-o", outputTemplate)

                val processId = "musicvault-$requestKey"
                requestId?.let { activeProcesses[it] = processId }
                val response = try {
                    YoutubeDL.getInstance().execute(
                        request,
                        processId,
                        false
                    ) { progress, _, _ ->
                        // yt-dlp can report 100 before FFmpeg/rename/FileProvider
                        // finalization has finished. Reserve 100 for the actual
                        // saved-file state so the UI never lies to the user.
                        onProgress(progress.coerceIn(0f, 99f).toInt())
                    }
                } finally {
                    requestId?.let { activeProcesses.remove(it) }
                }

                val downloadedFile = stagingDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
                    ?.maxByOrNull(File::lastModified)

                if (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) {
                    stagingDir.deleteRecursively()
                    val detail = response.err.trim().takeIf { it.isNotBlank() }
                        ?: response.out.trim().takeIf { it.isNotBlank() }
                        ?: "El proveedor terminó sin generar un archivo de audio"
                    return@withContext ProviderResult.Error(
                        "No se pudo encontrar el audio descargado${ytDlpVersion?.let { " (yt-dlp $it)" } ?: ""}: $detail"
                    )
                }

                // Finalization happens off the UI thread and is deliberately
                // excluded from the progress 0..99 range.
                val extension = downloadedFile.extension.ifBlank { "mp3" }.lowercase(Locale.ROOT)
                val safeTitle = sanitizeFileName(name).ifBlank { "MusicVault Track" }
                val finalFile = uniqueFile(rootDir, safeTitle, extension)

                try {
                    if (!downloadedFile.renameTo(finalFile)) {
                        downloadedFile.copyTo(finalFile, overwrite = false)
                        if (!downloadedFile.delete()) {
                            stagingDir.deleteRecursively()
                            return@withContext ProviderResult.Error(
                                "El audio se descargó, pero no se pudo finalizar el archivo \"${finalFile.name}\""
                            )
                        }
                    }
                } finally {
                    releaseFileReservation(finalFile)
                }
                stagingDir.deleteRecursively()

                val mime = mimeFor(extension)

                // Optional user-selected destination (SAF tree). When set, the
                // finished file is copied into the chosen folder and its
                // content URI is returned; otherwise the app-private folder
                // is used as before.
                val treeUri = prefs(context).getString(DOWNLOAD_TREE_PREF, null)
                    ?.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
                if (treeUri != null) {
                    val safResult = runCatching {
                        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                            ?: throw IllegalStateException("La URI persistida no es válida en este dispositivo")
                        val doc = tree.createFile(mime, finalFile.nameWithoutExtension)
                            ?: throw IllegalStateException("No se pudo crear el archivo en la carpeta seleccionada")
                        val out = context.contentResolver.openOutputStream(doc.uri)
                            ?: throw IllegalStateException("No se pudo abrir el archivo en la carpeta seleccionada")
                        out.use { finalFile.inputStream().use { input -> input.copyTo(it) } }
                        doc
                    }
                    safResult.fold(
                        onSuccess = { document ->
                            finalFile.delete()
                            onProgress(100)
                            return@withContext ProviderResult.Success(
                                document.uri.toString(),
                                document.name ?: finalFile.name,
                                mime,
                                document.uri.toString()
                            )
                        },
                        onFailure = {
                            prefs(context).edit().remove(DOWNLOAD_TREE_PREF).apply()
                            return@withContext ProviderResult.Error(
                                "La carpeta de descargas seleccionada ya no es válida. " +
                                    "Se restauró la carpeta interna de la app. Vuelve a elegir una carpeta."
                            )
                        }
                    )
                }

                val audioUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    finalFile
                ).toString()

                onProgress(100)
                ProviderResult.Success(audioUri, finalFile.name, mime, finalFile.absolutePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val version = runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()
                val suffix = version?.let { " (yt-dlp $it)" }.orEmpty()
                val detail = e.message?.trim().takeIf { !it.isNullOrBlank() }
                    ?: e.javaClass.simpleName
                unavailableReason(detail)?.let { return@withContext ProviderResult.Unavailable(it) }
                ProviderResult.Error(
                    "No se pudo descargar$suffix. ${detail.take(700)}"
                )
            }
        }
    }

    suspend fun download(
        track: CatalogTrack,
        onProgress: (Int) -> Unit = {},
        requestId: String? = null
    ): ProviderResult = download(
        name = track.title,
        author = track.artistName,
        onProgress = onProgress,
        requestId = requestId,
        sourceUrl = track.url ?: track.sourceId?.let { "https://www.youtube.com/watch?v=$it" }
    )

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

    private val fileReservationLock = Any()
    private val reservedFiles = mutableSetOf<String>()

    private fun uniqueFile(directory: File, title: String, extension: String): File = synchronized(fileReservationLock) {
        var candidate = File(directory, "$title.$extension")
        var index = 2
        while (candidate.exists() || candidate.absolutePath in reservedFiles) {
            candidate = File(directory, "$title ($index).$extension")
            index++
        }
        reservedFiles.add(candidate.absolutePath)
        candidate
    }

    private fun releaseFileReservation(file: File) {
        synchronized(fileReservationLock) { reservedFiles.remove(file.absolutePath) }
    }

    private fun unavailableReason(message: String?): String? {
        val text = message?.lowercase().orEmpty()
        return when {
            text.contains("video unavailable") -> "El video ya no está disponible."
            text.contains("this video is unavailable") -> "El video ya no está disponible."
            text.contains("removed by the uploader") -> "El video fue eliminado."
            text.contains("private video") -> "El video es privado."
            text.contains("not available") -> "El video ya no está disponible."
            else -> null
        }
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "webm", "wav", "flac"
    )
}

sealed interface ProviderResult {
    data class Success(
        val audioUri: String,
        val fileName: String,
        val mimeType: String? = null,
        val localPath: String? = null
    ) : ProviderResult

    data class Unavailable(val reason: String) : ProviderResult
    data class Error(val message: String) : ProviderResult
}
