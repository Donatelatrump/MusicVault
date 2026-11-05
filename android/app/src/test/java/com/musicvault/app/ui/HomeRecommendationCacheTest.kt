package com.musicvault.app.ui

import com.musicvault.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationCacheTest {
    private val now = 200_000_000L
    private fun snapshot(at: Long = now - 1000, profile: String = "profile") = HomeRecommendationCache.Snapshot(
        profile, listOf(Track("id", "Song", "Artist", "album", "Album", 180000)), emptyList(), at)

    @Test fun reusesFreshBatchWithoutRequiringNewCandidates() {
        assertTrue(HomeRecommendationCache.isFresh(snapshot(), "profile", now))
    }
    @Test fun refreshesExpiredLegacyAndFutureBatches() {
        for (at in listOf(0L, now - 86_400_000L, now + 1000L)) {
            assertFalse(HomeRecommendationCache.isFresh(snapshot(at), "profile", now))
        }
    }
    @Test fun changedTasteProfileInvalidatesBatch() {
        assertFalse(HomeRecommendationCache.isFresh(snapshot(), "other", now))
    }
    @Test fun dailyRotationKeepsEveryCandidateAndIsStable() {
        val batch = snapshot()
        val first = HomeRecommendationCache.forToday(batch)
        assertEquals(first, HomeRecommendationCache.forToday(batch))
        assertEquals(batch.tracks.map { it.id }.toSet(), first.first.map { it.id }.toSet())
    }
}
