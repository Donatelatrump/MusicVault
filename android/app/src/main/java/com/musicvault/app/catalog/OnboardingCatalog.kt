package com.musicvault.app.catalog

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Browsable taxonomy + paged tagged artists. Selections are independent of loaded pages. */
class OnboardingCatalog {
    private val provider = MusicBrainzCatalogProvider()
    private val offsets = mutableMapOf<String, Int>()
    private val exhausted = mutableSetOf<String>()
    private var cursor = 0
    private val aliases = mapOf(
        "Alternativo" to "alternative rock", "Rap en español" to "spanish hip hop",
        "Reggaetón" to "reggaeton", "Regional mexicano" to "regional mexican",
        "Pop latino" to "latin pop", "Salsa / Bachata" to "salsa", "Funk / Disco" to "funk",
        "Jazz / Soul" to "jazz", "Clásica" to "classical", "Lo-fi / Ambient" to "ambient",
        "Soundtracks" to "soundtrack"
    )
    data class Page(val artists: List<com.musicvault.app.providers.RelatedArtists.ArtistDetails>, val hasMore: Boolean)
    suspend fun next(context: Context, genres: Set<String>): Page {
        val tags = genres.map { aliases[it] ?: it.lowercase(java.util.Locale.ROOT) }.distinct()
        val available = tags.filterNot { it in exhausted }
        if (available.isEmpty()) return Page(emptyList(), false)
        val tag = available[cursor % available.size]
        val offset = offsets[tag] ?: 0
        val (names, more) = provider.artistsByTag(tag, offset)
        val details = coroutineScope {
            names.distinct().map { name -> async {
                ArtistPortraits.details(context, name)
                    ?: com.musicvault.app.providers.RelatedArtists.ArtistDetails(name, null, 0)
            } }.awaitAll()
        }
        offsets[tag] = offset + 20
        if (!more) exhausted += tag
        cursor++
        return Page(details.sortedByDescending { it.fans }, tags.any { it !in exhausted })
    }
}
