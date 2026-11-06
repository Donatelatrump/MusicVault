package com.musicvault.app.providers

import org.junit.Assert.*
import org.junit.Test

class BetaAudioResolverTest {
    @Test fun matchesOnlyTheSameTitleAndArtist() {
        assertTrue(BetaAudioResolver.matches("Canción", "Artista", "Cancion", "ARTISTA"))
        assertFalse(BetaAudioResolver.matches("Song", "Artist", "Song live", "Artist"))
        assertFalse(BetaAudioResolver.matches("Song", "Artist", "Song", "Cover band"))
        assertFalse(BetaAudioResolver.matches("", "Artist", "", "Artist"))
    }

    @Test fun subsonicStreamsOriginalAndDownloadsOriginal() {
        val fixture = org.json.JSONObject("""{"subsonic-response":{"searchResult3":{"song":[{"id":"42","title":"Song","artist":"Artist","suffix":"flac","contentType":"audio/flac"}]}}}""")
        for (download in listOf(false, true)) {
            val result = BetaAudioResolver.resolveConfigured("Song", "Artist", download, "https://music.example", "secret", "") { fixture }!!
            assertEquals("flac", result.extension)
            assertTrue(result.url.contains(if (download) "download.view" else "stream.view"))
            assertTrue(result.url.contains("apiKey=secret"))
            if (!download) assertTrue(result.url.contains("format=raw"))
        }
    }
    @Test fun jamendoDisallowedDownloadIsNeverReturned() {
        val fixture = org.json.JSONObject("""{"results":[{"id":"42","name":"Song","artist_name":"Artist","audiodownload_allowed":false}]}""")
        assertNull(BetaAudioResolver.resolveConfigured("Song", "Artist", true, "", "", "client") { fixture })
        val stream = BetaAudioResolver.resolveConfigured("Song", "Artist", false, "", "", "client") { fixture }!!
        assertTrue(stream.url.contains("audioformat=flac"))
        assertTrue(stream.url.contains("action=stream"))
    }
    @Test fun failuresAndMissingCredentialsFallBack() {
        assertNull(BetaAudioResolver.resolveConfigured("Song", "Artist", false, "", "", "" ) { error("Must not connect") })
        assertNull(BetaAudioResolver.resolveConfigured("Song", "Artist", false, "https://music.example", "key", "client") { error("Unavailable") })
    }
}
