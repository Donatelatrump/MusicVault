package com.musicvault.app.catalog

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object CoverImageCache {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        val existing = loader
        if (existing != null) return existing
        return synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    val runtime = Runtime.getRuntime()
                    val maxMemory = runtime.maxMemory()
                    val memoryPercent = when {
                        maxMemory < 512 * 1024 * 1024 -> 0.10
                        maxMemory < 1024 * 1024 * 1024 -> 0.15
                        else -> 0.20
                    }
                    coil.memory.MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(memoryPercent)
                        .build()
                }
                .diskCache {
                    val cacheDir = File(context.cacheDir, "musicvault_cover_cache")
                    val availableSpace = cacheDir.freeSpace
                    val diskCacheSize = when {
                        availableSpace < 1024L * 1024L * 1024L -> 32L * 1024L * 1024L
                        availableSpace < 4096L * 1024L * 1024L -> 64L * 1024L * 1024L
                        else -> 128L * 1024L * 1024L
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

    /**
     * Warm the exact cache keys/sizes used by the first Home rails. This is only
     * used during onboarding, while the loading animation is covering Home, so
     * the first revealed frame already has artwork instead of filling it in later.
     */
    suspend fun prefetchHomeAndAwait(
        context: Context,
        trackUrls: Iterable<String?>,
        albumUrls: Iterable<String?>,
        timeoutMs: Long = 6_500L
    ): Boolean {
        val appContext = context.applicationContext
        val imageLoader = loader(appContext)
        val requests = buildList {
            trackUrls.asSequence()
                .filterNotNull().filter(String::isNotBlank).distinct().take(8)
                .forEach { url ->
                    add(
                        ImageRequest.Builder(appContext)
                            .data(url)
                            .size(Size(256, 256))
                            .memoryCacheKey("home-track:$url")
                            .diskCacheKey(url)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .build()
                    )
                }
            albumUrls.asSequence()
                .filterNotNull().filter(String::isNotBlank).distinct().take(6)
                .forEach { url ->
                    add(
                        ImageRequest.Builder(appContext)
                            .data(url)
                            .size(Size(300, 300))
                            .memoryCacheKey("musicvault:$url:300")
                            .diskCacheKey(url)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .build()
                    )
                }
        }
        if (requests.isEmpty()) return true

        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                requests.map { request ->
                    async(Dispatchers.IO) {
                        try {
                            imageLoader.execute(request)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    }
                }.awaitAll()
            }
            true
        } ?: false
    }
}
