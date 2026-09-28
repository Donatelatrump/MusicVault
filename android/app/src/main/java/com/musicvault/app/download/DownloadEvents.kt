package com.musicvault.app.download

import android.content.Context
import android.content.Intent
import com.musicvault.app.providers.ProviderResult

/** UI notifications only. Room owns download state even when nobody receives these. */
object DownloadEvents {
    const val ACTION_PROGRESS = "com.musicvault.app.download.PROGRESS"
    const val ACTION_RESULT = "com.musicvault.app.download.RESULT"
    const val EXTRA_ID = "id"
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

    fun progress(context: Context, id: String, value: Int) {
        context.sendBroadcast(Intent(ACTION_PROGRESS).setPackage(context.packageName)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_PROGRESS, value))
    }

    fun result(context: Context, id: String, result: ProviderResult) {
        val intent = Intent(ACTION_RESULT).setPackage(context.packageName).putExtra(EXTRA_ID, id)
        when (result) {
            is ProviderResult.Success -> intent.putExtra(EXTRA_STATUS, STATUS_SUCCESS)
                .putExtra(EXTRA_AUDIO_URI, result.audioUri).putExtra(EXTRA_FILE_NAME, result.fileName)
                .putExtra(EXTRA_MIME, result.mimeType).putExtra(EXTRA_LOCAL_PATH, result.localPath)
            is ProviderResult.Error -> intent.putExtra(EXTRA_STATUS, STATUS_ERROR).putExtra(EXTRA_MESSAGE, result.message)
            is ProviderResult.Unavailable -> intent.putExtra(EXTRA_STATUS, STATUS_UNAVAILABLE).putExtra(EXTRA_MESSAGE, result.reason)
        }
        context.sendBroadcast(intent)
    }

    fun cancelled(context: Context, id: String) {
        context.sendBroadcast(Intent(ACTION_RESULT).setPackage(context.packageName).putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_STATUS, STATUS_CANCELLED).putExtra(EXTRA_MESSAGE, "Descarga cancelada"))
    }
}
