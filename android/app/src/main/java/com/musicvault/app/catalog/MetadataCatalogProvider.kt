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
        block()
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

    suspend fun searchTracksFast(query: String, limit: Int = 20): List<CatalogTrack> =
        fastWithQualityGate(
            query,
            fetch = { p -> safeCall { p.searchTracks(query, limit) } },
            score = { t, q -> SearchScorer.score(t, q, 0, 1) }
        )

    /**
     * Same scoring as [searchTracksFast] but always merges across every
     * provider (no early-exit on first non-empty list). Used when the UI wants
     * the best precision even at the cost of latency.
     */
    suspend fun searchTracksMerged(query: String, limit: Int = 20): List<CatalogTrack> = coroutineScope {
        val ordered = orderedTrackProviders(query)
        val results = mergeAll(ordered.map { provider ->
            async { safeCall { provider.searchTracks(query, limit) } }
        })
        SearchScorer.mergeAndRank(query, listOf(results)).take(limit)
    }

    suspend fun searchAlbumsFast(query: String, limit: Int = 10): List<CatalogAlbum> =
        filterReachableAlbums(
            fastWithQualityGate(
                query,
                fetch = { p -> safeCall { p.searchAlbums(query, limit) }.filterValidAlbums() },
                score = { a, q -> SearchScorer.scoreAlbum(a, q, 0, 1) }
            )
        )

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
        if (!album.isValid()) return emptyList()

        val preferred = providers.firstOrNull { itName(it) == album.provider }
        val ordered = buildList {
            preferred?.let { add(it) }
            providers.filter { it !== preferred }.forEach(::add)
        }

        // First use the exact provider/id returned by the search.
        for (source in ordered) {
            try {
                val exact = source.albumTracks(album.id, limit).filterValidTracks()
                if (exact.isNotEmpty()) return SearchScorer.mergeAndRank(
                    "${album.title} ${album.artistName}",
                    listOf(exact)
                ).take(limit)
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
    }
}
