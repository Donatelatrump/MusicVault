package com.musicvault.app

import android.content.Context
import com.musicvault.app.catalog.*
import com.musicvault.app.data.*
import com.musicvault.app.player.*
import com.musicvault.app.network.ConnectivityMonitor
import com.musicvault.app.spotify.*
import com.musicvault.app.settings.AppPreferences

/** Process-owned data dependencies; screen-owned player clients have explicit lifetimes. */
class AppContainer(private val context: Context, private val databaseOverride: MusicDatabase? = null) {
    val database by lazy { databaseOverride ?: MusicDatabase.get(context) }
    val library by lazy { MusicRepository(database) }
    val catalog by lazy { CatalogRepository(MetadataCatalogProvider(), database) }
    val preferences by lazy { AppPreferences.prefs(context) }
    val spotifySession by lazy { SpotifySessionStore(context) }
    val spotifyClient by lazy { SpotifySongMirrorClient() }
    fun playback() = PlayerController(context)
    fun connectivity() = ConnectivityMonitor(context)
    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(context: Context, database: MusicDatabase? = null): AppContainer {
            val shared = instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
            return if (database == null || database === shared.database) shared
                else AppContainer(context.applicationContext, database)
        }
    }
}
