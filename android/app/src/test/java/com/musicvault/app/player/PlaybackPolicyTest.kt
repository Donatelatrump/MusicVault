package com.musicvault.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun repeatOnceIsConsumedAtFirstRestart() {
        val policy = PlaybackPolicy()
        policy.cycleRepeat()
        assertEquals(1, policy.repeatMode)
        policy.onTrackRepeated()
        assertEquals(0, policy.repeatMode)
        policy.onTrackRepeated()
        assertEquals(0, policy.repeatMode)
    }
    @Test fun repeatForeverSurvivesRestartsUntilManuallyDisabled() {
        val policy = PlaybackPolicy()
        policy.cycleRepeat()
        policy.cycleRepeat()
        repeat(3) { policy.onTrackRepeated(); assertEquals(2, policy.repeatMode) }
        policy.cycleRepeat()
        assertEquals(0, policy.repeatMode)
    }
    @Test fun timerExpiresAtDeadlineAndOnlyOnce() {
        val policy = PlaybackPolicy()
        policy.setTimer(5, 1000L)
        assertFalse(policy.consumeTimerExpiry(300999L))
        assertTrue(policy.consumeTimerExpiry(301000L))
        assertFalse(policy.consumeTimerExpiry(302000L))
    }
    @Test fun replacingAndCancellingTimerDiscardsOldDeadline() {
        val policy = PlaybackPolicy()
        policy.setTimer(5, 0L)
        policy.setTimer(10, 1000L)
        assertFalse(policy.consumeTimerExpiry(300000L))
        policy.setTimer(0, 300000L)
        assertFalse(policy.consumeTimerExpiry(900000L))
    }
}
