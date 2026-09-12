package com.musicvault.app.spotify

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val url: String
)

data class SpotifyPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val total: Int,
    val tracks: List<SpotifyTrack>,
    val url: String
)

class SpotifySessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Read-only Android port of the embedded SongMirror-compatible Spotify reader. */
class SpotifySongMirrorClient {
    private data class TokenCache(val cookieHash: ByteArray, val token: String, val expiresAt: Long)
    private data class HttpResult(val status: Int, val body: ByteArray)
    private class HttpFailure(val status: Int, val detail: String) : Exception(detail)
    private class QueryHashExpired : Exception()

    @Volatile private var tokenCache: TokenCache? = null

    fun validate(cookie: String): Boolean = accessToken(cookie).isNotBlank()

    fun fetchTrack(trackId: String, cookie: String): SpotifyTrack {
        requireId(trackId)
        val token = accessToken(cookie)
        return try {
            val detail = jsonRequest(
                "$WEB_API$trackId",
                headers = headers(token)
            )
            trackFromWebApi(detail, trackId)
        } catch (failure: HttpFailure) {
            when (failure.status) {
                401, 403 -> throw SpotifySessionException(
                    "Spotify rechazó la sesión. Vuelve a conectar tu cuenta.", failure
                )
                404 -> throw SpotifySessionException("Spotify no encontró esa canción.", failure)
                429 -> publicTrack(trackId)
                else -> throw SpotifySessionException("Spotify no pudo leer esa canción.", failure)
            }
        }
    }

    fun fetchPlaylist(playlistId: String, cookie: String): SpotifyPlaylist {
        requireId(playlistId)
        val token = accessToken(cookie)
        var queryHash = PLAYLIST_HASH
        val uri = "spotify:playlist:$playlistId"
        val metadataResult = runPathfinder(
            operation = "fetchPlaylist",
            variables = JSONObject()
                .put("uri", uri)
                .put("offset", 0)
                .put("limit", 0)
                .put("enableWatchFeedEntrypoint", false),
            token = token,
            cookie = cookie,
            queryHash = queryHash
        )
        val metadata = metadataResult.first.optJSONObject("playlistV2")
            ?: throw SpotifySessionException("Spotify no encontró esa playlist o la cuenta no puede verla.")
        queryHash = metadataResult.second
        val title = metadata.optString("name").takeIf(String::isNotBlank)
            ?: throw SpotifySessionException("Spotify no encontró esa playlist o la cuenta no puede verla.")
        val cover = metadata.optJSONObject("images")
            ?.optJSONArray("items")
            ?.objects()
            ?.flatMap { it.optJSONArray("sources")?.objects().orEmpty() }
            ?.firstNotNullOfOrNull { it.optionalString("url") }
        val expected = metadata.optJSONObject("content")?.optInt("totalCount", -1) ?: -1

        val tracks = ArrayList<SpotifyTrack>()
        var offset = 0
        var total = expected
        while (true) {
            val pageResult = runPathfinder(
                operation = "fetchPlaylistContents",
                variables = JSONObject().put("uri", uri).put("offset", offset).put("limit", 100),
                token = token,
                cookie = cookie,
                queryHash = queryHash
            )
            queryHash = pageResult.second
            val content = pageResult.first.optJSONObject("playlistV2")?.optJSONObject("content")
                ?: throw SpotifySessionException("Spotify devolvió una playlist incompleta.")
            val items = content.optJSONArray("items")?.objects().orEmpty()
            total = content.optInt("totalCount", total)
            if (total < 0) throw SpotifySessionException("Spotify devolvió una playlist incompleta.")
            for (item in items) {
                val data = item.optJSONObject("itemV2")?.optJSONObject("data") ?: continue
                val trackUri = data.optString("uri")
                if (!trackUri.startsWith("spotify:track:")) continue
                val id = trackUri.substringAfterLast(':')
                val album = data.optJSONObject("albumOfTrack")
                val artists = data.optJSONObject("artists")?.optJSONArray("items")?.objects().orEmpty()
                    .mapNotNull { it.optJSONObject("profile")?.optionalString("name") }
                val image = album?.optJSONObject("coverArt")?.optJSONArray("sources")?.objects().orEmpty()
                    .firstNotNullOfOrNull { it.optionalString("url") }
                tracks += SpotifyTrack(
                    id = id,
                    title = data.optString("name", "Sin título"),
                    artist = artists.joinToString(", ").ifBlank { "Artista desconocido" },
                    album = album?.optString("name")?.takeIf(String::isNotBlank) ?: "Álbum desconocido",
                    durationMs = data.optJSONObject("trackDuration")?.optLong("totalMilliseconds", 0L) ?: 0L,
                    coverUrl = image,
                    url = "https://open.spotify.com/track/$id"
                )
            }
            offset += items.size
            if (offset >= total) break
            if (items.isEmpty() || offset > MAX_TRACKS) {
                throw SpotifySessionException("Spotify devolvió una playlist incompleta.")
            }
        }
        if (tracks.isEmpty()) throw SpotifySessionException("Spotify no encontró canciones disponibles en esta playlist.")
        return SpotifyPlaylist(
            id = playlistId,
            title = title,
            coverUrl = cover,
            total = maxOf(total, tracks.size),
            tracks = tracks,
            url = "https://open.spotify.com/playlist/$playlistId"
        )
    }

    private fun accessToken(cookie: String): String {
        if (cookie.isBlank()) throw SpotifySessionException("Conecta tu cuenta de Spotify antes de importar.")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray())
        val cached = tokenCache
        if (cached != null && cached.cookieHash.contentEquals(fingerprint) &&
            System.currentTimeMillis() < cached.expiresAt - 60_000L
        ) return cached.token
        try {
            val cookieHeader = mapOf("Cookie" to "sp_dc=$cookie")
            val serverTime = jsonRequest(SERVER_TIME, headers = cookieHeader).getLong("serverTime")
            val now = System.currentTimeMillis() / 1000L
            for ((version, secret) in TOTP_SECRETS) {
                val query = listOf(
                    "reason" to "init",
                    "productType" to "web-player",
                    "totp" to totp(secret, now),
                    "totpServer" to totp(secret, serverTime),
                    "totpVer" to version.toString()
                ).joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                }
                val body = jsonRequest("$TOKEN?$query", headers = cookieHeader)
                if (body.optString("error") == "totpVerExpired" || body.optBoolean("totpVerExpired")) continue
                val token = body.optionalString("accessToken")
                val expires = body.optLong("accessTokenExpirationTimestampMs", 0L)
                if (token != null && expires > 0L && !body.optBoolean("isAnonymous", true)) {
                    tokenCache = TokenCache(fingerprint, token, expires)
                    return token
                }
            }
        } catch (failure: Exception) {
            if (failure is SpotifySessionException) throw failure
            throw SpotifySessionException("Spotify rechazó la sesión. Vuelve a conectar tu cuenta.", failure)
        }
        throw SpotifySessionException("Spotify rechazó la sesión. Vuelve a conectar tu cuenta.")
    }

    private fun totp(secret: String, timestampSeconds: Long): String {
        val key = buildString {
            secret.forEachIndexed { index, char -> append(char.code xor (index % 33 + 9)) }
        }.toByteArray()
        val counter = ByteBuffer.allocate(8).putLong(timestampSeconds / 30L).array()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }.doFinal(counter)
        val offset = mac.last().toInt() and 0x0f
        val binary = ByteBuffer.wrap(mac, offset, 4).int and 0x7fffffff
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun runPathfinder(
        operation: String,
        variables: JSONObject,
        token: String,
        cookie: String,
        queryHash: String
    ): Pair<JSONObject, String> = try {
        pathfinder(operation, variables, token, queryHash) to queryHash
    } catch (_: QueryHashExpired) {
        val refreshed = refreshPlaylistHash(cookie)
        pathfinder(operation, variables, token, refreshed) to refreshed
    }

    private fun pathfinder(
        operation: String,
        variables: JSONObject,
        token: String,
        queryHash: String
    ): JSONObject {
        val payload = JSONObject()
            .put("variables", variables)
            .put("operationName", operation)
            .put("extensions", JSONObject().put(
                "persistedQuery", JSONObject().put("version", 1).put("sha256Hash", queryHash)
            ))
        val result = try {
            jsonRequest(PATHFINDER, "POST", headers(token), payload)
        } catch (failure: HttpFailure) {
            if (failure.status == 401 || failure.status == 403) {
                throw SpotifySessionException("Spotify rechazó la sesión. Vuelve a conectar tu cuenta.", failure)
            }
            throw SpotifySessionException("Spotify no pudo leer la playlist.", failure)
        }
        val errors = result.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            if (errors.toString().contains("PersistedQueryNotFound")) throw QueryHashExpired()
            throw SpotifySessionException("Spotify no pudo leer la playlist: ${errors.opt(0)}")
        }
        return result.optJSONObject("data") ?: JSONObject()
    }

    private fun refreshPlaylistHash(cookie: String): String {
        val shell = httpRequest(WEB, headers = mapOf("Cookie" to "sp_dc=$cookie"), maximum = 4 * 1024 * 1024)
        if (shell.status !in 200..299) throw SpotifySessionException("Spotify no pudo actualizar el lector de playlists.")
        val urls = Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[^\\\"']+\\.js")
            .findAll(shell.body.toString(Charsets.UTF_8)).map { it.value }.distinct().take(40)
        val hashPattern = Regex("\\.l\\(\\\"fetchPlaylistContents\\\",\\\"query\\\",\\\"([a-f0-9]{64})\\\"")
        for (url in urls) {
            val script = runCatching { httpRequest(url, maximum = 8 * 1024 * 1024) }.getOrNull() ?: continue
            if (script.status !in 200..299) continue
            hashPattern.find(script.body.toString(Charsets.UTF_8))?.groupValues?.getOrNull(1)?.let { return it }
        }
        throw SpotifySessionException("Spotify cambió su lector de playlists. Actualiza MusicVault.")
    }

    private fun trackFromWebApi(detail: JSONObject, fallbackId: String): SpotifyTrack {
        val id = detail.optionalString("id") ?: fallbackId
        val album = detail.optJSONObject("album")
        val artist = detail.optJSONArray("artists")?.objects().orEmpty()
            .mapNotNull { it.optionalString("name") }.joinToString(", ")
        val cover = album?.optJSONArray("images")?.objects().orEmpty()
            .firstNotNullOfOrNull { it.optionalString("url") }
        val title = detail.optionalString("name")
            ?: throw SpotifySessionException("Spotify no devolvió datos válidos para esa canción.")
        return SpotifyTrack(
            id, title, artist.ifBlank { "Artista desconocido" },
            album?.optString("name")?.takeIf(String::isNotBlank) ?: "Álbum desconocido",
            detail.optLong("duration_ms", 0L), cover,
            detail.optJSONObject("external_urls")?.optionalString("spotify")
                ?: "https://open.spotify.com/track/$id"
        )
    }

    private fun publicTrack(trackId: String): SpotifyTrack {
        val response = httpRequest("https://open.spotify.com/embed/track/$trackId", maximum = 4 * 1024 * 1024)
        if (response.status !in 200..299) throw SpotifySessionException("Spotify no encontró esa canción.")
        val html = response.body.toString(Charsets.UTF_8)
        val raw = Regex("<script[^>]*id=[\\\"']__NEXT_DATA__[\\\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1)
            ?: throw SpotifySessionException("Spotify no devolvió datos válidos para esa canción.")
        val entity = runCatching {
            JSONObject(raw).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        }.getOrElse { throw SpotifySessionException("Spotify no devolvió datos válidos para esa canción.", it) }
        if (entity.optString("type") != "track") throw SpotifySessionException("Spotify no encontró esa canción.")
        val artist = entity.optJSONArray("artists")?.objects().orEmpty()
            .mapNotNull { it.optionalString("name") }.joinToString(", ")
        val cover = entity.optJSONObject("visualIdentity")?.optJSONArray("image")?.objects().orEmpty()
            .firstNotNullOfOrNull { it.optionalString("url") }
        return SpotifyTrack(
            trackId,
            entity.optString("name", "Sin título"),
            artist.ifBlank { "Artista desconocido" },
            "Spotify",
            entity.optLong("duration", 0L),
            cover,
            "https://open.spotify.com/track/$trackId"
        )
    }

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "app-platform" to "WebPlayer",
        "spotify-app-version" to APP_VERSION,
        "Origin" to WEB,
        "Referer" to WEB,
        "Content-Type" to "application/json;charset=UTF-8"
    )

    private fun jsonRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        payload: JSONObject? = null
    ): JSONObject {
        val response = httpRequest(url, method, headers, payload?.toString()?.toByteArray())
        if (response.status !in 200..299) {
            throw HttpFailure(response.status, response.body.toString(Charsets.UTF_8).take(64 * 1024))
        }
        return try {
            JSONObject(response.body.toString(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw SpotifySessionException("Spotify devolvió una respuesta no válida.", failure)
        }
    }

    private fun httpRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        maximum: Int = MAX_RESPONSE
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            headers.forEach(connection::setRequestProperty)
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            if (input != null) input.use { stream ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximum) throw SpotifySessionException("La respuesta de Spotify es demasiado grande.")
                    output.write(buffer, 0, count)
                }
            }
            return HttpResult(status, output.toByteArray())
        } catch (failure: SpotifySessionException) {
            throw failure
        } catch (failure: Exception) {
            throw SpotifySessionException("No se pudo conectar con Spotify. Revisa tu conexión.", failure)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9]{22}"))) { "El enlace de Spotify no es válido." }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun JSONObject.optionalString(key: String): String? = optString(key).takeIf(String::isNotBlank)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    private companion object {
        const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val SERVER_TIME = "https://open.spotify.com/api/server-time"
        const val TOKEN = "https://open.spotify.com/api/token"
        const val WEB_API = "https://api.spotify.com/v1/tracks/"
        const val WEB = "https://open.spotify.com/"
        const val APP_VERSION = "1.2.95.312.gda5d7e47"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        const val PLAYLIST_HASH = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
        const val MAX_RESPONSE = 32 * 1024 * 1024
        const val MAX_TRACKS = 10_000
        val TOTP_SECRETS = listOf(
            61 to ",7/*F(\"rLJ2oxaKL^f+E1xvP@N",
            60 to "OmE{ZA.J^\":0FG\\Uz?[@WW",
            59 to "{iOFn;4}<1PFYKPV?5{%u14]M>/V0hDH"
        )
    }
}
