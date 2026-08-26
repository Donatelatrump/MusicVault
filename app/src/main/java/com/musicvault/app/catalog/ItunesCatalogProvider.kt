package com.musicvault.app.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Locale
import java.net.URLEncoder
import java.net.URL

/** Public iTunes Search API fallback. No account or API key is required. */
class ItunesCatalogProvider : CatalogProvider {
    companion object { private const val BASE = "https://itunes.apple.com/search" }

    private suspend fun getJson(query: String, entity: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$BASE?term=${URLEncoder.encode(query.trim(), "UTF-8")}&media=music&entity=$entity&limit=${limit.coerceIn(1, 50)}&country=${Locale.getDefault().country.ifBlank { "US" }}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.3.7 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("iTunes HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun art(url: String?): String? = url?.takeIf { it.isNotBlank() }?.replace("100x100", "200x200")

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val results = getJson(query, "song", limit).optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                add(CatalogTrack(
                    id = r.optString("trackId"), title = r.optString("trackName"),
                    artistId = r.optString("artistId"), artistName = r.optString("artistName", "Artista desconocido"),
                    albumId = r.optString("collectionId").takeIf { it.isNotBlank() },
                    albumTitle = r.optString("collectionName").takeIf { it.isNotBlank() },
                    durationMs = r.optLong("trackTimeMillis", 0L), coverUri = art(r.optString("artworkUrl100")),
                    provider = "itunes"
                ))
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = emptyList()

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> {
        val root = getLookupJson(albumId, limit)
        val results = root.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (r.optString("wrapperType") != "track" || r.optString("kind") != "song") continue
                add(CatalogTrack(
                    id = r.optString("trackId"),
                    title = r.optString("trackName"),
                    artistId = r.optString("artistId"),
                    artistName = r.optString("artistName", "Artista desconocido"),
                    albumId = r.optString("collectionId").takeIf { it.isNotBlank() },
                    albumTitle = r.optString("collectionName").takeIf { it.isNotBlank() },
                    durationMs = r.optLong("trackTimeMillis", 0L),
                    coverUri = art(r.optString("artworkUrl100")),
                    provider = "itunes"
                ))
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.take(limit)
    }

    private suspend fun getLookupJson(albumId: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://itunes.apple.com/lookup?id=${URLEncoder.encode(albumId, "UTF-8")}&entity=song&limit=${limit.coerceIn(1, 200)}&country=${Locale.getDefault().country.ifBlank { "US" }}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MusicVault/0.3.7 (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("iTunes HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> {
        val results = getJson(query, "album", limit).optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                add(CatalogAlbum(
                    id = r.optString("collectionId"), title = r.optString("collectionName"),
                    artistId = r.optString("artistId"), artistName = r.optString("artistName", "Artista desconocido"),
                    year = r.optString("releaseDate").take(4).toIntOrNull(), coverUri = art(r.optString("artworkUrl100")),
                    provider = "itunes"
                ))
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }
}
