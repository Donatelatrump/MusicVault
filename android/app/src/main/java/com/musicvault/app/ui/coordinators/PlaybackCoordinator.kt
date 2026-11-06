package com.musicvault.app.ui.coordinators

import android.content.Context
import com.musicvault.app.data.Track
import com.musicvault.app.player.StreamingCache
import com.musicvault.app.providers.Providers
import kotlinx.coroutines.withTimeoutOrNull

/** Audio acquisition is isolated from screen selection and generation ownership. */
class PlaybackCoordinator(private val context: Context, private val online: () -> Boolean) {
    suspend fun resolve(track: Track, prebuffer: Boolean, forceRefresh: Boolean): Track? {
        if (track.isDownloaded && !track.audioUri.isNullOrBlank()) return track
        StreamingCache.playbackUriIfComplete(context, track.id)?.let { return track.copy(audioUri = it, isDownloaded = false) }
        if (!online()) return null
        val sourceUrl = track.url ?: track.sourceId?.takeIf { track.source.equals("youtube", true) || track.id.startsWith("youtube:", true) }?.let { "https://www.youtube.com/watch?v=$it" }
        val resolved = withTimeoutOrNull(20_000L) { Providers.resolveStream(track.title, track.artistName, sourceUrl, track.id, forceRefresh) } ?: return null
        StreamingCache.rememberFormat(context, track.id, resolved.extension, resolved.mimeType)
        if (com.musicvault.app.download.DownloadPreferences.streamQuality(context) != com.musicvault.app.download.DownloadQuality.BETA && prebuffer && !StreamingCache.hasPrefetchedPrefix(context, track.id)) StreamingCache.prefetchPrefix(context, track.id, resolved.url)
        return track.copy(audioUri = resolved.url, isDownloaded = false)
    }
}
