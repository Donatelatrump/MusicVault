package com.musicvault.app.catalog

import com.musicvault.app.data.AlbumEntity
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.TrackEntity
import com.musicvault.app.data.CatalogSearchCacheEntity
import org.json.JSONArray
import org.json.JSONObject

class CatalogRepository(
    private val provider: CatalogProvider,
    private val db: MusicDatabase
) {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
    }

    private val albumTrackCache = mutableMapOf<String, List<CatalogTrack>>()

    suspend fun search(query: String, limitTracks: Int = 20, limitAlbums: Int = 10): MetadataCatalogProvider.SearchResult {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return MetadataCatalogProvider.SearchResult(emptyList(), emptyList())
        val cacheKey = "$normalized|$limitTracks|$limitAlbums"
        val now = System.currentTimeMillis()
        val cached = db.catalogSearchCacheDao().get(cacheKey)
        if (cached != null && now - cached.savedAt <= CACHE_TTL_MS) {
            val cachedResult = decode(cached)
            // Keep the Room library/catalog index warm even when the result comes
            // from the search cache. Home recommendations rely on this metadata.
            persist(cachedResult)
            return cachedResult
        }

        val results = if (provider is MetadataCatalogProvider) {
            provider.searchAll(normalized, limitTracks, limitAlbums)
        } else {
            MetadataCatalogProvider.SearchResult(
                tracks = provider.searchTracks(normalized, limitTracks),
                albums = provider.searchAlbums(normalized, limitAlbums)
            )
        }
        db.catalogSearchCacheDao().save(
            CatalogSearchCacheEntity(
                cacheKey = cacheKey,
                query = normalized,
                tracksJson = encodeTracks(results.tracks),
                albumsJson = encodeAlbums(results.albums),
                savedAt = now
            )
        )
        db.catalogSearchCacheDao().deleteOlderThan(now - (24L * 60L * 60L * 1000L))
        return results
    }

    private fun encodeTracks(items: List<CatalogTrack>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artistId", item.artistId)
                put("artistName", item.artistName)
                put("albumId", item.albumId ?: JSONObject.NULL)
                put("albumTitle", item.albumTitle ?: JSONObject.NULL)
                put("durationMs", item.durationMs)
                put("coverUri", item.coverUri ?: JSONObject.NULL)
                put("provider", item.provider)
            })
        }
    }.toString()

    private fun encodeAlbums(items: List<CatalogAlbum>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artistId", item.artistId)
                put("artistName", item.artistName)
                put("year", item.year ?: JSONObject.NULL)
                put("coverUri", item.coverUri ?: JSONObject.NULL)
                put("provider", item.provider)
            })
        }
    }.toString()

    private fun decode(cache: CatalogSearchCacheEntity): MetadataCatalogProvider.SearchResult =
        MetadataCatalogProvider.SearchResult(
            tracks = JSONArray(cache.tracksJson).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(CatalogTrack(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            artistId = item.optString("artistId"),
                            artistName = item.optString("artistName"),
                            albumId = item.optionalString("albumId"),
                            albumTitle = item.optionalString("albumTitle"),
                            durationMs = item.optLong("durationMs", 0L),
                            coverUri = item.optionalString("coverUri"),
                            provider = item.optString("provider", "musicbrainz")
                        ))
                    }
                }
            },
            albums = JSONArray(cache.albumsJson).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(CatalogAlbum(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            artistId = item.optString("artistId"),
                            artistName = item.optString("artistName"),
                            year = item.optionalInt("year"),
                            coverUri = item.optionalString("coverUri"),
                            provider = item.optString("provider", "musicbrainz")
                        ))
                    }
                }
            }
        )

    private fun JSONObject.optionalString(key: String): String? = opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    private fun JSONObject.optionalInt(key: String): Int? = opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.toIntOrNull()

    suspend fun searchTracks(query: String, limit: Int = 20): List<CatalogTrack> {
        val results = if (provider is MetadataCatalogProvider) provider.searchTracksOnly(query, limit)
        else provider.searchTracks(query, limit)
        persistTracks(results)
        return results
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<CatalogAlbum> {
        val results = if (provider is MetadataCatalogProvider) provider.searchAlbumsOnly(query, limit)
        else provider.searchAlbums(query, limit)
        persistAlbums(results)
        return results
    }

    suspend fun tracksForAlbum(album: CatalogAlbum, limit: Int = 50): List<CatalogTrack> {
        val key = "${album.provider}:${album.id}"
        synchronized(albumTrackCache) {
            albumTrackCache[key]?.let { return it.take(limit) }
        }

        val results = if (provider is MetadataCatalogProvider) {
            provider.tracksForAlbum(album, limit)
        } else {
            try {
                provider.albumTracks(album.id, limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (results.isNotEmpty()) {
            synchronized(albumTrackCache) { albumTrackCache[key] = results }
            persistTracks(results)
        }
        return results
    }

    private suspend fun persist(results: MetadataCatalogProvider.SearchResult) {
        persistTracks(results.tracks)
        persistAlbums(results.albums)
    }

    private suspend fun persistTracks(results: List<CatalogTrack>) {
        if (results.isEmpty()) return
        db.trackDao().upsertAll(results.map {
            TrackEntity(
                id = "${it.provider}:recording:${it.id}", title = it.title, artistName = it.artistName,
                albumId = it.albumId?.let { id -> "${it.provider}:release:$id" } ?: "unknown",
                albumTitle = it.albumTitle ?: "Álbum desconocido", durationMs = it.durationMs,
                audioUri = null, isDownloaded = false, coverUri = it.coverUri
            )
        })
    }

    private suspend fun persistAlbums(results: List<CatalogAlbum>) {
        if (results.isEmpty()) return
        db.albumDao().upsertAll(results.map {
            AlbumEntity("${it.provider}:release-group:${it.id}", it.title, it.artistId, it.artistName, it.year ?: 0, it.coverUri)
        })
    }
}
