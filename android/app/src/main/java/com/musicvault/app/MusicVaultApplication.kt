package com.musicvault.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.providers.Providers
import com.musicvault.app.player.StreamingCache

/**
 * Registers CoverImageCache's loader as the process-wide Coil singleton so
 * every AsyncImage (mini player, vinyl, circular visualizer, queue, dialogs)
 * shares ONE memory/disk cache instead of Coil's default loader duplicating
 * caches alongside the configured one.
 */
class MusicVaultApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        com.musicvault.app.diagnostics.PerformanceDiagnostics.initialize(this)
        com.musicvault.app.diagnostics.LocalCrashRecorder.install(this)
        Providers.init(this)
        // Open the Media3 cache DB and install/warm the preferred HTTP stack
        // before the user taps Play. This removes first-stream setup work from
        // the time-to-first-audio critical path.
        StreamingCache.initialize(this)
    }

    override fun newImageLoader(): ImageLoader = CoverImageCache.loader(this)
}