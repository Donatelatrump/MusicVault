package com.musicvault.app.player

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicvault.app.MainActivity

class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var settings: SharedPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var crossfadeJob: Job? = null
    private var crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET

    override fun onCreate() {
        super.onCreate()

        settings = getSharedPreferences("musicvault.settings", MODE_PRIVATE)
        player = buildPlayer()
        attachPlayerListeners(player)
        AudioSessionBridge.sessionId = player.audioSessionId
        startCrossfadeMonitor()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }


    private fun attachPlayerListeners(target: ExoPlayer) {
        target.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
                target.volume = 1f
            }
        })
    }

    private fun buildPlayer(requestAudioFocus: Boolean = true): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            requestAudioFocus
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private fun startCrossfadeMonitor() {
        serviceScope.launch {
            while (isActive) {
                maybeCrossfade()
                delay(100L)
            }
        }
    }

    private fun maybeCrossfade() {
        val seconds = settings.getInt("crossfade_seconds", 5).coerceIn(0, 12)
        if (seconds <= 0 || crossfadeJob?.isActive == true) return
        if (!player.isPlaying || player.duration <= 0L || !player.hasNextMediaItem()) return
        val remaining = player.duration - player.currentPosition
        if (remaining > seconds * 1000L) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == androidx.media3.common.C.INDEX_UNSET || nextIndex == crossfadeTargetIndex) return
        crossfadeTargetIndex = nextIndex
        crossfadeJob = serviceScope.launch {
            runCrossfade(player, nextIndex, remaining.coerceAtLeast(500L))
        }
    }

    private suspend fun runCrossfade(current: ExoPlayer, nextIndex: Int, durationMs: Long) {
        // The crossfade player must NOT request audio focus. If it did, Media3's
        // audio-focus manager can pause the current player as soon as the next
        // player starts, which turns the crossfade into a hard switch and leaves
        // the UI reporting the old player as paused.
        val next = buildPlayer(requestAudioFocus = false)
        attachPlayerListeners(next)

        // When shuffle is enabled, nextMediaItemIndex is a position in the
        // current shuffled order. Calling setMediaItems(..., nextIndex) and
        // then enabling shuffle again creates a NEW shuffle order, so the
        // crossfade could jump to an unrelated (and often the same) song.
        // Rebuild the remaining shuffled order explicitly and disable shuffle
        // on the temporary player. This preserves the exact sequence selected
        // by the original player across the hand-off.
        val sourceItems = (0 until current.mediaItemCount).map(current::getMediaItemAt)
        val orderedItems = if (!current.shuffleModeEnabled) {
            sourceItems
        } else {
            val result = ArrayList<androidx.media3.common.MediaItem>(sourceItems.size)
            val seen = HashSet<Int>()
            var index = nextIndex
            while (index != androidx.media3.common.C.INDEX_UNSET && seen.add(index)) {
                result += current.getMediaItemAt(index)
                index = current.currentTimeline.getNextWindowIndex(index, current.repeatMode, true)
            }
            // Include any items not reachable from the shuffled successor chain
            // (defensive fallback for unusual/repeat configurations).
            if (result.size < sourceItems.size) {
                sourceItems.forEachIndexed { i, item ->
                    if (seen.add(i)) result += item
                }
            }
            result
        }

        next.setMediaItems(orderedItems, 0, 0L)
        next.shuffleModeEnabled = false
        next.repeatMode = if (current.repeatMode == Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ALL
        }
        next.volume = 0f
        next.prepare()

        var ready = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready = true
            }
        }
        next.addListener(listener)
        val deadline = System.currentTimeMillis() + 4000L
        while (!ready && System.currentTimeMillis() < deadline) delay(25L)
        next.removeListener(listener)
        if (!ready) {
            next.release()
            crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
            return
        }

        next.play()
        val steps = 20
        val stepDelay = (durationMs / steps).coerceAtLeast(20L)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            current.volume = 1f - t
            next.volume = t
            delay(stepDelay)
        }
        if (currentCoroutineContext().isActive) {
            next.volume = 1f
            mediaSession?.setPlayer(next)
            AudioSessionBridge.sessionId = next.audioSessionId
            current.stop()
            current.release()
            player = next
        } else {
            next.stop()
            next.release()
        }
        crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        crossfadeJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        AudioSessionBridge.sessionId = 0
        super.onDestroy()
    }
}
