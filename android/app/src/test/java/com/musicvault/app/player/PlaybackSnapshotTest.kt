package com.musicvault.app.player

import com.musicvault.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    @Test fun remoteSongSurvivesRestartWithoutExpiredAudioUrl() {
        val track = Track("youtube:abc", "Canción", "Artista", "album", "Álbum", 180000,
            "https://expired.example/audio", false, "https://example/cover", "youtube", "abc",
            "https://www.youtube.com/watch?v=abc")
        val restored = PlaybackSnapshot.decode(PlaybackSnapshot(track, 42000).encode())!!
        assertEquals("Canción", restored.track.title)
        assertEquals("Artista", restored.track.artistName)
        assertEquals("https://example/cover", restored.track.coverUri)
        assertEquals("abc", restored.track.sourceId)
        assertEquals("https://www.youtube.com/watch?v=abc", restored.track.url)
        assertNull(restored.track.audioUri)
        assertEquals(42000L, restored.positionMs)
    }

    @Test fun downloadedSongKeepsLocalUri() {
        val restored = PlaybackSnapshot.decode(PlaybackSnapshot(
            Track("local", "Song", "Artist", "a", "Album", 90000, "content://music/1", true), 12000
        ).encode())!!
        assertEquals("content://music/1", restored.track.audioUri)
        assertTrue(restored.track.isDownloaded)
        assertEquals(12000L, restored.positionMs)
    }

    @Test fun damagedOrMissingSnapshotIsIgnored() {
        assertNull(PlaybackSnapshot.decode(null))
        assertNull(PlaybackSnapshot.decode("broken"))
        assertNull(PlaybackSnapshot.decode("{}"))
    }

    @Test fun negativePositionIsNormalized() {
        val restored = PlaybackSnapshot.decode(PlaybackSnapshot(
            Track("local", "Song", "Artist", "a", "Album", 90000), -1
        ).encode())!!
        assertEquals(0L, restored.positionMs)
    }
}
