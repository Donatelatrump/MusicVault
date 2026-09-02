package com.musicvault.app.lyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local LRC helper for downloaded tracks.
 *
 * The download layer gives us a stable local reference to the audio file
 * (filesystem path for app-private storage or content URI for SAF storage).
 * This helper reads/writes the sibling .lrc file next to that reference.
 */
object LyricsLocalStore {
    suspend fun read(context: Context, localReference: String?): Lyrics? = withContext(Dispatchers.IO) {
        val reference = localReference?.trim().orEmpty()
        if (reference.isBlank()) return@withContext null

        val raw = when {
            reference.startsWith("content://") -> readDocument(context, Uri.parse(reference))
            else -> readFile(File(reference))
        } ?: return@withContext null

        LyricsProvider.parseStoredLrc(raw)
    }

    suspend fun write(context: Context, localReference: String?, lyrics: Lyrics): Boolean = withContext(Dispatchers.IO) {
        val reference = localReference?.trim().orEmpty()
        if (reference.isBlank()) return@withContext false

        val text = LyricsProvider.buildLrc(lyrics)
        if (text.isBlank()) return@withContext false

        when {
            reference.startsWith("content://") -> writeDocument(context, Uri.parse(reference), text)
            else -> writeFile(File(reference), text)
        }
    }

    private fun readFile(audioFile: File): String? {
        val lrcFile = audioFile.siblingLrc()
        if (!lrcFile.exists()) return null
        return runCatching { lrcFile.readText() }.getOrNull()
    }

    private fun writeFile(audioFile: File, text: String): Boolean {
        val lrcFile = audioFile.siblingLrc()
        return runCatching {
            lrcFile.parentFile?.mkdirs()
            if (lrcFile.exists()) {
                val existing = lrcFile.readText()
                if (LyricsProvider.parseStoredLrc(existing) != null) return true
            }
            lrcFile.writeText(text)
            true
        }.getOrDefault(false)
    }

    private fun readDocument(context: Context, audioUri: Uri): String? {
        val audioDoc = DocumentFile.fromSingleUri(context, audioUri) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val lrcName = audioDoc.name?.baseName()?.let { "$it.lrc" } ?: return null
        val lrcDoc = parent.listFiles().firstOrNull { it.name == lrcName } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(lrcDoc.uri)?.use { input ->
                input.bufferedReader().use { it.readText() }
            }
        }.getOrNull()
    }

    private fun writeDocument(context: Context, audioUri: Uri, text: String): Boolean {
        val audioDoc = DocumentFile.fromSingleUri(context, audioUri) ?: return false
        val parent = audioDoc.parentFile ?: return false
        val lrcName = audioDoc.name?.baseName()?.let { "$it.lrc" } ?: return false

        val existing = parent.listFiles().firstOrNull { it.name == lrcName }
        if (existing != null) {
            val current = runCatching {
                context.contentResolver.openInputStream(existing.uri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                }
            }.getOrNull()
            if (current?.let(LyricsProvider::parseStoredLrc) != null) return true
            runCatching { existing.delete() }
        }

        val doc = parent.createFile("text/plain", lrcName) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    private fun File.siblingLrc(): File {
        val base = nameWithoutExtension.ifBlank { name }
        val parent = parentFile ?: return File("$base.lrc")
        return File(parent, "$base.lrc")
    }

    private fun String.baseName(): String {
        val dot = lastIndexOf('.')
        return if (dot > 0) substring(0, dot) else this
    }
}
