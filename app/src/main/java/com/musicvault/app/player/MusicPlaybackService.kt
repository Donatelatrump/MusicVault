package com.musicvault.app.player

import android.util.Log

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
    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

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
                // During an active crossfade the fade ramp owns both players'
                // volumes and the target index. A transition fired by the
                // fading player (auto-advance when it runs out of track) or by
                // the hand-off player (prepare) must not reset either, or the
                // old player audibly jumps back to full volume mid-fade.
                if (crossfadeJob?.isActive == true) return
                crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
                target.volume = 1f
            }
        })
    }

    private fun buildPlayer(requestAudioFocus: Boolean = true): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(mediaAudioAttributes, requestAudioFocus)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private fun startCrossfadeMonitor() {
        serviceScope.launch {
            while (isActive) {
                val crossfadeSeconds = settings.getInt("crossfade_seconds", 5).coerceIn(0, 12)
                
                // duration > 0 excludes C.TIME_UNSET: with an unknown duration
                // "remaining" is garbage and the crossfade can never trigger,
                // so poll slowly instead of busy-looping at 100ms.
                if (crossfadeSeconds > 0 && player.isPlaying && player.hasNextMediaItem() && player.duration > 0L) {
                    val remaining = player.duration - player.currentPosition
                    val crossfadeWindowMs = crossfadeSeconds * 1000L
                    
                    // ✅ Solo monitorear cuando estamos cerca del crossfade
                    if (remaining <= crossfadeWindowMs + 2000L) { // 2s buffer
                        maybeCrossfade()
                        delay(100L) // Monitor frecuente solo cerca del crossfade
                    } else {
                        // ✅ Delay inteligente: despertar justo antes del crossfade
                        val sleepTime = (remaining - crossfadeWindowMs - 1000L).coerceAtLeast(1000L)
                        delay(sleepTime.coerceAtMost(5000L)) // Máximo 5s de sleep
                    }
                } else {
                    // ✅ Sin crossfade necesario - sleep largo
                    delay(2000L)
                }
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
        Log.d("MusicPlaybackService", "Starting crossfade job for index $nextIndex with ${remaining}ms remaining")
        crossfadeJob = serviceScope.launch {
            runCrossfade(player, nextIndex, remaining.coerceAtLeast(500L))
        }
    }

    private suspend fun runCrossfade(current: ExoPlayer, nextIndex: Int, plannedDurationMs: Long) {
        // The crossfade player must NOT request audio focus. If it did, Media3's
        // audio-focus manager can pause the current player as soon as the next
        // player starts, which turns the crossfade into a hard switch and leaves
        // the UI reporting the old player as paused.
        val next = buildPlayer(requestAudioFocus = false)
        attachPlayerListeners(next)
        val wasShuffled = current.shuffleModeEnabled

        // Build the remaining sequence starting at the ACTUAL next item by
        // walking the timeline's successor chain. This is identical for
        // shuffled and ordered queues (and respects repeat modes). The
        // previous code fed the full queue to the hand-off player when shuffle
        // was off, so the crossfade faded into queue item 0 — the first song
        // of the list — instead of the track that would have played next.
        val result = ArrayList<androidx.media3.common.MediaItem>(current.mediaItemCount)
        val seen = HashSet<Int>()
        var index = nextIndex
        while (index != androidx.media3.common.C.INDEX_UNSET && seen.add(index)) {
            result += current.getMediaItemAt(index)
            index = current.currentTimeline.getNextWindowIndex(index, current.repeatMode, current.shuffleModeEnabled)
        }
        // Include any items not reachable from the successor chain
        // (defensive fallback for unusual/repeat configurations).
        if (result.size < current.mediaItemCount) {
            for (i in 0 until current.mediaItemCount) {
                if (seen.add(i)) result += current.getMediaItemAt(i)
            }
        }

        next.setMediaItems(result, 0, 0L)
        // Shuffle stays disabled on the hand-off player so Media3 does not
        // re-shuffle the exact sequence built above. The user's shuffle flag is
        // restored on the player right after the swap.
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
            Log.d("MusicPlaybackService", "Crossfade aborted: next item never became READY")
            next.release()
            crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
            return
        }

        // Abort cleanly when the situation changed while preparing: the user
        // paused, seeked backwards, or the current item already ended and
        // auto-advanced (its remaining time would then be a full track). In
        // those cases a fade would start the same or an unrelated song twice.
        val remainingMs = current.duration - current.currentPosition
        if (!current.isPlaying || remainingMs <= 0L || remainingMs > plannedDurationMs + 1500L) {
            Log.d("MusicPlaybackService", "Crossfade aborted: playing=${current.isPlaying} remainingMs=$remainingMs plannedMs=$plannedDurationMs")
            next.release()
            crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
            return
        }

        // End the fade ~300ms before the current track runs out so the ramp
        // always completes BEFORE a natural transition can fire on the old
        // player. The remaining time measured above is authoritative because it
        // also accounts for the preparation wait.
        val fadeMs = (remainingMs - 300L).coerceIn(250L, plannedDurationMs)

        Log.d("MusicPlaybackService", "Executing crossfade to next track index: $nextIndex, duration: ${fadeMs}ms")
        next.play()
        // Finer ramp: 20 fixed steps made long fades (10-12s) audibly chunky.
        val steps = (fadeMs / 50L).toInt().coerceIn(20, 60)
        val stepDelay = fadeMs / steps
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
            // Restore the user's shuffle setting on the session player so the
            // app state and future hand-offs stay consistent.
            next.shuffleModeEnabled = wasShuffled
            // The hand-off player was built without audio focus so it could not
            // pause the fading player. Once it owns playback it must handle
            // focus again (calls, navigation prompts, other apps).
            next.setAudioAttributes(mediaAudioAttributes, true)
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
