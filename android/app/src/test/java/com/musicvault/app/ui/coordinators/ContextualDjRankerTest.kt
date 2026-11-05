package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.PlayHistoryEntity
import com.musicvault.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class ContextualDjRankerTest {
    @Test fun positiveFeedbackRaisesScoreAndNegativeFeedbackLowersIt() {
        val positive = LinUcb(2, 0.0)
        val negative = LinUcb(2, 0.0)
        val x = doubleArrayOf(1.0, 0.0)
        repeat(5) { positive.update(x, 1.0); negative.update(x, -0.25) }
        assertTrue(positive.score(x) > 0.5)
        assertTrue(negative.score(x) < 0.0)
    }
    @Test fun confidenceDeclinesForObservedFeatures() {
        val model = LinUcb(2, 1.0)
        val x = doubleArrayOf(1.0, 0.0)
        val before = model.score(x)
        repeat(10) { model.update(x, 0.0) }
        assertTrue(model.score(x) < before)
        assertEquals(1.0, model.score(doubleArrayOf(0.0, 1.0)), 0.0001)
    }
    @Test fun mmrDiversifiesEqualCandidatesAndReplayIsDeterministic() {
        val songs = (0..11).map { Track("$it", "Song $it", "Artist ${it / 4}", "unknown", "Album", 180000) }
        fun rank() = ContextualDjRanker.rank(songs, emptyList(), songs, emptySet(), emptySet(), null, 1000L)
        assertEquals(3, rank().take(3).map { it.artistName }.distinct().size)
        assertEquals(rank(), rank())
        assertEquals(12, rank().distinctBy { it.id }.size)
    }
    @Test fun learnedTasteTransfersToUnheardSongBySameArtist() {
        val liked = Track("heard", "Heard", "Artist A", "unknown", "", 180000)
        val unheard = liked.copy(id = "new", title = "New")
        val other = liked.copy(id = "other", artistName = "Artist B")
        val history = (1..8).map { PlayHistoryEntity(it.toLong(), liked.id, 1000L, 1f) }
        val result = ContextualDjRanker.rank(listOf(other, unheard), history, listOf(liked), emptySet(), emptySet(), null, 1000L)
        assertEquals(unheard.id, result.first().id)
    }
}
