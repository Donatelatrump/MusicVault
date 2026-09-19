package com.musicvault.app.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Fast native/JVM YouTube resolver used before yt-dlp.
 *
 * NewPipeExtractor avoids the Python + QuickJS startup path that dominates the
 * measured MusicVault cold-start latency. yt-dlp remains the compatibility
 * fallback in Providers.resolveStream().
 */
internal data class NewPipeSearchItem(
    val id: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val url: String
)

internal data class NewPipePlaylistSearchItem(
    val id: String,
    val title: String,
    val uploader: String,
    val streamCount: Long,
    val thumbnailUrl: String?,
    val url: String
)

internal data class NewPipePlaylistContent(
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val declaredCount: Long,
    val tracks: List<NewPipeSearchItem>
)

internal object NewPipeStreamResolver {
    private const val TAG = "MusicVaultStreamPerf"
    private val initialized = AtomicBoolean(false)

    fun init() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(MusicVaultNewPipeDownloader())
            Log.d(TAG, "newpipe initialized")
        }
    }

    /**
     * Lightweight YouTube search used by the catalog provider. Keeping search on
     * NewPipe avoids paying yt-dlp/Python startup just to decide that YouTube has
     * results. yt-dlp remains a compatibility fallback in YouTubeMetadataProvider.
     */
    suspend fun searchYoutube(query: String, limit: Int): List<NewPipeSearchItem> =
        runInterruptible(Dispatchers.IO) {
            init()
            val clean = query.trim()
            if (clean.isBlank()) return@runInterruptible emptyList()

            val started = android.os.SystemClock.elapsedRealtime()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(clean)
            val info = SearchInfo.getInfo(service, handler)
            val result = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .asSequence()
                .mapNotNull { item ->
                    val url = item.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: return@mapNotNull null
                    val id = youtubeId(url) ?: return@mapNotNull null
                    val title = item.name.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val uploader = item.uploaderName?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube"
                    val durationMs = item.duration.takeIf { it > 0L }?.times(1_000L) ?: 0L
                    val thumbnail = item.thumbnails.maxByOrNull { image ->
                        val h = image.height.coerceAtLeast(0)
                        val w = image.width.coerceAtLeast(0)
                        h.toLong() * w.toLong()
                    }?.url
                    NewPipeSearchItem(
                        id = id,
                        title = title,
                        uploader = uploader,
                        durationMs = durationMs,
                        thumbnailUrl = thumbnail,
                        url = url
                    )
                }
                .distinctBy { it.id }
                .take(limit.coerceIn(1, 25))
                .toList()
            Log.d(
                "MusicVaultYouTubeSearch",
                "newpipe-search ${android.os.SystemClock.elapsedRealtime() - started}ms query='$clean' count=${result.size}"
            )
            result
        }

    /** Search YouTube playlists directly; no Deezer/yt-dlp detour. */
    suspend fun searchYoutubePlaylists(query: String, limit: Int): List<NewPipePlaylistSearchItem> =
        runInterruptible(Dispatchers.IO) {
            init()
            val clean = query.trim()
            if (clean.isBlank()) return@runInterruptible emptyList()

            val started = android.os.SystemClock.elapsedRealtime()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(clean, listOf("playlists"), "")
            val info = SearchInfo.getInfo(service, handler)
            val result = info.relatedItems
                .filterIsInstance<PlaylistInfoItem>()
                .asSequence()
                .mapNotNull { item ->
                    val url = item.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: return@mapNotNull null
                    val id = youtubePlaylistId(url) ?: url
                    val title = item.name.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val uploader = item.uploaderName?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube"
                    val thumbnail = item.thumbnails.maxByOrNull { image ->
                        image.height.coerceAtLeast(0).toLong() * image.width.coerceAtLeast(0).toLong()
                    }?.url
                    NewPipePlaylistSearchItem(
                        id = id,
                        title = title,
                        uploader = uploader,
                        streamCount = item.streamCount,
                        thumbnailUrl = thumbnail,
                        url = url
                    )
                }
                .distinctBy { it.id }
                .take(limit.coerceIn(1, 30))
                .toList()
            Log.d(
                "MusicVaultYouTubeSearch",
                "newpipe-playlist-search ${android.os.SystemClock.elapsedRealtime() - started}ms query='$clean' count=${result.size}"
            )
            result
        }

    /** Load playlist entries, following NewPipe continuation pages up to a safe UI/import cap. */
    suspend fun loadYoutubePlaylist(url: String, maxItems: Int = 500): NewPipePlaylistContent? =
        runInterruptible(Dispatchers.IO) {
            init()
            val safeMax = maxItems.coerceIn(1, 1000)
            val started = android.os.SystemClock.elapsedRealtime()
            val service = ServiceList.YouTube
            val info = PlaylistInfo.getInfo(service, url)
            val tracks = ArrayList<NewPipeSearchItem>(minOf(safeMax, 128))

            fun append(items: List<StreamInfoItem>) {
                items.asSequence()
                    .mapNotNull { item -> streamItemToSearchItem(item) }
                    .filter { candidate -> tracks.none { it.id == candidate.id } }
                    .take((safeMax - tracks.size).coerceAtLeast(0))
                    .forEach(tracks::add)
            }

            append(info.relatedItems)
            var next = info.nextPage
            while (tracks.size < safeMax && next != null && org.schabi.newpipe.extractor.Page.isValid(next)) {
                val page = PlaylistInfo.getMoreItems(service, url, next)
                append(page.items)
                next = page.nextPage
            }

            val thumbnail = info.thumbnails.maxByOrNull { image ->
                image.height.coerceAtLeast(0).toLong() * image.width.coerceAtLeast(0).toLong()
            }?.url
            Log.d(
                "MusicVaultYouTubeSearch",
                "newpipe-playlist-load ${android.os.SystemClock.elapsedRealtime() - started}ms items=${tracks.size} declared=${info.streamCount}"
            )
            NewPipePlaylistContent(
                title = info.name,
                uploader = info.uploaderName.ifBlank { "YouTube" },
                thumbnailUrl = thumbnail,
                declaredCount = info.streamCount,
                tracks = tracks
            )
        }

    private fun streamItemToSearchItem(item: StreamInfoItem): NewPipeSearchItem? {
        val url = item.url.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val id = youtubeId(url) ?: return null
        val title = item.name.trim().takeIf { it.isNotBlank() } ?: return null
        val uploader = item.uploaderName?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube"
        val durationMs = item.duration.takeIf { it > 0L }?.times(1_000L) ?: 0L
        val thumbnail = item.thumbnails.maxByOrNull { image ->
            image.height.coerceAtLeast(0).toLong() * image.width.coerceAtLeast(0).toLong()
        }?.url
        return NewPipeSearchItem(id, title, uploader, durationMs, thumbnail, url)
    }

    suspend fun findYoutubeMatch(name: String, author: String): String? =
        runInterruptible(Dispatchers.IO) {
            init()
            val query = listOf(name.trim(), author.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (query.isBlank()) return@runInterruptible null

            val started = android.os.SystemClock.elapsedRealtime()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(query)
            val info = SearchInfo.getInfo(service, handler)
            val candidates = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .take(8)
            val picked = candidates.maxByOrNull { matchScore(name, author, it) }
            Log.d(
                TAG,
                "newpipe-match ${android.os.SystemClock.elapsedRealtime() - started}ms candidates=${candidates.size}"
            )
            picked?.url
        }

    suspend fun resolveAudio(youtubeUrl: String): StreamResolution? =
        runInterruptible(Dispatchers.IO) {
            init()
            val started = android.os.SystemClock.elapsedRealtime()
            val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)
            val stream = selectAudioStream(info.audioStreams)
            val resolved = stream?.takeIf { it.isUrl }
                ?.content
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { url ->
                    val format = stream.format
                    val extension = format?.suffix?.ifBlank { null } ?: extensionFromUrl(url)
                    val mime = format?.mimeType?.ifBlank { null } ?: mimeForExtension(extension)
                    StreamResolution(url = url, extension = extension, mimeType = mime)
                }
            Log.d(
                TAG,
                "newpipe-stream-url ${android.os.SystemClock.elapsedRealtime() - started}ms audioStreams=${info.audioStreams.size} resolved=${resolved != null}"
            )
            resolved
        }

    private fun selectAudioStream(streams: List<AudioStream>): AudioStream? {
        val direct = streams.filter { stream ->
            stream.isUrl &&
                stream.content.isNotBlank() &&
                (stream.content.startsWith("https://") || stream.content.startsWith("http://")) &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (direct.isEmpty()) return null

        // Prefer a broadly supported direct audio format and keep bitrate modest
        // for the user's slow-link scenario. Anything <= 192 kbps is comfortably
        // below 0.5 Mb/s while still giving good audio quality.
        return direct.maxByOrNull { stream ->
            val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate.takeIf { it > 0 } ?: 0
            val bitrateScore = when {
                bitrate in 96..192 -> 400 - abs(160 - bitrate)
                bitrate in 1..95 -> 180 + bitrate
                bitrate > 192 -> 120 - ((bitrate - 192) / 8).coerceAtMost(100)
                else -> 0
            }
            val formatScore = when (stream.format?.name) {
                "M4A" -> 80
                "WEBMA_OPUS", "OPUS" -> 70
                "WEBMA", "WEBM" -> 60
                else -> 20
            }
            bitrateScore + formatScore
        }
    }

    private fun matchScore(name: String, author: String, item: StreamInfoItem): Int {
        val wantedTitle = normalize(name)
        val wantedArtist = normalize(author)
        val title = normalize(item.name)
        val uploader = normalize(item.uploaderName ?: "")

        var score = 0
        if (title == wantedTitle) score += 500
        if (wantedTitle.isNotBlank() && title.contains(wantedTitle)) score += 240
        if (wantedTitle.isNotBlank() && wantedTitle.contains(title)) score += 120
        if (wantedArtist.isNotBlank() && uploader.contains(wantedArtist)) score += 180
        if (wantedArtist.isNotBlank() && title.contains(wantedArtist)) score += 80
        if (title.contains("official audio") || title.contains("audio oficial")) score += 45
        if (title.contains("lyrics") || title.contains("lyric")) score += 15
        if (title.contains("live") && !wantedTitle.contains("live")) score -= 35
        if (title.contains("cover") && !wantedTitle.contains("cover")) score -= 45
        return score
    }

    private fun youtubeId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{6,})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,})"),
            Regex("/shorts/([A-Za-z0-9_-]{6,})")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.getOrNull(1) }
    }

    private fun youtubePlaylistId(url: String): String? =
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun extensionFromUrl(url: String): String = when {
        url.contains("mime=audio%2Fwebm", ignoreCase = true) -> "webm"
        url.contains("mime=audio%2Fmp4", ignoreCase = true) -> "m4a"
        else -> "m4a"
    }

    private fun mimeForExtension(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "webm", "opus" -> "audio/webm"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        else -> "audio/mp4"
    }
}

/** Minimal NewPipe downloader backed by OkHttp. */
private class MusicVaultNewPipeDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase(Locale.ROOT)
        val rawBody = request.dataToSend()
        val contentType = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
        val requestBody = when {
            rawBody != null -> rawBody.toRequestBody(contentType)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, requestBody)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"
            )

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val localizationHeaders = Request.getHeadersFromLocalization(request.localization())
        localizationHeaders.forEach { (name, values) ->
            if (request.headers().keys.none { it.equals(name, ignoreCase = true) }) {
                values.forEach { builder.addHeader(name, it) }
            }
        }

        val call = client.newCall(builder.build())
        try {
            call.execute().use { response ->
                val body = if (method == "HEAD") "" else response.body?.string().orEmpty()
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    response.request.url.toString()
                )
            }
        } catch (e: IOException) {
            if (Thread.currentThread().isInterrupted) {
                call.cancel()
            }
            throw e
        }
    }
}
