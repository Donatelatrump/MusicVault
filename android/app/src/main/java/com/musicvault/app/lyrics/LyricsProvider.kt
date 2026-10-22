package com.musicvault.app.lyrics

import java.util.Locale
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** One timed line of a synced (LRC) lyric. */
data class LyricLine(val timeMs: Long, val text: String)

/** Plain and/or synced lyrics for a track. */
data class Lyrics(val plain: String?, val synced: List<LyricLine>, val origin: String? = null) {
    val isEmpty: Boolean get() = plain.isNullOrBlank() && synced.isEmpty()
}

/**
 * Lyrics via LRCLib (https://lrclib.net) — free, no API key, plain + synced LRC.
 * Cancellable network requests with a shared connection pool and bounded waits.
 */
object LyricsProvider {
    private val lrcTimestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val lrcOffset = Regex("""(?im)^\s*\[offset\s*:\s*([+-]?\d+)\s*]\s*$""")
    // Quality gates: LRCLib's exact /api/get endpoint can return degenerate
    // records (single-line troll entries, "[00:00] *Rickrolling*", instrumental
    // placeholders). Accepting them made the player show "complete" lyrics with
    // no highlight at all. Real synced lyrics have many timed lines; real plain
    // lyrics have real length.
    private const val MIN_SYNCED_LINES = 5
    private const val MIN_PLAIN_CHARS = 80

    /**
     * A search result from a substantially different edit (live, remix, radio
     * cut) can contain perfectly valid LRC timestamps that will never line up
     * with the audio being played. Keep a generous allowance for video intros
     * and outro differences, but reject clearly incompatible versions.
     */
    private fun durationIsCompatible(localMs: Long, remoteMs: Long): Boolean {
        if (localMs <= 0L || remoteMs <= 0L) return true
        val allowance = max(30_000L, localMs * 12L / 100L)
        return abs(localMs - remoteMs) <= allowance
    }

    private fun isUsable(lyrics: Lyrics): Boolean =
        lyrics.synced.size >= MIN_SYNCED_LINES || (lyrics.plain?.length ?: 0) >= MIN_PLAIN_CHARS

    private fun normalizedTokens(value: String): Set<String> = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filterTo(linkedSetOf()) { it.length > 1 }

    private fun metadataMatches(requested: String, candidate: String): Boolean {
        if (candidate.isBlank()) return true
        val expected = normalizedTokens(requested)
        val actual = normalizedTokens(candidate)
        if (expected.isEmpty() || actual.isEmpty()) return true
        val overlap = expected.count { it in actual }
        return overlap.toDouble() / minOf(expected.size, actual.size) >= 0.6
    }

    /**
     * Enables timed highlighting only when the timestamps cover a plausible
     * portion of this exact audio. This is deliberately CPU-only and O(lines):
     * it adds no request, decoding pass, or perceptible loading time.
     */
    internal fun keepOnlyTrustedSync(
        lyrics: Lyrics,
        durationMs: Long,
        exactTimeline: Boolean = false
    ): Lyrics {
        if (lyrics.synced.size < MIN_SYNCED_LINES) return lyrics.copy(synced = emptyList())
        if (durationMs <= 0L) return lyrics
        // Captions from the exact YouTube video already belong to the playing
        // audio. Repair harmless duplicate/out-of-range cues instead of applying
        // LRCLib's stricter cross-version coverage checks and losing follow mode.
        val cleaned = lyrics.synced
            .asSequence()
            .filter { it.timeMs in 0L..(durationMs + 10_000L) }
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs }
            .toList()
        if (exactTimeline) {
            return if (cleaned.size >= MIN_SYNCED_LINES) lyrics.copy(synced = cleaned)
            else lyrics.copy(
                plain = lyrics.plain ?: lyrics.synced.joinToString("\n") { it.text },
                synced = emptyList()
            )
        }
        val times = cleaned.map { it.timeMs }
        if (times.size < MIN_SYNCED_LINES) return lyrics.copy(synced = emptyList())
        val first = times.first()
        val last = times.last()
        val gaps = times.zipWithNext { before, after -> after - before }
        val span = last - first
        val maxAllowedGap = max(90_000L, durationMs * 45L / 100L)
        val plausible = first <= minOf(90_000L, durationMs * 40L / 100L) &&
            last >= durationMs * 45L / 100L &&
            last <= durationMs + 5_000L &&
            times.all { it >= 0L } &&
            gaps.all { it >= 0L } &&
            gaps.none { it > maxAllowedGap } &&
            span >= minOf(45_000L, durationMs * 25L / 100L)
        return if (plausible) lyrics.copy(synced = cleaned) else lyrics.copy(
            plain = lyrics.plain ?: lyrics.synced.joinToString("\n") { it.text },
            synced = emptyList()
        )
    }

    fun buildLrc(lyrics: Lyrics): String {
        val body = when {
            lyrics.synced.isNotEmpty() -> buildString {
                lyrics.synced.forEach { line ->
                    val totalMs = line.timeMs.coerceAtLeast(0L)
                    val minutes = totalMs / 60_000L
                    val seconds = (totalMs % 60_000L) / 1000L
                    val centiseconds = ((totalMs % 1000L) / 10L).coerceIn(0L, 99L)
                    append('[')
                    append(String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centiseconds))
                    append("] ")
                    append(line.text)
                    append('\n')
                }
            }
            !lyrics.plain.isNullOrBlank() -> lyrics.plain.trimEnd()
            else -> ""
        }
        return if (body.isNotBlank() && lyrics.origin in setOf("youtube", "lrclib"))
            "[musicvault-source:${lyrics.origin}]\n$body" else body
    }

    fun parseStoredLrc(raw: String): Lyrics? {
        val marker = Regex("(?m)^\\[musicvault-source:(youtube|lrclib)]\\r?$")
        val origin = marker.find(raw)?.groupValues?.get(1)
        val body = raw.replace(marker, "").trim()
        val synced = parseLrc(body)
        if (synced.isNotEmpty()) return Lyrics(plain = null, synced = synced, origin = origin)
        return body.takeIf { it.isNotBlank() }?.let { Lyrics(plain = it, synced = emptyList(), origin = origin) }
    }

    suspend fun fetch(
        artist: String,
        title: String,
        album: String? = null,
        durationMs: Long = 0L,
        requestBody: suspend (String) -> String? = LyricsRequest::get
    ): Lyrics? {
        val durationSeconds = (durationMs / 1000L).takeIf { it > 0 }
        // 1. Try exact match query (with exact duration filter if available)
        val exact = lyricsAttempt {
            val query = buildString {
                append("https://lrclib.net/api/get?artist_name=").append(URLEncoder.encode(artist.trim(), "UTF-8"))
                append("&track_name=").append(URLEncoder.encode(title.trim(), "UTF-8"))
                if (!album.isNullOrBlank()) append("&album_name=").append(URLEncoder.encode(album.trim(), "UTF-8"))
                if (durationSeconds != null) append("&duration=").append(durationSeconds)
            }
            requestBody(query)
        }
        val direct = exact?.let { body ->
            runCatching { keepOnlyTrustedSync(toLyrics(JSONObject(body)) ?: return@runCatching null, durationMs) }.getOrNull()
        }
        if (direct != null && isUsable(direct)) return direct

        // 2. Search fallback without strict duration constraint, to find official studio lyrics
        val searchQuery = "https://lrclib.net/api/search?q=" +
            URLEncoder.encode("${title.trim()} ${artist.trim()}".trim(), "UTF-8")
        val candidates = lyricsAttempt {
            val body = requestBody(searchQuery) ?: return@lyricsAttempt null
            val array = org.json.JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                runCatching { array.getJSONObject(i) }.getOrNull()
            }
        } ?: emptyList()

        val best = candidates
            .asSequence()
            .filter { record ->
                metadataMatches(title, record.optString("trackName")) &&
                    metadataMatches(artist, record.optString("artistName"))
            }
            .mapNotNull { record ->
                val rawLyrics = runCatching { toLyrics(record) }.getOrNull() ?: return@mapNotNull null
                val lyrics = keepOnlyTrustedSync(rawLyrics, durationMs)
                val remoteMs = record.optLong("duration", 0L) * 1000L
                lyrics to remoteMs
            }
            .filter { (lyrics, _) -> isUsable(lyrics) }
            .filter { (_, remoteMs) -> durationIsCompatible(durationMs, remoteMs) }
            .minByOrNull { (lyrics, remoteMs) ->
                val syncedPenalty = if (lyrics.synced.size >= MIN_SYNCED_LINES) 0 else 60_000
                val durationPenalty = if (durationSeconds != null && remoteMs > 0) {
                    abs(remoteMs - durationMs)
                } else 0L
                syncedPenalty + durationPenalty
            }

        // Duration is useful for choosing a candidate, but it cannot establish a
        // vocal offset: different masters frequently have different outros.  The
        // caller aligns local audio with VocalActivityDetector when it has a file.
        return best?.first
    }

    private fun toLyrics(record: JSONObject): Lyrics? = runCatching {
        val plain = record.optString("plainLyrics").takeIf { it.isNotBlank() }
        val synced = record.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let(::parseLrc) ?: emptyList()
        Lyrics(plain, synced)
    }.getOrNull()

    /** Parses LRC text: "01:23.45 line" — supports multiple timestamps per line. */
    private fun parseLrc(raw: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        // Standard LRC files may declare one global millisecond correction.
        // Applying it while parsing also covers locally stored lyrics and keeps
        // every consumer on the same corrected timeline.
        val globalOffsetMs = lrcOffset.findAll(raw).lastOrNull()
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.coerceIn(-30_000L, 30_000L) ?: 0L
        raw.lineSequence().forEach { line ->
            val matches = lrcTimestamp.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).toLong()
                }
                val timeMs = minutes * 60_000L + seconds * 1000L + fractionMs + globalOffsetMs
                lines += LyricLine(timeMs.coerceAtLeast(0L), text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

}
