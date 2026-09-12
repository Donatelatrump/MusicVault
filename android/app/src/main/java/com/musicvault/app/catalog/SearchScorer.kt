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
 *   - phrase match (the query appears as a contiguous span inside title/artist)
 *   - combined title+artist match: when the query is "song artist", tracks
 *     that hit both fields score much higher than tracks that only hit one
 *     (this is the signal that fixes "Friends Marshmello" returning random
 *     songs titled "Friends" by other artists)
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

    /**
     * Length of the longest contiguous run of [phrase] tokens that appears
     * inside [target] in the same order. Returns 0 when nothing matches.
     *
     * "Fuerza Regida Sabor Fresa" vs target "sabor fresa" returns 2: the
     * trailing "Sabor Fresa" slice of the query still matches even though
     * the query as a whole does not. This makes the ranker robust to
     * reordered queries and to queries with extra/noise tokens.
     *
     * Implementation: for each starting offset in the target, walk the phrase
     * and count the longest prefix that lines up token-for-token. The maximum
     * over all starting offsets is returned. O(|target| * |phrase|) which is
     * fine: query and title are short strings.
     */
    fun longestPhraseMatch(target: String, phrase: String): Int {
        if (phrase.isBlank() || target.isBlank()) return 0
        val targetTokens = target.split(' ').filter { it.isNotBlank() }
        val phraseTokens = phrase.split(' ').filter { it.isNotBlank() }
        if (phraseTokens.isEmpty() || phraseTokens.size > targetTokens.size) return 0
        var best = 0
        for (start in 0..(targetTokens.size - phraseTokens.size)) {
            var run = 0
            for (offset in phraseTokens.indices) {
                if (targetTokens[start + offset] == phraseTokens[offset]) run++ else break
            }
            if (run > best) best = run
            // A shorter suffix of the phrase may also match starting at the
            // same target offset (covers reordered multi-segment queries).
            for (suffixStart in 1 until phraseTokens.size) {
                run = 0
                val maxLen = minOf(phraseTokens.size - suffixStart, targetTokens.size - start)
                for (offset in 0 until maxLen) {
                    if (targetTokens[start + offset] == phraseTokens[suffixStart + offset]) run++ else break
                }
                if (run > best) best = run
            }
        }
        return best
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

        // How many query tokens land on the title vs on the artist. This is the
        // single most useful signal for "Friends Marshmello": we want tracks
        // where one token matches the title and the other matches the artist,
        // not tracks where every token happens to appear in the title of an
        // unrelated song called "Friends".
        val titleHits = queryTokens.intersect(titleTokens).size
        val artistHits = queryTokens.intersect(artistTokens).size
        val titleCoverage = if (queryTokens.isNotEmpty()) titleHits.toFloat() / queryTokens.size else 0f
        val artistCoverage = if (queryTokens.isNotEmpty()) artistHits.toFloat() / queryTokens.size else 0f

        // Sub-phrase match: find the longest contiguous slice of the query that
        // appears in order inside the title (or artist). Covers reordered
        // queries ("Fuerza Regida Sabor Fresa") and queries with extra noise
        // tokens ("Sabor Fresa Fuerza Regida remix").
        val phraseTokensCount = queryTokens.size
        val titlePhraseLen = if (phraseTokensCount > 1) longestPhraseMatch(titleNorm, normalizedQuery) else 0
        val artistPhraseLen = if (phraseTokensCount > 1) longestPhraseMatch(artistNorm, normalizedQuery) else 0

        var score = 0f
        if (titleExact) score += 100f
        if (artistExact) score += 60f
        if (titleStartsWith && !titleExact) score += 35f
        if (artistStartsWith && !artistExact) score += 20f

        score += titleSim * 40f
        score += artistSim * 20f

        if (titleTokens.isNotEmpty() && queryTokens.isNotEmpty()) {
            score += titleCoverage * 25f
        }

        // Combined match bonus: query tokens distributed across title + artist.
        // This is what makes "Friends Marshmello" rank Marshmello's "Friends"
        // above a random song titled "Friends" by an unrelated artist.
        if (queryTokens.size >= 2) {
            // Matched coverage is the share of query tokens that landed on
            // either field. Capped at 1.0 because duplicated hits — the same
            // token appearing in both title and artist — would otherwise push
            // this past 100% and skew ordering.
            val totalCoverage = ((titleHits + artistHits).toFloat() / queryTokens.size).coerceAtMost(1f)
            if (totalCoverage >= 0.99f) {
                // Every query token was found across (title ∪ artist).
                score += 45f
                if (titleCoverage >= 0.49f && artistCoverage >= 0.49f) {
                    // At least one token on each side: this is the "song by
                    // artist" pattern the user typed.
                    score += 35f
                }
            } else if (totalCoverage >= 0.66f) {
                score += 20f
                if (titleCoverage >= 0.49f && artistCoverage >= 0.49f) {
                    score += 15f
                }
            }
        }

        // YouTube search results often embed the channel/uploader in the title
        // (e.g. "Marshmello - FRIENDS (Acoustic Version)"). When the artist
        // token is already part of the title, the title-derived match is
        // effectively redundant with the artist field and the combined bonus
        // above double-counts the artist hit. Penalize so a clean "FRIENDS"
        // track wins over a "Marshmello - FRIENDS (Acoustic Version)" one for
        // the query "friends marshmello".
        if (queryTokens.size >= 2 && artistHits > 0 && titleHits > 0 &&
            artistTokens.isNotEmpty() && artistTokens.intersect(titleTokens).size >= artistTokens.size / 2
        ) {
            score -= 25f
        }

        // Phrase bonus: scaled by how much of the query is matched as a
        // contiguous run. A full 4-token match ("Sabor Fresa Fuerza Regida"
        // all in order) is worth +30 in the title; a 2-token slice ("Sabor
        // Fresa" inside "Sabor Fresa (Remix)") is worth +15.
        if (titlePhraseLen >= 2) {
            score += 15f * (titlePhraseLen.toFloat() / phraseTokensCount.toFloat())
        }
        if (artistPhraseLen >= 2) {
            score += 9f * (artistPhraseLen.toFloat() / phraseTokensCount.toFloat())
        }

        // Hard penalty when none of the query tokens match the title at all
        // (but some token may have matched the artist). This prevents a query
        // like "Friends Marshmello" from returning every song titled "Friends"
        // by someone else just because the artist name happened to match
        // some unrelated field further down the result.
        if (queryTokens.isNotEmpty() && titleHits == 0 && artistHits > 0) {
            score -= 18f
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
     * even when their provider-specific IDs differ (e.g. Deezer id vs YouTube id).
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

    fun scoreAlbum(album: CatalogAlbum, query: String, originalIndex: Int, total: Int): Float {
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

        val titleHits = queryTokens.intersect(titleTokens).size
        val artistHits = queryTokens.intersect(artistTokens).size

        if (titleTokens.isNotEmpty() && queryTokens.isNotEmpty()) {
            val coverage = queryTokens.intersect(titleTokens).size.toFloat() / queryTokens.size
            score += coverage * 18f
        }

        if (queryTokens.size >= 2) {
            val totalCoverage = (titleHits + artistHits).toFloat() / queryTokens.size
            if (totalCoverage >= 0.99f) score += 35f
            val titleCoverage = if (queryTokens.isNotEmpty()) titleHits.toFloat() / queryTokens.size else 0f
            val artistCoverage = if (queryTokens.isNotEmpty()) artistHits.toFloat() / queryTokens.size else 0f
            if (titleCoverage >= 0.49f && artistCoverage >= 0.49f) score += 22f
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