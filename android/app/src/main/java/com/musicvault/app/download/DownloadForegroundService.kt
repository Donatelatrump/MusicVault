package com.musicvault.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.musicvault.app.R
import com.musicvault.app.data.DownloadRecordEntity
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.TrackEntity
import com.musicvault.app.providers.ProviderResult
import com.musicvault.app.providers.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns downloads independently of the Activity/ViewModel lifecycle.
 * This is important on EMUI/Huawei devices, which are more aggressive about
 * stopping background work. The service stays alive until every queued request
 * finishes and exposes progress/results through explicit app-local broadcasts.
 */
class DownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Boolean>()
    private val lastProgress = ConcurrentHashMap<String, ProgressEmission>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando descarga…", 0, false))
        Providers.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
            Providers.cancel(id)
            sendCancelled(id)
            if (running.remove(id) != null && running.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_DOWNLOAD) return START_NOT_STICKY

        val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        if (running.putIfAbsent(id, true) != null) return START_STICKY

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
        val albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        val albumTitle = intent.getStringExtra(EXTRA_ALBUM_TITLE).orEmpty()
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val coverUri = intent.getStringExtra(EXTRA_COVER_URI)
        val provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()

        scope.launch {
            try {
                broadcastProgress(id, 0)
                val result = Providers.download(
                    name = name,
                    author = author,
                    sourceUrl = sourceUrl,
                    requestId = id,
                    onProgress = { progress -> broadcastProgress(id, progress) }
                )

                if (result is ProviderResult.Success) {
                    val db = MusicDatabase.get(applicationContext)
                    db.downloadRecordDao().save(
                        DownloadRecordEntity(id, result.localPath ?: result.audioUri)
                    )
                    // Persist a minimal TrackEntity from the service as well.
                    // This makes a completed download survive an Activity/process
                    // recreation (the ViewModel may be gone while Huawei/EMUI
                    // leaves the foreground service running). The ViewModel can
                    // later enrich this row with the full catalog metadata.
                    db.trackDao().upsertAll(
                        listOf(
                            TrackEntity(
                                id = id,
                                title = name,
                                artistName = author,
                                albumId = albumId.ifBlank { "unknown" },
                                albumTitle = albumTitle.ifBlank { "Álbum desconocido" },
                                durationMs = durationMs,
                                audioUri = result.audioUri,
                                isDownloaded = true,
                                coverUri = coverUri,
                                source = provider.ifBlank { id.substringBefore(':').ifBlank { null } },
                                sourceId = sourceId.ifBlank { id.substringAfter(":recording:", id) }
                            )
                        )
                    )
                }
                broadcastResult(id, result)
            } catch (t: Throwable) {
                broadcastResult(
                    id,
                    ProviderResult.Error(
                        "Error inesperado durante la descarga: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
            } finally {
                running.remove(id)
                lastProgress.remove(id)
                updateNotification()
                if (running.isEmpty()) stopSelf()
            }
        }

        return START_STICKY
    }

    private fun broadcastProgress(id: String, progress: Int) {
        val value = progress.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        val prior = lastProgress[id]
        if (prior != null && value != 100 && value == prior.value) return
        if (prior != null && value != 100 && value - prior.value < 2 && now - prior.sentAt < 500L) return
        lastProgress[id] = ProgressEmission(value, now)
        val intent = Intent(ACTION_PROGRESS).setPackage(packageName)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_PROGRESS, value)
        sendBroadcast(intent)
        updateNotification(id, value)
    }

    private fun broadcastResult(id: String, result: ProviderResult) {
        val intent = Intent(ACTION_RESULT).setPackage(packageName).putExtra(EXTRA_ID, id)
        when (result) {
            is ProviderResult.Success -> intent
                .putExtra(EXTRA_STATUS, STATUS_SUCCESS)
                .putExtra(EXTRA_AUDIO_URI, result.audioUri)
                .putExtra(EXTRA_FILE_NAME, result.fileName)
                .putExtra(EXTRA_MIME, result.mimeType)
                .putExtra(EXTRA_LOCAL_PATH, result.localPath)
            is ProviderResult.Unavailable -> intent
                .putExtra(EXTRA_STATUS, STATUS_UNAVAILABLE)
                .putExtra(EXTRA_MESSAGE, result.reason)
            is ProviderResult.Error -> intent
                .putExtra(EXTRA_STATUS, STATUS_ERROR)
                .putExtra(EXTRA_MESSAGE, result.message)
        }
        sendBroadcast(intent)
    }

    private fun sendCancelled(id: String) {
        sendBroadcast(
            Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_STATUS, STATUS_CANCELLED)
                .putExtra(EXTRA_MESSAGE, "Descarga cancelada")
        )
    }

    private fun updateNotification(id: String? = null, progress: Int? = null) {
        val count = running.size
        val text = when {
            count == 0 -> "Sin descargas activas"
            id != null && progress != null -> "Descargando… $progress% · $count activa(s)"
            else -> "$count descarga(s) activa(s)"
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(text, progress ?: 0, progress != null)
        )
    }

    private fun notification(text: String, progress: Int, showProgress: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.musicvault_icon)
            .setContentTitle("MusicVault")
            .setContentText(text)
            .setOngoing(running.isNotEmpty() || showProgress)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), !showProgress)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Descargas de MusicVault",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Estado de las descargas de audio" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.keys.toList().forEach { Providers.cancel(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private data class ProgressEmission(val value: Int, val sentAt: Long)
        private const val TAG = "MusicVault/DownloadService"
        private const val CHANNEL_ID = "musicvault_downloads"
        private const val NOTIFICATION_ID = 7101

        const val ACTION_DOWNLOAD = "com.musicvault.app.download.DOWNLOAD"
        const val ACTION_CANCEL = "com.musicvault.app.download.CANCEL"
        const val ACTION_PROGRESS = "com.musicvault.app.download.PROGRESS"
        const val ACTION_RESULT = "com.musicvault.app.download.RESULT"

        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_SOURCE_URL = "sourceUrl"
        const val EXTRA_ALBUM_ID = "albumId"
        const val EXTRA_ALBUM_TITLE = "albumTitle"
        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_COVER_URI = "coverUri"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_SOURCE_ID = "sourceId"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_AUDIO_URI = "audioUri"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_MIME = "mimeType"
        const val EXTRA_LOCAL_PATH = "localPath"

        const val STATUS_SUCCESS = "success"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val STATUS_ERROR = "error"
        const val STATUS_CANCELLED = "cancelled"

        fun start(context: Context, intent: Intent) {
            androidx.core.content.ContextCompat.startForegroundService(
                context.applicationContext,
                intent.setClass(context, DownloadForegroundService::class.java)
            )
        }

        fun cancel(context: Context, id: String) {
            start(context, Intent(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }
    }
}
