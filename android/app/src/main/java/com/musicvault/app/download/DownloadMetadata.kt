package com.musicvault.app.download

import com.musicvault.app.data.Track
import com.musicvault.app.data.TrackEntity

object DownloadMetadata {
    fun snapshot(track: Track) = TrackEntity(track.id, track.title, track.artistName,
        track.albumId, track.albumTitle, track.durationMs, track.audioUri,
        track.isDownloaded, track.coverUri, track.source, track.sourceId)

    fun complete(existing: TrackEntity?, request: TrackEntity, audioUri: String): TrackEntity {
        val base = existing ?: request
        return base.copy(
            title = base.title.ifBlank { request.title },
            artistName = base.artistName.ifBlank { request.artistName },
            albumId = base.albumId.takeUnless { it.isBlank() || it == "unknown" } ?: request.albumId,
            albumTitle = base.albumTitle.takeUnless { it.isBlank() || it == "Álbum desconocido" } ?: request.albumTitle,
            durationMs = base.durationMs.takeIf { it > 0 } ?: request.durationMs,
            coverUri = base.coverUri ?: request.coverUri,
            source = base.source ?: request.source,
            sourceId = base.sourceId ?: request.sourceId,
            audioUri = audioUri, isDownloaded = true, userRemoved = false
        )
    }
}

enum class DownloadQuality(val label: String, val reuseStreamingCache: Boolean, val format: String) {
    BETA("Beta", false, "bestaudio/best"),
    BEST("Mejor calidad disponible", false, "bestaudio/best"),
    SAVER("Ahorrar datos", false, "bestaudio[abr<=128]/worstaudio/best");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: BEST
    }
}
