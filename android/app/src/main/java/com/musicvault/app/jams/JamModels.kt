package com.musicvault.app.jams

import com.musicvault.app.data.Track
import kotlin.math.abs

internal data class JamSong(val key: String, val videoId: String, val title: String, val artist: String, val durationMs: Long, val sourceTrackId: String? = null) {
    fun track() = Track("youtube:jam:$videoId", title, artist, "jam", "JAMS", durationMs,
        source = "youtube", sourceId = videoId, url = "https://www.youtube.com/watch?v=$videoId")
}
internal data class JamPlayback(val songKey: String = "", val playing: Boolean = false,
    val positionMs: Long = 0, val effectiveAt: Long = 0, val revision: Long = 0,
    val buffering: Boolean = false, val commandId: String = "", val action: String = "")
internal data class JamState(val installationUid: String, val uid: String? = null, val roomCode: String? = null,
    val shortCode: String? = null, val readySongs: Map<String, String> = emptyMap(),
    val hostUid: String? = null, val leaderUid: String? = null, val connected: Boolean = false, val hostOnline: Boolean = false,
    val busy: Boolean = false, val members: List<String> = emptyList(), val audioError: Boolean = false, val songs: List<JamSong> = emptyList(), val memberCount: Int = 0,
    val playback: JamPlayback = JamPlayback(), val message: String? = null) {
    val active get() = roomCode != null
    val isHost get() = uid != null && hostUid == uid
    val isLeader get() = uid != null && leaderUid == uid
    val currentSong get() = songs.firstOrNull { it.key == playback.songKey }
    val allReady get() = members.isNotEmpty() && members.all { readySongs[it] == playback.songKey }
}
internal object JamSync {
    fun targetPosition(playback: JamPlayback, serverNow: Long, durationMs: Long = 0): Long {
        val elapsed = if (playback.playing && !playback.buffering) (serverNow - playback.effectiveAt).coerceAtLeast(0) else 0
        val target = (playback.positionMs + elapsed).coerceAtLeast(0)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }
    fun needsSeek(current: Long, target: Long): Boolean = abs(current - target) > 350L
    fun roomCode(value: String): String? = JamInvitation.roomCode(value)
}

internal enum class JamTrackStatus { LOADING, ADDED, FAILED }
internal data class JamTrackFeedback(val status: JamTrackStatus, val token: Long)
