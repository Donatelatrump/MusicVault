package com.musicvault.app.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AdjustableCacheEvictorTest {
    @Test fun shrinkingEvictsLeastRecentlyUsedSpansAndAccountsForTouches() {
        val evictor = AdjustableCacheEvictor(100)
        val removed = mutableListOf<CacheSpan>()
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, method, args ->
            check(method.name == "removeSpan")
            val span = args!![0] as CacheSpan
            removed.add(span)
            evictor.onSpanRemoved(proxy as Cache, span)
            null
        } as Cache
        val first = CacheSpan("first", 0, 40, 1, File("unused-first"))
        val second = CacheSpan("second", 0, 40, 2, File("unused-second"))
        evictor.onSpanAdded(cache, first)
        evictor.onSpanAdded(cache, second)
        val touched = CacheSpan("first", 0, 40, 3, File("unused-first"))
        evictor.onSpanTouched(cache, first, touched)
        evictor.resize(cache, 40)
        assertEquals(listOf(second), removed)
        evictor.resize(cache, 100)
        assertEquals(1, removed.size)
        evictor.onStartFile(cache, "incoming", 0, 80)
        assertEquals(listOf(second, touched), removed)
    }
}
