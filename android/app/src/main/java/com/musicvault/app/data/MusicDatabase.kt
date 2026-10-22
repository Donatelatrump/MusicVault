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
        TrackSearchEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        AlbumTrackEntity::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        DownloadRecordEntity::class,
        CatalogSearchCacheEntity::class,
        UserPreferences::class,
        DownloadQueueEntity::class
    ],
    version = 20,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun albumTrackDao(): AlbumTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun catalogSearchCacheDao(): CatalogSearchCacheDao
    abstract fun userPreferencesDao(): UserPreferencesDao

    companion object {
        /** Rebuild only the derived index; every library and queue row stays intact. */
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_queue ADD COLUMN requiresCharging INTEGER NOT NULL DEFAULT 0")
                rebuildTrackSearchIndex(db, unicode = true)
            }
        }
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS download_queue (
                    id TEXT NOT NULL PRIMARY KEY, generation TEXT NOT NULL,
                    track_id TEXT NOT NULL, track_title TEXT NOT NULL, track_artistName TEXT NOT NULL,
                    track_albumId TEXT NOT NULL, track_albumTitle TEXT NOT NULL, track_durationMs INTEGER NOT NULL,
                    track_audioUri TEXT, track_isDownloaded INTEGER NOT NULL, track_coverUri TEXT,
                    track_source TEXT, track_sourceId TEXT, track_userRemoved INTEGER NOT NULL,
                    sourceUrl TEXT, quality TEXT NOT NULL, wifiOnly INTEGER NOT NULL,
                    state TEXT NOT NULL, progress INTEGER NOT NULL, attempts INTEGER NOT NULL,
                    error TEXT, updatedAt INTEGER NOT NULL)""")
            }
        }
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
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING FTS4(trackId TEXT NOT NULL, title TEXT NOT NULL, artistName TEXT NOT NULL, albumTitle TEXT NOT NULL)")
                db.execSQL("INSERT INTO tracks_fts(trackId,title,artistName,albumTitle) SELECT id,title,artistName,albumTitle FROM tracks")
                createFtsTriggers(db)
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Keep the migration schema identical to UserPreferences. Older builds
                // created these columns as nullable, which makes Room fail validation
                // after an in-place APK update even though a clean install works.
                db.execSQL("CREATE TABLE IF NOT EXISTS user_preferences (userId TEXT NOT NULL PRIMARY KEY, generosFavoritos TEXT NOT NULL, artistasFavoritos TEXT NOT NULL, primeraVezCompleto INTEGER NOT NULL, fechaUltimaActualizacion INTEGER NOT NULL)")
            }
        }

        /**
         * Compatibility migration for installs upgraded from historical Music Vault
         * APKs. Some of those builds shipped database version 17 with a different
         * user_preferences schema (nullable columns) and/or an experimental FTS table.
         * A clean install therefore worked while updating over the old APK crashed at
         * Room schema validation during startup.
         *
         * This migration repairs only metadata/index structures. User tracks,
         * playlists, favorites, history and download records are never deleted.
         */
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                normalizeUserPreferences(db)
                rebuildTrackSearchIndex(db)
            }
        }

        private fun tableExists(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            tableName: String
        ): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        private fun tableColumns(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            tableName: String
        ): Set<String> {
            if (!tableExists(db, tableName)) return emptySet()
            val result = linkedSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) result += cursor.getString(nameIndex)
                }
            }
            return result
        }

        private fun normalizeUserPreferences(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            val oldTable = "user_preferences"
            val newTable = "user_preferences_v18"
            db.execSQL("DROP TABLE IF EXISTS $newTable")
            db.execSQL(
                "CREATE TABLE $newTable (" +
                    "userId TEXT NOT NULL PRIMARY KEY, " +
                    "generosFavoritos TEXT NOT NULL, " +
                    "artistasFavoritos TEXT NOT NULL, " +
                    "primeraVezCompleto INTEGER NOT NULL, " +
                    "fechaUltimaActualizacion INTEGER NOT NULL)"
            )

            val columns = tableColumns(db, oldTable)
            if (columns.isNotEmpty()) {
                fun textExpr(column: String, fallback: String): String =
                    if (column in columns) "COALESCE(`$column`, '$fallback')" else "'$fallback'"
                fun numberExpr(column: String, fallback: Long): String =
                    if (column in columns) "COALESCE(`$column`, $fallback)" else fallback.toString()

                val userId = textExpr("userId", "current_user")
                val genres = textExpr("generosFavoritos", "")
                val artists = textExpr("artistasFavoritos", "")
                val firstRun = numberExpr("primeraVezCompleto", 1L)
                val updatedAt = numberExpr("fechaUltimaActualizacion", 0L)

                db.execSQL(
                    "INSERT OR REPLACE INTO $newTable " +
                        "(userId, generosFavoritos, artistasFavoritos, primeraVezCompleto, fechaUltimaActualizacion) " +
                        "SELECT $userId, $genres, $artists, $firstRun, $updatedAt FROM $oldTable"
                )
                db.execSQL("DROP TABLE $oldTable")
            }
            db.execSQL("ALTER TABLE $newTable RENAME TO $oldTable")
        }

        private fun rebuildTrackSearchIndex(db: androidx.sqlite.db.SupportSQLiteDatabase, unicode: Boolean = false) {
            // The FTS table is only a derived search index. Rebuilding it is safe and
            // prevents old experimental definitions/triggers from breaking startup.
            db.execSQL("DROP TRIGGER IF EXISTS tracks_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS tracks_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS tracks_fts_delete")
            db.execSQL("DROP TABLE IF EXISTS tracks_fts")
            val tokenizer = if (unicode) ", tokenize=unicode61 `remove_diacritics=1`" else ""
            db.execSQL("CREATE VIRTUAL TABLE tracks_fts USING FTS4(trackId TEXT NOT NULL, title TEXT NOT NULL, artistName TEXT NOT NULL, albumTitle TEXT NOT NULL$tokenizer)")
            db.execSQL("INSERT INTO tracks_fts(trackId,title,artistName,albumTitle) SELECT id,title,artistName,albumTitle FROM tracks")
            createFtsTriggers(db)
        }

        private fun createFtsTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tracks_fts_insert AFTER INSERT ON tracks BEGIN
                DELETE FROM tracks_fts WHERE trackId=new.id;
                INSERT INTO tracks_fts(trackId,title,artistName,albumTitle) VALUES(new.id,new.title,new.artistName,new.albumTitle);
                END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tracks_fts_update AFTER UPDATE OF title,artistName,albumTitle ON tracks BEGIN
                DELETE FROM tracks_fts WHERE trackId=old.id;
                INSERT INTO tracks_fts(trackId,title,artistName,albumTitle) VALUES(new.id,new.title,new.artistName,new.albumTitle);
                END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tracks_fts_delete AFTER DELETE ON tracks BEGIN
                DELETE FROM tracks_fts WHERE trackId=old.id;
                END""")
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
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        createFtsTriggers(db)
                    }
                })
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // Mejores operaciones concurrentes
                .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(4)) // Pool de queries
                .build().also { INSTANCE = it }
            }
    }
}
