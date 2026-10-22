package com.musicvault.app.player

import com.musicvault.app.data.Track
import org.json.JSONObject

/** Durable song identity; signed streaming URLs must be resolved again on Play. */
data class PlaybackSnapshot(val track: Track, val positionMs: Long) {
    fun encode(): String = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artistName)
        put("albumId", track.albumId)
        put("albumTitle", track.albumTitle)
        put("duration", track.durationMs)
        put("audioUri", if (track.isDownloaded) track.audioUri else null)
        put("downloaded", track.isDownloaded)
        put("cover", track.coverUri)
        put("source", track.source)
        put("sourceId", track.sourceId)
        put("url", track.url)
        put("position", positionMs.coerceAtLeast(0L))
    }.toString()

    companion object {
        const val KEY = "last_playback_snapshot"
        fun decode(value: String?): PlaybackSnapshot? = runCatching {
            val json = JSONObject(value ?: return null)
            fun optional(key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            val id = json.getString("id").takeIf { it.isNotBlank() } ?: return null
            PlaybackSnapshot(Track(
                id = id,
                title = json.getString("title"),
                artistName = json.getString("artist"),
                albumId = json.optString("albumId", "unknown"),
                albumTitle = json.optString("albumTitle", ""),
                durationMs = json.optLong("duration").coerceAtLeast(0L),
                audioUri = if (json.optBoolean("downloaded")) optional("audioUri") else null,
                isDownloaded = json.optBoolean("downloaded"),
                coverUri = optional("cover"), source = optional("source"),
                sourceId = optional("sourceId"), url = optional("url")
            ), json.optLong("position").coerceAtLeast(0L))
        }.getOrNull()
    }
}
