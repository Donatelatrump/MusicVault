package com.musicvault.app.catalog

import com.musicvault.app.providers.NewPipeStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

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
            android.util.Log.w("MusicVaultYouTubeSearch", "NewPipe search failed for '$query'", error)
            emptyList()
        }

        if (newPipeResults.isNotEmpty()) {
            queryCache[cacheKey(normalized, limit)] = newPipeResults
            newPipeResults.forEach { item -> infoCache[item.sourceId ?: item.id] = item }
        }
        return newPipeResults
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = emptyList()

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> = emptyList()

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> = emptyList()

    private fun classifyContentType(
        queryFallback: String?,
        title: String,
        uploader: String,
        description: String?,
        categories: org.json.JSONArray?,
        tags: org.json.JSONArray?
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

    private fun org.json.JSONArray.toText(): String = buildString {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value != null && value != org.json.JSONObject.NULL) {
                if (isNotEmpty()) append(' ')
                append(value.toString())
            }
        }
    }
}
