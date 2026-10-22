package com.musicvault.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.TrackEntity
import com.musicvault.app.settings.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real local audio and Media3 queue; no catalog/network requests. */
@RunWith(AndroidJUnit4::class)
class DjAlbumPlaybackTest {
    @Test fun fullMixOpensWithoutPlayingAndNextKeepsItsOrderAfterClosingDetail() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = AppPreferences.prefs(context)
        val wasOffline = prefs.getBoolean("offline_mode", false)
        prefs.edit().putBoolean("offline_mode", true).commit()
        val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        val audio = File(context.cacheDir, "dj-test-${System.nanoTime()}.wav")
        // Thirty seconds of silent mono PCM, long enough to test manual Next.
        val pcm = ByteArray(8_000 * 2 * 30)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        val store = ViewModelStore()
        lateinit var model: MusicVaultViewModel
        var created = false
        try {
            database.trackDao().upsertAll((0 until 40).map { index ->
                TrackEntity("dj-test:$index", "Song ${index.toString().padStart(2, '0')}",
                    "Artist ${index % 4}", "dj-test-album", "Test", 30_000,
                    audio.toURI().toString(), true)
            })
            instrumentation.runOnMainSync {
                model = MusicVaultViewModel(context, database, SavedStateHandle())
                created = true
                store.put("dj", model)
            }
            withTimeout(15_000) { model.offlinePlayableIds.first { ids -> ids.count { it.startsWith("dj-test:") } == 40 } }
            val before = model.state.value.selectedTrack?.id
            instrumentation.runOnMainSync { model.openDjAlbum() }
            val detail = withTimeout(15_000) { model.state.first { it.selectedPlaylist?.id == DJ_MIX_ID && it.selectedPlaylistTracks.size == 40 } }
            assertEquals(before, detail.selectedTrack?.id)
            assertEquals(40, detail.selectedPlaylistTracks.distinctBy { it.id }.size)
            val order = detail.selectedPlaylistTracks
            instrumentation.runOnMainSync { model.toggleShuffle() }
            withTimeout(5_000) { model.state.first { it.shuffle } }
            // An ordinary DJ row tap must override shuffle inherited from another album.
            instrumentation.runOnMainSync { model.playPlaylistTrack(detail.selectedPlaylist!!, order[4]) }
            withTimeout(15_000) { model.queue.first { it.firstOrNull()?.id == order[4].id && it.size >= 3 } }
            instrumentation.runOnMainSync { model.closePlaylist(); model.nextTrack() }
            val next = withTimeout(15_000) { model.state.first { it.selectedTrack?.id == order[5].id } }
            assertNull(next.selectedPlaylist)
            instrumentation.runOnMainSync { model.nextTrack() }
            withTimeout(15_000) { model.state.first { it.selectedTrack?.id == order[6].id } }
            instrumentation.runOnMainSync { model.openDjAlbum(refresh = true) }
            val refreshed = withTimeout(15_000) {
                model.state.first {
                    it.selectedPlaylist?.id == DJ_MIX_ID && it.selectedPlaylistTracks.isNotEmpty() &&
                        it.selectedTrack?.id == it.selectedPlaylistTracks.first().id
                }
            }
            // Explicit skips influence the refreshed recommendations and can remove songs.
            assertTrue(refreshed.selectedPlaylistTracks.size > 3)
            instrumentation.runOnMainSync { model.nextTrack() }
            withTimeout(15_000) { model.state.first { it.selectedTrack?.id == refreshed.selectedPlaylistTracks[1].id } }
        } finally {
            instrumentation.runOnMainSync { if (created) model.pauseDj(); store.clear() }
            database.close()
            audio.delete()
            prefs.edit().putBoolean("offline_mode", wasOffline).commit()
        }
    }
}
