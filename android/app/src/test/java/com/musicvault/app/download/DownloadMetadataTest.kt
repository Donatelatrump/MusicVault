package com.musicvault.app.download

import com.musicvault.app.data.TrackEntity
import org.junit.Assert.*
import org.junit.Test

class DownloadMetadataTest {
    private fun track() = TrackEntity("id", "Song", "Artist", "album", "Album", 123000, null, false, "cover", "deezer", "42")

    @Test fun completionPreservesExistingMetadataWhenRequestIsIncomplete() {
        val existing = track()
        val request = existing.copy(albumId = "unknown", albumTitle = "Álbum desconocido", durationMs = 0, coverUri = null)
        val saved = DownloadMetadata.complete(existing, request, "content://audio")
        assertEquals("album", saved.albumId)
        assertEquals("cover", saved.coverUri)
        assertEquals(123000L, saved.durationMs)
        assertTrue(saved.isDownloaded)
        assertEquals("content://audio", saved.audioUri)
    }

    @Test fun completeSnapshotWorksWithoutAnActivityOrExistingRow() {
        val saved = DownloadMetadata.complete(null, track(), "file://song")
        assertEquals(track().copy(audioUri = "file://song", isDownloaded = true), saved)
    }

    @Test fun qualityPolicyResolvesDownloadsIndependentlyFromStreaming() {
        assertFalse(DownloadQuality.BEST.reuseStreamingCache)
        assertFalse(DownloadQuality.SAVER.reuseStreamingCache)
        assertEquals(DownloadQuality.BEST, DownloadQuality.parse("invalid"))
    }
}
