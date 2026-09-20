package com.musicvault.app.providers

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.FileProvider
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.player.StreamingCache
import androidx.media3.datasource.cache.CacheWriter
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private var youtubeInitialized = false

    @Volatile
    private var ffmpegInitialized = false

    private val activeProcesses = ConcurrentHashMap<String, String>()
    private val activeCacheWriters = ConcurrentHashMap<String, CacheWriter>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    // Three concurrent yt-dlp/FFmpeg jobs improve album download throughput
    // while keeping CPU/RAM usage reasonable on typical Android devices.
    private val downloadSlots = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(1, 3))
    private val updateLock = Any()
    private val updateStarted = AtomicBoolean(false)
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Signed googlevideo URLs are expensive to resolve but normally remain valid
    // for much longer than a few minutes. Keep only a short-lived in-memory copy:
    // it makes repeat taps / queue preparation instant without ever persisting
    // ephemeral URLs in Room. A failed/expired URL can simply be invalidated.
    private data class CachedStream(val value: StreamResolution, val expiresAtMs: Long)
    private data class CachedYoutubeMatch(val url: String, val expiresAtMs: Long)
    private const val STREAM_URL_TTL_MS = 10L * 60L * 1000L
    private const val YOUTUBE_MATCH_TTL_MS = 60L * 60L * 1000L
    // Conservative fast path: skip only HLS/DASH manifest expansion.
    // Skipping webpage/config/player requests can remove the formats needed for
    // playback on some YouTube responses, so those aggressive flags are avoided.
    private const val STREAM_FAST_EXTRACTOR_ARGS = "youtube:skip=hls,dash"
    private val resolvedStreams = ConcurrentHashMap<String, CachedStream>()
    private val youtubeMatches = ConcurrentHashMap<String, CachedYoutubeMatch>()
    // One resolver per logical track. Search/Home warmup and a user tap join the
    // same extraction instead of starting two expensive yt-dlp processes.
    private val streamResolutionLocks = ConcurrentHashMap<String, Mutex>()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // NewPipe init is local/cheap (no network); make the primary resolver
        // ready before the first tap. Network extraction remains off-main-thread.
        runCatching { NewPipeStreamResolver.init() }
        // Initialization and yt-dlp maintenance stay off the UI thread.
        // Both are also kept outside the critical path of individual downloads.
        if (updateStarted.compareAndSet(false, true)) {
            providerScope.launch {
                // Streaming/search only need yt-dlp. FFmpeg is intentionally not
                // initialized here: its native startup cost must never sit on the
                // first-play critical path.
                runCatching { ensureYoutubeInitialized(app) }
                runCatching { synchronized(updateLock) { updateYtDlpIfNeeded(app) } }
            }
        }
    }

    fun isInitialized(): Boolean = appContext != null

    /**
     * Makes the embedded yt-dlp runtime ready for metadata-only operations.
     * Providers.init() intentionally warms it in the background so app startup
     * stays fast, but Search can be opened before that coroutine has finished.
     * In that race, calling YoutubeDL.execute() directly looks like a legitimate
     * empty result. Search uses this gate before invoking the extractor.
     */
    suspend fun ensureYoutubeReady(): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        try {
            ensureYoutubeInitialized(context)
            true
        } catch (_: Exception) {
            false
        }
    }

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
                // startup measured at ~16-20 s on the test phone. A stable
                // Deezer->YouTube match is cached separately from the signed
                // googlevideo URL.
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
                        // runInterruptible/OkHttp may surface cancellation as an
                        // IOException("interrupted"). Never reinterpret a canceled
                        // speculative warmup as a real extractor failure and fall
                        // through to the 15-20 s yt-dlp compatibility path.
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
                        NewPipeStreamResolver.resolveAudio(direct!!)
                    } catch (error: Exception) {
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
                        return@withLock newPipe
                    }
                }

                // Compatibility path: keep the battle-tested yt-dlp resolver.
                // It is intentionally initialized only when NewPipe cannot give
                // us a playable direct audio stream.
                android.util.Log.d("MusicVaultStreamPerf", "resolver=newpipe-fallback-ytdlp key=$key")
                ensureYoutubeInitialized(context)
                val query = direct ?: "ytsearch1:${name.trim()} ${author.trim()}".trim()
                val streamStarted = SystemClock.elapsedRealtime()
                val fast = try {
                    executeStreamRequest(query, fast = true)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    android.util.Log.d("MusicVaultStreamPerf", "fast resolver failed key=$key: ${error.message}")
                    null
                }
                val robust = fast ?: try {
                    executeStreamRequest(query, fast = false)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    android.util.Log.d("MusicVaultStreamPerf", "robust resolver failed key=$key: ${error.message}")
                    null
                }
                val resolved = robust ?: try {
                    executeStreamUrlOnlyRequest(query)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    android.util.Log.w("MusicVaultStreamPerf", "url-only resolver failed key=$key", error)
                    null
                }
                android.util.Log.d(
                    "MusicVaultStreamPerf",
                    "ytdlp-stream-url ${SystemClock.elapsedRealtime() - streamStarted}ms key=$key"
                )
                if (resolved != null) {
                    resolvedStreams[key] = CachedStream(
                        value = resolved,
                        expiresAtMs = SystemClock.elapsedRealtime() + STREAM_URL_TTL_MS
                    )
                    android.util.Log.d(
                        "MusicVaultStreamPerf",
                        "resolver=ytdlp-${if (fast != null) "fast" else if (robust != null) "fallback" else "url-only"} total=${SystemClock.elapsedRealtime() - totalStarted}ms key=$key"
                    )
                }
                resolved
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.w("MusicVaultStreamPerf", "resolveStream failed key=$key", error)
                null
            }
        }
    }

    /**
     * Cheap first stage for non-YouTube metadata. It only asks yt-dlp for the
     * best matching video id/URL; audio format extraction is deliberately left
     * for [resolveStream]. The result is stable enough to cache much longer than
     * a signed googlevideo URL.
     */
    private fun executeYoutubeMatchRequest(name: String, author: String): String? {
        val search = listOf(name.trim(), author.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (search.isBlank()) return null
        val request = YoutubeDLRequest("ytsearch1:$search").apply {
            addOption("--flat-playlist")
            addOption("--skip-download")
            addOption("--ignore-errors")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--extractor-retries", "1")
            addOption("--retries", "1")
            addOption("--socket-timeout", "6")
            addOption("--print", "%(id)s\t%(webpage_url)s")
        }
        val processId = "musicvault-match-${System.nanoTime()}"
        val response = YoutubeDL.getInstance().execute(request, processId, false)
        val line = response.out.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val fields = line.split('\t', limit = 2)
        val id = fields.getOrNull(0)?.trim().orEmpty()
        val url = fields.getOrNull(1)?.trim().orEmpty()
        return when {
            youtubeUrl(url) != null -> url
            id.matches(Regex("[A-Za-z0-9_-]{6,}")) -> "https://www.youtube.com/watch?v=$id"
            else -> null
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
                currentCoroutineContext().ensureActive()
                android.util.Log.d("MusicVaultStreamPerf", "prewarm-newpipe-match failed key=$key: ${error.message}")
                false
            }
        }
    }

    private fun executeStreamRequest(query: String, fast: Boolean): StreamResolution? {
        val request = YoutubeDLRequest(query).apply {
            // youtubedl-android 0.18.1 injects its bundled QuickJS runtime
            // with the native absolute path before executing this request.
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
            addOption("--extractor-retries", "2")
            addOption("--retries", "2")
            addOption("--socket-timeout", "8")
            addOption("-f", "bestaudio/best")
            if (fast) addOption("--extractor-args", STREAM_FAST_EXTRACTOR_ARGS)
            // One parseable line gives us both the native container and the signed
            // media URL selected by yt-dlp.
            addOption("--print", "%(ext)s\t%(url)s")
        }
        val processId = "musicvault-stream-${System.nanoTime()}"
        val response = YoutubeDL.getInstance().execute(request, processId, false)
        return parseStreamResponse(response.out)
    }

    /** Last-resort resolver using yt-dlp's direct get-url output. */
    private fun executeStreamUrlOnlyRequest(query: String): StreamResolution? {
        val request = YoutubeDLRequest(query).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
            addOption("--extractor-retries", "2")
            addOption("--retries", "2")
            addOption("--socket-timeout", "8")
            addOption("-f", "bestaudio/best")
            addOption("--get-url")
        }
        val processId = "musicvault-stream-url-${System.nanoTime()}"
        val response = YoutubeDL.getInstance().execute(request, processId, false)
        val url = response.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        return StreamResolution(url, "m4a", "audio/mp4")
    }

    private fun parseStreamResponse(raw: String): StreamResolution? {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        // Do not assume the media record is the last line: yt-dlp/native bridge
        // can append diagnostics. Pick the last line that actually contains an URL.
        val line = lines.lastOrNull { line ->
            line.substringAfter('\t', line).trim().let { url ->
                url.startsWith("http://") || url.startsWith("https://")
            }
        } ?: return null
        val tab = line.indexOf('\t')
        val ext: String
        val url: String
        if (tab > 0) {
            ext = line.substring(0, tab).trim().ifBlank { "m4a" }
            url = line.substring(tab + 1).trim()
        } else {
            ext = "m4a"
            url = line.trim()
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return StreamResolution(url, ext, mimeFor(ext.lowercase(Locale.ROOT)))
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

    private fun ensureYoutubeInitialized(context: Context) {
        if (youtubeInitialized) return
        synchronized(this) {
            if (youtubeInitialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            youtubeInitialized = true
        }
    }

    /** Kept separate so FFmpeg native startup never slows Search or Streaming. */
    private fun ensureFfmpegInitialized(context: Context) {
        if (ffmpegInitialized) return
        synchronized(this) {
            if (ffmpegInitialized) return
            FFmpeg.getInstance().init(context.applicationContext)
            ffmpegInitialized = true
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
                requestId?.let { cancelledRequests.remove(it) }

                // Reuse the streaming buffer before starting a traditional
                // yt-dlp file download. A complete cache is promoted directly.
                // If it is partial, CacheWriter fills only the missing ranges,
                // then the same cached bytes are materialized as the offline file.
                requestId?.let { cachedId ->
                    promoteCompleteStreamCache(context, cachedId, name, onProgress)?.let {
                        return@withContext it
                    }
                    if (StreamingCache.cachedBytes(context, cachedId) > 0L) {
                        val stream = resolveStream(
                            name = name,
                            author = author,
                            sourceUrl = sourceUrl,
                            cacheKey = cachedId
                        )
                        if (cancelledRequests.remove(cachedId)) {
                            return@withContext ProviderResult.Error("Descarga cancelada")
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
                            activeCacheWriters[cachedId] = writer
                            try {
                                writer.cache()
                            } catch (_: Exception) {
                                // A CDN URL can occasionally expire while filling
                                // the cache. Unless the user cancelled, fall back
                                // to the existing yt-dlp downloader below.
                            } finally {
                                activeCacheWriters.remove(cachedId, writer)
                            }
                            if (cancelledRequests.remove(cachedId)) {
                                return@withContext ProviderResult.Error("Descarga cancelada")
                            }
                            promoteCompleteStreamCache(context, cachedId, name, onProgress)?.let {
                                return@withContext it
                            }
                        }
                    }
                }
                ensureYoutubeInitialized(context)
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
                val directUrl = sourceUrl?.trim()?.takeIf {
                    it.startsWith("https://www.youtube.com/", true) ||
                        it.startsWith("https://youtube.com/", true) ||
                        it.startsWith("https://youtu.be/", true)
                }
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
                if (requestId != null && cancelledRequests.remove(requestId)) {
                    stagingDir.deleteRecursively()
                    return@withContext ProviderResult.Error("Descarga cancelada")
                }
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
                if (requestId != null && cancelledRequests.remove(requestId)) {
                    return@withContext ProviderResult.Error("Descarga cancelada")
                }
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

    private fun promoteCompleteStreamCache(
        context: Context,
        trackId: String,
        name: String,
        onProgress: (Int) -> Unit
    ): ProviderResult.Success? {
        if (!StreamingCache.isComplete(context, trackId)) return null
        val format = StreamingCache.cachedFormat(context, trackId)
        val externalMusic = context.getExternalFilesDir("Music")
        val rootDir = (externalMusic?.let { File(it, "downloads") } ?: File(context.filesDir, "downloads"))
            .apply { mkdirs() }
        val safeTitle = sanitizeFileName(name).ifBlank { "MusicVault Track" }
        val finalFile = uniqueFile(rootDir, safeTitle, format.extension)
        try {
            onProgress(20)
            if (!StreamingCache.exportComplete(context, trackId, finalFile)) {
                finalFile.delete()
                return null
            }
            onProgress(85)
            val treeUri = prefs(context).getString(DOWNLOAD_TREE_PREF, null)
                ?.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
            if (treeUri != null) {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: run {
                    finalFile.delete()
                    return null
                }
                val doc = tree.createFile(format.mimeType, finalFile.nameWithoutExtension) ?: run {
                    finalFile.delete()
                    return null
                }
                val output = context.contentResolver.openOutputStream(doc.uri) ?: run {
                    finalFile.delete()
                    return null
                }
                output.use { out -> finalFile.inputStream().use { input -> input.copyTo(out) } }
                finalFile.delete()
                onProgress(100)
                return ProviderResult.Success(
                    audioUri = doc.uri.toString(),
                    fileName = doc.name ?: "$safeTitle.${format.extension}",
                    mimeType = format.mimeType,
                    localPath = doc.uri.toString()
                )
            }

            val audioUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                finalFile
            ).toString()
            onProgress(100)
            return ProviderResult.Success(audioUri, finalFile.name, format.mimeType, finalFile.absolutePath)
        } catch (_: Exception) {
            finalFile.delete()
            return null
        } finally {
            releaseFileReservation(finalFile)
        }
    }

    fun cancel(requestId: String) {
        cancelledRequests.add(requestId)
        activeCacheWriters.remove(requestId)?.let { runCatching { it.cancel() } }
        activeProcesses.remove(requestId)?.let { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
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
    data class Error(val message: String) : ProviderResult
}
