package com.musicvault.app.jams

import kotlin.math.abs

/** UI intent remains authoritative locally until acknowledged; old snapshots cannot undo a tap. */
internal class JamPendingPlayback {
    private val pending = linkedMapOf<String, JamPlayback>()
    var server = JamPlayback()
        private set
    val current get() = pending.values.lastOrNull() ?: server
    fun stage(playback: JamPlayback) { pending[playback.commandId] = playback }
    fun receive(playback: JamPlayback): JamPlayback {
        if (playback.commandId in pending) {
            val consumed = pending.keys.takeWhile { it != playback.commandId } + playback.commandId
            consumed.forEach(pending::remove)
        }
        if (playback.revision >= server.revision) server = playback
        return current
    }
    fun fail(commandId: String): JamPlayback { pending.remove(commandId); return current }
    fun reset() { pending.clear(); server = JamPlayback() }
}

/** Use a monotonic local clock between Firebase time samples. */
internal class JamServerClock {
    private var epochMs = 0L
    private var elapsedMs = 0L
    var ready = false
        private set
    fun anchor(serverEpochMs: Long, monotonicMs: Long) { epochMs = serverEpochMs; elapsedMs = monotonicMs; ready = true }
    fun now(monotonicMs: Long) = epochMs + (monotonicMs - elapsedMs).coerceAtLeast(0)
}

internal object JamDriftPolicy {
    sealed interface Correction {
        data object None : Correction
        data class Seek(val positionMs: Long) : Correction
        data class Speed(val value: Float) : Correction
    }
    fun correction(current: Long, target: Long, ready: Boolean, playing: Boolean,
        sinceSeekMs: Long, forceSeek: Boolean = false): Correction {
        if (!ready) return Correction.None // Never discard buffers by seeking repeatedly during loading.
        val error = target - current
        if (!playing) return if (abs(error) > 50 && sinceSeekMs >= 250) Correction.Seek(target) else Correction.Speed(1f)
        if (forceSeek || abs(error) > 1200 && sinceSeekMs >= 2000) return Correction.Seek(target)
        return Correction.Speed(if (abs(error) <= 80) 1f else (1f + error / 10_000f).coerceIn(.97f, 1.03f))
    }
}
