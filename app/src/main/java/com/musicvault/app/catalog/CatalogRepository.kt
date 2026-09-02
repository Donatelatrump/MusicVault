package com.musicvault.app.catalog

import com.musicvault.app.data.AlbumEntity
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.TrackEntity
import com.musicvault.app.data.CatalogSearchCacheEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class CatalogRepository(
    private val provider: CatalogProvider,
    private val db: MusicDatabase
) {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
    }

    private val albumTrackCache = object : LinkedHashMap<String, List<CatalogTrack>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CatalogTrack>>?): Boolean = size > 64
    }

    /** Cached search metadata used by Home recommendations without another network request. */
    fun cachedTracks(): Flow<List<CatalogTrack>> =
        db.catalogSearchCacheDao().observeAll()
            .map { entries ->
                entries.asSequence()
                    .flatMap { decode(it).tracks.asSequence() }
                    .distinctBy { "${it.provider}:${it.id}" }
                    .take(120)
                    .toList()
            }
            // JSON decoding of the whole cache is not free: keep it off the
            // main thread and swallow no-op re-emissions (Room re-fires this
            // flow on ANY write to the table).
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun search(query: String, limitTracks: Int = 20, limitAlbums: Int = 10): MetadataCatalogProvider.SearchResult {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return MetadataCatalogProvider.SearchResult(emptyList(), emptyList())
        val cacheKey = "$normalized|$limitTracks|$limitAlbums"
        val now = System.currentTimeMillis()
        val cached = db.catalogSearchCacheDao().get(cacheKey)
        if (cached != null && now - cached.savedAt <= CACHE_TTL_MS) {
            // No re-persist on cache hit: the tracks/albums tables already hold
            // this exact metadata from the first save, and re-upserting
            // identical rows only invalidated Room flows and forced Home
            // recommendations to recompute on every repeated search.
            return decode(cached)
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
        // Populate the Room tracks/albums index once per NEW search. The old
        // code did this on repeated cache hits instead (upserting identical
        // rows and invalidating Room flows every time); fresh results landing
        // here keeps the index warm with a fraction of the writes.
        persist(results)
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
        // Insert-only: if a row already exists (e.g. a track the user just downloaded),
        // keeping it preserves isDownloaded/audioUri. REPLACE here was wiping the
        // downloaded state and making tracks vanish from the Library.
        db.trackDao().insertIfAbsent(results.map {
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
