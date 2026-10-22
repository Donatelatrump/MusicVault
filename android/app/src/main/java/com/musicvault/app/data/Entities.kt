package com.musicvault.app.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val year: Int,
    val coverUri: String? = null
)

@Entity(tableName = "tracks", indices = [
    Index(value = ["isDownloaded"]),
    Index(value = ["userRemoved", "title"]),
    Index(value = ["userRemoved", "isDownloaded", "title"])
])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val albumId: String,
    val albumTitle: String,
    val durationMs: Long,
    val audioUri: String?,
    val isDownloaded: Boolean,
    val coverUri: String? = null,
    val source: String? = null,
    val sourceId: String? = null,
    /**
     * True when the user removed this track from the library while the file is
     * still on the device (e.g. an audio file scanned from MediaStore whose
     * physical deletion was blocked by storage permissions). The scanner must
     * not resurrect userRemoved tracks even though MediaStore still reports
     * them.
     */
    val userRemoved: Boolean = false
)

/** Standalone FTS index. Triggers keep it synchronized with String-keyed tracks. */
@Fts4(tokenizer = "unicode61", tokenizerArgs = ["remove_diacritics=1"])
@Entity(tableName = "tracks_fts")
data class TrackSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val trackId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val coverUri: String? = null
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index(value = ["playlistId", "position"])]
)
data class PlaylistTrackEntity(val playlistId: String, val trackId: String, val position: Int)

@Entity(
    tableName = "album_tracks",
    primaryKeys = ["albumId", "trackId"],
    indices = [Index(value = ["albumId", "position"])]
)
data class AlbumTrackEntity(val albumId: String, val trackId: String, val position: Int)

@Entity(
    tableName = "play_history",
    indices = [
        Index(value = ["trackId"])
    ]
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val completionRatio: Float
)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val trackId: String, val createdAt: Long)


@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey val trackId: String,
    val path: String,
    val checksum: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)


@Entity(tableName = "catalog_search_cache")
data class CatalogSearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val query: String,
    val tracksJson: String,
    val albumsJson: String,
    val savedAt: Long
)

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val userId: String = "current_user",
    val generosFavoritos: String = "",  // separado por comas
    val artistasFavoritos: String = "",
    val primeraVezCompleto: Boolean = true,
    val fechaUltimaActualizacion: Long = java.lang.System.currentTimeMillis()
)
