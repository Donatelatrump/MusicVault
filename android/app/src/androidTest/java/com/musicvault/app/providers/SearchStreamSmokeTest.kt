package com.musicvault.app.providers

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.catalog.DeezerCatalogProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in network regression for catalog results that must resolve to readable audio. */
class SearchStreamSmokeTest {
    @Test fun deezerFriendsVersionsHaveReadableAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicvault.liveStreams") == "true")
        Providers.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val results = DeezerCatalogProvider().searchTracks("how can we go back to being friends", 50)
        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        for (artist in listOf("GauravW", "Money House")) {
            val track = results.firstOrNull { it.artistName.equals(artist, true) }
            if (track == null) { failures += "$artist missing from catalog"; continue }
            val match = NewPipeStreamResolver.findYoutubeMatch(track.title, track.artistName)
            report += "artist=$artist deezer=${track.id} match=$match"
            Log.i("MusicVaultStreamTest", report.last())
            val stream = Providers.resolveStream(track.title, track.artistName, cacheKey = "deezer:recording:${track.id}", forceRefresh = true)
            if (stream == null) { failures += "$artist: ${Providers.lastFailure.value}"; continue }
            try {
                client.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-1023").build()).execute().use { response ->
                    val readable = response.isSuccessful && (response.body?.byteStream()?.read() ?: -1) >= 0
                    report += "artist=$artist status=${response.code} format=${stream.extension} readable=$readable"
                    if (!readable) failures += "$artist: HTTP ${response.code}"
                }
            } catch (error: Exception) { failures += "$artist: $error" }
            val ready = java.util.concurrent.CountDownLatch(1)
            var playbackError: String? = null
            lateinit var player: androidx.media3.exoplayer.ExoPlayer
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(instrumentation.targetContext).build()
                player.volume = 0f
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) ready.countDown() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playbackError = "${error.errorCodeName}: ${error.cause}"
                        ready.countDown()
                    }
                })
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(stream.url))
                player.prepare()
                player.play()
            }
            try {
                val completed = ready.await(25, TimeUnit.SECONDS)
                report += "artist=$artist playbackStarted=${completed && playbackError == null} error=$playbackError"
                if (!completed || playbackError != null) failures += "$artist: playback timeout/error $playbackError"
            } finally { instrumentation.runOnMainSync { player.release() } }
        }
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "friends-stream-report.txt")
            .writeText((report + failures).joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
