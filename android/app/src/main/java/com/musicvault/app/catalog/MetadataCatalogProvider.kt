package com.musicvault.app.catalog

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multi-provider metadata facade.
 * Deezer is the primary metadata source: clean track/artist titles, accurate
 * metadata, fast public API. YouTube is the secondary provider and only kicks
 * in when Deezer comes back empty or with low-quality matches (very low top
 * score vs the query). Track and album searches race independently so one
 * category never delays the other.
 */
class MetadataCatalogProvider(
    private val primaryProviders: List<CatalogProvider> = listOf(
        DeezerCatalogProvider()
    ),
    private val secondaryProviders: List<CatalogProvider> = listOf(
        YouTubeMetadataProvider()
    ),
    private val fallbackProviders: List<CatalogProvider> = emptyList()
) : CatalogProvider {

    private val providers: List<CatalogProvider> =
        primaryProviders + secondaryProviders + fallbackProviders

    /** Providers whose result is considered authoritative when non-empty. */
    val effectiveProviders: List<CatalogProvider> get() = providers

    data class SearchResult(
        val tracks: List<CatalogTrack>,
        val albums: List<CatalogAlbum>
    )

    private suspend fun <T> safeCall(block: suspend () -> List<T>): List<T> = try {
        if (com.musicvault.app.providers.Providers.isOffline()) emptyList() else block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun <T> mergeAll(tasks: List<Deferred<List<T>>>): List<T> = coroutineScope {
        val results = tasks.map { task ->
            async {
                try {
                    task.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList<T>()
                }
            }
        }.awaitAll()
        results.flatten()
    }

    private suspend fun <T> firstNonEmpty(tasks: List<Deferred<List<T>>>): List<T> = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope emptyList()
        val channel = Channel<List<T>>(tasks.size)
        val forwarders = tasks.map { task ->
            async {
                try {
                    channel.send(task.await())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(emptyList())
                }
            }
        }
        var remaining = tasks.size
        var winner: List<T>? = null
        while (remaining > 0 && winner == null) {
            val result = channel.receive()
            remaining--
            if (result.isNotEmpty()) winner = result
        }
        forwarders.forEach { it.cancel() }
        tasks.forEach { it.cancel() }
        channel.close()
        winner ?: emptyList()
    }

    private fun orderedTrackProviders(query: String): List<CatalogProvider> {
        val normalized = query.trim().lowercase()
        val youtubeFirst = normalized.contains("podcast") ||
            normalized.contains("asmr") ||
            normalized.contains("audiolibro") ||
            normalized.contains("audiobook") ||
            normalized.contains("entrevista") ||
            normalized.contains("charla") ||
            normalized.contains("talk") ||
            normalized.contains("conferencia")
        return if (youtubeFirst) {
            secondaryProviders + primaryProviders + fallbackProviders
        } else {
            primaryProviders + secondaryProviders + fallbackProviders
        }
    }

    /**
     * Returns the first non-empty list across [providers] using a soft
     * quality gate. The fastest non-empty response wins the race as long as
     * its top match is strong enough relative to the query. If the primary
     * provider is slow, the secondary provider's first non-empty response
     * is shown immediately instead of waiting.
     */
    private suspend fun <T : Any> fastWithQualityGate(
        query: String,
        fetch: suspend (CatalogProvider) -> List<T>,
        score: (T, String) -> Float
    ): List<T> = coroutineScope {
        if (providers.isEmpty()) return@coroutineScope emptyList()
        val channel = Channel<List<T>>(providers.size)
        val forwarders = providers.map { provider ->
            async {
                try {
                    channel.send(fetch(provider))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(emptyList())
                }
            }
        }
        var remaining = providers.size
        var winner: List<T>? = null
        while (remaining > 0 && winner == null) {
            val result = channel.receive()
            remaining--
            if (result.isNotEmpty()) {
                val top = result.maxOf { score(it, query) }
                if (top >= PRIMARY_WEAK_THRESHOLD) {
                    winner = result
                }
            }
        }
        forwarders.forEach { it.cancel() }
        channel.close()
        winner ?: emptyList()
    }

    /**
     * Fast song search deliberately does NOT race yt-dlp against Deezer.
     *
     * yt-dlp ultimately performs blocking native/process work and is not guaranteed to
     * stop immediately when its coroutine is cancelled. The old race could therefore
     * receive a perfectly good Deezer result, cancel YouTube, then still remain inside
     * coroutineScope until the YouTube child unwound. The ViewModel timeout would fire
     * first and the UI ended up with zero songs while albums (which do not use yt-dlp)
     * appeared normally.
     *
     * For ordinary music searches we query the ordered primary source first and return
     * immediately when it has a solid match. Secondary providers are only touched when
     * the preferred source really returned no useful result.
     */
    suspend fun searchTracksFast(query: String, limit: Int = 20): List<CatalogTrack> {
        val ordered = orderedTrackProviders(query)
        if (ordered.isEmpty()) return emptyList()

        var bestWeakResult: List<CatalogTrack> = emptyList()
        for ((index, source) in ordered.withIndex()) {
            val timeoutMs = trackSearchTimeoutMs(source, merged = false, isFirst = index == 0)
            // yt-dlp has non-trivial process startup cost. Asking it for the full
            // 40-row UI page on the fallback path only delays first paint, so fetch
            // a compact first page and return it immediately when it is relevant.
            val providerLimit = if (source is YouTubeMetadataProvider) {
                minOf(limit, YOUTUBE_FAST_RESULT_LIMIT)
            } else {
                limit
            }
            val raw = withTimeoutOrNull(timeoutMs) {
                safeCall { source.searchTracks(query, providerLimit) }
            }.orEmpty().filterValidTracks()
            if (raw.isEmpty()) continue

            val ranked = SearchScorer.mergeAndRank(query, listOf(raw)).take(limit)
            if (ranked.isEmpty()) continue

            val topScore = ranked.maxOf { SearchScorer.score(it, query, 0, 1) }
            if (topScore >= PRIMARY_WEAK_THRESHOLD) return ranked
            if (bestWeakResult.isEmpty()) bestWeakResult = ranked
        }
        return bestWeakResult
    }

    /**
     * Same scoring as [searchTracksFast] but always merges across every
     * provider (no early-exit on first non-empty list). Used when the UI wants
     * the best precision even at the cost of latency.
     */
    suspend fun searchTracksMerged(query: String, limit: Int = 20): List<CatalogTrack> {
        val ordered = orderedTrackProviders(query)
        if (ordered.isEmpty()) return emptyList()

        val buckets = mutableListOf<List<CatalogTrack>>()
        for ((index, source) in ordered.withIndex()) {
            val timeoutMs = trackSearchTimeoutMs(source, merged = true, isFirst = index == 0)
            val result = withTimeoutOrNull(timeoutMs) {
                safeCall { source.searchTracks(query, limit) }
            }.orEmpty().filterValidTracks()
            if (result.isEmpty()) continue

            buckets += result
            // The merged pass intentionally continues into YouTube even when
            // Deezer returned several usable rows. NewPipe search is cheap enough
            // (~1-2 s on the test phone) and fixes queries where Deezer returns
            // plausible-but-poor matches (ASMR, mixes, talks, niche uploads, etc.).
            // The UI already published the fast Deezer page, so this refinement
            // never blocks first paint.
        }
        return SearchScorer.mergeAndRank(query, buckets).take(limit)
    }

    suspend fun searchAlbumsFast(query: String, limit: Int = 10): List<CatalogAlbum> =
        fastWithQualityGate(
            query,
            fetch = { p -> safeCall { p.searchAlbums(query, limit) }.filterValidAlbums() },
            score = { a, q -> SearchScorer.scoreAlbum(a, q, 0, 1) }
        )

    // Do not probe every album before first paint. A reachability check used to
    // fan out into N extra album-detail requests and made an ordinary artist
    // search wait several seconds. Album reachability is resolved only when the
    // user actually opens the album via tracksForAlbum().

    suspend fun searchAlbumsMerged(query: String, limit: Int = 10): List<CatalogAlbum> = coroutineScope {
        val ordered = orderedTrackProviders(query)
        val results = mergeAll(ordered.map { provider ->
            async { safeCall { provider.searchAlbums(query, limit) }.filterValidAlbums() }
        })
        SearchScorer.mergeAndRankAlbums(query, listOf(results)).take(limit)
    }

    /**
     * Returns [albums] with the ones whose [tracksForAlbum] would return empty
     * removed. Probing is bounded by [ALBUM_PROBE_TIMEOUT_MS] so a slow network
     * does not stall the search result list.
     */
    private suspend fun filterReachableAlbums(albums: List<CatalogAlbum>): List<CatalogAlbum> = coroutineScope {
        if (albums.isEmpty()) return@coroutineScope albums
        val probes = albums.map { album ->
            async {
                val ok = try {
                    withTimeoutOrNull(ALBUM_PROBE_TIMEOUT_MS) {
                        tracksForAlbum(album, 1).isNotEmpty()
                    } ?: false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (ok) album else null
            }
        }
        probes.mapNotNull { it.await() }
    }

    suspend fun searchAll(query: String, limitTracks: Int = 20, limitAlbums: Int = 10): SearchResult = coroutineScope {
        val tracksDeferred = async { searchTracksFast(query, limitTracks) }
        val albumsDeferred = async { searchAlbumsFast(query, limitAlbums) }
        SearchResult(
            tracks = tracksDeferred.await(),
            albums = albumsDeferred.await()
        )
    }

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> =
        searchTracksMerged(query, limit)

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = coroutineScope {
        firstNonEmpty(providers.map { provider ->
            async { safeCall { provider.searchArtists(query, limit) } }
        })
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> =
        searchAlbumsMerged(query, limit)

    suspend fun tracksForAlbum(album: CatalogAlbum, limit: Int = 50): List<CatalogTrack> {
        if (com.musicvault.app.providers.Providers.isOffline()) return emptyList()
        if (!album.isValid()) return emptyList()

        val preferred = providers.firstOrNull { itName(it) == album.provider }
        val ordered = buildList {
            preferred?.let { add(it) }
            providers.filter { it !== preferred }.forEach(::add)
        }

        // First use the exact provider/id returned by the search.
        for (source in ordered.filter { itName(it) == album.provider }) {
            try {
                val exact = source.albumTracks(album.id, limit).filterValidTracks()
                if (exact.isNotEmpty()) return exact.take(limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next provider below.
            }
        }

        // If an API rejected the original id, resolve the album again by title
        // and artist. This makes cached/mixed-provider album results resilient.
        val query = "${album.title} ${album.artistName}".trim()
        if (query.isBlank()) return emptyList()

        for (source in ordered) {
            try {
                val matches = source.searchAlbums(query, 5)
                    .filterValidAlbums()
                val match = matches.firstOrNull {
                    it.title.equals(album.title, ignoreCase = true) &&
                        (album.artistName.isBlank() || it.artistName.equals(album.artistName, ignoreCase = true))
                } ?: matches.firstOrNull { it.title.equals(album.title, ignoreCase = true) }
                if (match != null) {
                    val tracks = source.albumTracks(match.id, limit).filterValidTracks()
                    if (tracks.isNotEmpty()) return tracks.take(limit)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue to the next source.
            }
        }
        return emptyList()
    }


    /**
     * Deezer normally answers in well under a second. YouTube now uses NewPipe
     * first and yt-dlp only as a compatibility fallback, so keep enough budget
     * for a NewPipe request plus one fallback without treating a transient delay
     * as an authoritative empty search.
     */
    private fun trackSearchTimeoutMs(source: CatalogProvider, merged: Boolean, isFirst: Boolean): Long {
        if (source is YouTubeMetadataProvider) {
            return if (merged) TRACK_YOUTUBE_MERGE_TIMEOUT_MS else TRACK_YOUTUBE_FAST_TIMEOUT_MS
        }
        return if (merged) {
            if (isFirst) TRACK_PRIMARY_MERGE_TIMEOUT_MS else TRACK_FALLBACK_MERGE_TIMEOUT_MS
        } else {
            if (isFirst) TRACK_PRIMARY_FAST_TIMEOUT_MS else TRACK_FALLBACK_FAST_TIMEOUT_MS
        }
    }

    private fun CatalogAlbum.isValid(): Boolean =
        id.isNotBlank() && title.isNotBlank() && artistName.isNotBlank()

    private fun List<CatalogTrack>.filterValidTracks(): List<CatalogTrack> = filter {
        it.id.isNotBlank() && it.title.isNotBlank()
    }

    private fun List<CatalogAlbum>.filterValidAlbums(): List<CatalogAlbum> = filter {
        it.id.isNotBlank() && it.title.isNotBlank() && it.artistName.isNotBlank()
    }

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> = emptyList()

    private fun itName(provider: CatalogProvider): String = when (provider) {
        is YouTubeMetadataProvider -> "youtube"
        is DeezerCatalogProvider -> "deezer"
        is MusicBrainzCatalogProvider -> "musicbrainz"
        else -> ""
    }

    companion object {
        /**
         * Per-album timeout for the "is this album reachable?" probe that runs
         * after ranking. Keeps a slow provider from blocking the whole list.
         */
        private const val ALBUM_PROBE_TIMEOUT_MS = 1_500L

        /**
         * Below this score the top primary-provider match is considered
         * weak and the fast-path falls through to the secondary provider.
         * Threshold tuned empirically: a clean "Friends - Marshmello" hit
         * scores around 140, while off-topic matches cluster below 60.
         */
        private const val PRIMARY_WEAK_THRESHOLD = 60f
        private const val MIN_PRIMARY_TRACK_RESULTS = 3
        private const val YOUTUBE_FAST_RESULT_LIMIT = 12
        private const val TRACK_PRIMARY_FAST_TIMEOUT_MS = 3_200L
        private const val TRACK_FALLBACK_FAST_TIMEOUT_MS = 2_200L
        private const val TRACK_PRIMARY_MERGE_TIMEOUT_MS = 3_500L
        private const val TRACK_FALLBACK_MERGE_TIMEOUT_MS = 2_500L
        private const val TRACK_YOUTUBE_FAST_TIMEOUT_MS = 12_000L
        private const val TRACK_YOUTUBE_MERGE_TIMEOUT_MS = 14_000L
    }
}
