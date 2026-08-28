package com.musicvault.app.catalog

import androidx.compose.runtime.Immutable

@Immutable
data class CatalogTrack(
    val id: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val albumId: String?,
    val albumTitle: String?,
    val durationMs: Long,
    val coverUri: String? = null,
    val provider: String = "musicbrainz"
)

@Immutable
data class CatalogArtist(
    val id: String,
    val name: String,
    val country: String? = null,
    val disambiguation: String? = null
)

@Immutable
data class CatalogAlbum(
    val id: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val year: Int? = null,
    val coverUri: String? = null,
    val provider: String = "musicbrainz"
)
