package com.musicvault.app.catalog

import com.musicvault.app.providers.Providers
import com.musicvault.app.providers.NewPipeStreamResolver
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * YouTube metadata provider. NewPipeExtractor is the fast primary search path;
 * yt-dlp is kept only as a compatibility fallback. Downloads stay routed
 * through the existing Providers integration.
 */
class YouTubeMetadataProvider : CatalogProvider {
    companion object {
        const val PROVIDER_ID = "youtube"
        const val SOURCE_NAME = "YouTube"
        private const val MAX_PAGE_LIMIT = 25
        private const val SEARCH_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 220L
    }

    private val queryCache = ConcurrentHashMap<String, List<CatalogTrack>>()
    private val infoCache = ConcurrentHashMap<String, CatalogTrack>()

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        queryCache[cacheKey(normalized, limit)]?.let { return it }

        // Primary search path: NewPipeExtractor. On the test device this returns
        // YouTube candidates in ~1.2-1.4 s, while starting yt-dlp can take 15-20 s.
        // This also makes ASMR/podcast searches independent from Deezer quality.
        val newPipeResults = try {
            NewPipeStreamResolver.searchYoutube(query, limit.coerceIn(1, MAX_PAGE_LIMIT)).map { item ->
                CatalogTrack(
                    id = item.id,
                    title = item.title,
                    artistId = item.uploader,
                    artistName = item.uploader,
                    albumId = null,
                    albumTitle = null,
                    durationMs = item.durationMs,
                    coverUri = item.thumbnailUrl ?: "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg",
                    provider = PROVIDER_ID,
                    description = null,
                    url = item.url,
                    source = SOURCE_NAME,
                    sourceId = item.id,
                    contentType = classifyContentType(
                        queryFallback = query,
                        title = item.title,
                        uploader = item.uploader,
                        description = null,
                        categories = null,
                        tags = null
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            android.util.Log.w("MusicVaultYouTubeSearch", "NewPipe search failed for '$query'; falling back to yt-dlp", error)
            emptyList()
        }

        if (newPipeResults.isNotEmpty()) {
            queryCache[cacheKey(normalized, limit)] = newPipeResults
            newPipeResults.forEach { item -> infoCache[item.sourceId ?: item.id] = item }
            return newPipeResults
        }

        // Compatibility fallback only. Never turn an uninitialized yt-dlp runtime
        // into an authoritative empty YouTube search.
        if (!Providers.ensureYoutubeReady()) return emptyList()

        var results: List<CatalogTrack> = emptyList()
        for (attempt in 0 until SEARCH_ATTEMPTS) {
            results = try {
                withContext(Dispatchers.IO) {
                    searchFlatLines(query, limit)
                        .ifEmpty { searchJsonFallback(query, limit) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.w("MusicVaultYouTubeSearch", "yt-dlp search attempt ${attempt + 1} failed for '$query'", error)
                emptyList()
            }

            if (results.isNotEmpty()) break
            if (attempt + 1 < SEARCH_ATTEMPTS) delay(RETRY_DELAY_MS)
        }

        // Never memoize a transient failure as an authoritative empty search.
        if (results.isNotEmpty()) {
            queryCache[cacheKey(normalized, limit)] = results
            results.forEach { item -> infoCache[item.sourceId ?: item.id] = item }
        }
        return results
    }

    private fun searchFlatLines(query: String, limit: Int): List<CatalogTrack> {
        val safeLimit = limit.coerceIn(1, MAX_PAGE_LIMIT)
        val request = YoutubeDLRequest("ytsearch${safeLimit}:$query").apply {
            addOption("--flat-playlist")
            addOption("--skip-download")
            addOption("--ignore-errors")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--extractor-retries", "2")
            addOption("--retries", "2")
            addOption("--socket-timeout", "8")
            addOption(
                "--print",
                "%(id)s\t%(title)s\t%(uploader)s\t%(channel)s\t%(duration)s\t%(thumbnail)s\t%(webpage_url)s"
            )
        }
        val response = YoutubeDL.getInstance().execute(request, null, false)
        return parseFlatSearchLines(response.out, query, safeLimit)
    }

    private fun parseFlatSearchLines(raw: String, query: String, limit: Int): List<CatalogTrack> {
        return raw.lineSequence().mapNotNull { rawLine ->
            val fields = rawLine.trim().split('\t', limit = 7)
            if (fields.size < 2) return@mapNotNull null
            val id = cleanField(fields.getOrNull(0)) ?: return@mapNotNull null
            val title = cleanField(fields.getOrNull(1)) ?: return@mapNotNull null
            val uploader = cleanField(fields.getOrNull(2))
                ?: cleanField(fields.getOrNull(3))
                ?: "YouTube"
            val durationMs = cleanField(fields.getOrNull(4))
                ?.toDoubleOrNull()?.times(1_000.0)?.toLong()?.coerceAtLeast(0L) ?: 0L
            val thumbnail = cleanField(fields.getOrNull(5))
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            val webpageUrl = cleanField(fields.getOrNull(6))
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: "https://www.youtube.com/watch?v=$id"

            CatalogTrack(
                id = id,
                title = title,
                artistId = uploader,
                artistName = uploader,
                albumId = null,
                albumTitle = null,
                durationMs = durationMs,
                coverUri = thumbnail,
                provider = PROVIDER_ID,
                description = null,
                url = webpageUrl,
                source = SOURCE_NAME,
                sourceId = id,
                contentType = classifyContentType(
                    queryFallback = query,
                    title = title,
                    uploader = uploader,
                    description = null,
                    categories = null,
                    tags = null
                )
            )
        }.distinctBy { it.id }.take(limit).toList()
    }

    private fun cleanField(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("NA", true) && !it.equals("null", true) && it != "None" }

    private fun searchJsonFallback(query: String, limit: Int): List<CatalogTrack> {
        val request = YoutubeDLRequest("ytsearch${limit.coerceIn(1, MAX_PAGE_LIMIT)}:$query").apply {
            addOption("--flat-playlist")
            addOption("--skip-download")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--ignore-errors")
            addOption("--extractor-retries", "2")
            addOption("--retries", "2")
            addOption("--socket-timeout", "8")
        }
        val response = YoutubeDL.getInstance().execute(request, null, false)
        return parseSearchResponse(response.out, query, limit)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = emptyList()

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> = emptyList()

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> = emptyList()

    private fun parseSearchResponse(raw: String, query: String, limit: Int): List<CatalogTrack> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val candidates = mutableListOf<JSONObject>()
        collectCandidates(root, candidates)

        return candidates.asSequence()
            .mapNotNull { candidate -> candidate.toCatalogTrack(queryFallback = query) }
            .distinctBy { it.sourceId ?: it.id }
            .take(limit)
            .toList()
    }

    private fun collectCandidates(node: JSONObject, out: MutableList<JSONObject>) {
        val type = node.optString("_type").lowercase()
        val extractorKey = node.optString("extractor_key").lowercase()
        val ieKey = node.optString("ie_key").lowercase()
        val hasEntries = node.has("entries")

        if (isLeafVideo(node, type, extractorKey, ieKey)) {
            out += node
        }

        if (hasEntries) {
            val entries = node.opt("entries")
            when (entries) {
                is JSONArray -> {
                    for (index in 0 until entries.length()) {
                        val entry = entries.optJSONObject(index) ?: continue
                        collectCandidates(entry, out)
                    }
                }
                is JSONObject -> collectCandidates(entries, out)
            }
        }
    }

    private fun isLeafVideo(node: JSONObject, type: String, extractorKey: String, ieKey: String): Boolean {
        if (node.optString("id").isBlank() || node.optString("title").isBlank()) return false
        if (type == "playlist" || type == "multi_video") return false
        if (extractorKey.contains("playlist") || extractorKey.contains("tab") || ieKey.contains("playlist") || ieKey.contains("tab")) return false
        if (node.has("entries")) return false
        val webpageUrl = node.optString("webpage_url")
        val url = node.optString("url")
        return webpageUrl.contains("watch?v=") || webpageUrl.contains("youtu.be/") ||
            url.contains("watch?v=") || url.contains("youtu.be/") ||
            extractorKey.contains("youtube") || ieKey.contains("youtube")
    }

    private fun JSONObject.toCatalogTrack(queryFallback: String?): CatalogTrack? {
        val id = optString("id").takeIf { it.isNotBlank() }
            ?: optString("display_id").takeIf { it.isNotBlank() }
            ?: return null

        val title = optString("title").takeIf { it.isNotBlank() }
            ?: optString("fulltitle").takeIf { it.isNotBlank() }
            ?: return null

        val uploader = optString("uploader")
            .takeIf { it.isNotBlank() }
            ?: optString("channel")
                .takeIf { it.isNotBlank() }
            ?: "Canal desconocido"

        val url = pickUrl(id)
        val thumbnail = optString("thumbnail")
            .takeIf { it.isNotBlank() }
            ?: optJSONArray("thumbnails")
                ?.largestThumbnailUrl()
        val durationMs = max(optLong("duration", 0L), 0L) * 1_000L
        val description = optString("description").takeIf { it.isNotBlank() }
        val sourceId = id
        val contentType = classifyContentType(
            queryFallback = queryFallback,
            title = title,
            uploader = uploader,
            description = description,
            categories = optJSONArray("categories"),
            tags = optJSONArray("tags")
        )

        return CatalogTrack(
            id = id,
            title = title,
            artistId = optString("uploader_id").takeIf { it.isNotBlank() } ?: uploader,
            artistName = uploader,
            albumId = null,
            albumTitle = null,
            durationMs = durationMs,
            coverUri = thumbnail,
            provider = PROVIDER_ID,
            description = description,
            url = url,
            source = SOURCE_NAME,
            sourceId = sourceId,
            contentType = contentType
        )
    }

    private fun JSONObject.pickUrl(id: String): String? {
        val webpageUrl = optString("webpage_url").takeIf { it.isNotBlank() }
        if (webpageUrl != null) return webpageUrl
        val directUrl = optString("url").takeIf { it.isNotBlank() }
        if (directUrl != null) return directUrl
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun JSONArray.largestThumbnailUrl(): String? {
        var bestUrl: String? = null
        repeat(length()) { index ->
            val item = optJSONObject(index) ?: return@repeat
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@repeat
            bestUrl = url
        }
        return bestUrl
    }

    private fun classifyContentType(
        queryFallback: String?,
        title: String,
        uploader: String,
        description: String?,
        categories: JSONArray?,
        tags: JSONArray?
    ): CatalogContentType {
        val haystack = buildString {
            append(queryFallback.orEmpty()).append(' ')
            append(title).append(' ')
            append(uploader).append(' ')
            append(description.orEmpty()).append(' ')
            append(categories?.toText().orEmpty()).append(' ')
            append(tags?.toText().orEmpty())
        }.lowercase()

        return when {
            haystack.contains("asmr") -> CatalogContentType.ASMR
            haystack.contains("podcast") || haystack.contains("episodio") || haystack.contains("episode") ||
                haystack.contains("entrevista") || haystack.contains("charla") || haystack.contains("talk") ||
                haystack.contains("audiobook") || haystack.contains("audiolibro") -> CatalogContentType.PODCAST
            haystack.contains("music") || haystack.contains("música") || haystack.contains("musica") ||
                haystack.contains("song") || haystack.contains("album") || haystack.contains("official video") ||
                haystack.contains("topic") -> CatalogContentType.MUSIC
            else -> CatalogContentType.OTHER
        }
    }

    private fun cacheKey(query: String, limit: Int): String = "youtube:${query.trim().lowercase()}|$limit"

    private fun JSONArray.toText(): String = buildString {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value != null && value != JSONObject.NULL) {
                if (isNotEmpty()) append(' ')
                append(value.toString())
            }
        }
    }
}
