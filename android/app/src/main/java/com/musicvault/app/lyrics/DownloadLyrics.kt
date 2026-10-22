package com.musicvault.app.lyrics

import android.content.Context
import com.musicvault.app.data.Track
import com.musicvault.app.providers.NewPipeStreamResolver
import kotlinx.coroutines.*

/** Shared by playback and durable downloads, independent of the activity. */
object DownloadLyrics {
    internal fun usesCatalogLyrics(source: String?): Boolean =
        source.equals("Deezer", ignoreCase = true) || source.equals("Spotify", ignoreCase = true)

    internal fun canUseStoredLyrics(source: String?, lyrics: Lyrics): Boolean =
        lyrics.origin == if (usesCatalogLyrics(source)) "lrclib" else "youtube"

    internal inline fun fetchFromSource(source: String?, youtube: () -> Lyrics?, catalog: () -> Lyrics?): Lyrics? =
        if (usesCatalogLyrics(source)) catalog() else youtube()

    suspend fun fetch(appContext: Context, track: Track, localReference: String?): Lyrics? = withContext(Dispatchers.IO) {
        fetchFromSource(track.source, youtube = {
            val sourceVideoId = track.sourceId?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
                ?: Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(track.url.orEmpty())?.groupValues?.get(1)
            val captionKey = listOf(track.id, sourceVideoId).firstOrNull(NewPipeStreamResolver::hasCachedYoutubeCaptions)
                ?: NewPipeStreamResolver.cachedYoutubeVideoId(track.id) ?: sourceVideoId
            lyricsAttempt { NewPipeStreamResolver.cachedYoutubeLyrics(captionKey) }?.let {
                LyricsProvider.keepOnlyTrustedSync(it, track.durationMs, exactTimeline = true).copy(origin = "youtube")
            }
        }, catalog = {
            lyricsAttempt {
                LyricsProvider.fetch(
                    artist = track.artistName,
                    title = track.title,
                    album = track.albumTitle.takeIf { it.isNotBlank() && !it.equals("Álbum desconocido", ignoreCase = true) },
                    durationMs = track.durationMs
                )
            }?.let {
                LyricsProvider.keepOnlyTrustedSync(
                    VocalActivityDetector.align(appContext, localReference, it), track.durationMs
                ).copy(origin = "lrclib")
            }
        })
    }

    suspend fun persist(context: Context, track: Track, reference: String) {
        val stored = LyricsLocalStore.read(context, reference)
        if (stored != null && canUseStoredLyrics(track.source, stored)) return
        val lyrics = fetch(context, track, reference) ?: return
        LyricsLocalStore.write(context, reference, lyrics)
    }
}
