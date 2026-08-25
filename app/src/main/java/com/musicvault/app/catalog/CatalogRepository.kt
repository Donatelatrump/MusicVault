package com.musicvault.app.catalog

import com.musicvault.app.data.AlbumEntity
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.TrackEntity

class CatalogRepository(
    private val provider: CatalogProvider,
    private val db: MusicDatabase
) {
    suspend fun search(query: String, limitTracks: Int = 20, limitAlbums: Int = 10): MetadataCatalogProvider.SearchResult {
        val results = if (provider is MetadataCatalogProvider) {
            provider.searchAll(query, limitTracks, limitAlbums)
        } else {
            MetadataCatalogProvider.SearchResult(
                tracks = provider.searchTracks(query, limitTracks),
                albums = provider.searchAlbums(query, limitAlbums)
            )
        }
        persist(results)
        return results
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<CatalogTrack> {
        val results = if (provider is MetadataCatalogProvider) provider.searchTracksOnly(query, limit)
        else provider.searchTracks(query, limit)
        persistTracks(results)
        return results
    }

    suspend fun searchAlbums(query: String, limit: Int = 10): List<CatalogAlbum> {
        val results = if (provider is MetadataCatalogProvider) provider.searchAlbumsOnly(query, limit)
        else provider.searchAlbums(query, limit)
        persistAlbums(results)
        return results
    }

    suspend fun tracksForAlbum(album: CatalogAlbum, limit: Int = 50): List<CatalogTrack> {
        val results = if (provider is MetadataCatalogProvider) {
            provider.tracksForAlbum(album, limit)
        } else {
            try {
                provider.albumTracks(album.id, limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        persistTracks(results)
        return results
    }

    private suspend fun persist(results: MetadataCatalogProvider.SearchResult) {
        persistTracks(results.tracks)
        persistAlbums(results.albums)
    }

    private suspend fun persistTracks(results: List<CatalogTrack>) {
        if (results.isEmpty()) return
        db.trackDao().upsertAll(results.map {
            TrackEntity(
                id = "${it.provider}:recording:${it.id}", title = it.title, artistName = it.artistName,
                albumId = it.albumId?.let { id -> "${it.provider}:release:$id" } ?: "unknown",
                albumTitle = it.albumTitle ?: "Álbum desconocido", durationMs = it.durationMs,
                audioUri = null, isDownloaded = false, coverUri = it.coverUri
            )
        })
    }

    private suspend fun persistAlbums(results: List<CatalogAlbum>) {
        if (results.isEmpty()) return
        db.albumDao().upsertAll(results.map {
            AlbumEntity("${it.provider}:release-group:${it.id}", it.title, it.artistId, it.artistName, it.year ?: 0, it.coverUri)
        })
    }
}
