package com.musicvault.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread bridge to the playback service, which owns the actual options. */
internal object PlaybackOptions {
    val policy = PlaybackPolicy()
    private val repeat = MutableStateFlow(0)
    val repeatMode = repeat.asStateFlow()
    private val remaining = MutableStateFlow(0L)
    val sleepRemainingMs = remaining.asStateFlow()
    var onRepeatChanged: (() -> Unit)? = null
    var onTimerChanged: (() -> Unit)? = null

    fun restoreRepeat(mode: Int) {
        while (policy.repeatMode != mode.coerceIn(0, 2)) policy.cycleRepeat()
        publishRepeat()
    }
    fun cycleRepeat() {
        policy.cycleRepeat()
        publishRepeat()
    }
    fun trackRepeated() {
        policy.onTrackRepeated()
        publishRepeat()
    }
    private fun publishRepeat() {
        repeat.value = policy.repeatMode
        onRepeatChanged?.invoke()
    }
    fun setTimer(minutes: Int) {
        policy.setTimer(minutes, android.os.SystemClock.elapsedRealtime())
        updateRemaining()
        onTimerChanged?.invoke()
    }
    fun updateRemaining() {
        remaining.value = policy.remainingMs(android.os.SystemClock.elapsedRealtime())
    }
    fun reset() {
        onRepeatChanged = null
        onTimerChanged = null
        while (policy.repeatMode != 0) policy.cycleRepeat()
        repeat.value = 0
        policy.setTimer(0, 0L)
        remaining.value = 0L
    }
}
