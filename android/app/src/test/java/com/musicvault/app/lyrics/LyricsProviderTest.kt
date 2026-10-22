package com.musicvault.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LyricsProviderTest {
    @Test
    fun `canceling exact lookup does not start fallback search`() = kotlinx.coroutines.runBlocking {
        var requests = 0
        try {
            LyricsProvider.fetch("Artist", "Title", requestBody = {
                requests++
                throw kotlinx.coroutines.CancellationException("Changed song")
            })
            org.junit.Assert.fail("Cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, requests)
        }
    }

    @Test
    fun `catalog miss searches for the same song and returns available plain lyrics`() = kotlinx.coroutines.runBlocking {
        val words = "These are the words of the requested song. They are long enough to represent a real lyric rather than a placeholder."
        val result = LyricsProvider.fetch("Artist", "Title", durationMs = 180_000, requestBody = { url ->
            when {
                "/api/get?" in url -> null
                "/api/search?q=Title+Artist" in url -> """[{"trackName":"Title","artistName":"Artist","duration":180,"plainLyrics":"$words"}]"""
                else -> throw AssertionError("Unexpected request: $url")
            }
        })
        assertEquals(words, result?.plain)
    }

    @Test
    fun `stored lyrics preserve YouTube provenance`() {
        val lyrics = Lyrics("Words from the exact video", emptyList(), origin = "youtube")
        assertEquals(lyrics, LyricsProvider.parseStoredLrc(LyricsProvider.buildLrc(lyrics)))
    }

    private fun timed(vararg times: Long) = Lyrics(
        plain = "A complete plain lyric that remains available whenever synchronization confidence is too low.",
        synced = times.mapIndexed { index, time -> LyricLine(time, "Line $index") }
    )

    @Test
    fun `stored LRC applies positive global offset`() {
        val parsed = LyricsProvider.parseStoredLrc(
            """
            [offset:+750]
            [00:01.25]First line
            [00:02.000]Second line
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(listOf(2_000L, 2_750L), parsed!!.synced.map { it.timeMs })
    }

    @Test
    fun `stored LRC applies negative offset without negative timestamps`() {
        val parsed = LyricsProvider.parseStoredLrc(
            """
            [00:00.20]First line
            [00:01.00]Second line
            [OFFSET: -500]
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(listOf(0L, 500L), parsed!!.synced.map { it.timeMs })
    }

    @Test
    fun `keeps synchronized lyrics with plausible timeline coverage`() {
        val lyrics = timed(10_000, 30_000, 60_000, 90_000, 150_000)

        assertEquals(5, LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000).synced.size)
    }

    @Test
    fun `falls back to plain lyrics when timestamps belong to another edit`() {
        val lyrics = timed(10_000, 50_000, 100_000, 190_000, 240_000)

        val trusted = LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000)

        assertEquals(0, trusted.synced.size)
        assertEquals(lyrics.plain, trusted.plain)
    }

    @Test
    fun `rejects a timeline with repeated timestamps`() {
        val lyrics = timed(10_000, 10_000, 10_000, 90_000, 150_000)

        assertEquals(0, LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000).synced.size)
    }

    @Test
    fun `rejects lines concentrated in a small part of the song`() {
        val lyrics = timed(70_000, 72_000, 74_000, 76_000, 85_000)

        assertEquals(0, LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000).synced.size)
    }

    @Test
    fun `rejects captions extending well beyond the audio`() {
        val lyrics = timed(10_000, 40_000, 80_000, 130_000, 186_000)

        assertEquals(0, LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000).synced.size)
    }

    @Test
    fun `keeps exact video timeline for short late vocal sections`() {
        val lyrics = timed(70_000, 72_000, 74_000, 76_000, 85_000)

        assertEquals(
            5,
            LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000, exactTimeline = true).synced.size
        )
    }

    @Test
    fun `repairs duplicate cues in exact video captions`() {
        val lyrics = timed(10_000, 10_000, 30_000, 60_000, 90_000, 120_000)

        assertEquals(
            listOf(10_000L, 30_000L, 60_000L, 90_000L, 120_000L),
            LyricsProvider.keepOnlyTrustedSync(lyrics, 180_000, exactTimeline = true)
                .synced.map { it.timeMs }
        )
    }
}
