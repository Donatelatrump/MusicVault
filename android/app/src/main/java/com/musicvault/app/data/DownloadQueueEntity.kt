package com.musicvault.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download_queue")
data class DownloadQueueEntity(
    @PrimaryKey val id: String,
    val generation: String,
    @Embedded(prefix = "track_") val track: TrackEntity,
    val sourceUrl: String?,
    val quality: String,
    val wifiOnly: Boolean,
    val state: String = "queued",
    val progress: Int = 0,
    val attempts: Int = 0,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val requiresCharging: Boolean = false
)

@Dao
interface DownloadQueueDao {
    @Query("SELECT * FROM download_queue ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadQueueEntity>>
    @Query("SELECT * FROM download_queue WHERE id=:id")
    suspend fun get(id: String): DownloadQueueEntity?
    @Query("SELECT * FROM download_queue WHERE state IN ('queued','running','waiting')")
    suspend fun pending(): List<DownloadQueueEntity>
    @Query("SELECT * FROM download_queue WHERE state='failed'")
    suspend fun failed(): List<DownloadQueueEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: DownloadQueueEntity)
    @Query("UPDATE download_queue SET state=:state, progress=:progress, error=:error, attempts=:attempts, updatedAt=:now WHERE id=:id AND generation=:generation AND state NOT IN ('cancelled','complete')")
    suspend fun update(id: String, generation: String, state: String, progress: Int, error: String?, attempts: Int, now: Long = System.currentTimeMillis()): Int
    @Query("UPDATE download_queue SET state='cancelled', updatedAt=:now WHERE id=:id AND state IN ('queued','running','waiting','failed')")
    suspend fun cancel(id: String, now: Long = System.currentTimeMillis())
}
