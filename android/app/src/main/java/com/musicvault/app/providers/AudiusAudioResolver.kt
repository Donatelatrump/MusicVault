package com.musicvault.app.providers

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Anonymous access only. Beta accepts original lossless files, never MP3 relabelled as HiFi. */
internal object AudiusAudioResolver {
    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
    data class Candidate(val id: String, val extension: String)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun candidates(results: JSONArray, title: String, artist: String): List<Candidate> {
        if (normalize(title).isBlank() || normalize(artist).isBlank()) return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val user = row.optJSONObject("user") ?: return@mapNotNull null
            if (normalize(row.optString("title")) != normalize(title) || normalize(user.optString("name")) != normalize(artist)) return@mapNotNull null
            if (!row.optBoolean("is_downloadable") || !row.optBoolean("is_original_available")) return@mapNotNull null
            if (row.opt("download_conditions") is JSONObject || row.opt("stream_conditions") is JSONObject || row.optBoolean("is_delete")) return@mapNotNull null
            if (row.optJSONObject("access")?.optBoolean("download", true) == false) return@mapNotNull null
            val ext = row.optString("orig_filename").substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in setOf("flac", "wav", "aiff")) return@mapNotNull null
            val id = row.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9]+"))) return@mapNotNull null
            Candidate(id, ext)
        }.distinctBy { it.id }
    }

    fun resolve(title: String, artist: String): StreamResolution? {
        val url = "https://api.audius.co/v1/tracks/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", "$artist $title").addQueryParameter("limit", "10")
            .addQueryParameter("only_downloadable", "true")
            .addQueryParameter("app_name", "MusicVault").build()
        val results = http.newCall(Request.Builder().url(url).header("User-Agent", "MusicVault/Android").build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body?.string() ?: return null).optJSONArray("data") ?: return null
        }
        for (candidate in candidates(results, title, artist).take(2)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val audioUrl = "https://api.audius.co/v1/tracks/${candidate.id}/download?app_name=MusicVault"
            // Validate the actual public audio response, not just the metadata or filename.
            http.newCall(Request.Builder().url(audioUrl).header("User-Agent", "MusicVault/Android")
                .header("Range", "bytes=0-63").build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                val input = response.body?.byteStream() ?: return@use
                val header = ByteArray(64)
                var count = 0
                while (count < header.size) {
                    val read = input.read(header, count, header.size - count)
                    if (read < 0) break
                    count += read
                }
                val ext = sniff(header.copyOf(count)) ?: return@use
                val mime = when (ext) { "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "audio/aiff" }
                return StreamResolution(response.request.url.toString(), ext, mime)
            }
        }
        return null
    }

    fun sniff(bytes: ByteArray): String? {
        fun marker(start: Int, text: String) = bytes.size >= start + text.length && text.indices.all { bytes[start + it] == text[it].code.toByte() }
        return when {
            marker(0, "fLaC") -> "flac"
            marker(0, "RIFF") && marker(8, "WAVE") && pcmWav(bytes) -> "wav"
            marker(0, "FORM") && marker(8, "AIFF") -> "aiff"
            else -> null
        }
    }
    private fun pcmWav(bytes: ByteArray): Boolean {
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        var offset = 12
        while (offset + 10 <= bytes.size) {
            val size = (0..3).fold(0L) { total, i -> total or ((bytes[offset + 4 + i].toLong() and 255) shl (8 * i)) }
            if (String(bytes, offset, 4, Charsets.US_ASCII) == "fmt ") {
                val format = u16(offset + 8)
                if (size < 16) return false
                return format in setOf(1, 3) || (format == 65534 && size >= 40 && offset + 48 <= bytes.size && u16(offset + 32) in setOf(1, 3))
            }
            val next = offset.toLong() + 8 + size + size % 2
            if (next > bytes.size || next <= offset) return false
            offset = next.toInt()
        }
        return false
    }

}
