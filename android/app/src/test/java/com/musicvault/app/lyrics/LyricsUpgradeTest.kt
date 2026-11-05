package com.musicvault.app.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsUpgradeTest {
    @Test fun acceptsImportedLyricsAndCatalogFallbackForExistingYoutubeSongs() {
        assertTrue(DownloadLyrics.canUseStoredLyrics("youtube", Lyrics("text", emptyList(), "lrclib")))
        assertTrue(DownloadLyrics.canUseStoredLyrics("local", Lyrics("text", emptyList())))
        assertTrue(DownloadLyrics.canUseStoredLyrics("Spotify", Lyrics("text", emptyList(), "user")))
    }
    @Test fun userImportPreservesOriginAndTimestamps() {
        val original = Lyrics(null, listOf(LyricLine(1500, "Line")), "user")
        val stored = LyricsProvider.parseStoredLrc(LyricsProvider.buildLrc(original))!!
        assertEquals("user", stored.origin)
        assertEquals(original.synced, stored.synced)
    }
    @Test fun instrumentalSurvivesStorageWithoutBecomingMissing() {
        val stored = LyricsProvider.parseStoredLrc(LyricsProvider.buildLrc(Lyrics(null, emptyList(), "lrclib", true)))!!
        assertTrue(stored.instrumental)
        assertFalse(stored.isEmpty)
    }
    @Test fun successfulYoutubeCaptionsAvoidCatalogRequest() {
        val expected = Lyrics("Caption", emptyList(), "youtube")
        assertEquals(expected, DownloadLyrics.fetchFromSource("youtube", { expected }, { error("Unexpected fallback") }))
    }
}
