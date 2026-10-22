package com.musicvault.app.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.musicvault.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Counts shared audio once; only app-owned audio is eligible for recoverable cleanup. */
object LibraryStorage {
    data class Collection(val id: String, val title: String, val tracks: Int, val bytes: Long, val sharedBytes: Long)
    data class Snapshot(val bytes: Long, val collections: List<Collection>, val unavailable: Int, val trashBytes: Long)
    data class Duplicate(val checksum: String, val tracks: List<TrackEntity>, val bytesEach: Long)
    data class Removal(val removed: Int, val skipped: Int)
    private fun trash(context: Context) = File(context.filesDir, "library_trash").apply { mkdirs() }
    suspend fun snapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context); val all = db.trackDao().exportAll().filter { it.isDownloaded && it.audioUri != null }
        val identityById = all.associate { t -> t.id to identity(db, t) }
        val sizes = all.associate { t -> identityById.getValue(t.id) to size(context, t.audioUri!!) }
        val playlists = db.playlistDao().exportAll(); val links = db.playlistDao().exportLinks()
        val uriById = identityById
        val membership = links.mapNotNull { uriById[it.trackId]?.let { u -> u to it.playlistId } }.groupBy({ it.first }, { it.second })
        val collections = mutableListOf(Collection("all", "Todo el audio", all.size, sizes.values.filter { it >= 0 }.sum(), 0))
        playlists.forEach { p ->
            val uris = links.filter { it.playlistId == p.id }.mapNotNull { uriById[it.trackId] }.toSet()
            collections += Collection(p.id, p.title, uris.size, uris.sumOf { (sizes[it] ?: 0).coerceAtLeast(0) },
                uris.filter { (membership[it]?.distinct()?.size ?: 0) > 1 }.sumOf { (sizes[it] ?: 0).coerceAtLeast(0) })
        }
        Snapshot(collections.first().bytes, collections, sizes.values.count { it < 0 }, trash(context).walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
    suspend fun duplicates(context: Context, progress: (Int, Int) -> Unit = { _, _ -> }): List<Duplicate> = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context)
        val all = db.trackDao().exportAll().filter { it.isDownloaded && it.audioUri != null }.distinctBy { identity(db, it) }
        val result = mutableMapOf<String, MutableList<TrackEntity>>()
        all.forEachIndexed { index, t ->
            coroutineContext.ensureActive()
            val hash = try { context.contentResolver.openInputStream(Uri.parse(t.audioUri))?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (hash != null) result.getOrPut(hash) { mutableListOf() }.add(t)
            progress(index + 1, all.size)
        }
        result.filterValues { it.size > 1 }.map { Duplicate(it.key, it.value, size(context, it.value.first().audioUri!!).coerceAtLeast(0)) }
    }
    /** Stages app files in trash and persists undo before detaching their metadata. */
    suspend fun remove(context: Context, ids: Set<String>): Removal = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context); val all = db.trackDao().exportAll(); val selected = all.filter { it.id in ids && it.isDownloaded }
        val retainedPaths = all.filter { it.id !in ids }.mapTo(hashSetOf()) { identity(db, it) }
        val batch = File(trash(context), System.currentTimeMillis().toString()).apply { mkdirs() }
        val manifest = JSONArray(); var skipped = 0
        val byUri = selected.filter { it.audioUri != null }.groupBy { it.audioUri!! }
        byUri.forEach { (uri, tracks) ->
            if (all.any { it.audioUri == uri && it.id !in ids }) { skipped += tracks.size; return@forEach }
            val records = tracks.mapNotNull { db.downloadRecordDao().get(it.id) }
            val original = records.firstOrNull()?.path?.let(::File) ?: Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let(::File)
            val root = context.filesDir.canonicalPath + File.separator
            if (original == null || !original.isFile || original.canonicalPath in retainedPaths || !original.canonicalPath.startsWith(root) || original.canonicalPath.startsWith(trash(context).canonicalPath + File.separator)) {
                skipped += tracks.size; return@forEach
            }
            val target = File(batch, "audio-${manifest.length()}.${original.extension}")
            tracks.forEach { t -> manifest.put(JSONObject().put("id", t.id).put("uri", uri)
                .put("original", original.absolutePath).put("trash", target.absolutePath)) }
            File(batch, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            if (!original.renameTo(target)) {
                repeat(tracks.size) { manifest.remove(manifest.length() - 1) }; skipped += tracks.size
                File(batch, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8); return@forEach
            }
            try { db.withTransaction { tracks.forEach { t -> db.trackDao().update(t.copy(audioUri = null, isDownloaded = false)) } } }
            catch (failure: Exception) {
                if (target.renameTo(original)) {
                    repeat(tracks.size) { manifest.remove(manifest.length() - 1) }
                    File(batch, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
                }
                throw failure
            }
        }
        Removal(manifest.length(), skipped)
    }
    suspend fun undo(context: Context): Int = withContext(Dispatchers.IO) {
        val db = MusicDatabase.get(context); var restored = 0
        trash(context).listFiles()?.filter { it.isDirectory }?.forEach { batch ->
            val file = File(batch, "manifest.json"); if (!file.isFile) return@forEach
            val array = JSONArray(file.readText(Charsets.UTF_8)); val remaining = JSONArray()
            val moved = hashSetOf<String>()
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i); val source = File(entry.getString("trash")); val destination = File(entry.getString("original"))
                val track = db.trackDao().get(entry.getString("id"))
                if (track?.isDownloaded == true && (track.audioUri != entry.getString("uri") || (source.isFile && destination.isFile))) {
                    remaining.put(entry); continue // A newer download takes precedence over undo.
                }
                if (source.exists() && source.absolutePath !in moved) {
                    destination.parentFile?.mkdirs()
                    if (destination.exists() || !source.renameTo(destination)) { remaining.put(entry); continue }
                    moved += source.absolutePath
                }
                if (!destination.isFile) { remaining.put(entry); continue }
                if (track != null) { db.trackDao().update(track.copy(audioUri = entry.getString("uri"), isDownloaded = true)); restored++ }
            }
            if (remaining.length() == 0) batch.deleteRecursively() else file.writeText(remaining.toString(), Charsets.UTF_8)
        }; restored
    }
    suspend fun emptyTrash(context: Context): Long = withContext(Dispatchers.IO) {
        val root = trash(context); val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        root.listFiles()?.forEach { check(it.deleteRecursively()) { "No se pudo vaciar toda la papelera." } }; bytes
    }
    private suspend fun identity(db: MusicDatabase, track: TrackEntity): String {
        val path = db.downloadRecordDao().get(track.id)?.path ?: track.audioUri?.let { uri ->
            Uri.parse(uri).takeIf { it.scheme == "file" }?.path
        }
        return path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) } ?: track.audioUri ?: track.id
    }
    private fun size(context: Context, uri: String): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { afd ->
            if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
        } ?: -1
    }.getOrDefault(-1)
}
