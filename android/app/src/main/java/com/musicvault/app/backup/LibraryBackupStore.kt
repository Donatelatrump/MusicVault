package com.musicvault.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.download.DownloadPreferences
import com.musicvault.app.player.StreamingCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LibraryBackupStore {
    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context)
        val snapshot = db.withTransaction {
            val tracks = db.trackDao().exportAll()
            val ids = tracks.map { it.id }.toSet()
            LibraryBackup(tracks, db.playlistDao().exportAll(),
                db.playlistDao().exportLinks().filter { it.trackId in ids },
                db.favoriteDao().exportAll().filter { it.trackId in ids },
                BackupCodec.settings(DownloadPreferences.prefs(context).all))
        }
        val text = BackupCodec.encode(snapshot)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("No se pudo escribir el respaldo.")
    }

    suspend fun import(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= BackupCodec.MAX_BYTES) { "El respaldo supera los 20 MB." }
                output.write(buffer, 0, n)
            }
            output.toString("UTF-8")
        } ?: error("No se pudo leer el respaldo.")
        val data = BackupCodec.decode(text) // Fully validate before modifying the library.
        val db = MusicDatabase.get(context)
        db.withTransaction {
            db.trackDao().insertIfAbsent(data.tracks)
            data.tracks.distinctBy { it.albumId }.filter { it.albumId != "unknown" }.forEach {
                db.albumDao().insertIfAbsent(com.musicvault.app.data.AlbumEntity(it.albumId, it.albumTitle,
                    "artist:${it.artistName}", it.artistName, 0, it.coverUri))
            }
            data.playlists.forEach { db.playlistDao().insertIfAbsent(it) }
            data.links.groupBy { it.playlistId }.forEach { (playlist, links) ->
                val existing = db.playlistDao().trackIds(playlist).toSet()
                val start = db.playlistDao().nextPosition(playlist)
                db.playlistDao().upsertTracks(links.sortedBy { it.position }.filterNot { it.trackId in existing }
                    .mapIndexed { i, link -> link.copy(position = start + i) })
            }
            data.favorites.forEach { if (!db.favoriteDao().isFavorite(it.trackId)) db.favoriteDao().add(it) }
        }
        val editor = DownloadPreferences.prefs(context).edit()
        data.settings.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        } }
        editor.apply()
        StreamingCache.resize(context, DownloadPreferences.cacheMb(context))
        data.playlists.size
    }
}
