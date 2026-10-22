package com.musicvault.app.lyrics

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Conservative local VAD used only to correct lyrics that start too early.
 *
 * It decodes the beginning of the *actual* downloaded audio and finds the
 * first sustained voiced/acoustic segment.  We never move lyrics earlier:
 * an uncertain result is deliberately ignored rather than making a song less
 * synchronized.  It is intentionally local (no audio is uploaded).
 */
object VocalActivityDetector {
    private const val MAX_ANALYSIS_MS = 45_000L
    private const val MIN_DELAY_MS = 350L
    private const val MAX_DELAY_MS = 20_000L

    internal fun isLocalReference(reference: String): Boolean =
        reference.startsWith("/") || reference.startsWith("content://") || reference.startsWith("file://")

    fun align(context: Context, audioReference: String?, lyrics: Lyrics): Lyrics {
        if (lyrics.synced.isEmpty() || audioReference.isNullOrBlank() || !isLocalReference(audioReference)) return lyrics
        val onset = detectOnset(context, audioReference) ?: return lyrics
        val firstLine = lyrics.synced.first().timeMs
        val delay = onset - firstLine
        // A later detected onset means the LRC was early.  Do not try to fix
        // late lyrics: an instrumental intro can otherwise yield a false VAD.
        if (delay !in MIN_DELAY_MS..MAX_DELAY_MS) return lyrics
        return lyrics.copy(synced = lyrics.synced.map { it.copy(timeMs = it.timeMs + delay) })
    }

    private fun detectOnset(context: Context, reference: String): Long? = runCatching {
        val deadline = System.nanoTime() + 1_500_000_000L
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(reference)
            if (uri.scheme == "content" || uri.scheme == "file") extractor.setDataSource(context, uri, null)
            else extractor.setDataSource(reference)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var inputEnded = false
                var outputEnded = false
                var noiseFloor = 0.0
                var voicedSince = -1L
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return null
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex >= 0) {
                        val timeMs = info.presentationTimeUs / 1_000L
                        if (timeMs > MAX_ANALYSIS_MS) break
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            val rms = buffer?.let { rms16Bit(it, info.offset, info.size) } ?: 0.0
                            // Learn the opening noise floor, then require a
                            // sustained signal well above it (three 20ms-ish
                            // decoder frames) to reject clicks and silence.
                            if (timeMs < 1_000L) noiseFloor = maxOf(noiseFloor, rms)
                            val active = rms > maxOf(0.012, noiseFloor * 2.8)
                            if (active) {
                                if (voicedSince < 0) voicedSince = timeMs
                                if (timeMs - voicedSince >= 80L) return voicedSince
                            } else voicedSince = -1L
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally { extractor.release() }
        null
    }.getOrNull()

    private fun rms16Bit(source: java.nio.ByteBuffer, offset: Int, size: Int): Double {
        val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(offset)
        buffer.limit(offset + size)
        var sum = 0.0
        var count = 0
        while (buffer.remaining() >= 2) {
            val sample = buffer.short / 32768.0
            sum += sample * sample
            count++
        }
        return if (count == 0) 0.0 else kotlin.math.sqrt(sum / count)
    }
}
