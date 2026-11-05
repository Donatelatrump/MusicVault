package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.PlayHistoryEntity
import com.musicvault.app.data.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Shared linear contextual bandit. Replayed from durable history, never double-updated. */
internal class LinUcb(private val dimensions: Int, private val alpha: Double = 0.15) {
    private val inverse = Array(dimensions) { i -> DoubleArray(dimensions) { j -> if (i == j) 1.0 else 0.0 } }
    private val b = DoubleArray(dimensions)
    private fun multiply(x: DoubleArray) = DoubleArray(dimensions) { i -> inverse[i].indices.sumOf { j -> inverse[i][j] * x[j] } }
    fun update(x: DoubleArray, reward: Double) {
        val ax = multiply(x)
        val denominator = 1.0 + x.indices.sumOf { x[it] * ax[it] }
        for (i in x.indices) {
            b[i] += reward * x[i]
            for (j in x.indices) inverse[i][j] -= ax[i] * ax[j] / denominator
        }
    }
    fun score(x: DoubleArray): Double {
        val ax = multiply(x)
        val mean = x.indices.sumOf { ax[it] * b[it] }
        return mean + alpha * sqrt(x.indices.sumOf { x[it] * ax[it] }.coerceAtLeast(0.0))
    }
}

internal object ContextualDjRanker {
    private const val DIMENSIONS = 24
    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    /** Content features generalize to songs with no listens; no album-as-genre proxy. */
    private fun features(track: Track): DoubleArray = DoubleArray(DIMENSIONS).apply {
        this[0] = 1.0
        val artist = normalize(track.artistName)
        this[1 + (artist.hashCode() and Int.MAX_VALUE) % 16] = 1.0
        this[17] = (track.durationMs / 600_000.0).coerceIn(0.0, 1.0)
        this[18] = if (track.durationMs in 1..180_000) 1.0 else 0.0
        this[19] = if (track.durationMs > 300_000) 1.0 else 0.0
        this[20] = if (Regex("(?i)\\b(live|en vivo|directo)\\b").containsMatchIn(track.title)) 1.0 else 0.0
        this[21] = if (Regex("(?i)\\b(remix|sped up|slowed)\\b").containsMatchIn(track.title)) 1.0 else 0.0
        this[22] = if (Regex("(?i)\\b(acoustic|acustic[oa])\\b").containsMatchIn(track.title)) 1.0 else 0.0
        this[23] = if (track.albumId.isNotBlank() && track.albumId != "unknown") 1.0 else 0.0
    }

    fun rank(candidates: List<Track>, history: List<PlayHistoryEntity>, known: List<Track>,
             preferred: Set<String>, favorites: Set<String>, current: Track?, now: Long, limit: Int = 40): List<Track> {
        val model = LinUcb(DIMENSIONS)
        val byId = known.associateBy { it.id }
        // Old listens fade in importance. Skips are weak negative evidence, not a ban.
        for (event in history.sortedBy { it.playedAt }) {
            val track = byId[event.trackId] ?: continue
            val ageDays = (now - event.playedAt).coerceAtLeast(0L) / 86_400_000.0
            val weight = kotlin.math.exp(-ageDays / 30.0)
            val reward = if (event.completionRatio < 0) -0.25 else (event.completionRatio.toDouble().coerceIn(0.0, 1.0) - 0.35)
            val x = features(track).map { it * sqrt(weight) }.toDoubleArray()
            model.update(x, reward * sqrt(weight))
        }
        val preferredKeys = preferred.mapTo(HashSet(), ::normalize)
        val heard = history.mapTo(HashSet()) { it.trackId }
        val recent = history.filter { now - it.playedAt in 0 until 6 * 3_600_000L }.mapTo(HashSet()) { it.trackId }
        val scores = candidates.associate { track ->
            track.id to (model.score(features(track)) +
                (if (normalize(track.artistName) in preferredKeys) 0.25 else 0.0) +
                (if (track.id in favorites) 0.3 else 0.0) +
                (if (track.id !in heard) 0.1 else 0.0) -
                (if (track.id in recent) 0.8 else 0.0))
        }
        fun similarity(a: Track, b: Track): Double = when {
            normalize(a.artistName) == normalize(b.artistName) -> 1.0
            a.albumId.isNotBlank() && a.albumId != "unknown" && a.albumId == b.albumId -> 0.2
            else -> 0.0
        }
        // MMR: relevance versus redundancy, with a stronger penalty for the last artist.
        val remaining = candidates.toMutableList()
        val result = mutableListOf<Track>()
        repeat(minOf(limit, remaining.size)) {
            val next = remaining.maxByOrNull { candidate ->
                val redundancy = result.maxOfOrNull { similarity(candidate, it) } ?: current?.let { similarity(candidate, it) } ?: 0.0
                val adjacent = (result.lastOrNull() ?: current)?.let { similarity(candidate, it) } ?: 0.0
                0.7 * scores.getValue(candidate.id) - 0.3 * redundancy - 0.25 * adjacent
            } ?: return@repeat
            result += next
            remaining.remove(next)
        }
        return result
    }
}
