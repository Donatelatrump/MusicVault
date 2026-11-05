package com.musicvault.app.jams

import org.junit.Assert.*
import org.junit.Test

class JamPlaybackPolicyTest {
    @Test fun oldSnapshotsDoNotUndoRapidPauseAndResume() {
        val pending = JamPendingPlayback()
        val pause = JamPlayback(playing = false, commandId = "pause", revision = 1)
        val resume = pause.copy(playing = true, commandId = "resume", revision = 2)
        pending.stage(pause); pending.stage(resume)
        assertEquals(resume, pending.receive(JamPlayback()))
        assertEquals(resume, pending.receive(pause))
        assertEquals(resume, pending.receive(resume))
        assertEquals(resume, pending.receive(pause))
    }
    @Test fun failedControlRestoresLastAcknowledgedState() {
        val pending = JamPendingPlayback()
        val server = JamPlayback(playing = true, revision = 4)
        pending.receive(server)
        pending.stage(server.copy(playing = false, commandId = "pause"))
        assertEquals(server, pending.fail("pause"))
    }
    @Test fun preparationHoldsSharedTimeline() {
        assertEquals(4000L, JamSync.targetPosition(JamPlayback(playing = true, positionMs = 4000, effectiveAt = 10, buffering = true), 50000))
    }
    @Test fun bufferAndSmallDriftNeverTriggerRepeatedSeeks() {
        assertEquals(JamDriftPolicy.Correction.None, JamDriftPolicy.correction(0, 5000, false, true, 5000))
        assertTrue(JamDriftPolicy.correction(1000, 1500, true, true, 5000) is JamDriftPolicy.Correction.Speed)
        assertTrue(JamDriftPolicy.correction(0, 5000, true, true, 100) is JamDriftPolicy.Correction.Speed)
        assertEquals(JamDriftPolicy.Correction.Seek(5000), JamDriftPolicy.correction(0, 5000, true, true, 2000))
    }
    @Test fun clockAdvancesFromMonotonicTimeOnly() {
        val clock = JamServerClock()
        clock.anchor(100000, 500)
        assertEquals(101000L, clock.now(1500))
        assertEquals(100000L, clock.now(100))
    }
}
