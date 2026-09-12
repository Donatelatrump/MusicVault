package com.musicvault.app.download

import com.musicvault.app.data.Track

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Int) : DownloadState
    data object Complete : DownloadState
    data class Error(val message: String) : DownloadState
}

/**
 * Orchestrates downloads without coupling the app to a particular source.
 * The actual downloader should be implemented per authorized source.
 */
class DownloadManager(
    private val providers: List<AudioSourceProvider> = listOf(FutureVideoCatalogProvider())
) {
    suspend fun resolve(track: Track): ResolvedAudio? {
        for (provider in providers) {
            val result = provider.resolve(track)
            if (result != null) return result
        }
        return null
    }
}
