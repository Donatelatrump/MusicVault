package com.musicvault.app.player

/** App repeat modes: off (0), one additional play (1), same track forever (2). */
class PlaybackPolicy {
    var repeatMode: Int = 0
        private set
    var timerDeadlineMs: Long = 0L
        private set

    fun cycleRepeat() { repeatMode = (repeatMode + 1) % 3 }
    fun onTrackRepeated() { if (repeatMode == 1) repeatMode = 0 }
    fun setTimer(minutes: Int, nowMs: Long) {
        require(minutes in 0..60)
        timerDeadlineMs = if (minutes == 0) 0L else nowMs + minutes * 60_000L
    }
    fun remainingMs(nowMs: Long): Long = (timerDeadlineMs - nowMs).coerceAtLeast(0L)
    fun consumeTimerExpiry(nowMs: Long): Boolean {
        if (timerDeadlineMs == 0L || nowMs < timerDeadlineMs) return false
        timerDeadlineMs = 0L
        return true
    }
}
