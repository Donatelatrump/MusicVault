package com.musicvault.app.player

import com.musicvault.app.data.Track
import org.json.JSONArray
import org.json.JSONObject

/** Physical queue and exact traversal order; remote URLs are deliberately excluded. */
data class PlaybackQueueState(
    val tracks: List<Track>, val index: Int, val positionMs: Long,
    val shuffle: Boolean, val shuffleOrder: List<Int>, val repeatMode: Int = 0
) {
    fun encode(): String = JSONObject().apply {
        put("tracks", JSONArray(tracks.map { JSONObject(PlaybackSnapshot(it, 0L).encode()) }))
        put("index", index); put("position", positionMs.coerceAtLeast(0L))
        put("shuffle", shuffle); put("order", JSONArray(shuffleOrder)); put("repeat", repeatMode)
    }.toString()
    companion object {
        const val KEY = "playback_queue_v2"
        fun decode(raw: String?): PlaybackQueueState? = runCatching {
            val json = JSONObject(raw ?: return null)
            val list = json.getJSONArray("tracks")
            val tracks = (0 until list.length()).map { PlaybackSnapshot.decode(list.getJSONObject(it).toString())?.track ?: return null }
            if (tracks.isEmpty()) return null
            val orderJson = json.optJSONArray("order") ?: JSONArray()
            val order = (0 until orderJson.length()).map { orderJson.getInt(it) }
            PlaybackQueueState(tracks, json.optInt("index").coerceIn(tracks.indices), json.optLong("position").coerceAtLeast(0L),
                json.optBoolean("shuffle"), order.takeIf { it.sorted() == tracks.indices.toList() } ?: tracks.indices.toList(),
                json.optInt("repeat").coerceIn(0, 2))
        }.getOrNull()
    }
}

/** Keeps the complete durable queue while remote successors are resolving. Main-thread only. */
internal object QueueRestoration {
    var state: PlaybackQueueState? = null
}
