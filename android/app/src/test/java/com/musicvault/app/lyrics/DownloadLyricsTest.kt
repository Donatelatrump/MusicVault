package com.musicvault.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadLyricsTest {
    @Test
    fun `remote audio is never decoded to wait for lyrics`() {
        assertFalse(VocalActivityDetector.isLocalReference("https://cdn.example/audio.m4a"))
        assertFalse(VocalActivityDetector.isLocalReference("http://cdn.example/audio.m4a"))
        org.junit.Assert.assertTrue(VocalActivityDetector.isLocalReference("content://media/audio/123"))
        org.junit.Assert.assertTrue(VocalActivityDetector.isLocalReference("/storage/Music/song.m4a"))
    }

    @Test
    fun `catalog tracks reject saved YouTube captions`() {
        assertFalse(DownloadLyrics.canUseStoredLyrics("Spotify", Lyrics("Video", emptyList(), "youtube")))
        assertFalse(DownloadLyrics.canUseStoredLyrics("Deezer", Lyrics("Video", emptyList(), "youtube")))
    }

    @Test
    fun `other sources use only YouTube even when captions are unavailable`() {
        for (source in listOf("youtube", "local", null)) {
            var catalogCalls = 0
            val result = DownloadLyrics.fetchFromSource(source,
                youtube = { null },
                catalog = { catalogCalls++; Lyrics("Studio lyrics", emptyList()) }
            )
            assertEquals(null, result)
            assertEquals(0, catalogCalls)
        }
    }

    @Test
    fun `Deezer and Spotify tracks use catalog lyrics`() {
        val expected = Lyrics("Deezer lyrics", emptyList())
        for (source in listOf("Deezer", "spotify", "SPOTIFY")) {
            assertEquals(expected, DownloadLyrics.fetchFromSource(source,
                youtube = { throw AssertionError("Unexpected YouTube request") },
                catalog = { expected }
            ))
        }
    }
}
