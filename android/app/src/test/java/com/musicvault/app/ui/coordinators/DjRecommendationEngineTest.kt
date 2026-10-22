package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DjRecommendationEngineTest {
    private fun track(index: Int) = Track("song:$index", "Song $index", "Artist ${index % 4}", "album", "Album", 180_000)

    @Test fun offlineMixContainsFortySongsAndInterleavesArtists() = runBlocking {
        val known = (0 until 50).map(::track)
        val (mix, _) = DjRecommendationEngine().recommendations(
            emptyList(), known, emptyList(), emptySet(), null, false,
            known.mapTo(HashSet()) { it.id }, { error("Offline must not discover remote tracks") }
        )
        assertEquals(40, mix.size)
        assertEquals(40, mix.distinctBy { it.id }.size)
        assertEquals(listOf("Artist 0", "Artist 1", "Artist 2", "Artist 3"), mix.take(4).map { it.artistName })
    }

    @Test fun offlineMixExcludesUnavailableCurrentAndPreviouslyPlayedSongs() = runBlocking {
        val known = (0 until 8).map(::track)
        val (mix, _) = DjRecommendationEngine().recommendations(
            emptyList(), known, emptyList(), setOf("song:1"), known[0], false,
            setOf("song:0", "song:1", "song:2", "song:3"), { error("Offline") }
        )
        assertEquals(setOf("song:2", "song:3"), mix.map { it.id }.toSet())
    }
}
