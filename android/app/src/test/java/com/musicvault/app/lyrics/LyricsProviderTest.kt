package com.musicvault.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LyricsProviderTest {
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
}
