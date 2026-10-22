package com.musicvault.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.download.DownloadPreferences
import com.musicvault.app.player.StreamingCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object LibraryBackupStore {
    suspend fun snapshot(context: Context): LibraryBackup = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context)
        db.withTransaction {
            val tracks = db.trackDao().exportAll()
            val ids = tracks.map { it.id }.toSet()
            LibraryBackup(tracks, db.playlistDao().exportAll(),
                db.playlistDao().exportLinks().filter { it.trackId in ids },
                db.favoriteDao().exportAll().filter { it.trackId in ids },
                BackupCodec.settings(DownloadPreferences.prefs(context).all))
        }
    }

    suspend fun export(context: Context, uri: Uri, password: CharArray? = null) = withContext(Dispatchers.IO) {
        val snapshot = snapshot(context)
        val text = BackupEncryption.encode(BackupCodec.encode(snapshot), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("No se pudo escribir el respaldo.")
    }

    suspend fun import(context: Context, uri: Uri, password: CharArray? = null, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= BackupCodec.MAX_BYTES * 2) { "El respaldo supera los 20 MB." }
                output.write(buffer, 0, n)
            }
            output.toString("UTF-8")
        } ?: error("No se pudo leer el respaldo.")
        val data = BackupCodec.decode(BackupEncryption.decode(text, password)) // Fully validate before modifying the library.
        restore(context, data, progress = progress)
    }

    suspend fun restore(context: Context, data: LibraryBackup, applySettings: Boolean = true, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context)
        val albums = data.tracks.distinctBy { it.albumId }.filter { it.albumId != "unknown" }
        val total = data.tracks.size + albums.size + data.playlists.size + data.links.size + data.favorites.size
        var completed = 0
        progress(0, total)
        suspend fun advance(units: Int) {
            kotlin.coroutines.coroutineContext.ensureActive()
            completed += units; progress(completed, total)
        }
        db.withTransaction {
            data.tracks.chunked(250).forEach { db.trackDao().insertIfAbsent(it); advance(it.size) }
            albums.forEach {
                db.albumDao().insertIfAbsent(com.musicvault.app.data.AlbumEntity(it.albumId, it.albumTitle,
                    "artist:${it.artistName}", it.artistName, 0, it.coverUri))
                advance(1)
            }
            data.playlists.forEach { db.playlistDao().insertIfAbsent(it); advance(1) }
            data.links.groupBy { it.playlistId }.forEach { (playlist, links) ->
                val existing = db.playlistDao().trackIds(playlist).toSet()
                val start = db.playlistDao().nextPosition(playlist)
                db.playlistDao().upsertTracks(links.sortedBy { it.position }.filterNot { it.trackId in existing }
                    .mapIndexed { i, link -> link.copy(position = start + i) })
                advance(links.size)
            }
            data.favorites.forEach { if (!db.favoriteDao().isFavorite(it.trackId)) db.favoriteDao().add(it); advance(1) }
        }
        if (applySettings) restoreSettings(context, data.settings)
        data.playlists.size
    }

    suspend fun restoreSettings(context: Context, settings: Map<String, Any>) = withContext(Dispatchers.IO) {
        val editor = DownloadPreferences.prefs(context).edit()
        settings.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        } }
        editor.apply()
        StreamingCache.resize(context, DownloadPreferences.cacheMb(context))
    }
}
