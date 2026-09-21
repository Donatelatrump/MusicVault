package com.musicvault.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YouTubeCaptionParserTest {
    @Test
    fun `parses timed YouTube WebVTT and ignores music markers`() {
        val parsed = YouTubeCaptionParser.parse(
            """
            WEBVTT

            00:00:01.250 --> 00:00:02.500
            ♪ First <c>line</c> ♪

            00:00:03.000 --> 00:00:04.000
            [Music]

            00:00:05.100 --> 00:00:06.000
            Second line

            00:00:07.000 --> 00:00:08.000
            Third line

            00:00:09.000 --> 00:00:10.000
            Fourth line

            00:00:11.000 --> 00:00:12.000
            Fifth line
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(listOf(1_250L, 5_100L, 7_000L, 9_000L, 11_000L), parsed!!.synced.map { it.timeMs })
        assertEquals("First line", parsed.synced.first().text)
    }

    @Test
    fun `uses exact official-video cue time for Stressed Out`() {
        val parsed = YouTubeCaptionParser.parse(
            """
            WEBVTT

            00:00:05.145 --> 00:00:07.728
            (upbeat music)

            00:00:16.585 --> 00:00:19.587
            ♪ I wish I found some better
            sounds no one's ever heard ♪

            00:00:19.587 --> 00:00:22.352
            ♪ I wish I had a better voice ♪

            00:00:22.352 --> 00:00:24.982
            ♪ I wish I found some chords ♪

            00:00:24.982 --> 00:00:27.961
            ♪ I wish I didn't have to rhyme ♪

            00:00:27.961 --> 00:00:30.323
            ♪ I was told, when I get older ♪
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(16_585L, parsed!!.synced.first().timeMs)
        assertEquals("I wish I found some better sounds no one's ever heard", parsed.synced.first().text)
    }
}
