package com.musicvault.app.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer

/**
 * Unified wrapper around the Android audio effects stack used by MusicVault:
 *
 *   - [Equalizer] (multi-band gain, native)
 *   - [LoudnessEnhancer] (master gain up to ~30 dB for volume boost)
 *   - [BassBoost] (single-purpose low-frequency boost, separate audio effect)
 *   - [Virtualizer] (stereo widening, headphones)
 *
 * Each effect is created lazily against the same audio session id. Effects that
 * the device does not support (some Xiaomi/HyperOS builds disable BassBoost or
 * Virtualizer) fail to construct; the rest still work and the UI hides the
 * missing one.
 *
 * The intent is to keep the rest of the app free of media3 detail: callers only
 * touch the band/listener/gain API exposed below.
 */
class AudioEffects {
    data class Band(
        val index: Int,
        val centerFrequencyHz: Int,
        val minLevel: Short,
        val maxLevel: Short,
        val level: Short
    )

    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var sessionId: Int = 0
    private var loudnessGainMb: Int = 0

    val isEqualizerAvailable: Boolean get() = equalizer != null
    val isLoudnessAvailable: Boolean get() = loudness != null
    val isBassBoostAvailable: Boolean get() = bassBoost != null
    val isVirtualizerAvailable: Boolean get() = virtualizer != null

    val equalizerBandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0

    fun attach(audioSessionId: Int, enabled: Boolean = false): Boolean {
        if (audioSessionId <= 0) return false
        if (sessionId == audioSessionId && equalizer != null) return true
        release()
        sessionId = audioSessionId
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
            ?.also { it.enabled = enabled }
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            ?.also { it.enabled = enabled; it.setTargetGain(loudnessGainMb) }
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            ?.also { it.enabled = enabled }
        virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
            ?.also { it.enabled = enabled }
        return equalizer != null
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
        val eq = equalizer ?: return
        if (index in 0 until eq.numberOfBands.toInt()) {
            val range = eq.bandLevelRange
            eq.setBandLevel(index.toShort(), level.coerceIn(range[0], range[1]))
        }
    }

    fun setEnabled(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
        runCatching { loudness?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
        runCatching { virtualizer?.enabled = enabled }
    }

    /**
     * Sets LoudnessEnhancer gain in millibels (1000 = 1 dB). Range accepted by
     * Android is typically 0..3000 (0..30 dB); values outside the supported
     * range are clamped by the platform.
     */
    fun setLoudnessGain(gainMb: Int) {
        loudnessGainMb = gainMb.coerceIn(0, 3000)
        runCatching { loudness?.setTargetGain(loudnessGainMb) }
    }

    /**
     * BassBoost strength in the platform range (0..1000). Some devices cap the
     * effective range at 0..500 or refuse non-zero values.
     */
    fun setBassBoostStrength(strength: Int) {
        val bb = bassBoost ?: return
        runCatching { bb.setStrength(strength.coerceIn(0, 1000).toShort()) }
    }

    /**
     * Virtualizer strength in the platform range (0..1000).
     */
    fun setVirtualizerStrength(strength: Int) {
        val v = virtualizer ?: return
        runCatching { v.setStrength(strength.coerceIn(0, 1000).toShort()) }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        loudness = null
        bassBoost = null
        virtualizer = null
        sessionId = 0
    }
}