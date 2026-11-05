package com.musicvault.app.dj

import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.data.Track
import com.musicvault.app.providers.RelatedArtists
import com.musicvault.app.ui.coordinators.DjRecommendationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DjRuntimeSmokeTest {
    @Test fun offlineMixOnDeviceIsBoundedDiverseAndRepeatable() = runBlocking {
        val known = (0..49).map { Track("test:$it", "Test $it", "Artist ${it % 4}", "unknown", "", 180000) }
        val engine = DjRecommendationEngine()
        suspend fun mix() = engine.recommendations(emptyList(), known, emptyList(), setOf("test:1"), known[0], false,
            known.take(30).mapTo(HashSet()) { it.id }, { error("Offline discovery") }).first
        val first = mix()
        assertEquals(first, mix())
        assertEquals(28, first.size)
        assertTrue(first.none { it.id == "test:0" || it.id == "test:1" })
        assertEquals(4, first.take(4).map { it.artistName }.distinct().size)
    }

    @Test fun artistPortraitDownloadsAndDecodesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val details = RelatedArtists.artistDetails("Queen")
        assertTrue("Wrong homonym chosen", (details?.fans ?: 0) > 1_000_000L)
        val url = details?.photo
        assertNotNull("Artist search did not return a photo", url)
        val result = CoverImageCache.loader(context).execute(coil.request.ImageRequest.Builder(context).data(url).size(160).build())
        assertTrue("Photo did not decode: $result", result is coil.request.SuccessResult)
    }
    @Test fun genreTaxonomyAndArtistPaginationOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val genres = context.assets.open("music_genres.txt").bufferedReader().use { it.readLines() }
        assertTrue(genres.size > 2000)
        val provider = com.musicvault.app.catalog.MusicBrainzCatalogProvider()
        val first = provider.artistsByTag("rock", 0)
        val second = provider.artistsByTag("rock", 20)
        assertTrue(first.first.isNotEmpty())
        assertTrue(first.second)
        assertTrue(second.first.isNotEmpty())
        assertTrue(first.first != second.first)
    }

}
