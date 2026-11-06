package com.musicvault.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import com.musicvault.app.download.DownloadPreferences

/**
 * Temporary streaming cache shared by every ExoPlayer instance in the app.
 *
 * The cache is deliberately NOT part of the offline library. Bytes are retained
 * for at most 24 hours since the last playback request and are also bounded by a
 * 1 GiB LRU cap. A stable key based on Track.id is used instead of the temporary
 * googlevideo URL, so resolving a fresh stream URL still reuses the same bytes.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object StreamingCache {
    const val MAX_BYTES: Long = 1024L * 1024L * 1024L
    const val TTL_MS: Long = 24L * 60L * 60L * 1000L

    private const val PREFS = "musicvault.streaming_cache"
    private const val ACCESS_PREFIX = "access:"
    private const val EXT_PREFIX = "ext:"
    private const val MIME_PREFIX = "mime:"

    @Volatile private var cache: SimpleCache? = null
    private var evictor: AdjustableCacheEvictor? = null
    @Volatile private var lastCleanupAt: Long = 0L
    @Volatile private var httpFactory: HttpDataSource.Factory? = null
    @Volatile private var cronetActive: Boolean = false
    private val lock = Any()
    private val networkLock = Any()
    private val cronetExecutor: ExecutorService by lazy { Executors.newCachedThreadPool() }
    private val playbackRequests = ConcurrentHashMap<String, Long>()

    // ~192 KiB is roughly 4-12 seconds for common compressed audio bitrates.
    // It is intentionally small: enough for instant next-track handoff without
    // pre-downloading whole playlists or wasting meaningful mobile data.
    const val PRELOAD_BYTES: Long = 192L * 1024L

    fun cacheKey(trackId: String): String = "musicvault-stream:$trackId"

    /** Local-only startup telemetry. Nothing is uploaded; logcat can correlate
     * user/request time with yt-dlp resolution, prebuffer and Media3 READY. */
    fun markPlaybackRequested(trackId: String) {
        playbackRequests[trackId] = android.os.SystemClock.elapsedRealtime()
    }

    fun markPlaybackReady(trackId: String) {
        playbackRequests.remove(trackId)?.let { started ->
            Log.d(
                "MusicVaultStreamPerf",
                "request-to-audio id=$trackId ${android.os.SystemClock.elapsedRealtime() - started}ms"
            )
        }
    }

    /**
     * Starts cache/database and networking setup outside the first Play tap.
     * Cronet from Google Play Services is installed asynchronously; until it is
     * available we use DefaultHttpDataSource, then rebuild the HTTP factory so
     * later streams automatically gain HTTP/2/HTTP/3 where supported.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        runCatching {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(app)
                .addOnCompleteListener {
                    synchronized(networkLock) { httpFactory = null }
                    runCatching { networkFactory(app) }
                }
        }
        Thread {
            runCatching { get(app) }
            runCatching { networkFactory(app) }
        }.apply { name = "MusicVault-stream-warmup"; isDaemon = true }.start()
    }

    fun isCronetActive(): Boolean = cronetActive

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val app = context.applicationContext
            val dir = File(app.cacheDir, "streaming_audio").apply { mkdirs() }
            val policy = AdjustableCacheEvictor(DownloadPreferences.cacheMb(app) * 1024L * 1024L)
            val created = SimpleCache(
                dir,
                policy,
                StandaloneDatabaseProvider(app)
            )
            evictor = policy
            cache = created
            cleanupExpired(app, created)
            return created
        }
    }

    fun resize(context: Context, megabytes: Int) {
        require(megabytes in DownloadPreferences.cacheSizes)
        DownloadPreferences.prefs(context).edit().putInt(DownloadPreferences.CACHE_MB, megabytes).apply()
        val current = get(context)
        evictor?.resize(current, megabytes * 1024L * 1024L)
    }

    fun usedBytes(context: Context): Long = get(context).cacheSpace

    /** Only evicts streaming spans; never touches the download directory or SAF. */
    fun clearStreamingCache(context: Context) {
        val current = get(context)
        synchronized(current) { current.keys.toList().forEach(current::removeResource) }
    }

    private fun networkFactory(context: Context): HttpDataSource.Factory {
        httpFactory?.let { return it }
        synchronized(networkLock) {
            httpFactory?.let { return it }
            val app = context.applicationContext
            val cronet = runCatching { CronetUtil.buildCronetEngine(app, "MusicVault/0.6", true) }.getOrNull()
            val built: HttpDataSource.Factory = if (cronet != null) {
                cronetActive = true
                Log.d("MusicVaultStreamPerf", "network=Cronet (HTTP/2 + HTTP/3 when CDN supports it)")
                CronetDataSource.Factory(cronet, cronetExecutor)
                    .setConnectionTimeoutMs(8_000)
                    .setReadTimeoutMs(15_000)
            } else {
                cronetActive = false
                Log.d("MusicVaultStreamPerf", "network=DefaultHttpDataSource fallback")
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("MusicVault/0.6")
            }
            httpFactory = built
            return built
        }
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return object : DataSource.Factory {
            override fun createDataSource(): DataSource = RoutingDataSource(app)
        }
    }

    /** Cache only HTTP(S) streaming. Local/content URIs bypass SimpleCache so
     * permanent downloads are never duplicated inside the temporary 1 GiB cache. */
    private class RoutingDataSource(context: Context) : DataSource {
        private val upstreamHttp = networkFactory(context)
        private val direct = DefaultDataSource.Factory(context, upstreamHttp).createDataSource()
        private val cached = CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstreamHttp)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            direct.addTransferListener(transferListener)
            cached.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val source = if (scheme == "http" || scheme == "https") cached else direct
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.getUri()

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.getResponseHeaders() ?: emptyMap()

        override fun close() {
            try { active?.close() } finally { active = null }
        }
    }

    /** Refresh the 24-hour TTL when a track is selected for streaming. */
    fun touch(context: Context, trackId: String) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(ACCESS_PREFIX + cacheKey(trackId), now)
            .apply()
        if (now - lastCleanupAt > 60L * 60L * 1000L) {
            lastCleanupAt = now
            cleanupExpired(app, get(app))
        }
    }

    fun rememberFormat(context: Context, trackId: String, extension: String?, mimeType: String?) {
        val key = cacheKey(trackId)
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        extension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() }?.let { edit.putString(EXT_PREFIX + key, it) }
        mimeType?.trim()?.takeIf { it.isNotBlank() }?.let { edit.putString(MIME_PREFIX + key, it) }
        edit.putLong(ACCESS_PREFIX + key, System.currentTimeMillis()).apply()
    }

    data class CachedFormat(val extension: String, val mimeType: String)

    fun cachedFormat(context: Context, trackId: String): CachedFormat {
        val key = cacheKey(trackId)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val extension = prefs.getString(EXT_PREFIX + key, null)?.takeIf { it.isNotBlank() } ?: "m4a"
        val mime = prefs.getString(MIME_PREFIX + key, null)?.takeIf { it.isNotBlank() }
            ?: mimeFor(extension)
        return CachedFormat(extension, mime)
    }

    /**
     * True only when Media3 knows the full resource length and every byte from
     * zero to that length is present. Partial buffers never masquerade as a
     * completed offline download.
     */
    fun isComplete(context: Context, trackId: String): Boolean {
        val simpleCache = get(context)
        val key = cacheKey(trackId)
        val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        return length > 0L && simpleCache.isCached(key, 0L, length)
    }

    fun cachedBytes(context: Context, trackId: String): Long =
        get(context).getCachedSpans(cacheKey(trackId))
            .asSequence()
            .filter { it.isCached }
            .sumOf { it.length }

    /**
     * Builds a CacheWriter against the same SimpleCache used by playback.
     * CacheWriter skips ranges that are already cached, so converting a partly
     * listened stream into an offline download only transfers the missing bytes.
     */
    fun writer(
        context: Context,
        trackId: String,
        streamUrl: String,
        progressListener: CacheWriter.ProgressListener? = null
    ): CacheWriter {
        val app = context.applicationContext
        val dataSource = CacheDataSource.Factory()
            .setCache(get(app))
            .setUpstreamDataSourceFactory(networkFactory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(streamUrl)
            .setKey(cacheKey(trackId))
            .build()
        return CacheWriter(dataSource, spec, null, progressListener)
    }

    /**
     * Preloads only the beginning of a remote track. This is the queue look-ahead
     * path: one or two upcoming tracks get enough bytes for near-instant startup,
     * never the whole playlist. Existing cached bytes are skipped automatically.
     */
    suspend fun prefetchPrefix(
        context: Context,
        trackId: String,
        streamUrl: String,
        bytes: Long = PRELOAD_BYTES
    ): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        touch(app, trackId)
        val started = android.os.SystemClock.elapsedRealtime()
        val before = cachedBytes(app, trackId)
        val source = CacheDataSource.Factory()
            .setCache(get(app))
            .setUpstreamDataSourceFactory(networkFactory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        // IMPORTANT: keep the DataSpec unbounded. A bounded DataSpec can make
        // cache metadata look as if the prefix were the full resource length,
        // which would incorrectly mark a partial preload as a complete track.
        // Open the full resource, read only [bytes], then close. CacheDataSource
        // persists the real upstream content length when the server reports it.
        val spec = DataSpec.Builder()
            .setUri(streamUrl)
            .setKey(cacheKey(trackId))
            .setPosition(0L)
            .build()
        runCatching {
            var remaining = bytes.coerceAtLeast(1L)
            val buffer = ByteArray(32 * 1024)
            source.open(spec)
            try {
                while (remaining > 0L) {
                    val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    remaining -= read.toLong()
                }
            } finally {
                source.close()
            }
        }
        val after = cachedBytes(app, trackId)
        Log.d(
            "MusicVaultStreamPerf",
            "preload id=$trackId +${(after - before).coerceAtLeast(0L)}B ${android.os.SystemClock.elapsedRealtime() - started}ms"
        )
        after
    }

    fun hasPrefetchedPrefix(context: Context, trackId: String, bytes: Long = PRELOAD_BYTES / 2): Boolean {
        if (isComplete(context, trackId)) return true
        val key = cacheKey(trackId)
        return get(context).isCached(key, 0L, bytes.coerceAtLeast(1L))
    }

    /**
     * A complete cache entry can be played without resolving a fresh signed URL.
     * CacheDataSource uses the custom cache key first, so this synthetic HTTPS URI
     * is never contacted as long as the resource is complete. The extension helps
     * Media3 choose an extractor for the cached bytes.
     */
    fun playbackUriIfComplete(context: Context, trackId: String): String? {
        if (com.musicvault.app.download.DownloadPreferences.streamQuality(context) == com.musicvault.app.download.DownloadQuality.BETA) return null
        if (!isComplete(context, trackId)) return null
        touch(context, trackId)
        val ext = cachedFormat(context, trackId).extension
        val safeId = android.net.Uri.encode(trackId)
        return "https://cache.musicvault.invalid/$safeId.$ext"
    }

    /**
     * Materializes a completely cached track into [destination] without network
     * access. Returns false if the cache is partial or contains an unexpected gap.
     */
    fun exportComplete(context: Context, trackId: String, destination: File): Boolean {
        val simpleCache = get(context)
        val key = cacheKey(trackId)
        val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        if (length <= 0L || !simpleCache.isCached(key, 0L, length)) return false

        val spans = simpleCache.getCachedSpans(key).filter { it.isCached }.sortedBy { it.position }
        if (spans.isEmpty()) return false

        destination.parentFile?.mkdirs()
        var expectedPosition = 0L
        var written = 0L
        return try {
            FileOutputStream(destination).use { output ->
                for (span in spans) {
                    if (span.position != expectedPosition) throw IOException("Cache gap at $expectedPosition")
                    val file = span.file ?: throw IOException("Cached span has no file")
                    val remaining = (length - written).coerceAtMost(span.length)
                    if (remaining <= 0L) break
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var left = remaining
                        while (left > 0L) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read <= 0) throw IOException("Unexpected end of cache span")
                            output.write(buffer, 0, read)
                            left -= read
                            written += read
                        }
                    }
                    expectedPosition += span.length
                    if (written >= length) break
                }
                output.flush()
            }
            if (written == length && destination.length() == length) true
            else { destination.delete(); false }
        } catch (_: Exception) {
            destination.delete()
            false
        }
    }

    fun cleanupExpired(context: Context) = cleanupExpired(context.applicationContext, get(context))

    private fun cleanupExpired(context: Context, simpleCache: SimpleCache) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        var changed = false
        for (key in simpleCache.getKeys().toList()) {
            if (!key.startsWith("musicvault-stream:")) continue
            val lastAccess = prefs.getLong(ACCESS_PREFIX + key, 0L)
            if (lastAccess == 0L || now - lastAccess > TTL_MS) {
                runCatching { simpleCache.removeResource(key) }
                editor.remove(ACCESS_PREFIX + key)
                    .remove(EXT_PREFIX + key)
                    .remove(MIME_PREFIX + key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
