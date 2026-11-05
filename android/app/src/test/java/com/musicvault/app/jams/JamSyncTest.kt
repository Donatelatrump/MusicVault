package com.musicvault.app.jams

import org.junit.Assert.*
import org.junit.Test

class JamSyncTest {
    @Test fun lateJoinCatchesUpWithServerTimeline() {
        val playing = JamPlayback("song", true, 12_000, 100_000, 1)
        assertEquals(17_000L, JamSync.targetPosition(playing, 105_000))
        assertEquals(12_000L, JamSync.targetPosition(playing, 99_000))
    }
    @Test fun pauseDoesNotAdvanceAndSeekingResetsAnchor() {
        val paused = JamPlayback("song", false, 30_000, 100_000, 2)
        assertEquals(30_000L, JamSync.targetPosition(paused, 200_000))
        val resumed = paused.copy(playing = true, effectiveAt = 200_000, revision = 3)
        assertEquals(32_000L, JamSync.targetPosition(resumed, 202_000))
    }
    @Test fun neverSeeksBeyondDurationOrBelowZero() {
        assertEquals(104_000L, JamSync.targetPosition(JamPlayback("song", true, 0, 0, 1), 200_000, 104_000))
        assertEquals(0L, JamSync.targetPosition(JamPlayback(positionMs = -1), 0))
    }
    @Test fun smallDriftIsIgnoredToAvoidRepeatedAudibleSeeks() {
        assertFalse(JamSync.needsSeek(1000, 1350))
        assertTrue(JamSync.needsSeek(1000, 1351))
        assertTrue(JamSync.needsSeek(2000, 1000))
    }
    @Test fun onlyFullRandomInvitationCodesAreAccepted() {
        assertEquals("a".repeat(32), JamSync.roomCode(" AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "))
        assertEquals("a".repeat(32), JamSync.roomCode("https://musicvault-jams-donatelatrump.web.app/jam/" + "a".repeat(32)))
        assertEquals("a".repeat(32), JamSync.roomCode("MusicVault JAMS: " + "a".repeat(32)))
        assertNull(JamSync.roomCode("123456"))
        assertNull(JamSync.roomCode("../rooms"))
    }
}
