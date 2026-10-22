package com.musicvault.app.player

import com.musicvault.app.data.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueStateTest {
    private val local = Track("local", "Local", "Artist", "a", "Album", 90000, "content://music/1", true)
    private val remote = Track("remote", "Remote", "Artist", "a", "Album", 120000, "https://expired/audio", false,
        source = "youtube", sourceId = "abcdefghijk", url = "https://www.youtube.com/watch?v=abcdefghijk")
    @Test fun mixedQueuePreservesIndexTraversalAndLocalUriButDropsExpiredStream() {
        val original = PlaybackQueueState(listOf(local, remote), 1, 42000, true, listOf(1, 0), 2)
        val restored = PlaybackQueueState.decode(original.encode())!!
        assertEquals(listOf("local", "remote"), restored.tracks.map { it.id })
        assertEquals("content://music/1", restored.tracks[0].audioUri)
        assertNull(restored.tracks[1].audioUri)
        assertEquals(listOf(1, 0), restored.shuffleOrder)
        assertEquals(1, restored.index)
        assertEquals(42000L, restored.positionMs)
        assertEquals(2, restored.repeatMode)
    }
    @Test fun corruptTraversalIsNormalizedWithoutDroppingSongs() {
        val json = JSONObject(PlaybackQueueState(listOf(local, remote), 20, -1, true, listOf(0, 0), 99).encode())
        val restored = PlaybackQueueState.decode(json.toString())!!
        assertEquals(listOf(0, 1), restored.shuffleOrder)
        assertEquals(1, restored.index)
        assertEquals(0L, restored.positionMs)
        assertEquals(2, restored.repeatMode)
        assertNull(PlaybackQueueState.decode("broken"))
    }
}
