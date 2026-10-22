package com.musicvault.app.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable debug-to-release transfer. Audio is streamed; only metadata is held in memory. */
object MigrationArchive {
    data class Source(val trackId: String, val extension: String, val open: () -> InputStream)
    data class Result(val libraryJson: String, val audioFiles: Map<String, File>)

    private val audioName = Regex("audio/[0-9]+\\.(mp3|m4a|aac|ogg|opus|webm|wav|flac)")
    private const val MAX_TRACKS = 50_000
    private const val MAX_AUDIO_BYTES = 4L * 1024 * 1024 * 1024

    fun write(libraryJson: String, sources: List<Source>, output: OutputStream, progress: (Int, Long) -> Unit = { _, _ -> }, checkCancelled: () -> Unit = {}) {
        var bytesProcessed = 0L
        require(libraryJson.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES)
        require(sources.size <= MAX_TRACKS && sources.map { it.trackId }.distinct().size == sources.size)
        val manifest = JSONArray()
        ZipOutputStream(output).use { zip ->
            // Audio containers are already compressed; avoid expensive recompression.
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(libraryJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sources.forEachIndexed { index, source ->
                val extension = source.extension.lowercase()
                require(audioName.matches("audio/$index.$extension")) { "Formato de audio no compatible." }
                val name = "audio/$index.$extension"
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.putNextEntry(ZipEntry(name))
                source.open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_AUDIO_BYTES) { "Archivo de audio demasiado grande." }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                        bytesProcessed += count
                        require(bytesProcessed <= MAX_AUDIO_BYTES) { "El paquete de audio supera los 4 GB." }
                        if (bytesProcessed % (1024 * 1024) < count) progress(index, bytesProcessed)
                    }
                }
                require(size > 0) { "Archivo de audio vacío." }
                zip.closeEntry()
                progress(index + 1, bytesProcessed)
                manifest.put(JSONObject().put("trackId", source.trackId).put("file", name)
                    .put("size", size).put("sha256", digest.digest().hex()))
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "musicvault-migration").put("version", 1)
                .put("audio", manifest).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun read(input: InputStream, directory: File, progress: (Int, Long) -> Unit = { _, _ -> }, checkCancelled: () -> Unit = {}): Result {
        var bytesProcessed = 0L
        require(directory.isDirectory || directory.mkdirs())
        val files = mutableMapOf<String, File>()
        val digests = mutableMapOf<String, Pair<Long, String>>()
        var library: String? = null
        var manifest: String? = null
        ZipInputStream(input).use { zip ->
            var count = 0
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_TRACKS + 2 && !entry.isDirectory) { "Paquete inválido." }
                val name = entry.name
                require(name == "library.json" || name == "manifest.json" || audioName.matches(name)) {
                    "Ruta no permitida en el paquete."
                }
                require(name !in files && !(name == "library.json" && library != null) &&
                    !(name == "manifest.json" && manifest != null)) { "Archivo duplicado." }
                if (name == "library.json" || name == "manifest.json") {
                    val bytes = readLimited(zip, BackupCodec.MAX_BYTES)
                    if (name == "library.json") library = bytes.toString(Charsets.UTF_8)
                    else manifest = bytes.toString(Charsets.UTF_8)
                } else {
                    val target = File(directory, name.substringAfter('/'))
                    require(!target.exists()) { "Archivo duplicado." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            size += read
                            require(size <= MAX_AUDIO_BYTES) { "Archivo de audio demasiado grande." }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            bytesProcessed += read
                            require(bytesProcessed <= MAX_AUDIO_BYTES) { "El paquete de audio supera los 4 GB." }
                            if (bytesProcessed % (1024 * 1024) < read) progress(files.size, bytesProcessed)
                        }
                    }
                    files[name] = target
                    progress(files.size, bytesProcessed)
                    digests[name] = size to digest.digest().hex()
                }
                zip.closeEntry()
            }
        }
        val json = requireNotNull(library) { "Falta la biblioteca." }
        val root = JSONObject(requireNotNull(manifest) { "Falta el índice de audio." })
        require(root.getString("format") == "musicvault-migration" && root.getInt("version") == 1)
        val rows = root.getJSONArray("audio")
        require(rows.length() <= MAX_TRACKS && rows.length() == files.size) { "Índice de audio incompleto." }
        val result = mutableMapOf<String, File>()
        val listed = mutableSetOf<String>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("trackId")
            val name = row.getString("file")
            require(id.isNotBlank() && result[id] == null && listed.add(name) && audioName.matches(name))
            val actual = requireNotNull(digests[name]) { "Falta un audio." }
            require(actual.first > 0 && actual.first == row.getLong("size") &&
                actual.second.equals(row.getString("sha256"), ignoreCase = true)) { "Audio dañado." }
            result[id] = files.getValue(name)
        }
        return Result(json, result)
    }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "Metadatos demasiado grandes." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
