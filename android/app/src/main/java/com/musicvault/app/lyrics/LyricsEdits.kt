package com.musicvault.app.lyrics

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** User edits are separate from provider/sibling LRC caches and always take precedence. */
object LyricsEdits {
    private fun file(context: Context, trackId: String): File {
        val id = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "lyrics-edits"), "$id.json")
    }
    private fun readRecord(context: Context, trackId: String): JSONObject? = runCatching {
        val source = file(context, trackId)
        if (!source.exists()) null else JSONObject(source.readText())
    }.getOrNull()
    fun read(context: Context, trackId: String): Lyrics? = readRecord(context, trackId)?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let(LyricsProvider::parseStoredLrc)?.copy(origin = "manual")
    fun offsetMs(context: Context, trackId: String): Long = readRecord(context, trackId)?.optLong("offset")?.coerceIn(-30_000L, 30_000L) ?: 0L
    fun applyOffset(context: Context, trackId: String, lyrics: Lyrics): Lyrics {
        val offset = offsetMs(context, trackId)
        return lyrics.copy(synced = lyrics.synced.map { it.copy(timeMs = (it.timeMs + offset).coerceAtLeast(0L)) })
    }
    fun raw(context: Context, trackId: String): String? = readRecord(context, trackId)?.optString("text")?.takeIf { it.isNotBlank() }
    /** Reject malformed/decreasing LRC times; plain text remains a valid edit. */
    fun validate(text: String): String? {
        if (text.length > 200_000) return "Las letras superan el límite de 200000 caracteres."
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val tags = timestamp.findAll(text).toList()
        if (text.contains(Regex("\\[\\d+:")) && tags.isEmpty()) return "Formato LRC inválido; usa [mm:ss.xx]."
        var previous = -1L
        for (tag in tags) {
            val seconds = tag.groupValues[2].toLong()
            if (seconds >= 60) return "Los segundos deben estar entre 00 y 59."
            val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            val time = tag.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction
            if (time < previous) return "Los tiempos LRC deben estar en orden ascendente."
            previous = time
        }
        return null
    }
    @Synchronized
    fun save(context: Context, trackId: String, text: String, offsetMs: Long): String? {
        validate(text)?.let { return it }
        return runCatching {
            val target = file(context, trackId)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(JSONObject().put("text", text.trim()).put("offset", offsetMs.coerceIn(-30_000L, 30_000L)).toString())
            check(temp.renameTo(target)) { "No se pudo guardar el cambio." }
            null
        }.getOrElse { it.message ?: "No se pudieron guardar las letras." }
    }
    fun setOffset(context: Context, trackId: String, offsetMs: Long): String? =
        save(context, trackId, raw(context, trackId).orEmpty(), offsetMs)
    fun delete(context: Context, trackId: String): Boolean = file(context, trackId).let { !it.exists() || it.delete() }
}
