package com.musicvault.app.jams

import org.junit.Assert.*
import org.junit.Test

class JamQueueNavigationTest {
    private val songs = listOf("a", "b", "c").map { JamSong(it, "QTzZqdJ-oXw", it, "Artist", 1000) }
    @Test fun navigationFollowsSharedQueueRatherThanLocalSingleTrack() {
        val first = JamState("installation", songs = songs, playback = JamPlayback(songKey = "a"))
        assertTrue(first.canNext)
        assertFalse(first.canPrevious)
        val last = first.copy(playback = JamPlayback(songKey = "c"))
        assertFalse(last.canNext)
        assertTrue(last.canPrevious)
        assertTrue(first.copy(playback = JamPlayback()).canNext)
        assertFalse(JamState("installation").canNext)
    }
    @Test fun deletionSelectsSuccessorOrStopsWhenQueueBecomesEmpty() {
        val state = JamState("installation", songs = songs)
        assertEquals("b", state.songAfterRemoval("a")?.key)
        assertEquals("a", state.songAfterRemoval("c")?.key)
        assertNull(state.copy(songs = songs.take(1)).songAfterRemoval("a"))
    }
}
