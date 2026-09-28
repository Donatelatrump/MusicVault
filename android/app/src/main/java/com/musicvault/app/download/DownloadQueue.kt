package com.musicvault.app.download

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.musicvault.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object DownloadQueue {
    private val lock = Mutex()
    private val active = setOf("queued", "running", "waiting")
    fun isActive(state: String) = state in active

    suspend fun enqueue(context: Context, track: Track, sourceUrl: String? = track.url) =
        enqueueAll(context, listOf(track to sourceUrl))

    suspend fun enqueueAll(context: Context, tracks: List<Pair<Track, String?>>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = MusicDatabase.get(context)
            val dao = db.downloadQueueDao()
            // Commit the whole playlist before scheduling: a process exit cannot
            // lose the songs that had not yet reached the concurrency window.
            val requests = db.withTransaction {
                tracks.distinctBy { it.first.id }.mapNotNull { (track, url) ->
                    val existing = db.trackDao().get(track.id)
                    if (existing?.isDownloaded == true && !existing.audioUri.isNullOrBlank()) {
                        DownloadEvents.result(context, track.id, com.musicvault.app.providers.ProviderResult.Success(
                            audioUri = existing.audioUri, fileName = existing.title))
                        return@mapNotNull null
                    }
                    val prior = dao.get(track.id)
                    if (prior != null && isActive(prior.state)) prior else {
                        DownloadQueueEntity(track.id, UUID.randomUUID().toString(),
                            DownloadMetadata.snapshot(track), url,
                            DownloadPreferences.quality(context).name, DownloadPreferences.wifiOnly(context))
                            .also { dao.save(it) }
                    }
                }
            }
            requests.forEach { schedule(context, it) }
        }
    }

    private fun schedule(context: Context, item: DownloadQueueEntity) {
        val constraints = Constraints.Builder().setRequiresStorageNotLow(true)
        if (item.wifiOnly && android.os.Build.VERSION.SDK_INT >= 28) {
            constraints.setRequiredNetworkRequest(android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), NetworkType.CONNECTED)
        } else {
            constraints.setRequiredNetworkType(if (item.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        }
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(UUID.fromString(item.generation))
            .setInputData(workDataOf("trackId" to item.id, "generation" to item.generation))
            .setConstraints(constraints.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("musicvault-download")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-${item.generation}", ExistingWorkPolicy.KEEP, work).result.get()
    }

    suspend fun cancel(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dao = MusicDatabase.get(context).downloadQueueDao()
            val row = dao.get(id) ?: return@withLock
            if (!isActive(row.state)) return@withLock
            dao.cancel(id)
            com.musicvault.app.providers.Providers.cancel(row.generation)
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(row.generation)).result.get()
            DownloadEvents.cancelled(context, id)
        }
    }

    suspend fun retry(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dao = MusicDatabase.get(context).downloadQueueDao()
            val row = dao.get(id) ?: return@withLock
            if (isActive(row.state) || row.state == "complete") return@withLock
            val updated = row.copy(generation = UUID.randomUUID().toString(), state = "queued",
                progress = 0, attempts = 0, error = null, updatedAt = System.currentTimeMillis(),
                quality = DownloadPreferences.quality(context).name, wifiOnly = DownloadPreferences.wifiOnly(context))
            dao.save(updated)
            schedule(context, updated)
        }
    }

    suspend fun retryFailed(context: Context) {
        MusicDatabase.get(context).downloadQueueDao().failed().forEach { retry(context, it.id) }
    }

    /** WorkManager recovers across reboot; this repairs the commit/schedule gap. */
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dao = MusicDatabase.get(context).downloadQueueDao()
            val wm = WorkManager.getInstance(context)
            dao.pending().forEach { row ->
                val info = wm.getWorkInfoById(UUID.fromString(row.generation)).get()
                when {
                    info == null -> schedule(context, row)
                    info.state == WorkInfo.State.CANCELLED -> dao.cancel(row.id)
                    info.state.isFinished -> dao.update(row.id, row.generation, "failed", row.progress,
                        "La tarea se interrumpió. Puedes reintentarla.", row.attempts)
                }
            }
        }
    }
}
