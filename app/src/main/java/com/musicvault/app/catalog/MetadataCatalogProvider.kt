package com.musicvault.app.catalog

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel

/**
 * Multi-provider metadata facade.
 * iTunes/Deezer are fast public sources; MusicBrainz is retained as a structured fallback.
 * Track and album searches race independently so one category never delays the other.
 */
class MetadataCatalogProvider(
    private val providers: List<CatalogProvider> = listOf(
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

    suspend fun searchTracksOnly(query: String, limit: Int = 20): List<CatalogTrack> = coroutineScope {
        firstNonEmpty(providers.map { provider ->
            async { safeCall { provider.searchTracks(query, limit) } }
        })
    }

    suspend fun searchAlbumsOnly(query: String, limit: Int = 10): List<CatalogAlbum> = coroutineScope {
        firstNonEmpty(providers.map { provider ->
            async { safeCall { provider.searchAlbums(query, limit) } }
        })
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
        val preferred = providers.firstOrNull { itName(it) == album.provider }
        val ordered = buildList {
            preferred?.let { add(it) }
            providers.filter { it !== preferred }.forEach(::add)
        }

        // First use the exact provider/id returned by the search.
        for (source in ordered) {
            try {
                val exact = source.albumTracks(album.id, limit)
                if (exact.isNotEmpty()) return exact
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next provider below.
            }
        }

        // If an API rejected the original id, resolve the album again by title
        // and artist. This makes cached/mixed-provider album results resilient.
        val query = "${album.title} ${album.artistName}".trim()
        for (source in ordered) {
            try {
                val matches = source.searchAlbums(query, 5)
                val match = matches.firstOrNull {
                    it.title.equals(album.title, ignoreCase = true) &&
                    (album.artistName.isBlank() || it.artistName.equals(album.artistName, ignoreCase = true))
                } ?: matches.firstOrNull { it.title.equals(album.title, ignoreCase = true) }
                if (match != null) {
                    val tracks = source.albumTracks(match.id, limit)
                    if (tracks.isNotEmpty()) return tracks
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue to the next source.
            }
        }
        return emptyList()
    }

    override suspend fun albumTracks(albumId: String, limit: Int): List<CatalogTrack> = emptyList()

    private fun itName(provider: CatalogProvider): String = when (provider) {
        is ItunesCatalogProvider -> "itunes"
        is DeezerCatalogProvider -> "deezer"
        is MusicBrainzCatalogProvider -> "musicbrainz"
        else -> ""
    }
}
