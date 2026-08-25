package com.musicvault.app.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Public Deezer search endpoint used only as metadata/artwork fallback. */
class DeezerCatalogProvider : CatalogProvider {
    private suspend fun getJson(query: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://api.deezer.com/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=${limit.coerceIn(1, 50)}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.3.7 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Deezer HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val data = getJson(query, limit).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val r = data.getJSONObject(i)
                val artist = r.optJSONObject("artist")
                val album = r.optJSONObject("album")
                add(CatalogTrack(
                    id = r.optString("id"), title = r.optString("title"),
                    artistId = artist?.optString("id").orEmpty(), artistName = artist?.optString("name") ?: "Artista desconocido",
                    albumId = album?.optString("id"), albumTitle = album?.optString("title"),
                    durationMs = r.optLong("duration", 0L) * 1000L,
                    coverUri = album?.optString("cover_medium")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("cover_big")?.takeIf { it.isNotBlank() }, provider = "deezer"
                ))
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = emptyList()

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> {
        val root = getAlbumJson(albumId, limit)
        val album = root.optJSONObject("album") ?: root
        val tracks = root.optJSONArray("data") ?: return emptyList()
        val artist = album.optJSONObject("artist")
        val albumTitle = album.optString("title").takeIf { it.isNotBlank() }
        val cover = album.optString("cover_medium").takeIf { it.isNotBlank() }
            ?: album.optString("cover_big").takeIf { it.isNotBlank() }
        return buildList {
            for (i in 0 until tracks.length()) {
                val r = tracks.getJSONObject(i)
                add(CatalogTrack(
                    id = r.optString("id"),
                    title = r.optString("title"),
                    artistId = r.optJSONObject("artist")?.optString("id") ?: artist?.optString("id").orEmpty(),
                    artistName = r.optJSONObject("artist")?.optString("name")
                        ?: artist?.optString("name") ?: "Artista desconocido",
                    albumId = albumId,
                    albumTitle = r.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() } ?: albumTitle,
                    durationMs = r.optLong("duration", 0L) * 1000L,
                    coverUri = r.optJSONObject("album")?.optString("cover_medium")?.takeIf { it.isNotBlank() } ?: cover,
                    provider = "deezer"
                ))
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.take(limit)
    }

    private suspend fun getAlbumJson(albumId: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://api.deezer.com/album/${URLEncoder.encode(albumId, "UTF-8")}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.3.7 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Deezer HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally { c.disconnect() }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> =
        searchTracks(query, limit).distinctBy { it.albumId }.mapNotNull { t ->
            t.albumId?.let { CatalogAlbum(it, t.albumTitle ?: "Álbum", t.artistId, t.artistName, null, t.coverUri, "deezer") }
        }
}
