package com.musicvault.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtTest {
    @Test fun upgradesSavedYoutubeThumbnail() {
        assertEquals("https://i.ytimg.com/vi/D6YZ3a-m0HE/maxresdefault.jpg",
            CoverArt.highResolution("https://i.ytimg.com/vi/D6YZ3a-m0HE/hqdefault.jpg?sqp=small"))
    }
    @Test fun upgradesDeezerThumbnail() {
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/abc/1200x1200-000000-80-0-0.jpg",
            CoverArt.highResolution("https://e-cdns-images.dzcdn.net/images/cover/abc/250x250-000000-80-0-0.jpg"))
    }
    @Test fun preservesLocalAndUnknownArtwork() {
        for (url in listOf("content://media/123", "file:///music/cover.jpg", "https://example.com/250x250-cover.jpg")) {
            assertEquals(url, CoverArt.highResolution(url))
        }
    }
}
