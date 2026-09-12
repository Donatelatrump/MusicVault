package com.musicvault.app.catalog

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * YouTube metadata provider backed by yt-dlp.
 *
 * This provider only extracts search metadata. Downloads stay routed through
 * the existing Providers integration.
 */
class YouTubeMetadataProvider : CatalogProvider {
    companion object {
        const val PROVIDER_ID = "youtube"
        const val SOURCE_NAME = "YouTube"
        private const val MAX_PAGE_LIMIT = 25
    }

    private val queryCache = ConcurrentHashMap<String, List<CatalogTrack>>()
    private val infoCache = ConcurrentHashMap<String, CatalogTrack>()

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        queryCache[cacheKey(normalized, limit)]?.let { return it }

        val results = try {
            withContext(Dispatchers.IO) {
                val request = YoutubeDLRequest("ytsearch${limit.coerceIn(1, MAX_PAGE_LIMIT)}:$query")
                request.addOption("--flat-playlist")
                request.addOption("--skip-download")
                request.addOption("--dump-single-json")
                request.addOption("--no-warnings")
                request.addOption("--no-progress")

                val response = YoutubeDL.getInstance().execute(request, null, true)
                parseSearchResponse(response.out, query, limit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        queryCache[cacheKey(normalized, limit)] = results
        results.forEach { item ->
            infoCache[item.sourceId ?: item.id] = item
        }
        return results
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
