package com.musicvault.app.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies durable queue ownership without scheduling downloads or contacting providers. */
@RunWith(AndroidJUnit4::class)
class DownloadQueuePersistenceTest {
    @Test fun oldWorkerCannotOverwriteRetryOrCanceledGeneration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val dao = db.downloadQueueDao()
            val track = TrackEntity("song", "Canción", "Artista", "album", "Álbum", 180000, null, false)
            val first = DownloadQueueEntity("song", "first", track, null, "BEST", true, requiresCharging = true)
            dao.save(first)
            dao.update("song", "first", "running", 20, null, 1)
            val retry = first.copy(generation = "retry", state = "waiting", progress = 0)
            dao.save(retry)
            assertEquals(0, dao.update("song", "first", "complete", 100, null, 1))
            val recovered = dao.pending().single()
            assertEquals("retry", recovered.generation)
            assertTrue(recovered.wifiOnly)
            assertTrue(recovered.requiresCharging)
            assertTrue(DownloadQueue.isActive(recovered.state))
            dao.cancel("song")
            assertEquals(0, dao.update("song", "retry", "running", 50, null, 2))
            assertEquals("cancelled", dao.get("song")!!.state)
            assertTrue(dao.pending().isEmpty())
        } finally { db.close() }
    }
}
