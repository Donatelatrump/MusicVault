package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.PlayHistoryEntity
import kotlin.math.pow
import kotlin.math.roundToInt

/** Completed listening is positive; a genuine user skip is recorded explicitly. */
object RecommendationRanker {
    fun historyScores(history: List<PlayHistoryEntity>, now: Long): Map<String, Int> = history.groupBy { it.trackId }.mapValues { (_, plays) ->
        plays.sumOf { play ->
            val days = ((now - play.playedAt).coerceAtLeast(0L) / 86_400_000.0)
            val decay = 0.5.pow(days / 30.0)
            val evidence = when {
                play.completionRatio < 0f -> -35.0
                play.completionRatio >= .8f -> 18.0
                play.completionRatio >= .5f -> 8.0
                else -> 0.0 // Stop/error/process death is not a negative preference.
            }
            evidence * decay
        }.roundToInt().coerceIn(-100, 100)
    }
}
