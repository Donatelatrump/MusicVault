package com.musicvault.app.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * MusicBrainz metadata provider.
 *
 * MusicBrainz requires a meaningful User-Agent and asks clients to stay at
 * or below one request per second. A single Mutex makes that rule hold even
 * when several Compose searches are triggered concurrently.
 */
class MusicBrainzCatalogProvider : CatalogProvider {

    companion object {
        private const val BASE = "https://musicbrainz.org/ws/2"
        private const val USER_AGENT =
            "MusicVault/0.3.7 (Android local music app)"
        private const val REQUEST_GAP_MS = 1_100L
        private const val MAX_ATTEMPTS = 3
    }

    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    private suspend fun getJson(path: String, params: Map<String, String>): JSONObject = requestMutex.withLock {
        val wait = REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val result = withContext(Dispatchers.IO) {
                    val query = params.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                    }
                    val connection = (URL("$BASE/$path?$query&fmt=json").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; doInput = true; useCaches = false
                        connectTimeout = 15_000; readTimeout = 20_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                            throw IllegalStateException("MusicBrainz HTTP $status" + if (body.isNotBlank()) ": ${body.take(240)}" else "")
                        }
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                lastRequestAt = System.currentTimeMillis()
                return@withLock result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) delay(1_000L * (attempt + 1))
            }
        }
        lastRequestAt = System.currentTimeMillis()
        throw lastError ?: IllegalStateException("MusicBrainz: respuesta vacía")
    }

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> {
        val root = getJson(
            "recording",
            mapOf("query" to query.trim(), "limit" to limit.coerceIn(1, 100).toString())
        )
        val recordings = root.optJSONArray("recordings") ?: return emptyList()

        return buildList {
            for (i in 0 until recordings.length()) {
                val r = recordings.getJSONObject(i)
                val artistCredit = r.optJSONArray("artist-credit")
                val artist = artistCredit?.optJSONObject(0)
                val artistObj = artist?.optJSONObject("artist")
                val artistId = artistObj?.optString("id").orEmpty()
                val artistName = artistObj?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: artist?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: "Artista desconocido"

                val releases = r.optJSONArray("releases")
                val release = releases?.optJSONObject(0)
                val releaseId = release?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                val releaseTitle = release?.optString("title")
                    ?.takeIf { it.isNotBlank() }

                add(
                    CatalogTrack(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        artistId = artistId,
                        artistName = artistName,
                        albumId = releaseId,
                        albumTitle = releaseTitle,
                        durationMs = r.optLong("length", 0L),
                        coverUri = CoverArt.releaseFront(releaseId)
                    )
                )
            }
        }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> {
        val root = getJson(
            "artist",
            mapOf("query" to query.trim(), "limit" to limit.coerceIn(1, 100).toString())
        )
        val artists = root.optJSONArray("artists") ?: return emptyList()

        return buildList {
            for (i in 0 until artists.length()) {
                val a = artists.getJSONObject(i)
                add(
                    CatalogArtist(
                        id = a.optString("id"),
                        name = a.optString("name"),
                        country = a.optString("country").takeIf { it.isNotBlank() },
                        disambiguation = a.optString("disambiguation")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> {
        val group = getJson(
            "release-group/$albumId",
            mapOf("inc" to "releases", "limit" to "20")
        )
        val releases = group.optJSONArray("releases") ?: return emptyList()
        val release = (0 until releases.length())
            .map { releases.getJSONObject(it) }
            .firstOrNull { it.optString("id").isNotBlank() }
            ?: return emptyList()
        val releaseId = release.optString("id")
        val releaseRoot = getJson(
            "release/$releaseId",
            mapOf("inc" to "recordings+artist-credits", "limit" to limit.coerceIn(1, 100).toString())
        )
        val media = releaseRoot.optJSONArray("media") ?: return emptyList()
        val albumTitle = releaseRoot.optString("title").takeIf { it.isNotBlank() }
            ?: group.optString("title").takeIf { it.isNotBlank() }
        val releaseArtist = releaseRoot.optJSONArray("artist-credit")?.optJSONObject(0)
        val releaseArtistObj = releaseArtist?.optJSONObject("artist")
        val releaseArtistId = releaseArtistObj?.optString("id").orEmpty()
        val releaseArtistName = releaseArtistObj?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: releaseArtist?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "Artista desconocido"

        return buildList {
            for (m in 0 until media.length()) {
                val tracks = media.getJSONObject(m).optJSONArray("tracks") ?: continue
                for (i in 0 until tracks.length()) {
                    val t = tracks.getJSONObject(i)
                    val recording = t.optJSONObject("recording") ?: continue
                    val credits = recording.optJSONArray("artist-credit")
                    val credit = credits?.optJSONObject(0)
                    val artistObj = credit?.optJSONObject("artist")
                    val artistId = artistObj?.optString("id")
                        ?.takeIf { it.isNotBlank() } ?: releaseArtistId
                    val artistName = artistObj?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?: credit?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: releaseArtistName
                    add(CatalogTrack(
                        id = recording.optString("id"),
                        title = recording.optString("title").ifBlank { t.optString("title") },
                        artistId = artistId,
                        artistName = artistName,
                        albumId = releaseId,
                        albumTitle = albumTitle,
                        durationMs = recording.optLong("length", 0L),
                        coverUri = CoverArt.releaseFront(releaseId),
                        provider = "musicbrainz"
                    ))
                    if (size >= limit) break
                }
                if (size >= limit) break
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> {
        val root = getJson(
            "release-group",
            mapOf("query" to query.trim(), "limit" to limit.coerceIn(1, 100).toString())
        )
        val groups = root.optJSONArray("release-groups") ?: return emptyList()

        return buildList {
            for (i in 0 until groups.length()) {
                val g = groups.getJSONObject(i)
                val credits = g.optJSONArray("artist-credit")
                val artist = credits?.optJSONObject(0)
                val artistObj = artist?.optJSONObject("artist")
                val date = g.optString("first-release-date")

                add(
                    CatalogAlbum(
                        id = g.optString("id"),
                        title = g.optString("title"),
                        artistId = artistObj?.optString("id").orEmpty(),
                        artistName = artistObj?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                            ?: artist?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                            ?: "Artista desconocido",
                        year = date.takeIf { it.length >= 4 }
                            ?.substring(0, 4)
                            ?.toIntOrNull(),
                        coverUri = CoverArt.releaseGroupFront(g.optString("id"))
                    )
                )
            }
        }
    }
}
