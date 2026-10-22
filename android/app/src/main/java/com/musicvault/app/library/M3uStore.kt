package com.musicvault.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musicvault.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** UTF-8 SAF playlists. Relative entries resolve inside a separately granted music tree. */
object M3uStore {
    data class ImportResult(val playlistId: String, val imported: Int, val duplicates: Int, val missing: List<String>)
    suspend fun import(context: Context, document: Uri, title: String, musicTree: Uri? = null): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val text = resolver.openInputStream(document)?.use { input ->
            val bytes = input.readBytesLimited(4 * 1024 * 1024)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } ?: error("No se pudo abrir la playlist.")
        val db = MusicDatabase.get(context)
        val existing = db.trackDao().exportAll().associateBy { it.audioUri }
        val tracks = mutableListOf<Track>()
        val missing = mutableListOf<String>()
        val seen = hashSetOf<String>()
        var duplicates = 0
        var label: String? = null
        var seconds = 0L
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:")) {
                seconds = line.substringAfter(':').substringBefore(',').toLongOrNull()?.coerceIn(0, 604800) ?: 0
                label = line.substringAfter(',', "").takeIf { it.isNotBlank() }
                continue
            }
            if (line.isBlank() || line.startsWith('#')) continue
            val uri = resolve(context, line, musicTree)
            if (uri == null || !readable(context, uri)) { missing += line; label = null; seconds = 0; continue }
            if (!seen.add(uri.toString())) { duplicates++; label = null; seconds = 0; continue }
            val found = existing[uri.toString()]
            val name = label ?: runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment ?: "Audio"
            val artist = name.substringBefore(" - ", "Artista desconocido")
            val trackTitle = if (" - " in name) name.substringAfter(" - ") else name
            val id = "m3u:" + MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            tracks += if (found != null) Track(found.id, found.title, found.artistName, found.albumId, found.albumTitle,
                found.durationMs, found.audioUri, found.isDownloaded, found.coverUri, found.source, found.sourceId)
            else Track(id, trackTitle, artist, "unknown", "Importada M3U", seconds * 1000, uri.toString(), true, source = "local", sourceId = uri.toString())
            label = null; seconds = 0
        }
        require(tracks.isNotEmpty()) { "No se encontró audio accesible. Selecciona la carpeta de música si hay rutas relativas." }
        val id = "playlist:m3u:${UUID.randomUUID()}"
        MusicRepository(db).replaceImportedPlaylist(id, title.ifBlank { "Playlist M3U" }, "Importada desde M3U", null, tracks)
        ImportResult(id, tracks.size, duplicates, missing)
    }
    suspend fun export(context: Context, destination: Uri, playlistId: String): Int = withContext(Dispatchers.IO) {
        val tracks = MusicDatabase.get(context).playlistDao().getTracks(playlistId)
        var count = 0
        context.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.appendLine("#EXTM3U")
            tracks.forEach { t ->
                val uri = t.audioUri?.takeIf { t.isDownloaded && readable(context, Uri.parse(it)) } ?: return@forEach
                writer.appendLine("#EXTINF:${t.durationMs / 1000},${t.artistName.clean()} - ${t.title.clean()}")
                writer.appendLine(uri.clean()); count++
            }
        } ?: error("No se pudo escribir la playlist.")
        count
    }
    private fun String.clean() = replace('\r', ' ').replace('\n', ' ')
    private fun readable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
    private fun resolve(context: Context, value: String, tree: Uri?): Uri? {
        val parsed = Uri.parse(value)
        if (parsed.scheme == "content" || parsed.scheme == "file") return parsed
        if (parsed.scheme != null) return null // Remote manifests are not downloaded implicitly.
        if (File(value).isAbsolute) return Uri.fromFile(File(value))
        val root = tree?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        var file = root
        for (part in value.replace('\\', '/').split('/')) {
            if (part.isBlank() || part == ".") continue
            if (part == "..") return null
            file = file.findFile(Uri.decode(part)) ?: return null
        }
        return file.takeIf { it.isFile }?.uri
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "Playlist demasiado grande." }; output.write(buffer, 0, n) }
        return output.toByteArray()
    }
}
