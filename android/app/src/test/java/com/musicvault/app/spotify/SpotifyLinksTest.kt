package com.musicvault.app.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyLinksTest {
    @Test
    fun parsesTrackPlaylistAndRegionalLinks() {
        assertEquals(
            SpotifyLink(SpotifyLinkKind.TRACK, "4uLU6hMCjMI75M1A2tKUQC"),
            SpotifyLinks.parse("https://open.spotify.com/intl-es/track/4uLU6hMCjMI75M1A2tKUQC?si=test")
        )
        assertEquals(
            SpotifyLink(SpotifyLinkKind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M"),
            SpotifyLinks.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        )
    }

    @Test
    fun findsSupportedLinkInsideClipboardText() {
        assertEquals(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            SpotifyLinks.findInText(
                "Escucha esto: https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=test)."
            )?.canonicalUrl
        )
        assertNull(SpotifyLinks.findInText("https://open.spotify.com/album/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(SpotifyLinks.findInText("https://example.com/track/4uLU6hMCjMI75M1A2tKUQC"))
    }
}
