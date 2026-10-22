package com.musicvault.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Local decoded RMS measurement, attenuated to -18 dBFS. Never boosts or clips audio.
 * This is RMS leveling, not an EBU R128/LUFS or ReplayGain certification. */
internal object LocalLoudness {
    fun gain(context: Context, reference: String): Float? = runCatching {
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(reference)
            if (uri.scheme in listOf("file", "content")) extractor.setDataSource(context, uri, null)
            else if (uri.scheme == null) extractor.setDataSource(reference) else return null
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                codec.configure(format, null, null, 0); codec.start()
                var inputEnded = false
                var samples = 0L
                var squares = 0.0
                val info = MediaCodec.BufferInfo()
                // Bound work, examine a representative minute beginning 10 s in.
                extractor.seekTo(10_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val deadline = System.nanoTime() + 8_000_000_000L
                while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(5_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                            else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 5_000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) return null
                    if (out >= 0) {
                        codec.getOutputBuffer(out)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)?.let { buffer ->
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            while (buffer.remaining() >= 2) { val sample = buffer.short / 32768.0; squares += sample * sample; samples++ }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.presentationTimeUs >= 70_000_000L || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (samples < 16_000L) null else {
                    val rms = sqrt(squares / samples)
                    if (rms <= 0.0001) 1f else (0.12589254 / rms).toFloat().coerceIn(0.1f, 1f)
                }
            } finally { runCatching { codec.stop() }; codec.release() }
        } finally { extractor.release() }
    }.getOrNull()
}
