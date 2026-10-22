package com.musicvault.app.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.musicvault.app.data.DownloadRecordEntity
import com.musicvault.app.data.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ReleaseMigrationStore {
    data class Summary(val tracks: Int, val audioFiles: Int, val playlists: Int)

    suspend fun exportFromDebug(context: Context, uri: Uri, progress: (String, Long, Long) -> Unit = { _, _, _ -> }): Summary = withContext(Dispatchers.IO) {
        check(context.packageName.endsWith(".debug")) { "Esta opción solo está disponible en debug." }
        val operationContext = kotlin.coroutines.coroutineContext
        val snapshot = LibraryBackupStore.snapshot(context)
        val downloaded = snapshot.tracks.filter { it.isDownloaded && !it.audioUri.isNullOrBlank() }
        val sources = downloaded.map { track ->
            val audioUri = Uri.parse(track.audioUri)
            val extension = audioUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it in setOf("mp3", "m4a", "aac", "ogg", "opus", "webm", "wav", "flac") }
                ?: when (context.contentResolver.getType(audioUri)) {
                    "audio/mpeg" -> "mp3"
                    "audio/mp4", "audio/x-m4a" -> "m4a"
                    "audio/aac" -> "aac"
                    "audio/ogg" -> "ogg"
                    "audio/webm" -> "webm"
                    "audio/flac" -> "flac"
                    "audio/wav", "audio/x-wav" -> "wav"
                    else -> error("Formato no identificado: ${track.title}")
                }
            MigrationArchive.Source(track.id, extension) {
                context.contentResolver.openInputStream(audioUri)
                    ?: error("No se pudo leer: ${track.title}")
            }
        }
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            MigrationArchive.write(BackupCodec.encode(snapshot), sources, output,
                { files, bytes -> progress("Audio: $files/${sources.size} archivos", bytes, -1) },
                { operationContext.ensureActive() })
        } ?: error("No se pudo crear el paquete de migración.")
        Summary(snapshot.tracks.size, sources.size, snapshot.playlists.size)
    }

    suspend fun importIntoRelease(context: Context, uri: Uri, progress: (String, Long, Long) -> Unit = { _, _, _ -> }): Summary = withContext(Dispatchers.IO) {
        check(context.packageName == "com.musicvault.app") { "Esta opción solo está disponible en release." }
        val operationContext = kotlin.coroutines.coroutineContext
        val root = File(context.filesDir, "migrated_audio").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".pending-$id").apply { mkdirs() }
        val destination = File(root, id)
        var moved = false
        var restoreStarted = false
        try {
            val archive = context.contentResolver.openInputStream(uri)?.use {
                MigrationArchive.read(it, staging, { files, bytes -> progress("Leyendo $files archivos de audio", bytes, -1) },
                    { operationContext.ensureActive() })
            } ?: error("No se pudo abrir el paquete de migración.")
            val backup = BackupCodec.decode(archive.libraryJson)
            val trackIds = backup.tracks.mapTo(HashSet()) { it.id }
            require(archive.audioFiles.keys.all { it in trackIds }) { "El paquete contiene audio sin canción." }
            check(staging.renameTo(destination)) { "No se pudo guardar el audio en la release." }
            moved = true
            val audioUris = archive.audioFiles.mapValues { (_, oldFile) ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider",
                    File(destination, oldFile.name)).toString()
            }
            val db = MusicDatabase.get(context)
            var playlists = 0
            db.withTransaction {
                playlists = LibraryBackupStore.restore(context, backup, applySettings = false) { done, total -> progress("Biblioteca", done.toLong(), total.toLong()) }
                var attached = 0
                archive.audioFiles.forEach { (trackId, oldFile) ->
                    val file = File(destination, oldFile.name)
                    val current = db.trackDao().get(trackId) ?: error("Falta la canción migrada.")
                    // Existing release downloads take precedence over imported audio.
                    val accessible = current.isDownloaded && !current.audioUri.isNullOrBlank() && runCatching {
                        context.contentResolver.openAssetFileDescriptor(Uri.parse(current.audioUri), "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (!accessible) {
                        val savedUri = audioUris.getValue(trackId)
                        db.trackDao().update(current.copy(audioUri = savedUri, isDownloaded = true))
                        db.downloadRecordDao().save(DownloadRecordEntity(trackId, file.absolutePath))
                    }
                    operationContext.ensureActive(); attached++
                    progress("Registrando audio", attached.toLong(), archive.audioFiles.size.toLong())
                }
            }
            restoreStarted = true
            LibraryBackupStore.restoreSettings(context, backup.settings)
            Summary(backup.tracks.size, archive.audioFiles.size, playlists)
        } catch (failure: Exception) {
            if (moved && !restoreStarted) destination.deleteRecursively()
            if (!moved) staging.deleteRecursively()
            throw failure
        }
    }
}
