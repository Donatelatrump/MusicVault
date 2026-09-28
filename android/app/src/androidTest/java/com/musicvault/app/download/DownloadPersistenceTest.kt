package com.musicvault.app.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadPersistenceTest {
    @Test fun upgradePreservesLibraryAndQueueSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-smoke-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, MusicDatabase::class.java, name)
            .addMigrations(MusicDatabase.MIGRATION_18_19).build()
        var db = open()
        try {
            val track = TrackEntity("song", "Song", "Artist", "album", "Album", 120000, "content://existing", true, "cover")
            db.trackDao().upsertAll(listOf(track))
            db.favoriteDao().add(FavoriteEntity(track.id, 123))
            db.playlistDao().upsertAll(listOf(PlaylistEntity("list", "List", "")))
            db.playlistDao().upsertTracks(listOf(PlaylistTrackEntity("list", track.id, 0)))
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE download_queue")
                old.version = 18
            }
            db = open()
            assertEquals(track, db.trackDao().get(track.id))
            assertTrue(db.favoriteDao().isFavorite(track.id))
            assertEquals(listOf(track.id), db.playlistDao().trackIds("list"))
            val request = DownloadQueueEntity(track.id, "generation", track, null, "BEST", false)
            db.downloadQueueDao().save(request)
            db.close()
            db = open()
            assertEquals(request, db.downloadQueueDao().get(track.id))
            db.downloadQueueDao().cancel(track.id)
            assertEquals(0, db.downloadQueueDao().update(track.id, "generation", "complete", 100, null, 1))
            assertEquals("cancelled", db.downloadQueueDao().get(track.id)?.state)
            db.downloadQueueDao().save(request.copy(generation = "retry"))
            assertEquals(0, db.downloadQueueDao().update(track.id, "generation", "failed", 0, "stale", 1))
            assertEquals("queued", db.downloadQueueDao().get(track.id)?.state)
            assertEquals(1, db.downloadQueueDao().update(track.id, "retry", "complete", 100, null, 1))
            assertEquals(0, db.downloadQueueDao().update(track.id, "retry", "running", 10, null, 1))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
