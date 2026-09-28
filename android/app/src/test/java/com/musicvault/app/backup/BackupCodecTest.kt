package com.musicvault.app.backup

import com.musicvault.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun fixture() = LibraryBackup(
        listOf(TrackEntity("track", "Canción", "Autor", "album", "Álbum", 200000,
            "content://private/song", true, "file:///private/cover", "YouTube", "abcdefghijk")),
        listOf(PlaylistEntity("list", "Mi lista", "Descripción")),
        listOf(PlaylistTrackEntity("list", "track", 0)),
        listOf(FavoriteEntity("track", 1234)),
        mapOf("download_wifi_only" to true, "stream_cache_mb" to 512,
            "spotify_cookie" to "never-export-this", "download_tree_uri" to "content://private/tree"))

    @Test fun portableRoundTripDoesNotClaimForeignAudioExists() {
        val encoded = BackupCodec.encode(fixture())
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("never-export"))
        val decoded = BackupCodec.decode(encoded)
        assertFalse(decoded.tracks.single().isDownloaded)
        assertNull(decoded.tracks.single().audioUri)
        assertNull(decoded.tracks.single().coverUri)
        assertEquals(fixture().playlists, decoded.playlists)
        assertEquals(fixture().links, decoded.links)
        assertEquals(fixture().favorites, decoded.favorites)
        assertEquals(512, decoded.settings["stream_cache_mb"])
    }

    @Test fun rejectsBrokenReferencesBeforeImport() {
        val root = JSONObject(BackupCodec.encode(fixture()))
        root.getJSONArray("links").getJSONObject(0).put("track", "missing")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }

    @Test fun rejectsFutureVersionsAndUnknownSettings() {
        val root = JSONObject(BackupCodec.encode(fixture()))
        root.put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
        root.put("version", 1)
        root.getJSONObject("settings").put("spotify_cookie", "bad")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }

    @Test fun refusesDuplicateTrackIds() {
        val root = JSONObject(BackupCodec.encode(fixture()))
        root.getJSONArray("tracks").put(root.getJSONArray("tracks").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }
}
