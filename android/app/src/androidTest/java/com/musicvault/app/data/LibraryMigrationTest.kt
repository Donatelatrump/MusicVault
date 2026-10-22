package com.musicvault.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** These device tests are intentionally not executed during the requested release build. */
@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MusicDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Test fun schema19To20PreservesLibraryQueueAndIndexesUnicode() = migrate(19)
    @Test fun schema18To20PreservesLibraryAndCreatesQueue() = migrate(18)

    private fun migrate(version: Int) {
        val name = "migration-$version-to20"
        helper.createDatabase(name, version).apply {
            execSQL("INSERT INTO tracks VALUES ('song','Canción del corazón','México','album','Álbum',123000,'content://audio/1',1,NULL,'youtube','video',0)")
            execSQL("INSERT INTO playlists VALUES ('list','Lista','Mi lista',NULL)")
            execSQL("INSERT INTO playlist_tracks VALUES ('list','song',7)")
            execSQL("INSERT INTO favorites VALUES ('song',123)")
            execSQL("INSERT INTO download_records VALUES ('song','/private/audio.mp3','sha',123)")
            if (version == 19) execSQL("""INSERT INTO download_queue
                (id,generation,track_id,track_title,track_artistName,track_albumId,track_albumTitle,track_durationMs,
                track_audioUri,track_isDownloaded,track_coverUri,track_source,track_sourceId,track_userRemoved,
                sourceUrl,quality,wifiOnly,state,progress,attempts,error,updatedAt)
                VALUES ('queued','00000000-0000-0000-0000-000000000001','queued','Pendiente','Artista','album','Álbum',123000,
                NULL,0,NULL,'youtube','id',0,NULL,'BEST',1,'waiting',25,1,NULL,123)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 20, true, MusicDatabase.MIGRATION_18_19, MusicDatabase.MIGRATION_19_20).use { db ->
            fun scalar(sql: String): String = db.query(sql).use { assertTrue(it.moveToFirst()); it.getString(0) }
            assertEquals("content://audio/1", scalar("SELECT audioUri FROM tracks WHERE id='song'"))
            assertEquals("7", scalar("SELECT position FROM playlist_tracks WHERE trackId='song'"))
            assertEquals("123", scalar("SELECT createdAt FROM favorites WHERE trackId='song'"))
            assertEquals("sha", scalar("SELECT checksum FROM download_records WHERE trackId='song'"))
            for (term in listOf("cancion*", "canción*", "corazon*", "mexico*", "México*")) {
                assertEquals("song", scalar("SELECT trackId FROM tracks_fts WHERE tracks_fts MATCH '$term'"))
            }
            if (version == 19) {
                assertEquals("waiting", scalar("SELECT state FROM download_queue WHERE id='queued'"))
                assertEquals("25", scalar("SELECT progress FROM download_queue WHERE id='queued'"))
                assertEquals("0", scalar("SELECT requiresCharging FROM download_queue WHERE id='queued'"))
            }
            db.execSQL("UPDATE tracks SET title='Niñez', artistName='Bogotá' WHERE id='song'")
            assertEquals("song", scalar("SELECT trackId FROM tracks_fts WHERE tracks_fts MATCH 'ninez*'"))
            assertEquals("song", scalar("SELECT trackId FROM tracks_fts WHERE tracks_fts MATCH 'bogota*'"))
            db.execSQL("DELETE FROM tracks WHERE id='song'")
            assertEquals("0", scalar("SELECT COUNT(*) FROM tracks_fts"))
            db.execSQL("INSERT INTO tracks VALUES ('new','Canción','México','album','Álbum',0,NULL,0,NULL,NULL,NULL,0)")
            assertEquals("new", scalar("SELECT trackId FROM tracks_fts WHERE tracks_fts MATCH 'cancion*'"))
        }
    }
    @Test fun historicalNullableVersion17IsRepairedWithoutLosingTracks() = historical17(false)
    @Test fun historicalExternalContentFtsVersion17IsRepaired() = historical17(true)
    private fun historical17(experimentalFts: Boolean) {
        val name = "migration-historical17-$experimentalFts-to20"
        helper.createDatabase(name, 18).apply {
            execSQL("INSERT INTO tracks VALUES ('kept','Canción','México','album','Álbum',0,NULL,0,NULL,NULL,NULL,0)")
            execSQL("DROP TABLE user_preferences")
            execSQL("CREATE TABLE user_preferences (userId TEXT PRIMARY KEY, generosFavoritos TEXT, artistasFavoritos TEXT, primeraVezCompleto INTEGER, fechaUltimaActualizacion INTEGER)")
            execSQL("INSERT INTO user_preferences VALUES ('current_user',NULL,NULL,NULL,NULL)")
            if (experimentalFts) {
                execSQL("DROP TABLE tracks_fts")
                execSQL("CREATE VIRTUAL TABLE tracks_fts USING fts4(trackId,title,artistName,albumTitle,content='tracks')")
            }
            version = 17
            close()
        }
        helper.runMigrationsAndValidate(name, 20, true, MusicDatabase.MIGRATION_17_18, MusicDatabase.MIGRATION_18_19, MusicDatabase.MIGRATION_19_20).use { db ->
            db.query("SELECT generosFavoritos,primeraVezCompleto FROM user_preferences").use { assertTrue(it.moveToFirst()); assertEquals("", it.getString(0)); assertEquals(1, it.getInt(1)) }
            db.query("SELECT trackId FROM tracks_fts WHERE tracks_fts MATCH 'cancion*'").use { assertTrue(it.moveToFirst()); assertEquals("kept", it.getString(0)) }
        }
    }
}
