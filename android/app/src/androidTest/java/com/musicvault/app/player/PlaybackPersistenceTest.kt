package com.musicvault.app.player

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.data.Track
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceTest {
    @Test fun playbackPositionSurvivesServiceRestartAndRestoresPaused() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("musicvault.settings", Context.MODE_PRIVATE)
        val previous = settings.getString(PlaybackSnapshot.KEY, null)
        val audio = File(context.cacheDir, "playback-persistence-test.wav")
        val samples = 8000 * 30 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(samples + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples)
        }.array()
        audio.outputStream().use { it.write(header); it.write(ByteArray(samples)) }
        var controller: PlayerController? = null
        try {
            instrumentation.runOnMainSync {
                controller = PlayerController(context)
                controller!!.play(Track("persistence-test", "Restart song", "Artist", "test", "Album",
                    30000, audio.toURI().toString(), true))
            }
            await { controller!!.isPlaying.value }
            instrumentation.runOnMainSync { controller!!.seekTo(12000); controller!!.pause() }
            await {
                val saved = PlaybackSnapshot.decode(settings.getString(PlaybackSnapshot.KEY, null))
                saved?.track?.id == "persistence-test" && saved.positionMs >= 12000
            }
            // An item that fails before producing audio must not replace the played song.
            instrumentation.runOnMainSync {
                controller!!.play(Track("missing", "Missing", "Artist", "test", "Album", 1000,
                    File(context.cacheDir, "nonexistent.wav").toURI().toString(), true))
            }
            Thread.sleep(1000)
            assertEquals("persistence-test", PlaybackSnapshot.decode(settings.getString(PlaybackSnapshot.KEY, null))!!.track.id)
            instrumentation.runOnMainSync {
                controller!!.release()
                context.stopService(Intent(context, MusicPlaybackService::class.java))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            instrumentation.runOnMainSync { controller = PlayerController(context) }
            await { controller!!.isConnected }
            assertEquals("Restart song", controller!!.restoredSnapshot!!.track.title)
            assertTrue(controller!!.progress.value.positionMs >= 12000)
            assertFalse(controller!!.isPlaying.value)
            var hasItem = true
            instrumentation.runOnMainSync { hasItem = controller!!.hasCurrentMediaItem() }
            assertFalse(hasItem)
        } finally {
            instrumentation.runOnMainSync {
                controller?.release()
                context.stopService(Intent(context, MusicPlaybackService::class.java))
            }
            instrumentation.waitForIdleSync()
            settings.edit().putString(PlaybackSnapshot.KEY, previous).commit()
            audio.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Playback condition timed out", condition())
    }
}
