package com.musicvault.app.providers

import com.musicvault.app.catalog.CatalogArtist
import com.musicvault.app.catalog.CatalogTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Metadata recommendations only: never resolves audio or starts playback as a side effect. */
object RelatedArtists {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private fun get(path: String, query: String? = null, limit: Int = 20): JSONObject {
        if (Providers.isOffline()) throw java.io.IOException("Offline mode")
        val url = "https://api.deezer.com/".toHttpUrl().newBuilder()
        path.split('/').forEach { url.addPathSegment(it) }
        url.addQueryParameter("limit", limit.coerceIn(1, 50).toString())
        query?.let { url.addQueryParameter("q", it) }
        http.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
            check(response.isSuccessful) { "Deezer HTTP ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            check(!json.has("error")) { "Deezer metadata unavailable" }
            return json
        }
    }
    private fun artistId(name: String): String? = get("search/artist", name, 1).optJSONArray("data")
        ?.optJSONObject(0)?.optString("id")?.takeIf { it.matches(Regex("[0-9]+")) }

    suspend fun related(artistName: String, limit: Int = 12): List<CatalogArtist> = runInterruptible(Dispatchers.IO) {
        val id = artistId(artistName) ?: return@runInterruptible emptyList()
        val data = get("artist/$id/related", limit = limit).optJSONArray("data") ?: return@runInterruptible emptyList()
        (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogArtist(item.optString("id"), name)
        }.distinctBy { it.id }.take(limit.coerceIn(1, 50))
    }

    suspend fun radio(artistName: String, limit: Int = 30): List<CatalogTrack> = runInterruptible(Dispatchers.IO) {
        val seed = artistId(artistName) ?: return@runInterruptible emptyList()
        val related = get("artist/$seed/related", limit = 5).optJSONArray("data")
        val ids = listOf(seed) + (0 until (related?.length() ?: 0)).mapNotNull { related?.optJSONObject(it)?.optString("id") }
        val tracks = buildList {
            for (artist in ids.distinct().take(6)) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val data = get("artist/$artist/top", limit = 8).optJSONArray("data") ?: continue
                for (index in 0 until data.length()) {
                    val item = data.getJSONObject(index)
                    val performer = item.optJSONObject("artist")
                    val album = item.optJSONObject("album")
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(CatalogTrack(id, item.optString("title"), performer?.optString("id").orEmpty(),
                        performer?.optString("name").orEmpty(), album?.optString("id"), album?.optString("title"),
                        item.optLong("duration") * 1000, album?.optString("cover_medium")?.takeIf { it.isNotBlank() }, provider = "deezer"))
                }
            }
        }
        // Round-robin artists rather than burying every related artist below all of the seed's tracks.
        tracks.groupBy { it.artistId }.values.toList().let { groups ->
            (0 until 8).flatMap { i -> groups.mapNotNull { it.getOrNull(i) } }
        }.distinctBy { it.id }.take(limit.coerceIn(1, 50))
    }
}
