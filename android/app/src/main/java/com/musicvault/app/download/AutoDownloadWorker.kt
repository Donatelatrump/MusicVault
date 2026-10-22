package com.musicvault.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.musicvault.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Periodic selection only; durable DownloadQueue handles concurrency and deduplication. */
class AutoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (com.musicvault.app.settings.AppPreferences.prefs(applicationContext).getBoolean("offline_mode", false)) return Result.success()
        val prefs = AutoDownloads.prefs(applicationContext)
        val selection = prefs.getStringSet("playlists", emptySet()) ?: emptySet()
        if (selection.isEmpty()) return Result.success()
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return Result.retry()
        try {
            val db = MusicDatabase.get(applicationContext)
            val selected = selection.flatMap { id -> if (id == MusicRepository.FAVORITES_PLAYLIST_ID) {
                db.favoriteDao().exportAll().map { it.trackId }.chunked(400).flatMap { db.trackDao().getByIds(it) }
            } else db.playlistDao().getTracks(id) }.distinctBy { it.id }.filter { !it.userRemoved }
            val selectedIds = selected.mapTo(hashSetOf()) { it.id }
            db.downloadQueueDao().pending().filter { it.requiresCharging && it.id !in selectedIds }
                .forEach { DownloadQueue.cancel(applicationContext, it.id) }
            val limitBytes = prefs.getInt("quota_mb", 1024).toLong() * 1024 * 1024
            val used = com.musicvault.app.library.LibraryStorage.snapshot(applicationContext).bytes
            var reserved = db.downloadQueueDao().pending().sumOf { estimate(it.track) }
            val candidates = mutableListOf<TrackEntity>()
            for (t in selected) {
                if (candidates.size >= 50) break
                if (t.isDownloaded) continue
                val pending = db.downloadQueueDao().get(t.id)
                // A completed queue row is historical: cleanup may have removed
                // its audio since then. The current library determines availability.
                val eligible = pending == null || !DownloadQueue.isActive(pending.state)
                if (eligible && used + reserved + estimate(t) <= limitBytes) {
                    reserved += estimate(t)
                    candidates += t
                }
            }
            DownloadQueue.enqueueAll(applicationContext, candidates.map { t -> Track(t.id, t.title, t.artistName,
                t.albumId, t.albumTitle, t.durationMs, t.audioUri, t.isDownloaded, t.coverUri, t.source, t.sourceId) to null },
                wifiOnlyOverride = true, requiresCharging = true)
            prefs.edit().putLong("last_run", System.currentTimeMillis()).putInt("last_added", candidates.size)
                .putString("status", if (used + reserved >= limitBytes) "Cuota alcanzada" else "${candidates.size} canciones añadidas a la cola").apply()
            return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { prefs.edit().putString("status", failure.message ?: "No se pudo programar").apply(); return Result.retry() }
    }
    private fun estimate(t: TrackEntity): Long = (t.durationMs.coerceAtLeast(180000) * 320 / 8).coerceAtLeast(1024 * 1024)
}
object AutoDownloads {
    private const val WORK = "musicvault-auto-download"
    fun prefs(context: Context) = context.getSharedPreferences("musicvault.auto-download", Context.MODE_PRIVATE)
    suspend fun configure(context: Context, playlists: Set<String>, quotaMb: Int = 1024) = withContext(Dispatchers.IO) {
        require(quotaMb in 128..102400)
        prefs(context).edit().putStringSet("playlists", playlists).putInt("quota_mb", quotaMb).apply()
        val wm = WorkManager.getInstance(context)
        val db = MusicDatabase.get(context)
        val selectedIds = playlists.flatMap { id -> if (id == MusicRepository.FAVORITES_PLAYLIST_ID)
            db.favoriteDao().exportAll().map { it.trackId } else db.playlistDao().trackIds(id) }.toSet()
        db.downloadQueueDao().pending().filter { it.requiresCharging && it.id !in selectedIds }
            .forEach { DownloadQueue.cancel(context, it.id) }
        if (playlists.isEmpty()) { wm.cancelUniqueWork(WORK); return@withContext }
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresStorageNotLow(true).setRequiredNetworkType(NetworkType.UNMETERED).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build())
    }
}
