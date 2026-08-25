package com.musicvault.app.library

import android.content.ContentResolver
import android.provider.MediaStore
import com.musicvault.app.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAudioScanner(
    private val resolver: ContentResolver
) {
    companion object {
        const val MIN_MUSIC_DURATION_MS = 90_000L
    }

    suspend fun scan(): List<TrackEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<TrackEntity>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf(MIN_MUSIC_DURATION_MS.toString()),
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    .buildUpon()
                    .appendPath(mediaId.toString())
                    .build()

                val fileName = cursor.getString(displayName).orEmpty()
                if (shouldIgnoreFile(fileName)) continue

                val safeTitle = cursor.getString(title) ?: "Unknown"
                val safeArtist = cursor.getString(artist) ?: "Unknown Artist"
                val safeAlbum = cursor.getString(album) ?: "Unknown Album"

                result += TrackEntity(
                    id = "local:$mediaId",
                    title = safeTitle,
                    artistName = safeArtist,
                    albumId = "local-album:$safeArtist:$safeAlbum",
                    albumTitle = safeAlbum,
                    durationMs = cursor.getLong(duration),
                    audioUri = uri.toString(),
                    isDownloaded = true
                )
            }
        }

        result
    }

    private fun shouldIgnoreFile(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        if (base.startsWith("AUD", ignoreCase = true)) return true
        if (base.isNotEmpty() && base.all { it.isDigit() || it == '-' || it == '_' || it == '.' || it == '/' }) return true
        // Common date-only names: YYYY-MM-DD, YYYY_MM_DD, YYYYMMDD, DD-MM-YYYY, etc.
        val digits = base.filter(Char::isDigit)
        if (digits.length == 8 && base.count { it.isDigit() } == 8) return true
        if (Regex("^\\d{4}[-_.]\\d{2}[-_.]\\d{2}$").matches(base)) return true
        if (Regex("^\\d{2}[-_.]\\d{2}[-_.]\\d{4}$").matches(base)) return true
        return false
    }
}
