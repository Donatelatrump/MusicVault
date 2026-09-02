package com.musicvault.app.catalog

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multi-provider metadata facade.
 * iTunes/Deezer are fast public sources; MusicBrainz is retained as a structured fallback.
 * Track and album searches race independently so one category never delays the other.
 */
class MetadataCatalogProvider(
    private val providers: List<CatalogProvider> = listOf(
        YouTubeMetadataProvider(),
        ItunesCatalogProvider(),
        DeezerCatalogProvider(),
        MusicBrainzCatalogProvider()
    )
) : CatalogProvider {
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
        val youtubeProviders = providers.filterIsInstance<YouTubeMetadataProvider>()
        val others = providers.filterNot { it is YouTubeMetadataProvider }
        return if (youtubeFirst) youtubeProviders + others else others + youtubeProviders
    }

    suspend fun searchTracksOnly(query: String, limit: Int = 20): List<CatalogTrack> {
        val ordered = orderedTrackProviders(query)
        val fast = coroutineScope {
            firstNonEmpty(ordered.map { provider ->
                async { safeCall { provider.searchTracks(query, limit) } }
            })
        }
        if (fast.size >= FALLBACK_THRESHOLD) {
            return SearchScorer.mergeAndRank(query, listOf(fast)).take(limit)
        }

        val enriched = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            searchTracksMerged(query, limit)
        } ?: fast

        return SearchScorer.mergeAndRank(query, listOf(fast, enriched)).take(limit)
    }

    /**
     * Same scoring as [searchTracksOnly] but always merges across every provider
     * (no early-exit on first non-empty list). Used when we want the best
     * precision even at the cost of latency.
     */
    suspend fun searchTracksMerged(query: String, limit: Int = 20): List<CatalogTrack> = coroutineScope {
        val ordered = orderedTrackProviders(query)
        val results = mergeAll(ordered.map { provider ->
            async { safeCall { provider.searchTracks(query, limit) } }
        })
        SearchScorer.mergeAndRank(query, listOf(results)).take(limit)
    }

    suspend fun searchAlbumsOnly(query: String, limit: Int = 10): List<CatalogAlbum> {
        val ordered = orderedTrackProviders(query)
        val fast = coroutineScope {
            firstNonEmpty(ordered.map { provider ->
                async { safeCall { provider.searchAlbums(query, limit) } }
            })
        }.filterValidAlbums()
        if (fast.size >= FALLBACK_THRESHOLD) {
            return SearchScorer.mergeAndRankAlbums(query, listOf(fast)).take(limit)
        }
        val enriched = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            coroutineScope {
                mergeAll(ordered.map { provider ->
                    async { safeCall { provider.searchAlbums(query, limit) } }
                })
            }.filterValidAlbums()
        } ?: fast
        return SearchScorer.mergeAndRankAlbums(query, listOf(fast, enriched)).take(limit)
    }

    suspend fun searchAll(query: String, limitTracks: Int = 20, limitAlbums: Int = 10): SearchResult = coroutineScope {
        val tracksDeferred = async { searchTracksOnly(query, limitTracks) }
        val albumsDeferred = async { searchAlbumsOnly(query, limitAlbums) }
        SearchResult(
            tracks = tracksDeferred.await(),
            albums = albumsDeferred.await()
        )
    }

    override suspend fun searchTracks(query: String, limit: Int): List<CatalogTrack> = searchTracksOnly(query, limit)
    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = coroutineScope {
        firstNonEmpty(providers.map { provider ->
            async { safeCall { provider.searchArtists(query, limit) } }
        })
    }
    override suspend fun searchAlbums(query: String, limit: Int): List<CatalogAlbum> = searchAlbumsOnly(query, limit)

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
        is ItunesCatalogProvider -> "itunes"
        is DeezerCatalogProvider -> "deezer"
        is MusicBrainzCatalogProvider -> "musicbrainz"
        else -> ""
    }

    companion object {
        /**
         * When the fast path returns fewer than this many results, we still
         * fan out to every provider before declaring "nothing to show".
         * Tuned for the average user: most real songs match on iTunes/Deezer,
         * but obscure queries (regional artists, ASMR, podcasts) need YouTube.
         */
        private const val FALLBACK_THRESHOLD = 3
        private const val FALLBACK_TIMEOUT_MS = 1_500L
    }
}
