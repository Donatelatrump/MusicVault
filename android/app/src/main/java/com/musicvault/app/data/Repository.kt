package com.musicvault.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class MusicRepository(private val db: MusicDatabase) {
    fun tracks(): Flow<List<Track>> =
        db.trackDao().observeAll().distinctUntilChanged().map { list -> list.map(::toTrack) }.flowOn(Dispatchers.IO)

    fun search(query: String): Flow<List<Track>> {
        val normalizedQuery = query.trim()
        val expression = ftsQuery(normalizedQuery)
        return if (expression.isBlank()) emptyFlow()
        else db.trackDao().search(expression).map { list -> list.map(::toTrack) }.flowOn(Dispatchers.IO)
    }

    private fun ftsQuery(query: String): String = Regex("[\\p{L}\\p{Nd}]+")
        .findAll(query)
        .map { "\"${it.value.replace("\"", "\"\"")}\"*" }
        .joinToString(" AND ")

    fun downloaded(): Flow<List<Track>> =
        db.trackDao().observeDownloaded().distinctUntilChanged().map { list -> list.map(::toTrack) }.flowOn(Dispatchers.IO)

    fun albums(): Flow<List<Album>> =
        db.albumDao().observeAll().distinctUntilChanged().map { list ->
            list.map {
                Album(it.id, it.title, it.artistId, it.artistName, it.year, it.coverUri)
            }
        }

    fun customAlbumTracks(id: String): Flow<List<Track>> =
        db.albumTrackDao().observeTracks(id).distinctUntilChanged().map { list -> list.map(::toTrack) }.flowOn(Dispatchers.IO)

    suspend fun getAlbumTracks(id: String): List<Track> =
        db.albumTrackDao().getTracks(id).map(::toTrack)

    fun playlists(): Flow<List<Playlist>> =
        db.playlistDao().observeAll().distinctUntilChanged().map { list ->
            list.map { Playlist(it.id, it.title, it.description, emptyList(), it.coverUri) }
        }

    fun playlistTracks(id: String): Flow<List<Track>> =
        db.playlistDao().observeTracks(id).distinctUntilChanged().map { list -> list.map(::toTrack) }.flowOn(Dispatchers.IO)

    suspend fun getPlaylistTracks(id: String): List<Track> =
        db.playlistDao().getTracks(id).map(::toTrack)

    suspend fun containsSpotifyImport(kind: String, spotifyId: String): Boolean = when (kind) {
        "track" -> db.trackDao().containsSpotifyTrack(spotifyId)
        "playlist" -> db.playlistDao().contains("playlist:spotify:$spotifyId")
        else -> false
    }

    suspend fun createPlaylist(id: String, title: String, description: String) {
        db.playlistDao().upsertAll(listOf(PlaylistEntity(id, title, description)))
    }

    suspend fun createAlbum(id: String, title: String, tracks: List<Track>) {
        db.albumDao().upsertAll(listOf(AlbumEntity(id, title, "custom", "Tu biblioteca", 0, tracks.firstOrNull()?.coverUri)))
        db.albumTrackDao().upsertAll(tracks.mapIndexed { index, track -> AlbumTrackEntity(id, track.id, index) })
    }

    suspend fun addTracksToPlaylist(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        db.withTransaction {
            val dao = db.playlistDao()
            val existing = dao.trackIds(id).toHashSet()
            val additions = tracks.filter { existing.add(it.id) }
            val start = dao.nextPosition(id)
            dao.upsertTracks(additions.mapIndexed { index, track -> PlaylistTrackEntity(id, track.id, start + index) })
        }
    }

    suspend fun addTrackToPlaylist(id: String, track: Track) = addTracksToPlaylist(id, listOf(track))

    suspend fun importSpotifyTrack(track: Track) {
        db.withTransaction {
            val current = db.trackDao().get(track.id)
            val incoming = TrackEntity(
                track.id, track.title, track.artistName, track.albumId, track.albumTitle,
                track.durationMs, track.audioUri, track.isDownloaded, track.coverUri,
                track.source, track.sourceId
            )
            db.trackDao().upsertAll(listOf(
                if (current?.isDownloaded == true && current.audioUri != null) {
                    incoming.copy(audioUri = current.audioUri, isDownloaded = true)
                } else incoming
            ))
        }
    }

    suspend fun replaceSpotifyPlaylist(id: String, title: String, coverUri: String?, tracks: List<Track>) {
        db.withTransaction {
            val trackDao = db.trackDao()
            // Bound IN queries stay below SQLite's variable limit on older devices.
            // Preserve downloaded files with batch reads instead of one query/song.
            val existing = tracks.map { it.id }.distinct().chunked(500)
                .flatMap { trackDao.getByIds(it) }.associateBy { it.id }
            val entities = tracks.map { track ->
                val current = existing[track.id]
                val incoming = TrackEntity(
                    track.id, track.title, track.artistName, track.albumId, track.albumTitle,
                    track.durationMs, track.audioUri, track.isDownloaded, track.coverUri,
                    track.source, track.sourceId
                )
                if (current?.isDownloaded == true && current.audioUri != null) {
                    incoming.copy(audioUri = current.audioUri, isDownloaded = true)
                } else incoming
            }
            trackDao.upsertAll(entities)
            val playlistDao = db.playlistDao()
            playlistDao.upsertAll(listOf(PlaylistEntity(id, title, "Importada desde Spotify", coverUri)))
            playlistDao.deleteTracks(id)
            playlistDao.upsertTracks(tracks.mapIndexed { index, track -> PlaylistTrackEntity(id, track.id, index) })
        }
    }

    suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
        db.playlistDao().deleteTrack(id, trackId)
    }

    suspend fun addTrackToAlbum(id: String, track: Track) {
        val current = db.albumTrackDao().getTracks(id)
        db.albumTrackDao().upsertAll(listOf(AlbumTrackEntity(id, track.id, current.size)))
    }

    suspend fun deleteAlbum(id: String) {
        db.albumTrackDao().deleteTracks(id)
        db.albumDao().delete(id)
    }

    suspend fun deletePlaylist(id: String) {
        db.playlistDao().deleteTracks(id)
        db.playlistDao().delete(id)
    }


    fun recentSearches(limit: Int = 12): Flow<List<String>> =
        db.searchHistoryDao().recent(limit).map { items -> items.map { it.query } }

    suspend fun recordSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        db.withTransaction {
            db.searchHistoryDao().deleteQuery(normalized)
            db.searchHistoryDao().add(SearchHistoryEntity(query = normalized))
            db.searchHistoryDao().trim(50)
        }
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
        db.withTransaction {
            db.historyDao().insert(
                PlayHistoryEntity(
                    trackId = track.id,
                    playedAt = System.currentTimeMillis(),
                    completionRatio = completionRatio
                )
            )
            db.historyDao().trim(2_000)
        }
    }

    /** Apply a MediaStore snapshot without invalidating Room when nothing changed. */
    suspend fun syncScannedTracks(scanned: List<TrackEntity>) = db.withTransaction {
        val dao = db.trackDao()
        val existing = dao.localScannedTracks().associateBy { it.id }
        val incomingIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
        val changed = scanned.filter { incoming ->
            val current = existing[incoming.id]
            current?.userRemoved != true && current != incoming
        }
        val removed = existing.values.asSequence()
            .filter { !it.userRemoved && it.id !in incomingIds }
            .map { it.id }.toList()
        changed.chunked(500).forEach { dao.upsertAll(it) }
        removed.chunked(500).forEach { dao.deleteByIds(it) }
    }

    suspend fun updateLatestCompletion(trackId: String, completionRatio: Float) {
        val id = db.historyDao().latestId(trackId) ?: return
        db.historyDao().updateCompletion(id, completionRatio.coerceIn(0f, 1f))
    }


    suspend fun deleteTrack(id: String) {
        db.trackDao().delete(id)
    }

    /**
     * Marks a track as user-removed without deleting the row. Used for
     * MediaStore-backed tracks whose physical file deletion was blocked by
     * storage permissions: the row stays so the scanner can see the flag and
     * leave the file alone on future rescans.
     */
    suspend fun markTrackRemoved(id: String) {
        db.trackDao().markRemoved(id)
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
                coverUri = track.coverUri,
                source = track.source,
                sourceId = track.sourceId
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
        coverUri = t.coverUri,
        source = t.source,
        sourceId = t.sourceId
    )
}
