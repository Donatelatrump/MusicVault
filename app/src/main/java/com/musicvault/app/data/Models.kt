package com.musicvault.app.data

import androidx.compose.runtime.Immutable

@Immutable
data class Artist(
    val id: String,
    val name: String
)

@Immutable
data class Album(
    val id: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val year: Int,
    val coverUri: String? = null
)

@Immutable
data class Track(
    val id: String,
    val title: String,
    val artistName: String,
    val albumId: String,
    val albumTitle: String,
    val durationMs: Long,
    val audioUri: String? = null,
    val isDownloaded: Boolean = false,
    val coverUri: String? = null,
    val source: String? = null,
    val sourceId: String? = null,
    val url: String? = null
)

@Immutable
data class Playlist(
    val id: String,
    val title: String,
    val description: String,
    val trackIds: List<String>
)
