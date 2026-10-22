package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.PlayHistoryEntity
import org.junit.Assert.*
import org.junit.Test

class RecommendationRankerTest {
    private val now = 100L * 86_400_000
    private fun play(track: String = "song", completion: Float, daysAgo: Int = 0) =
        PlayHistoryEntity(trackId = track, playedAt = now - daysAgo * 86_400_000L, completionRatio = completion)

    @Test fun explicitSkipIsNegativeButInterruptedListeningIsNeutral() {
        val score = RecommendationRanker.historyScores(listOf(play(completion = -1f), play(completion = .2f)), now)
        assertEquals(-35, score["song"])
        assertEquals(0, RecommendationRanker.historyScores(listOf(play(completion = 0f)), now)["song"])
    }
    @Test fun recentCompletionOutweighsOldCompletion() {
        val score = RecommendationRanker.historyScores(listOf(play("recent", 1f), play("old", 1f, 30)), now)
        assertEquals(18, score["recent"])
        assertEquals(9, score["old"])
    }
    @Test fun halfListeningIsPositiveAndRepeatedEvidenceIsBounded() {
        assertEquals(8, RecommendationRanker.historyScores(listOf(play(completion = .5f)), now)["song"])
        assertEquals(100, RecommendationRanker.historyScores(List(100) { play(completion = 1f) }, now)["song"])
        assertEquals(-100, RecommendationRanker.historyScores(List(100) { play(completion = -1f) }, now)["song"])
    }
    @Test fun inputOrderingDoesNotChangeScoresAndFutureClockDoesNotAmplifyEvidence() {
        val history = listOf(play("a", 1f), play("b", -1f, 3), play("a", .5f, 20))
        assertEquals(RecommendationRanker.historyScores(history, now), RecommendationRanker.historyScores(history.reversed(), now))
        val future = play(completion = 1f).copy(playedAt = now + 86_400_000L)
        assertEquals(18, RecommendationRanker.historyScores(listOf(future), now)["song"])
    }
}
