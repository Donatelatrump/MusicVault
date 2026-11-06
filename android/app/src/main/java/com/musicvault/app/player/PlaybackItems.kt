package com.musicvault.app.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.musicvault.app.data.Track

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object PlaybackItems {
    fun build(context: Context, track: Track): MediaItem? {
        val reference = track.audioUri?.takeIf { it.isNotBlank() } ?: return null
        val uri = Uri.parse(reference)
        val remote = uri.scheme in listOf("http", "https")
        val builder = MediaItem.Builder().setUri(uri).setMediaId(track.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artistName)
                .setAlbumTitle(track.albumTitle).setArtworkUri(track.coverUri?.let(Uri::parse))
                .setIsBrowsable(false).setIsPlayable(true)
                .setExtras(Bundle().apply { putString(PlaybackSnapshot.KEY, PlaybackSnapshot(track, 0).encode()) }).build())
        if (remote && !track.isDownloaded) {
            StreamingCache.touch(context, track.id)
            val beta = com.musicvault.app.download.DownloadPreferences.streamQuality(context) == com.musicvault.app.download.DownloadQuality.BETA
            builder.setCustomCacheKey(if (beta) "musicvault-beta:${track.id}:" + java.security.MessageDigest.getInstance("SHA-256").digest(reference.toByteArray()).joinToString("") { "%02x".format(it) } else StreamingCache.cacheKey(track.id))
        }
        return builder.build()
    }
    fun isReadableLocal(context: Context, track: Track): Boolean {
        val raw = track.audioUri ?: return false
        val uri = Uri.parse(raw)
        return runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                "file" -> java.io.File(uri.path.orEmpty()).canRead()
                null -> java.io.File(raw).canRead()
                else -> false
            }
        }.getOrDefault(false)
    }
}
