package com.musicvault.app.providers

import android.content.Context
import com.musicvault.app.settings.AppPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Optional original-audio providers. Never substitute a different artist/title. */
internal object BetaAudioResolver {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    fun matches(title: String, artist: String, candidateTitle: String, candidateArtist: String): Boolean {
        fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        return title.isNotBlank() && artist.isNotBlank() && normalized(title) == normalized(candidateTitle) && normalized(artist) == normalized(candidateArtist)
    }
    private fun json(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful)
        JSONObject(it.body!!.string())
    }
    fun resolve(context: Context, title: String, artist: String, download: Boolean): StreamResolution? {
        val prefs = AppPreferences.prefs(context)
        return resolveConfigured(title, artist, download,
            prefs.getString("beta_subsonic_server", "")!!.trim().trimEnd('/'),
            prefs.getString("beta_subsonic_key", "")!!.trim(),
            prefs.getString("beta_jamendo_client", "")!!.trim())
    }
    internal fun resolveConfigured(title: String, artist: String, download: Boolean,
        server: String, key: String, client: String, request: (String) -> JSONObject = ::json): StreamResolution? {
        if (server.startsWith("https://") && key.isNotBlank()) {
            try {
                fun endpoint(method: String) = "$server/rest/$method.view".toHttpUrl().newBuilder()
                    .addQueryParameter("apiKey", key).addQueryParameter("v", "1.16.1")
                    .addQueryParameter("c", "MusicVault").addQueryParameter("f", "json")
                val songs = request(endpoint("search3").addQueryParameter("query", "$artist $title")
                    .addQueryParameter("songCount", "20").addQueryParameter("artistCount", "0")
                    .addQueryParameter("albumCount", "0").build().toString())
                    .optJSONObject("subsonic-response")?.optJSONObject("searchResult3")?.optJSONArray("song")
                for (i in 0 until (songs?.length() ?: 0)) {
                    val song = songs!!.getJSONObject(i)
                    if (song.optString("suffix").lowercase() !in setOf("flac", "wav", "aiff")) continue
                    if (!matches(title, artist, song.optString("title"), song.optString("artist"))) continue
                    val url = endpoint(if (download) "download" else "stream").addQueryParameter("id", song.getString("id"))
                    if (!download) url.addQueryParameter("format", "raw")
                    return StreamResolution(url.build().toString(), song.optString("suffix"), song.optString("contentType", "audio/flac"))
                }
            } catch (interrupted: InterruptedException) { throw interrupted } catch (_: Exception) { /* Try the next configured provider. */ }
        }
                if (client.isNotBlank()) {
            try {
                val url = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", client).addQueryParameter("format", "json")
                    .addQueryParameter("search", "$artist $title").addQueryParameter("limit", "20")
                    .addQueryParameter("audioformat", "flac").build()
                val songs = request(url.toString()).optJSONArray("results")
                for (i in 0 until (songs?.length() ?: 0)) {
                    val song = songs!!.getJSONObject(i)
                    if (!matches(title, artist, song.optString("name"), song.optString("artist_name"))) continue
                    if (download && !song.optBoolean("audiodownload_allowed")) continue
                    val audio = "https://api.jamendo.com/v3.0/tracks/file/".toHttpUrl().newBuilder()
                        .addQueryParameter("client_id", client).addQueryParameter("id", song.getString("id"))
                        .addQueryParameter("audioformat", "flac").addQueryParameter("action", if (download) "download" else "stream").build()
                    return StreamResolution(audio.toString(), "flac", "audio/flac")
                }
            } catch (interrupted: InterruptedException) { throw interrupted } catch (_: Exception) { /* Fall back to the normal source. */ }
        }
        return null
    }
}
