package com.musicvault.app.ui.coordinators

import android.content.Context
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.download.DownloadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class DownloadsCoordinator(private val context: Context, database: MusicDatabase, scope: CoroutineScope) {
    val progress = database.downloadQueueDao().observeAll().map { rows ->
        rows.filter { DownloadQueue.isActive(it.state) }.associate { it.id to it.progress }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())
    suspend fun reconcile() = DownloadQueue.reconcile(context)
}
