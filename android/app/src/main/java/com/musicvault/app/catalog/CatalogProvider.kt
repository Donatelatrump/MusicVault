package com.musicvault.app.catalog

interface CatalogProvider {
    suspend fun searchTracks(query: String, limit: Int = 20): List<CatalogTrack>
    suspend fun searchArtists(query: String, limit: Int = 10): List<CatalogArtist>
    suspend fun searchAlbums(query: String, limit: Int = 10): List<CatalogAlbum>

    /** Returns tracks belonging to a catalog album identified by this provider. */
    suspend fun albumTracks(albumId: String, limit: Int = 50): List<CatalogTrack>
}
