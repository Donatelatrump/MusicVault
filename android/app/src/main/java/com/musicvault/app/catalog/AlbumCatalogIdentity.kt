package com.musicvault.app.catalog

import com.musicvault.app.data.Album

/** Downloaded metadata uses release:, search uses release-group:; both retain Deezer's album ID. */
object AlbumCatalogIdentity {
    fun fromStored(album: Album): CatalogAlbum? {
        val parts = album.id.split(":", limit = 3)
        if (parts.size != 3 || parts[2].isBlank()) return null
        val supported = (parts[0] == "deezer" && parts[1] in setOf("release", "release-group")) ||
            (parts[0] == "musicbrainz" && parts[1] == "release-group")
        if (!supported) return null
        return CatalogAlbum(parts[2], album.title, album.artistId, album.artistName,
            album.year.takeIf { it > 0 }, album.coverUri, parts[0])
    }
}
