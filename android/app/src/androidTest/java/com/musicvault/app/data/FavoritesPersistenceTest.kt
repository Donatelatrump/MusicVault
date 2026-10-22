package com.musicvault.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesPersistenceTest {
    private val playlistId = "playlist:favorites"
    private val remote = Track("youtube:favorite-test", "Song", "Artist", "album", "Album", 180_000,
        audioUri = "https://expired-stream.example/audio", source = "YouTube", sourceId = "videoId0001")

    @Test
    fun streamedFavoriteAppearsInPlaylist() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val repository = MusicRepository(db)
            repository.createPlaylist(playlistId, "Favoritos", "")
            assertTrue(repository.toggleFavorite(remote))
            assertEquals(listOf(remote.id), repository.getPlaylistTracks(playlistId).map { it.id })
            assertNull(db.trackDao().get(remote.id)!!.audioUri)
        } finally { db.close() }
    }

    @Test
    fun removingFromFavoritesAlsoClearsLike() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val repository = MusicRepository(db)
            repository.createPlaylist(playlistId, "Favoritos", "")
            repository.toggleFavorite(remote)
            repository.removeTrackFromPlaylist(playlistId, remote.id)
            assertFalse(db.favoriteDao().isFavorite(remote.id))
        } finally { db.close() }
    }

    @Test
    fun addingFavoritePreservesExistingDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val saved = TrackEntity(remote.id, remote.title, remote.artistName, remote.albumId,
                remote.albumTitle, remote.durationMs, "content://saved-audio", true,
                source = remote.source, sourceId = remote.sourceId)
            db.trackDao().upsertAll(listOf(saved))
            val repository = MusicRepository(db)
            repository.createPlaylist(playlistId, "Favoritos", "")
            repository.toggleFavorite(remote)
            assertEquals(saved, db.trackDao().get(remote.id))
        } finally { db.close() }
    }

    @Test
    fun recoversOldLikesFromAvailableMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            db.favoriteDao().add(FavoriteEntity(remote.id, 123L))
            db.favoriteDao().add(FavoriteEntity("unknown-song", 456L))
            val repository = MusicRepository(db)
            assertEquals(setOf("unknown-song"), repository.reconcileFavorites(listOf(remote)))
            assertEquals(listOf(remote.id), repository.getPlaylistTracks(playlistId).map { it.id })
            assertTrue(db.favoriteDao().isFavorite("unknown-song"))
            repository.reconcileFavorites(listOf(remote))
            assertEquals(2, db.playlistDao().trackIds(playlistId).size)
        } finally { db.close() }
    }

    @Test
    fun togglingOffRemovesBothFavoriteAndPlaylistEntry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val repository = MusicRepository(db)
            assertTrue(repository.toggleFavorite(remote))
            assertFalse(repository.toggleFavorite(remote))
            assertFalse(db.favoriteDao().isFavorite(remote.id))
            assertTrue(repository.getPlaylistTracks(playlistId).isEmpty())
        } finally { db.close() }
    }
}
