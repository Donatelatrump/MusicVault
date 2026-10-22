package com.musicvault.app.player

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.Locale
import kotlin.math.pow

/** ID3 TXXX, MP4 freeform (----), and Vorbis/FLAC ReplayGain track tags. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object ReplayGain {
    fun gain(metadata: Metadata): Float? {
        val fields = mutableMapOf<String, String>()
        for (i in 0 until metadata.length()) {
            val pair = when (val entry = metadata[i]) {
                is TextInformationFrame -> if (entry.id in listOf("TXXX", "TXX")) entry.description to entry.values.firstOrNull() else null
                is InternalFrame -> entry.description to entry.text
                is VorbisComment -> entry.key to entry.value
                else -> null
            } ?: continue
            val key = pair.first?.trim()?.uppercase(Locale.ROOT) ?: continue
            pair.second?.let { fields[key] = it }
        }
        fun number(value: String?): Double? = value?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)
        val db = number(fields["REPLAYGAIN_TRACK_GAIN"]) ?: return null
        if (db !in -60.0..30.0) return null
        val requested = 10.0.pow(db / 20.0)
        val peak = number(fields["REPLAYGAIN_TRACK_PEAK"])?.takeIf { it > 0.0 }
        // ExoPlayer volume is attenuation-only; cap at unity and protect declared peaks.
        return minOf(requested, 1.0, peak?.let { 0.98 / it } ?: 1.0).toFloat().coerceIn(0.001f, 1f)
    }
}
