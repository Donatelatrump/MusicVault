package com.musicvault.app.ui.coordinators

import com.musicvault.app.catalog.*
import kotlinx.coroutines.*

/** Owns the independent fast/refined search jobs and propagates cancellation. */
class SearchCoordinator(private val catalog: CatalogRepository) {
    fun search(
        scope: CoroutineScope, query: String,
        fastTrackTimeout: Long, fastAlbumTimeout: Long,
        refineTrackTimeout: Long, refineAlbumTimeout: Long,
        onTracks: (List<CatalogTrack>) -> Unit, onAlbums: (List<CatalogAlbum>) -> Unit,
        onFailure: (Throwable) -> Unit, onFinished: () -> Unit
    ): Job = scope.launch {
        try {
            var albums: List<CatalogAlbum> = emptyList()
            coroutineScope {
                launch { withTimeoutOrNull(fastTrackTimeout) { catalog.searchTracksFast(query, 40) }.orEmpty().takeIf { it.isNotEmpty() }?.let(onTracks) }
                launch { albums = withTimeoutOrNull(fastAlbumTimeout) { catalog.searchAlbumsFast(query, 20) }.orEmpty(); if (albums.isNotEmpty()) onAlbums(albums) }
            }
            coroutineScope {
                launch { withTimeoutOrNull(refineTrackTimeout) { catalog.searchTracksMerged(query, 40) }.orEmpty().takeIf { it.isNotEmpty() }?.let(onTracks) }
                if (albums.isEmpty()) launch { withTimeoutOrNull(refineAlbumTimeout) { catalog.searchAlbumsMerged(query, 20) }.orEmpty().takeIf { it.isNotEmpty() }?.let(onAlbums) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
        finally { onFinished() }
    }
}
