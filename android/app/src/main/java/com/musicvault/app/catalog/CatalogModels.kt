package com.musicvault.app.catalog

import androidx.compose.runtime.Immutable

enum class CatalogContentType {
    MUSIC,
    PODCAST,
    ASMR,
    OTHER
}

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
    val provider: String = "musicbrainz",
    val description: String? = null,
    val url: String? = null,
    val source: String? = null,
    val sourceId: String? = null,
    val contentType: CatalogContentType = CatalogContentType.MUSIC
)

@Immutable
data class CatalogArtist(
    val id: String,
    val name: String,
    val country: String? = null,
    val disambiguation: String? = null,
    val portraitUri: String? = null
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

@Immutable
data class CatalogPlaylist(
    val id: String,
    val title: String,
    val uploaderName: String,
    val trackCount: Long = 0L,
    val coverUri: String? = null,
    val url: String,
    val provider: String = "youtube"
)
