package com.musicvault.app.data

data class Artist(
    val id: String,
    val name: String
)

data class Album(
    val id: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val year: Int,
    val coverUri: String? = null
)

data class Track(
    val id: String,
    val title: String,
    val artistName: String,
    val albumId: String,
    val albumTitle: String,
    val durationMs: Long,
    val audioUri: String? = null,
    val isDownloaded: Boolean = false,
    val coverUri: String? = null
)

data class Playlist(
    val id: String,
    val title: String,
    val description: String,
    val trackIds: List<String>
)
