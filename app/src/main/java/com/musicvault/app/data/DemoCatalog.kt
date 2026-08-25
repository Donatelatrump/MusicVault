package com.musicvault.app.data

object DemoCatalog {
    val artists = listOf(
        ArtistEntity("a1", "Neon Valley"),
        ArtistEntity("a2", "The Night Drive"),
        ArtistEntity("a3", "Static Hearts"),
        ArtistEntity("a4", "Velvet Circuit")
    )

    val albums = listOf(
        AlbumEntity("al1", "After Midnight", "a1", "Neon Valley", 2025, null),
        AlbumEntity("al2", "Road Lights", "a2", "The Night Drive", 2024, null),
        AlbumEntity("al3", "Electric Dreams", "a3", "Static Hearts", 2026, null),
        AlbumEntity("al4", "Soft Machines", "a4", "Velvet Circuit", 2023, null)
    )

    val tracks = listOf(
        TrackEntity("t1", "After Midnight", "Neon Valley", "al1", "After Midnight", 218000, null, false),
        TrackEntity("t2", "Blue Horizon", "Neon Valley", "al1", "After Midnight", 244000, null, false),
        TrackEntity("t3", "City Lights", "The Night Drive", "al2", "Road Lights", 201000, null, false),
        TrackEntity("t4", "Highway Echoes", "The Night Drive", "al2", "Road Lights", 263000, null, false),
        TrackEntity("t5", "Electric Dreams", "Static Hearts", "al3", "Electric Dreams", 226000, null, false),
        TrackEntity("t6", "Static Rain", "Static Hearts", "al3", "Electric Dreams", 238000, null, false),
        TrackEntity("t7", "Soft Machines", "Velvet Circuit", "al4", "Soft Machines", 211000, null, false),
        TrackEntity("t8", "Glass Signals", "Velvet Circuit", "al4", "Soft Machines", 251000, null, false)
    )

    val playlists = listOf(
        PlaylistEntity("p1", "Night Drive", "Synths, lights and late-night roads."),
        PlaylistEntity("p2", "Focus Mode", "A calm selection for studying and coding."),
        PlaylistEntity("p3", "Daily Mix", "A local mix generated from your listening history.")
    )

    val playlistTracks = listOf(
        PlaylistTrackEntity("p1", "t1", 0),
        PlaylistTrackEntity("p1", "t3", 1),
        PlaylistTrackEntity("p1", "t5", 2),
        PlaylistTrackEntity("p1", "t7", 3),
        PlaylistTrackEntity("p2", "t2", 0),
        PlaylistTrackEntity("p2", "t6", 1),
        PlaylistTrackEntity("p2", "t8", 2),
        PlaylistTrackEntity("p3", "t1", 0),
        PlaylistTrackEntity("p3", "t4", 1),
        PlaylistTrackEntity("p3", "t6", 2),
        PlaylistTrackEntity("p3", "t8", 3)
    )

    suspend fun seedIfEmpty(db: MusicDatabase) {
        if (db.trackDao().count() > 0) return
        db.trackDao().upsertAll(tracks)
        db.albumDao().upsertAll(albums)
        db.playlistDao().upsertAll(playlists)
        db.playlistDao().upsertTracks(playlistTracks)
    }

    @Deprecated("Use seedIfEmpty()")
    suspend fun seed(db: MusicDatabase) = seedIfEmpty(db)
}
