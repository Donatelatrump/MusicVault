package com.musicvault.app.providers

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.FileProvider
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.player.StreamingCache
import com.musicvault.app.download.DownloadQuality
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import android.os.SystemClock

/**
 * Single integration point for the future/actual download provider.
 *
 * Callers only need: download(name, author, onProgress).
 * The implementation can later be replaced without changing the UI layer.
 */
data class StreamResolution(
    val url: String,
    val extension: String,
    val mimeType: String
)

// CacheWriter is an intentionally pinned Media3 integration (see the pinned
// Media3 version in app/build.gradle.kts). Keep the unstable-API acceptance
// local to this boundary instead of forcing every Providers caller to opt in.
@SuppressLint("UnsafeOptInUsageError")
object Providers {
    // User-selected download destination (SAF tree), shared with the settings UI.
    const val SETTINGS_PREFS = "musicvault.settings"
    const val DOWNLOAD_TREE_PREF = "download_tree_uri"

    private fun prefs(context: Context) =
        com.musicvault.app.settings.AppPreferences.prefs(context)

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
    fun isOffline(): Boolean = appContext?.let { prefs(it).getBoolean("offline_mode", false) } ?: false

    private val activeProcesses = ConcurrentHashMap<String, String>()
    private val activeCacheWriters = ConcurrentHashMap<String, CacheWriter>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val mutableFailure = MutableStateFlow<ProviderFailure?>(null)
    val lastFailure = mutableFailure.asStateFlow()

    // Signed googlevideo URLs are expensive to resolve but normally remain valid
    // for much longer than a few minutes. Keep only a short-lived in-memory copy:
    // it makes repeat taps / queue preparation instant without ever persisting
    // ephemeral URLs in Room. A failed/expired URL can simply be invalidated.
    private data class CachedStream(val value: StreamResolution, val expiresAtMs: Long)
    private data class CachedYoutubeMatch(val url: String, val expiresAtMs: Long)
    private const val STREAM_URL_TTL_MS = 10L * 60L * 1000L
    private const val YOUTUBE_MATCH_TTL_MS = 60L * 60L * 1000L
    private val resolvedStreams = ConcurrentHashMap<String, CachedStream>()
    private val youtubeMatches = ConcurrentHashMap<String, CachedYoutubeMatch>()
    // One resolver per logical track. Search/Home warmup and a user tap join the
    // same extraction instead of starting two expensive extractor processes.
    private val streamResolutionLocks = ConcurrentHashMap<String, Mutex>()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // NewPipe init is local/cheap (no network); make the primary resolver
        // ready before the first tap. Network extraction remains off-main-thread.
        runCatching { NewPipeStreamResolver.init() }
    }

    fun isInitialized(): Boolean = appContext != null

    /**
     * Resolve an ephemeral direct audio URL without downloading the media.
     * The returned URL is intentionally kept out of Room; callers resolve it
     * again when needed while Media3 reuses bytes through a stable cache key.
     */
    suspend fun resolveStream(
        name: String,
        author: String,
        sourceUrl: String? = null,
        cacheKey: String? = null,
        forceRefresh: Boolean = false
    ): StreamResolution? = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext null
        if (prefs(context).getBoolean("offline_mode", false)) {
            mutableFailure.value = ProviderFailure(ProviderErrorKind.OFFLINE,
                "Modo sin conexión activo. Desactívalo en Ajustes para buscar audio en línea.", ProviderAction.RECONNECT, false)
            return@withContext null
        }
        val key = streamResolutionKey(cacheKey, sourceUrl, name, author)
        val lock = streamResolutionLocks.getOrPut(key) { Mutex() }

        lock.withLock {
            val now = SystemClock.elapsedRealtime()
            if (!forceRefresh) {
                resolvedStreams[key]?.takeIf { it.expiresAtMs > now }?.let { cached ->
                    android.util.Log.d("MusicVaultStreamPerf", "stream-cache-hit key=$key")
                    return@withLock cached.value
                }
            } else {
                resolvedStreams.remove(key)
            }

            val totalStarted = SystemClock.elapsedRealtime()
            try {
                // Primary path: NewPipeExtractor. It avoids the Python + QuickJS
                // startup measured at ~16-20 s on the test phone.
                var direct = youtubeUrl(sourceUrl)
                if (direct == null) {
                    youtubeMatches[key]?.takeIf { it.expiresAtMs > now }?.let { cached ->
                        direct = cached.url
                        android.util.Log.d("MusicVaultStreamPerf", "youtube-match cache key=$key")
                    }
                }

                if (direct == null) {
                    val matchStarted = SystemClock.elapsedRealtime()
                    direct = try {
                        NewPipeStreamResolver.findYoutubeMatch(name, author)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        android.util.Log.d(
                            "MusicVaultStreamPerf",
                            "newpipe-match failed ${SystemClock.elapsedRealtime() - matchStarted}ms key=$key: ${error.message}"
                        )
                        null
                    }
                    if (direct != null) {
                        youtubeMatches[key] = CachedYoutubeMatch(
                            direct!!,
                            SystemClock.elapsedRealtime() + YOUTUBE_MATCH_TTL_MS
                        )
                    }
                }

                if (direct != null) {
                    val newPipeStarted = SystemClock.elapsedRealtime()
                    val newPipe = try {
                        NewPipeStreamResolver.resolveAudio(direct!!, cacheKey)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        android.util.Log.d(
                            "MusicVaultStreamPerf",
                            "newpipe-stream failed ${SystemClock.elapsedRealtime() - newPipeStarted}ms key=$key: ${error.message}"
                        )
                        null
                    }
                    if (newPipe != null) {
                        resolvedStreams[key] = CachedStream(
                            value = newPipe,
                            expiresAtMs = SystemClock.elapsedRealtime() + STREAM_URL_TTL_MS
                        )
                        android.util.Log.d(
                            "MusicVaultStreamPerf",
                            "resolver=newpipe total=${SystemClock.elapsedRealtime() - totalStarted}ms key=$key"
                        )
                        mutableFailure.value = null
                        return@withLock newPipe
                    }
                }

                mutableFailure.value = ProviderErrors.classify(IllegalStateException("YouTube extractor returned no audio"))
                android.util.Log.w("MusicVaultStreamPerf", "newpipe returned no audio key=$key")
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                mutableFailure.value = ProviderErrors.classify(error)
                com.musicvault.app.diagnostics.LocalDiagnostics.record(context, "stream", mutableFailure.value!!.kind.name)
                android.util.Log.w("MusicVaultStreamPerf", "resolveStream failed key=$key", error)
                null
            }
        }
    }

    /** Warm only the Deezer->YouTube match. Useful for lower-priority visible rows. */
    suspend fun prewarmYoutubeMatch(
        name: String,
        author: String,
        sourceUrl: String? = null,
        cacheKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (youtubeUrl(sourceUrl) != null) return@withContext true
        val context = appContext ?: return@withContext false
        if (prefs(context).getBoolean("offline_mode", false)) return@withContext false
        val key = streamResolutionKey(cacheKey, sourceUrl, name, author)
        val now = SystemClock.elapsedRealtime()
        youtubeMatches[key]?.takeIf { it.expiresAtMs > now }?.let { return@withContext true }
        val lock = streamResolutionLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            youtubeMatches[key]?.takeIf { it.expiresAtMs > SystemClock.elapsedRealtime() }?.let {
                return@withLock true
            }
            return@withLock try {
                val started = SystemClock.elapsedRealtime()
                val url = NewPipeStreamResolver.findYoutubeMatch(name, author)
                android.util.Log.d("MusicVaultStreamPerf", "prewarm-newpipe-match ${SystemClock.elapsedRealtime() - started}ms key=$key")
                if (url != null) {
                    youtubeMatches[key] = CachedYoutubeMatch(url, SystemClock.elapsedRealtime() + YOUTUBE_MATCH_TTL_MS)
                    true
                } else false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                android.util.Log.d("MusicVaultStreamPerf", "prewarm-newpipe-match failed key=$key: ${error.message}")
                false
            }
        }
    }

    fun invalidateStream(cacheKey: String) {
        val prefix = "id:${cacheKey.trim()}"
        resolvedStreams.remove(prefix)
    }

    private fun streamResolutionKey(cacheKey: String?, sourceUrl: String?, name: String, author: String): String =
        cacheKey?.trim()?.takeIf { it.isNotBlank() }?.let { "id:$it" }
            ?: youtubeUrl(sourceUrl)?.let { "url:$it" }
            ?: "search:${name.trim().lowercase(Locale.ROOT)}|${author.trim().lowercase(Locale.ROOT)}"

    private fun youtubeUrl(value: String?): String? = value?.trim()?.takeIf {
        it.startsWith("https://www.youtube.com/", true) ||
            it.startsWith("https://youtube.com/", true) ||
            it.startsWith("https://youtu.be/", true)
    }


    suspend fun download(
        name: String,
        author: String,
        onProgress: (Int) -> Unit = {},
        requestId: String? = null,
        sourceUrl: String? = null,
        quality: DownloadQuality = DownloadQuality.BEST,
        cacheKey: String? = requestId,
        albumTitle: String? = null,
        coverUri: String? = null
    ): ProviderResult = {
        withContext(Dispatchers.IO) {
            val context = appContext
                ?: return@withContext ProviderResult.Error("El proveedor no ha sido inicializado")

            try {
                currentCoroutineContext().ensureActive()
                requestId?.let { cancelledRequests.remove(it) }

                // Reuse the streaming buffer before starting a traditional
                // yt-dlp file download. A complete cache is promoted directly.
                // If it is partial, CacheWriter fills only the missing ranges,
                // then the same cached bytes are materialized as the offline file.
                if (quality.reuseStreamingCache) requestId?.let { taskId ->
                    val cachedId = cacheKey ?: taskId
                    promoteCompleteStreamCache(context, cachedId, AudioTags(name, author, albumTitle, coverUri), requestId, onProgress)?.let {
                        return@withContext it
                    }
                    if (StreamingCache.cachedBytes(context, cachedId) > 0L) {
                        val stream = resolveStream(
                            name = name,
                            author = author,
                            sourceUrl = sourceUrl,
                            cacheKey = cachedId
                        )
                        if (cancelledRequests.remove(taskId)) {
                            throw CancellationException("Descarga cancelada")
                        }
                        if (stream != null) {
                            StreamingCache.rememberFormat(
                                context,
                                cachedId,
                                stream.extension,
                                stream.mimeType
                            )
                            val writer = StreamingCache.writer(
                                context = context,
                                trackId = cachedId,
                                streamUrl = stream.url,
                                progressListener = CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                                    if (requestLength > 0L) {
                                        val pct = ((bytesCached * 94L) / requestLength)
                                            .coerceIn(1L, 94L)
                                            .toInt()
                                        onProgress(pct)
                                    }
                                }
                            )
                            activeCacheWriters[taskId] = writer
                            try {
                                writer.cache()
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                currentCoroutineContext().ensureActive()
                                // A CDN URL can occasionally expire while filling
                                // the cache. Unless the user cancelled, fall back
                                // to the existing yt-dlp downloader below.
                            } finally {
                                activeCacheWriters.remove(taskId, writer)
                            }
                            if (cancelledRequests.remove(taskId)) {
                                throw CancellationException("Descarga cancelada")
                            }
                            promoteCompleteStreamCache(context, cachedId, AudioTags(name, author, albumTitle, coverUri), requestId, onProgress)?.let {
                                return@withContext it
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (prefs(context).getBoolean("offline_mode", false)) return@withContext ProviderResult.Error(
                    "Modo sin conexión activo. Tu descarga sigue pendiente hasta desactivarlo.",
                    ProviderFailure(ProviderErrorKind.OFFLINE, "Modo sin conexión activo", ProviderAction.RECONNECT, false))

                val stream = resolveStream(
                    name = name,
                    author = author,
                    sourceUrl = sourceUrl,
                    cacheKey = cacheKey
                )
                if (cancelledRequests.remove(requestId ?: "")) {
                    throw CancellationException("Descarga cancelada")
                }

                val externalMusic = context.getExternalFilesDir("Music")
                val rootDir = when {
                    externalMusic != null -> File(externalMusic, "downloads")
                    else -> File(context.filesDir, "downloads")
                }.apply { mkdirs() }

                val requestKey = requestId?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    ?: System.nanoTime().toString()
                val stagingDir = File(rootDir, ".pending/$requestKey").apply { mkdirs() }

                if (requestId != null && cancelledRequests.remove(requestId)) {
                    stagingDir.deleteRecursively()
                    throw CancellationException("Descarga cancelada")
                }

                val streamUrl = stream ?: run {
                    stagingDir.deleteRecursively()
                    return@withContext ProviderResult.Error(
                        "No se pudo obtener el stream de audio de YouTube",
                        ProviderFailure(ProviderErrorKind.UNAVAILABLE, "NewPipe extractor returned no audio", ProviderAction.RETRY, false))
                }

                val tmpFile = File(stagingDir, "audio.${stream.extension}")
                val http = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build()
                val request = okhttp3.Request.Builder().url(streamUrl).build()
                val call = http.newCall(request)
                activeProcesses[requestId ?: "no-id"] = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            stagingDir.deleteRecursively()
                            val detail = "HTTP ${response.code} ${response.message}"
                            return@withContext ProviderResult.Error(detail, ProviderErrors.classify(IOException(detail)))
                        }
                        val body = response.body ?: run {
                            stagingDir.deleteRecursively()
                            return@withContext ProviderResult.Error(
                                "Sin cuerpo de respuesta del stream",
                                ProviderFailure(ProviderErrorKind.UNAVAILABLE, "Empty stream body", ProviderAction.RETRY, false))
                        }
                        val total = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                        var bytesRead = 0L
                        body.byteStream().use { input ->
                            tmpFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while ({ read = input.read(buffer); read >= 0 }()) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    output.write(buffer, 0, read)
                                    bytesRead += read
                                    if (total > 0L) {
                                        val pct = ((bytesRead * 94L) / total).coerceIn(1L, 94L).toInt()
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    requestId?.let { activeProcesses.remove(it) }
                }

                if (tmpFile.length() == 0L) {
                    stagingDir.deleteRecursively()
                    return@withContext ProviderResult.Error(
                        "El archivo descargado está vacío",
                        ProviderFailure(ProviderErrorKind.UNAVAILABLE, "Empty download", ProviderAction.RETRY, false))
                }

                val tagged = AudioTagger.embed(context, tmpFile, AudioTags(name, author, albumTitle, coverUri), requestId)
                if (requestId != null && cancelledRequests.remove(requestId)) {
                    stagingDir.deleteRecursively()
                    throw CancellationException("Descarga cancelada")
                }

                // Finalization happens off the UI thread and is deliberately
                // excluded from the progress 0..99 range.
                val extension = tagged.extension.ifBlank { "mp3" }.lowercase(Locale.ROOT)
                val safeTitle = sanitizeFileName(name).ifBlank { "MusicVault Track" }
                val finalFile = uniqueFile(rootDir, safeTitle, extension)

                try {
                    if (!tagged.renameTo(finalFile)) {
                        tagged.copyTo(finalFile, overwrite = false)
                        if (!tagged.delete()) {
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
                        try {
                            val out = context.contentResolver.openOutputStream(doc.uri)
                                ?: throw IllegalStateException("No se pudo abrir el archivo en la carpeta seleccionada")
                            out.use { finalFile.inputStream().use { input -> input.copyTo(it) } }
                            doc
                        } catch (failure: Throwable) { doc.delete(); throw failure }
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
                            com.musicvault.app.diagnostics.LocalDiagnostics.record(context, "download_destination", "private_folder_fallback")
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
                currentCoroutineContext().ensureActive()
                if (requestId != null && cancelledRequests.remove(requestId)) {
                    throw CancellationException("Descarga cancelada")
                }
                val failure = ProviderErrors.classify(e)
                mutableFailure.value = failure
                com.musicvault.app.diagnostics.LocalDiagnostics.record(context, "download", failure.kind.name)
                if (failure.kind == ProviderErrorKind.UNAVAILABLE) ProviderResult.Unavailable(failure.message)
                else ProviderResult.Error(failure.message, failure)
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
        sourceUrl = track.url ?: track.sourceId?.let { "https://www.youtube.com/watch?v=$it" },
        albumTitle = track.albumTitle, coverUri = track.coverUri
    )

    private suspend fun promoteCompleteStreamCache(
        context: Context, trackId: String, tags: AudioTags, requestId: String?, onProgress: (Int) -> Unit
    ): ProviderResult.Success? {
        if (!StreamingCache.isComplete(context, trackId)) return null
        val format = StreamingCache.cachedFormat(context, trackId)
        val rootDir = (context.getExternalFilesDir("Music")?.let { File(it, "downloads") }
            ?: File(context.filesDir, "downloads")).apply { mkdirs() }
        val stage = File(rootDir, ".pending/cache-${System.nanoTime()}").apply { mkdirs() }
        val raw = File(stage, "audio.${format.extension}")
        var finalFile: File? = null
        try {
            onProgress(20)
            if (!StreamingCache.exportComplete(context, trackId, raw)) return null
            currentCoroutineContext().ensureActive()
            val tagged = AudioTagger.embed(context, raw, tags, requestId)
            if (requestId != null && cancelledRequests.remove(requestId)) throw CancellationException("Descarga cancelada")
            val destination = uniqueFile(rootDir, sanitizeFileName(tags.title).ifBlank { "MusicVault Track" }, tagged.extension)
            finalFile = destination
            if (!tagged.renameTo(destination)) tagged.copyTo(destination)
            val mime = mimeFor(destination.extension)
            val treeUri = prefs(context).getString(DOWNLOAD_TREE_PREF, null)?.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
            if (treeUri != null) {
                val tree = checkNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri))
                val doc = checkNotNull(tree.createFile(mime, destination.nameWithoutExtension))
                try {
                    checkNotNull(context.contentResolver.openOutputStream(doc.uri)).use { out -> destination.inputStream().use { it.copyTo(out) } }
                } catch (failure: Throwable) { doc.delete(); throw failure }
                destination.delete()
                onProgress(100)
                return ProviderResult.Success(doc.uri.toString(), doc.name ?: destination.name, mime, doc.uri.toString())
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination).toString()
            onProgress(100)
            return ProviderResult.Success(uri, destination.name, mime, destination.absolutePath)
        } catch (cancelled: CancellationException) { finalFile?.delete(); throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            finalFile?.delete()
            return null
        } finally {
            finalFile?.let(::releaseFileReservation)
            stage.deleteRecursively()
        }
    }

    fun cancel(requestId: String) {
        cancelledRequests.add(requestId)
        AudioTagger.cancel(requestId)
        activeCacheWriters.remove(requestId)?.let { runCatching { it.cancel() } }
        activeProcesses.remove(requestId)?.let { call ->
            runCatching { call.cancel() }
        }
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
    data class Error(val message: String, val failure: ProviderFailure? = null) : ProviderResult
}
