package com.musicvault.app.lyrics

import android.content.Context
import com.musicvault.app.data.Track
import com.musicvault.app.providers.NewPipeStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Shared by playback and durable downloads, independent of the activity. */
object DownloadLyrics {
    suspend fun fetch(appContext: Context, track: Track, localReference: String?): com.musicvault.app.lyrics.Lyrics? = coroutineScope {
        val sourceVideoId = track.sourceId?.takeIf { it.length == 11 }
            ?: Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(track.url.orEmpty())?.groupValues?.get(1)
        // resolveAudio also indexes captions by the logical track id. This is
        // essential for Spotify/Deezer entries whose sourceId is not YouTube's
        // 11-character id even though their audio was matched to YouTube.
        val captionKey = listOf(track.id, sourceVideoId).firstOrNull(NewPipeStreamResolver::hasCachedYoutubeCaptions)
        val exactVideoCaptionsAvailable = captionKey != null
        val youtube = async {
            runCatching { NewPipeStreamResolver.cachedYoutubeLyrics(captionKey) }.getOrNull()?.let {
                LyricsProvider.keepOnlyTrustedSync(it, track.durationMs, exactTimeline = true)
            }
        }
        val lrclib = async {
            runCatching {
                LyricsProvider.fetch(
                    artist = track.artistName,
                    title = track.title,
                    album = track.albumTitle.takeIf { it.isNotBlank() && !it.equals("Álbum desconocido", ignoreCase = true) },
                    durationMs = track.durationMs
                )
            }.getOrNull()?.let {
                LyricsProvider.keepOnlyTrustedSync(
                    VocalActivityDetector.align(appContext, localReference, it),
                    track.durationMs
                )
            }
        }
        // Captions from the exact video own its timeline. Do not let a faster
        // LRCLib response for the studio edit beat them (e.g. Stressed Out's
        // official video starts singing at 16.585s). LRCLib still runs in
        // parallel, so a failed caption fetch adds no second serial wait.
        if (exactVideoCaptionsAvailable) {
            val exact = youtube.await()
            if (exact != null && !exact.isEmpty) {
                lrclib.cancel()
                return@coroutineScope exact
            }
            return@coroutineScope lrclib.await()
        }

        val first = select<Pair<Boolean, com.musicvault.app.lyrics.Lyrics?>> {
            youtube.onAwait { true to it }
            lrclib.onAwait { false to it }
        }
        val firstLyrics = first.second
        if (firstLyrics != null && !firstLyrics.isEmpty) {
            if (first.first) lrclib.cancel() else youtube.cancel()
            firstLyrics
        } else {
            if (first.first) lrclib.await() else youtube.await()
        }
    }

    suspend fun persist(context: Context, track: Track, reference: String) {
        if (LyricsLocalStore.read(context, reference) != null) return
        val lyrics = fetch(context, track, reference) ?: return
        LyricsLocalStore.write(context, reference, lyrics)
    }
}
