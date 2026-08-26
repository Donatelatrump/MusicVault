package com.musicvault.app.catalog

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import java.io.File

object CoverImageCache {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        val existing = loader
        if (existing != null) return existing
        return synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(0.20)
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(File(context.cacheDir, "musicvault_cover_cache"))
                        .maxSizeBytes(64L * 1024L * 1024L)
                        .build()
                }
                .respectCacheHeaders(false)
                .build()
                .also { loader = it }
        }
    }

    fun prefetch(context: Context, urls: Iterable<String?>) {
        val imageLoader = loader(context)
        urls.asSequence()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .forEach { url ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(Size(72, 72))
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build()
                )
            }
    }
}
