package com.musicvault.app.ui.coordinators

import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.data.PlayHistoryEntity
import com.musicvault.app.data.Track
import com.musicvault.app.providers.RelatedArtists
import kotlinx.coroutines.CancellationException
import java.text.Normalizer
import kotlin.math.pow

/** Local listening profile. Download status never contributes to musical taste. */
class DjRecommendationEngine {
    data class Profile(
        val artists: Map<String, Double>,
        val tracks: Map<String, Double>,
        val recent: Set<String>,
        val genres: Map<String, Double>,
        val preferredArtists: Set<String>
    )
    private var discovered = emptyList<CatalogTrack>()
    private var discoveredSeeds = emptyList<String>()
    private var discoveredAt = 0L

    fun profile(history: List<PlayHistoryEntity>, known: List<Track>, preferredArtists: Set<String>, now: Long): Profile {
        val byId = known.associateBy { it.id }
        val artists = mutableMapOf<String, Double>()
        val songs = mutableMapOf<String, Double>()
        val genres = mutableMapOf<String, Double>()
        history.forEach { play ->
            val days = (now - play.playedAt).coerceAtLeast(0L) / 86_400_000.0
            val decay = 0.5.pow(days / 21.0)
            val evidence = when {
                play.completionRatio < 0 -> -5.0
                play.completionRatio >= .9f -> 8.0
                play.completionRatio >= .8f -> 6.0
                play.completionRatio >= .6f -> 4.0
                play.completionRatio >= .3f -> 2.0
                play.completionRatio >= .15f -> 1.0
                else -> 0.0
            } * decay
            songs[play.trackId] = (songs[play.trackId] ?: 0.0) + evidence
            byId[play.trackId]?.let { track ->
                track.artistName?.let { artist ->
                    val key = normalize(artist)
                    artists[key] = (artists[key] ?: 0.0) + evidence
                }

            }
        }
        return Profile(artists, songs, history.filter { now - it.playedAt < 6 * 3_600_000L }.mapTo(HashSet()) { it.trackId }, genres, preferredArtists)
    }

    suspend fun recommendations(
        history: List<PlayHistoryEntity>, known: List<Track>, preferredArtists: List<String>,
        excluded: Set<String>, current: Track?, online: Boolean, playableOffline: Set<String>,
        convert: (CatalogTrack) -> Track,
        favoriteIds: Set<String> = emptySet()
    ): Pair<List<Track>, String> {
        val now = System.currentTimeMillis()
        val taste = profile(history, known, preferredArtists.toSet(), now)
        val names = known.associate { normalize(it.artistName) to it.artistName }
        val learned = taste.artists.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .mapNotNull { names[it.key] }.take(5)
        val preferredSet = preferredArtists.toSet()
        val seeds = (learned + preferredSet).distinct().take(5)
        if (online && seeds.isNotEmpty() && (discoveredSeeds != seeds || now - discoveredAt > 15 * 60_000L)) {
            val fresh = mutableListOf<CatalogTrack>()
            for (seed in seeds.take(3)) {
                try { fresh += RelatedArtists.radio(seed, 25) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep other artists/local candidates available. */ }
            }
            if (fresh.isNotEmpty()) {
                discovered = fresh.distinctBy { "${it.provider}:${it.id}" }
                discoveredSeeds = seeds
                discoveredAt = now
            }
        }
        val pool = known + (if (online) discovered.map(convert) else emptyList())
        fun songKey(track: Track) = normalize(track.artistName) + ":" + normalize(track.title)
        val excludedSongs = known.filter { it.id in excluded || it.id == current?.id }.mapTo(HashSet(), ::songKey)
        val candidates = pool.distinctBy(::songKey)
            .filter { it.id !in excluded && it.id != current?.id && songKey(it) !in excludedSongs }
            .filter { online || it.id in playableOffline }
        val result = ContextualDjRanker.rank(candidates, history, known, preferredSet, favoriteIds, current, now)
        val reason = when {
            !online -> "dj_reason_offline"
            learned.isNotEmpty() -> learned.take(2).joinToString(" · ")
            preferredArtists.isNotEmpty() -> preferredArtists.take(2).joinToString(" · ")
            else -> "dj_reason_learning"
        }
        return result to reason
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()
}

data class DjUiState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val reason: String = "dj_reason_learning",
    val upcoming: List<Track> = emptyList(),
    val message: String? = null
)
