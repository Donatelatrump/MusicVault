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

        private val voiceMessageName = Regex("^AUD[-_]?\\d{8}(?:[-_]WA\\d+)?$", RegexOption.IGNORE_CASE)

        internal fun shouldIgnoreFile(fileName: String): Boolean {
            val base = fileName.substringBeforeLast('.', fileName).trim()
            if (voiceMessageName.matches(base)) return true
            // Keep the existing exclusion of numeric/date-only filenames, but
            // allow song titles containing dates and artists such as Audioslave.
            return base.isNotEmpty() && base.all {
                it.isDigit() || it == '-' || it == '_' || it == '.' || it == '/'
            }
        }
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

}
