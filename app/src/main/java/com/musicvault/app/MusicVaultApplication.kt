package com.musicvault.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.providers.Providers

/**
 * Registers CoverImageCache's loader as the process-wide Coil singleton so
 * every AsyncImage (mini player, vinyl, circular visualizer, queue, dialogs)
 * shares ONE memory/disk cache instead of Coil's default loader duplicating
 * caches alongside the configured one.
 */
class MusicVaultApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Providers.init(this)
    }

    override fun newImageLoader(): ImageLoader = CoverImageCache.loader(this)
}