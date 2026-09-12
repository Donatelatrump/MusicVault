package com.musicvault.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        AlbumTrackEntity::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        DownloadRecordEntity::class,
        CatalogSearchCacheEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun albumTrackDao(): AlbumTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun catalogSearchCacheDao(): CatalogSearchCacheDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN coverUri TEXT")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS album_tracks (albumId TEXT NOT NULL, trackId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(albumId, trackId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS favorites (trackId TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(trackId))")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS search_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, query TEXT NOT NULL, searchedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS download_records (trackId TEXT PRIMARY KEY NOT NULL, path TEXT NOT NULL, checksum TEXT, savedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_search_cache (cacheKey TEXT NOT NULL PRIMARY KEY, query TEXT NOT NULL, tracksJson TEXT NOT NULL, albumsJson TEXT NOT NULL, savedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_search_cache_savedAt ON catalog_search_cache(savedAt)")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_play_history_trackId ON play_history(trackId)")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_isDownloaded ON tracks(isDownloaded)")
            }
        }
        // 7 -> 8 is kept as a no-op for databases created by V15.
        // The previous V16 build introduced an external-content FTS table that can
        // crash Room at startup on databases whose tracks table uses a String PK.
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Intentionally empty.
            }
        }

        // Remove the experimental FTS table from databases created by the broken V16.
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS tracks_fts")
            }
        }
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sourceId TEXT")
            }
        }
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN userRemoved INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN coverUri TEXT")
            }
        }
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE playlists SET description = 'Importada desde Spotify' WHERE description = 'Importada desde Spotify mediante SongMirror'")
            }
        }
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_position ON playlist_tracks(playlistId, position)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_album_tracks_albumId_position ON album_tracks(albumId, position)")
            }
        }
        @Volatile private var INSTANCE: MusicDatabase? = null
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_userRemoved_title ON tracks(userRemoved, title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_userRemoved_isDownloaded_title ON tracks(userRemoved, isDownloaded, title)")
            }
        }

        fun get(context: Context): MusicDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "musicvault.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                // ✅ Performance optimizations for Room
                .addMigrations(MIGRATION_14_15)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // Mejores operaciones concurrentes
                .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(4)) // Pool de queries
                .build().also { INSTANCE = it }
            }
    }
}
