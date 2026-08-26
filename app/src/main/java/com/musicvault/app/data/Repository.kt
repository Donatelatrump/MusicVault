package com.musicvault.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class MusicRepository(private val db: MusicDatabase) {
    fun tracks(): Flow<List<Track>> =
        db.trackDao().observeAll().map { list -> list.map(::toTrack) }

    fun search(query: String): Flow<List<Track>> =
        db.trackDao().search(query).map { list -> list.map(::toTrack) }

    fun downloaded(): Flow<List<Track>> =
        db.trackDao().observeDownloaded().map { list -> list.map(::toTrack) }

    fun albums(): Flow<List<Album>> =
        db.albumDao().observeAll().map { list ->
            list.map {
                Album(it.id, it.title, it.artistId, it.artistName, it.year, it.coverUri)
            }
        }

    fun customAlbumTracks(id: String): Flow<List<Track>> =
        db.albumTrackDao().observeTracks(id).map { list -> list.map(::toTrack) }

    suspend fun getAlbumTracks(id: String): List<Track> =
        db.albumTrackDao().observeTracks(id).first().map(::toTrack)

    fun playlists(): Flow<List<Playlist>> =
        db.playlistDao().observeAll().map { list ->
            list.map { Playlist(it.id, it.title, it.description, emptyList()) }
        }

    fun playlistTracks(id: String): Flow<List<Track>> =
        db.playlistDao().observeTracks(id).map { list -> list.map(::toTrack) }

    suspend fun getPlaylistTracks(id: String): List<Track> =
        db.playlistDao().getTracks(id).map(::toTrack)

    suspend fun createPlaylist(id: String, title: String, description: String) {
        db.playlistDao().upsertAll(listOf(PlaylistEntity(id, title, description)))
    }

    suspend fun createAlbum(id: String, title: String, tracks: List<Track>) {
        db.albumDao().upsertAll(listOf(AlbumEntity(id, title, "custom", "Tu biblioteca", 0, tracks.firstOrNull()?.coverUri)))
        db.albumTrackDao().upsertAll(tracks.mapIndexed { index, track -> AlbumTrackEntity(id, track.id, index) })
    }

    suspend fun addTracksToPlaylist(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val existing = db.playlistDao().observeTracks(id)
        // Preserve the existing ordering by appending after the current count.
        val current = existing.first()
        val start = current.size
        db.playlistDao().upsertTracks(tracks.mapIndexed { index, track -> PlaylistTrackEntity(id, track.id, start + index) })
    }

    suspend fun addTrackToPlaylist(id: String, track: Track) = addTracksToPlaylist(id, listOf(track))

    suspend fun addTrackToAlbum(id: String, track: Track) {
        val current = db.albumTrackDao().observeTracks(id).first()
        db.albumTrackDao().upsertAll(listOf(AlbumTrackEntity(id, track.id, current.size)))
    }

    suspend fun deleteAlbum(id: String) {
        db.albumTrackDao().deleteTracks(id)
        db.albumDao().delete(id)
    }


    fun recentSearches(limit: Int = 12): Flow<List<String>> =
        db.searchHistoryDao().recent(limit).map { items -> items.map { it.query } }

    suspend fun recordSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        db.searchHistoryDao().add(SearchHistoryEntity(query = normalized))
    }

    fun favorites(): Flow<Set<String>> =
        db.favoriteDao().observeAll().map { list -> list.mapTo(HashSet()) { it.trackId } }

    suspend fun toggleFavorite(trackId: String): Boolean {
        val favorite = db.favoriteDao().isFavorite(trackId)
        if (favorite) {
            db.favoriteDao().remove(FavoriteEntity(trackId, 0L))
            return false
        }
        db.favoriteDao().add(FavoriteEntity(trackId, System.currentTimeMillis()))
        return true
    }

    suspend fun recordPlay(track: Track, completionRatio: Float) {
        db.historyDao().insert(
            PlayHistoryEntity(
                trackId = track.id,
                playedAt = System.currentTimeMillis(),
                completionRatio = completionRatio
            )
        )
    }

    suspend fun updateLatestCompletion(trackId: String, completionRatio: Float) {
        val id = db.historyDao().latestId(trackId) ?: return
        db.historyDao().updateCompletion(id, completionRatio.coerceIn(0f, 1f))
    }


    suspend fun deleteTrack(id: String) {
        db.trackDao().delete(id)
    }

    suspend fun setDownloaded(track: Track, audioUri: String) {
        db.trackDao().update(
            TrackEntity(
                id = track.id,
                title = track.title,
                artistName = track.artistName,
                albumId = track.albumId,
                albumTitle = track.albumTitle,
                durationMs = track.durationMs,
                audioUri = audioUri,
                isDownloaded = true,
                coverUri = track.coverUri
            )
        )
    }

    private fun toTrack(t: TrackEntity) = Track(
        id = t.id,
        title = t.title,
        artistName = t.artistName,
        albumId = t.albumId,
        albumTitle = t.albumTitle,
        durationMs = t.durationMs,
        audioUri = t.audioUri,
        isDownloaded = t.isDownloaded,
        coverUri = t.coverUri
    )
}
