package com.musicvault.app.player

import android.media.audiofx.Equalizer

/** Lightweight wrapper around Android's native Equalizer effect. */
class NativeEqualizer {
    data class Band(
        val index: Int,
        val centerFrequencyHz: Int,
        val minLevel: Short,
        val maxLevel: Short,
        val level: Short
    )

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    val isAvailable: Boolean get() = equalizer != null
    val bandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0

    /**
     * Creates the effect for the given audio session. The effect is created
     * DISABLED unless [enabled] is true: force-enabling on every attach (e.g.
     * when the activity is recreated while music plays) slams an active audio
     * effect onto a live session, which some devices — Xiaomi/HyperOS in
     * particular — reward with silent output until the track is recreated.
     */
    fun attach(audioSessionId: Int, enabled: Boolean = false): Boolean {
        if (audioSessionId <= 0) return false
        if (sessionId == audioSessionId && equalizer != null) return true
        release()
        return runCatching {
            equalizer = Equalizer(0, audioSessionId).also { it.enabled = enabled }
            sessionId = audioSessionId
        }.isSuccess
    }

    fun bands(): List<Band> {
        val eq = equalizer ?: return emptyList()
        val range = eq.bandLevelRange
        return (0 until eq.numberOfBands.toInt()).map { index ->
            val frequency = eq.getCenterFreq(index.toShort()) / 1000
            Band(index, frequency, range[0], range[1], eq.getBandLevel(index.toShort()))
        }
    }

    fun setBandLevel(index: Int, level: Short) {
        equalizer?.let { eq ->
            if (index in 0 until eq.numberOfBands.toInt()) {
                val range = eq.bandLevelRange
                eq.setBandLevel(index.toShort(), level.coerceIn(range[0], range[1]))
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        sessionId = 0
    }
}
