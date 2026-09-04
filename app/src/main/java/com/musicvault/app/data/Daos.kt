package com.musicvault.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE userRemoved = 0 ORDER BY title") fun observeAll(): Flow<List<TrackEntity>>
    @Query("SELECT COUNT(*) FROM tracks") suspend fun count(): Int
    @Query(
        """
        SELECT * FROM tracks
        WHERE userRemoved = 0 AND (
            title LIKE '%' || REPLACE(REPLACE(:query, '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\'
            OR artistName LIKE '%' || REPLACE(REPLACE(:query, '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\'
            OR albumTitle LIKE '%' || REPLACE(REPLACE(:query, '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\'
        )
        ORDER BY title
        """
    )
    fun search(query: String): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1") suspend fun get(id: String): TrackEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<TrackEntity>)
    // Catalog metadata is a placeholder: it must never clobber an existing row,
    // otherwise REPLACE would reset isDownloaded/audioUri of already downloaded tracks.
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(items: List<TrackEntity>)
    @Query("DELETE FROM tracks WHERE id LIKE 'local:%' AND userRemoved = 0") suspend fun deleteLocalScannedTracks()
    @Query("UPDATE tracks SET userRemoved = 1 WHERE id = :id") suspend fun markRemoved(id: String)
    @Query("UPDATE tracks SET userRemoved = 0 WHERE id = :id") suspend fun unmarkRemoved(id: String)
    @Update suspend fun update(item: TrackEntity)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 AND userRemoved = 0 ORDER BY title") fun observeDownloaded(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun getByIds(ids: List<String>): List<TrackEntity>
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY title") fun observeAll(): Flow<List<AlbumEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<AlbumEntity>)
    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1") suspend fun get(id: String): AlbumEntity?
    @Query("DELETE FROM albums WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AlbumTrackDao {
    @Query("SELECT t.* FROM tracks t INNER JOIN album_tracks at ON at.trackId=t.id WHERE at.albumId=:albumId ORDER BY at.position")
    fun observeTracks(albumId: String): Flow<List<TrackEntity>>
    @Query("SELECT t.* FROM tracks t INNER JOIN album_tracks at ON at.trackId=t.id WHERE at.albumId=:albumId ORDER BY at.position")
    suspend fun getTracks(albumId: String): List<TrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<AlbumTrackEntity>)
    @Query("DELETE FROM album_tracks WHERE albumId=:albumId") suspend fun deleteTracks(albumId: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY title") fun observeAll(): Flow<List<PlaylistEntity>>
    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId=t.id WHERE pt.playlistId=:playlistId ORDER BY pt.position") fun observeTracks(playlistId: String): Flow<List<TrackEntity>>
    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId=t.id WHERE pt.playlistId=:playlistId ORDER BY pt.position") suspend fun getTracks(playlistId: String): List<TrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<PlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTracks(items: List<PlaylistTrackEntity>)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId") suspend fun deleteTracks(playlistId: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId AND trackId=:trackId") suspend fun deleteTrack(playlistId: String, trackId: String)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun delete(id: String)
}

@Dao
interface HistoryDao {
    @Insert suspend fun insert(item: PlayHistoryEntity): Long
    @Query("SELECT id FROM play_history WHERE trackId=:trackId ORDER BY playedAt DESC LIMIT 1") suspend fun latestId(trackId: String): Long?
    @Query("UPDATE play_history SET completionRatio=:ratio WHERE id=:id") suspend fun updateCompletion(id: Long, ratio: Float)
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<PlayHistoryEntity>>
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC") fun observeAll(): Flow<List<FavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(item: FavoriteEntity)
    @Delete suspend fun remove(item: FavoriteEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId=:trackId)") suspend fun isFavorite(trackId: String): Boolean
}


@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(item: SearchHistoryEntity)
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit") fun recent(limit: Int): Flow<List<SearchHistoryEntity>>
    @Query("DELETE FROM search_history") suspend fun clear()
}

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records WHERE trackId=:id LIMIT 1") suspend fun get(id: String): DownloadRecordEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(item: DownloadRecordEntity)
}


@Dao
interface CatalogSearchCacheDao {
    @Query("SELECT * FROM catalog_search_cache WHERE cacheKey=:cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): CatalogSearchCacheEntity?

    // LIMIT keeps this flow cheap: each row carries large JSON blobs and the
    // only consumer (Home recommendations) flattens the newest entries anyway.
    @Query("SELECT * FROM catalog_search_cache ORDER BY savedAt DESC LIMIT 12")
    fun observeAll(): Flow<List<CatalogSearchCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: CatalogSearchCacheEntity)

    @Query("DELETE FROM catalog_search_cache WHERE savedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
