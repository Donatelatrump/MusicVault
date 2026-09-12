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
                    // ✅ Dynamic memory cache sizing based on device capabilities
                    val runtime = Runtime.getRuntime()
                    val maxMemory = runtime.maxMemory()
                    val memoryPercent = when {
                        maxMemory < 512 * 1024 * 1024 -> 0.10 // 10% para devices < 512MB
                        maxMemory < 1024 * 1024 * 1024 -> 0.15 // 15% para devices < 1GB  
                        else -> 0.20 // 20% para devices >= 1GB
                    }
                    coil.memory.MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(memoryPercent)
                        .build()
                }
                .diskCache {
                    // ✅ Dynamic disk cache sizing based on available space
                    val cacheDir = File(context.cacheDir, "musicvault_cover_cache")
                    val availableSpace = cacheDir.freeSpace
                    val diskCacheSize = when {
                        availableSpace < 1024L * 1024L * 1024L -> 32L * 1024L * 1024L  // 32MB para < 1GB libre
                        availableSpace < 4096L * 1024L * 1024L -> 64L * 1024L * 1024L  // 64MB para < 4GB libre
                        else -> 128L * 1024L * 1024L // 128MB para >= 4GB libre
                    }
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir)
                        .maxSizeBytes(diskCacheSize)
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
