package com.musicvault.app.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Public Deezer endpoints used only for metadata/artwork. */
class DeezerCatalogProvider : CatalogProvider {
    private suspend fun getSearchJson(path: String, query: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://api.deezer.com/$path?q=$encoded&limit=${limit.coerceIn(1, 50)}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 5_000
        c.readTimeout = 7_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.5.0 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Deezer HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val data = getSearchJson("search", query, limit).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val r = data.getJSONObject(i)
                val artist = r.optJSONObject("artist")
                val album = r.optJSONObject("album")
                add(
                    CatalogTrack(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        artistId = artist?.optString("id").orEmpty(),
                        artistName = artist?.optString("name") ?: "Artista desconocido",
                        albumId = album?.optString("id"),
                        albumTitle = album?.optString("title"),
                        durationMs = r.optLong("duration", 0L) * 1000L,
                        coverUri = album?.optString("cover_medium")?.takeIf { it.isNotBlank() }
                            ?: album?.optString("cover_big")?.takeIf { it.isNotBlank() },
                        provider = "deezer"
                    )
                )
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = emptyList()

    /**
     * Use Deezer's album search directly. The previous implementation derived
     * albums from a track search, which was slower and could easily miss an
     * artist's releases when the first page contained many songs from one album.
     */
    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> {
        val data = getSearchJson("search/album", query, limit).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val r = data.getJSONObject(i)
                val artist = r.optJSONObject("artist")
                val releaseDate = r.optString("release_date")
                val year = releaseDate.take(4).toIntOrNull()
                add(
                    CatalogAlbum(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        artistId = artist?.optString("id").orEmpty(),
                        artistName = artist?.optString("name") ?: "Artista desconocido",
                        year = year,
                        coverUri = r.optString("cover_medium").takeIf { it.isNotBlank() }
                            ?: r.optString("cover_big").takeIf { it.isNotBlank() },
                        provider = "deezer"
                    )
                )
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.artistName.isNotBlank() }
    }

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> {
        return parseAlbumTracks(albumId, getAlbumJson(albumId), limit)
    }

    /** Home requires a genuine multi-track release and its available track list. */
    suspend fun recommendationAlbumTracks(albumId: String): List<CatalogTrack> {
        val root = getAlbumJson(albumId)
        val kind = root.optString("record_type").lowercase()
        if (kind !in setOf("album", "ep") || root.optInt("nb_tracks") < 2) return emptyList()
        return parseAlbumTracks(albumId, root, 100).takeIf { it.size >= 2 }.orEmpty()
    }

    private fun parseAlbumTracks(albumId: String, root: JSONObject, limit: Int): List<CatalogTrack> {
        val tracks = root.optJSONObject("tracks")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: return emptyList()
        val artist = root.optJSONObject("artist")
        val albumTitle = root.optString("title").takeIf { it.isNotBlank() }
        val cover = root.optString("cover_medium").takeIf { it.isNotBlank() }
            ?: root.optString("cover_big").takeIf { it.isNotBlank() }

        return buildList {
            for (i in 0 until tracks.length()) {
                val r = tracks.getJSONObject(i)
                val trackArtist = r.optJSONObject("artist")
                add(
                    CatalogTrack(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        artistId = trackArtist?.optString("id") ?: artist?.optString("id").orEmpty(),
                        artistName = trackArtist?.optString("name")
                            ?: artist?.optString("name") ?: "Artista desconocido",
                        albumId = albumId,
                        albumTitle = albumTitle,
                        durationMs = r.optLong("duration", 0L) * 1000L,
                        coverUri = cover,
                        provider = "deezer"
                    )
                )
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.take(limit)
    }

    private suspend fun getAlbumJson(albumId: String): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://api.deezer.com/album/${URLEncoder.encode(albumId, "UTF-8")}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 5_000
        c.readTimeout = 7_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.5.0 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Deezer HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
