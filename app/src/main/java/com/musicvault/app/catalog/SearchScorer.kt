package com.musicvault.app.catalog

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Heuristic re-ranker used after merging tracks from multiple metadata providers.
 *
 * The score is composed of:
 *   - exact (case/diacritic-insensitive) match of title and artist against the query
 *   - token Jaccard similarity over title/artist
 *   - how plausible the track duration is (filters out podcasts/compilations when
 *     the user is searching a song)
 *   - position-based popularity (earlier = more popular)
 *   - penalties for low-quality variants (karaoke, cover, tribute, remix, live)
 *   - small bonus for having artwork (proxy for "real" catalog entries)
 *
 * The intent is to lift precision without changing upstream providers: same
 * network calls, smarter ordering of the merged list.
 */
internal object SearchScorer {

    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
        return noAccents
            .lowercase()
            .replace(NON_ALPHANUM, " ")
            .split(SEPARATORS)
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .joinToString(" ")
    }

    fun tokens(value: String?): Set<String> =
        normalize(value).split(' ').filter { it.isNotBlank() }.toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size
        val union = a.size + b.size - intersection
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    fun score(track: CatalogTrack, query: String, originalIndex: Int, total: Int): Float {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return 0f

        val titleTokens = tokens(track.title)
        val artistTokens = tokens(track.artistName)
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }.toSet()

        val titleNorm = titleTokens.joinToString(" ")
        val artistNorm = artistTokens.joinToString(" ")

        val titleExact = titleNorm == normalizedQuery
        val artistExact = artistNorm == normalizedQuery
        val titleStartsWith = titleNorm.startsWith(normalizedQuery)
        val artistStartsWith = artistNorm.startsWith(normalizedQuery)

        val titleSim = jaccard(queryTokens, titleTokens)
        val artistSim = jaccard(queryTokens, artistTokens)

        var score = 0f
        if (titleExact) score += 100f
        if (artistExact) score += 60f
        if (titleStartsWith && !titleExact) score += 35f
        if (artistStartsWith && !artistExact) score += 20f

        score += titleSim * 40f
        score += artistSim * 20f

        if (titleTokens.isNotEmpty() && queryTokens.isNotEmpty()) {
            val coverage = queryTokens.intersect(titleTokens).size.toFloat() / queryTokens.size
            score += coverage * 25f
        }

        if (track.durationMs in 30_000L..600_000L) {
            score += 8f
        } else if (track.durationMs in 600_000L..3_600_000L) {
            score -= 12f
        } else if (track.durationMs > 3_600_000L) {
            score -= 25f
        }

        val positionScore = max(0f, 1f - (originalIndex.toFloat() / max(total, 1)))
        score += positionScore * 10f

        val lower = "${track.title} ${track.artistName}".lowercase()
        PENALTY_KEYWORDS.forEach { (keyword, penalty) ->
            if (lower.contains(keyword)) score -= penalty
        }

        if (!track.coverUri.isNullOrBlank()) score += 3f

        if (track.albumTitle != null && tokens(track.albumTitle).intersect(queryTokens).isNotEmpty()) {
            score += 4f
        }

        return score
    }

    /**
     * Stable, content-based key for deduplicating results across providers.
     * Two tracks with the same normalized artist + title count as the same track
     * even when their provider-specific IDs differ (iTunes id vs Deezer id, etc.).
     */
    fun dedupKey(track: CatalogTrack): String {
        val title = normalize(track.title).take(120)
        val artist = normalize(track.artistName).take(120)
        val durationBucket = (track.durationMs / 5_000L).toString()
        return "$artist::$title::$durationBucket"
    }

    /**
     * Merge tracks from multiple providers into one ranked list.
     *
     * The first occurrence of a dedup key wins the ID/provider/album info;
     * metadata from later occurrences (description, cover) is appended only when
     * the primary entry is missing that field.
     */
    fun mergeAndRank(
        query: String,
        ranked: List<List<CatalogTrack>>
    ): List<CatalogTrack> {
        val scored = mutableListOf<Scored>()
        val indexByKey = HashMap<String, Int>()
        val merged = LinkedHashMap<String, CatalogTrack>()

        ranked.forEach { list ->
            list.forEachIndexed { i, track ->
                val key = dedupKey(track)
                val s = score(track, query, i, list.size)
                val existingIdx = indexByKey[key]
                if (existingIdx == null) {
                    indexByKey[key] = scored.size
                    scored += Scored(track, s)
                    merged[key] = track
                } else {
                    val previous = scored[existingIdx]
                    val winner = mergePreferringMetadata(previous.track, track)
                    val mergedScore = max(previous.score, s)
                    merged[key] = winner
                    scored[existingIdx] = Scored(winner, mergedScore)
                }
            }
        }

        return scored
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.track.title.lowercase() })
            .map { it.track }
    }

    private data class Scored(val track: CatalogTrack, val score: Float)

    private fun mergePreferringMetadata(primary: CatalogTrack, secondary: CatalogTrack): CatalogTrack {
        if (primary == secondary) return primary
        return primary.copy(
            coverUri = primary.coverUri ?: secondary.coverUri,
            albumTitle = primary.albumTitle ?: secondary.albumTitle,
            albumId = primary.albumId ?: secondary.albumId,
            description = primary.description ?: secondary.description,
            url = primary.url ?: secondary.url
        )
    }

    /**
     * Same shape as [mergeAndRank] but for [CatalogAlbum]. Album entries rarely
     * have popularity signals, so the score is dominated by title/artist match.
     */
    fun mergeAndRankAlbums(
        query: String,
        ranked: List<List<CatalogAlbum>>
    ): List<CatalogAlbum> {
        val scored = mutableListOf<AlbumScored>()
        val indexByKey = HashMap<String, Int>()
        val merged = LinkedHashMap<String, CatalogAlbum>()

        ranked.forEach { list ->
            list.forEachIndexed { i, album ->
                val key = albumDedupKey(album)
                val s = scoreAlbum(album, query, i, list.size)
                val existingIdx = indexByKey[key]
                if (existingIdx == null) {
                    indexByKey[key] = scored.size
                    scored += AlbumScored(album, s)
                    merged[key] = album
                } else {
                    val previous = scored[existingIdx]
                    val winner = mergeAlbumPreferringMetadata(previous.album, album)
                    val mergedScore = max(previous.score, s)
                    merged[key] = winner
                    scored[existingIdx] = AlbumScored(winner, mergedScore)
                }
            }
        }

        return scored
            .sortedWith(compareByDescending<AlbumScored> { it.score }.thenBy { it.album.title.lowercase() })
            .map { it.album }
    }

    private fun albumDedupKey(album: CatalogAlbum): String {
        val title = normalize(album.title).take(120)
        val artist = normalize(album.artistName).take(120)
        val year = album.year?.toString().orEmpty()
        return "$artist::$title::$year"
    }

    private fun scoreAlbum(album: CatalogAlbum, query: String, originalIndex: Int, total: Int): Float {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return 0f

        val titleTokens = tokens(album.title)
        val artistTokens = tokens(album.artistName)
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }.toSet()
        val titleNorm = titleTokens.joinToString(" ")
        val artistNorm = artistTokens.joinToString(" ")

        var score = 0f
        if (titleNorm == normalizedQuery) score += 80f
        if (artistNorm == normalizedQuery) score += 50f
        if (titleNorm.startsWith(normalizedQuery)) score += 25f
        if (artistNorm.startsWith(normalizedQuery)) score += 15f

        score += jaccard(queryTokens, titleTokens) * 30f
        score += jaccard(queryTokens, artistTokens) * 18f

        if (titleTokens.isNotEmpty() && queryTokens.isNotEmpty()) {
            val coverage = queryTokens.intersect(titleTokens).size.toFloat() / queryTokens.size
            score += coverage * 18f
        }

        val positionScore = max(0f, 1f - (originalIndex.toFloat() / max(total, 1)))
        score += positionScore * 8f

        if (!album.coverUri.isNullOrBlank()) score += 4f
        if (album.year != null) score += 3f

        return score
    }

    private fun mergeAlbumPreferringMetadata(primary: CatalogAlbum, secondary: CatalogAlbum): CatalogAlbum {
        if (primary == secondary) return primary
        return primary.copy(
            coverUri = primary.coverUri ?: secondary.coverUri,
            year = primary.year ?: secondary.year
        )
    }

    private data class AlbumScored(val album: CatalogAlbum, val score: Float)

    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUM = Regex("[^\\p{L}\\p{Nd}]+")
    private val SEPARATORS = Regex("\\s+")

    private val STOP_WORDS = setOf(
        "the", "a", "an", "la", "el", "los", "las", "de", "del", "y", "e", "feat", "featuring"
    )

    private val PENALTY_KEYWORDS = listOf(
        "karaoke" to 30f,
        "tribute" to 28f,
        "cover" to 12f,
        "remix" to 10f,
        "live" to 8f,
        "instrumental" to 18f,
        "acoustic" to 4f,
        "remastered" to 4f,
        "version" to 4f,
        "demo" to 6f,
        "preview" to 8f
    )

    @Suppress("unused")
    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}