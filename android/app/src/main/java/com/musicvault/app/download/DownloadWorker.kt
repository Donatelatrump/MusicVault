package com.musicvault.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.musicvault.app.MainActivity
import com.musicvault.app.R
import com.musicvault.app.data.*
import com.musicvault.app.providers.*
import kotlinx.coroutines.*

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString("trackId") ?: return@withContext Result.failure()
        val generation = inputData.getString("generation") ?: return@withContext Result.failure()
        val context = applicationContext
        val db = MusicDatabase.get(context)
        val dao = db.downloadQueueDao()
        val row = dao.get(trackId) ?: return@withContext Result.success()
        if (row.generation != generation || !DownloadQueue.isActive(row.state)) return@withContext Result.success()
        var wifiLost = false
        try {
            if (row.wifiOnly && !DownloadPreferences.wifiAvailable(context)) {
                dao.update(trackId, generation, "waiting", 0, "Esperando Wi-Fi", row.attempts)
                return@withContext Result.retry()
            }
            setForeground(foreground(row.track.title, 0))
            dao.update(trackId, generation, "running", 0, null, row.attempts + 1)
            Providers.init(context)
            var lastProgress = -1
            var lastTime = 0L
            val result = coroutineScope {
                val transfer = async {
                    Providers.download(row.track.title, row.track.artistName,
                        requestId = generation, sourceUrl = row.sourceUrl, cacheKey = trackId,
                        quality = DownloadQuality.parse(row.quality), onProgress = { value ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (value != lastProgress && (value == 100 || now - lastTime >= 750)) {
                                lastTime = now
                                lastProgress = value
                                val changed = runBlocking { dao.update(trackId, generation, "running", value, null, row.attempts + 1) }
                                if (changed > 0) {
                                    DownloadEvents.progress(context, trackId, value)
                                    setForegroundAsync(foreground(row.track.title, value))
                                }
                            }
                        })
                }
                val networkWatch = launch {
                    while (isActive && !transfer.isCompleted && row.wifiOnly) {
                        delay(1000)
                        if (!DownloadPreferences.wifiAvailable(context)) {
                            wifiLost = true
                            Providers.cancel(generation)
                            transfer.cancel()
                            break
                        }
                    }
                }
                try { transfer.await() } catch (cancelled: CancellationException) {
                    // Stop blocking native IO before coroutineScope waits for
                    // the transfer child, including WorkManager constraint stops.
                    Providers.cancel(generation)
                    throw cancelled
                } finally { networkWatch.cancel() }
            }
            ensureActive()
            if (wifiLost) {
                dao.update(trackId, generation, "waiting", 0, "Esperando Wi-Fi", row.attempts)
                return@withContext Result.retry()
            }
            when (result) {
                is ProviderResult.Success -> {
                    val stored = db.withTransaction {
                        val current = dao.get(trackId)
                        if (current?.generation != generation || !DownloadQueue.isActive(current.state)) false else {
                            val track = DownloadMetadata.complete(db.trackDao().get(trackId), row.track, result.audioUri)
                            if (track.albumId != "unknown") db.albumDao().insertIfAbsent(
                                AlbumEntity(track.albumId, track.albumTitle, "artist:${track.artistName}", track.artistName, 0, track.coverUri))
                            db.trackDao().upsertAll(listOf(track))
                            db.downloadRecordDao().save(DownloadRecordEntity(trackId, result.localPath ?: result.audioUri))
                            dao.update(trackId, generation, "complete", 100, null, row.attempts + 1)
                            true
                        }
                    }
                    if (stored) {
                        DownloadEvents.result(context, trackId, result)
                        try {
                            val saved = db.trackDao().get(trackId) ?: row.track
                            val track = Track(saved.id, saved.title, saved.artistName, saved.albumId,
                                saved.albumTitle, saved.durationMs, result.audioUri, true, saved.coverUri,
                                saved.source, saved.sourceId, row.sourceUrl)
                            withTimeoutOrNull(20_000) {
                                com.musicvault.app.lyrics.DownloadLyrics.persist(context, track, result.localPath ?: result.audioUri)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // Lyrics are optional: a failed lookup must not undo saved audio.
                            android.util.Log.w("MusicVaultDownload", "Offline lyrics unavailable", failure)
                        }
                    }
                    Result.success()
                }
                else -> {
                    val message = when (result) {
                        is ProviderResult.Error -> result.message
                        is ProviderResult.Unavailable -> result.reason
                        else -> "No se pudo descargar"
                    }
                    val retry = result is ProviderResult.Error && row.attempts < 2
                    val changed = dao.update(trackId, generation, if (retry) "waiting" else "failed", 0, message, row.attempts + 1)
                    if (!retry && changed > 0) DownloadEvents.result(context, trackId, result)
                    if (retry) Result.retry() else Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            Providers.cancel(generation)
            withContext(NonCancellable) {
                dao.update(trackId, generation, "waiting", 0, if (row.wifiOnly) "Esperando Wi-Fi" else "Descarga interrumpida; pendiente", row.attempts)
            }
            if (wifiLost && currentCoroutineContext().isActive) Result.retry() else throw cancelled
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            android.util.Log.e("MusicVaultDownload", "Download worker failed", failure)
            val message = "No se pudo preparar o completar la descarga. Reintenta desde Descargas."
            if (dao.update(trackId, generation, "failed", 0, message, row.attempts + 1) > 0) {
                DownloadEvents.result(context, trackId, ProviderResult.Error(message))
            }
            Result.failure()
        }
    }

    private fun foreground(title: String, progress: Int): ForegroundInfo {
        val context = applicationContext
        val channel = "musicvault_downloads"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Descargas", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.musicvault_icon).setContentTitle(title)
            .setContentText("Descargando… $progress%")
            .setProgress(100, progress, progress == 0).setOngoing(true)
            .setContentIntent(open).setGroup("musicvault-downloads").build()
        val notificationId = 10000 + (id.hashCode() and 0x0fffffff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}
