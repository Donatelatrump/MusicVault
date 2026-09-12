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
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
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
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    if (playerCommand == Player.COMMAND_SEEK_TO_NEXT || playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                        if (!player.hasNextMediaItem()) {
                            Log.d("MusicPlaybackService", "Bluetooth Next requested with no next item: preventing song restart")
                            return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                        }
                    }
                    return SessionResult.RESULT_SUCCESS
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val keyEvent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                if (!player.hasNextMediaItem()) {
                                    Log.d("MusicPlaybackService", "Bluetooth media button NEXT: no next item, ignoring")
                                    return true
                                }
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                if (player.currentPosition > 3000L || !player.hasPreviousMediaItem()) {
                                    player.seekTo(0L)
                                } else {
                                    player.seekToPreviousMediaItem()
                                }
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
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
                // Nothing can crossfade while paused: sleep long and skip the
                // prefs read instead of waking every 2 s for the whole session.
                if (!player.isPlaying) {
                    delay(5_000L)
                    continue
                }
                val crossfadeSeconds = settings.getInt("crossfade_seconds", 5).coerceIn(0, 12)

                // duration > 0 excludes C.TIME_UNSET: with an unknown duration
                // "remaining" is garbage and the crossfade can never trigger,
                // so poll slowly instead of busy-looping at 100ms.
                if (crossfadeSeconds > 0 && player.hasNextMediaItem() && player.duration > 0L) {
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
        // REPEAT_ONE exposes the current window as its own successor; fading
        // into it would restart the same track, so skip the crossfade there.
        if (nextIndex == player.currentMediaItemIndex) return
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
        val sourceTimeline = current.currentTimeline
        val sourceIndex = current.currentMediaItemIndex
        val sourceRepeat = current.repeatMode
        fun queueChanged() = current.currentTimeline != sourceTimeline ||
            current.currentMediaItemIndex != sourceIndex ||
            current.shuffleModeEnabled != wasShuffled || current.repeatMode != sourceRepeat
        // Identity of the track being faded out. Every external control (UI,
        // Bluetooth/AVRCP, notification) keeps acting on the session player,
        // which is `current` until the hand-off. If its item changes mid-fade
        // the user has taken over (picked a song from the library, skipped)
        // and the fade must give way instead of stomping their action.
        val fadeSourceMediaId = current.currentMediaItem?.mediaId

        // Keep history and physical indices intact, including the shuffle order.
        val result = List(current.mediaItemCount) { current.getMediaItemAt(it) }
        val order = ArrayList<Int>(result.size)
        var index = sourceTimeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            order += index
            index = sourceTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        next.setMediaItems(result, nextIndex, 0L)
        next.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 0L))
        next.shuffleModeEnabled = wasShuffled
        // Copy the repeat mode verbatim: the old mapping turned REPEAT_ONE
        // into ALL behind the user's back after every crossfade hand-off.
        next.repeatMode = current.repeatMode
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
        // paused, seeked backwards, picked a different song, or the current
        // item already ended and auto-advanced (its remaining time would then
        // be a full track). In those cases a fade would start the same or an
        // unrelated song twice.
        val remainingMs = current.duration - current.currentPosition
        val switchedDuringPrepare = fadeSourceMediaId != null &&
            current.currentMediaItem?.mediaId != fadeSourceMediaId
        if (queueChanged() || !current.isPlaying || switchedDuringPrepare || remainingMs <= 0L || remainingMs > plannedDurationMs + 1500L) {
            Log.d("MusicPlaybackService", "Crossfade aborted: playing=${current.isPlaying} switched=$switchedDuringPrepare remainingMs=$remainingMs plannedMs=$plannedDurationMs")
            abortCrossfade(current, next)
            return
        }

        // End the fade ~600ms before the current track runs out so the hand-off
        // always completes BEFORE a natural transition can fire on the old
        // player. The remaining time measured above is authoritative because it
        // also accounts for the preparation wait.
        val handoffMarginMs = 600L
        val fadeMs = (remainingMs - handoffMarginMs).coerceIn(250L, plannedDurationMs)
        val fadeStartRemaining = remainingMs
        val fadeDeadline = System.currentTimeMillis() + fadeStartRemaining + 3000L

        Log.d("MusicPlaybackService", "Executing crossfade to next track index: $nextIndex, duration: ${fadeMs}ms")
        next.play()
        // Every external control (UI, Bluetooth/AVRCP, notification, widget)
        // targets the session player, which is still `current` during the
        // whole ramp. Without these guards a pause only stopped the fading-out
        // player while the fade-in player kept playing (pause looked broken),
        // a skip produced double audio on both players, and picking a song
        // from the library landed on `current` only to be destroyed by the
        // hand-off release below. User interaction always wins over the fade.
        var lastPosition = current.currentPosition
        while (currentCoroutineContext().isActive) {
            val position = current.currentPosition
            val switched = fadeSourceMediaId != null && current.currentMediaItem?.mediaId != fadeSourceMediaId
            // A backwards jump beyond normal step advance means the user seeked
            // (or REPEAT_ONE restarted the track) mid-fade.
            val seekedBack = position < lastPosition - 700L
            if (queueChanged() || !current.isPlaying || switched || seekedBack) {
                Log.d("MusicPlaybackService", "Crossfade interrupted by user: playing=${current.isPlaying} switched=$switched seekedBack=$seekedBack")
                abortCrossfade(current, next)
                return
            }
            lastPosition = position
            // The playback clock drives the ramp: t is recomputed from the
            // measured remaining time every step, so delay() overshoot can
            // never push the fade past the track's natural end (which used to
            // auto-advance the old player mid-fade and kill the fade-in).
            val remaining = (current.duration - position).coerceAtLeast(0L)
            val t = when {
                remaining <= handoffMarginMs || System.currentTimeMillis() > fadeDeadline -> 1f
                else -> (1f - (remaining - handoffMarginMs).toFloat() / fadeStartRemaining).coerceIn(0f, 1f)
            }
            if (t >= 1f) break
            current.volume = 1f - t
            next.volume = t
            delay(40L)
        }
        if (currentCoroutineContext().isActive) {
            current.volume = 0f
            next.volume = 1f
            // Re-enable focus handling and swap the session BEFORE the old
            // player is released. Releasing `current` abandons audio focus;
            // doing that before `next` re-requests it leaves a window with no
            // focused player that system UI/AVRCP can latch onto as "paused".
            next.setAudioAttributes(mediaAudioAttributes, true)
            mediaSession?.setPlayer(next)
            AudioSessionBridge.sessionId = next.audioSessionId
            current.stop()
            current.release()
            player = next
            // Restore the user's shuffle setting on the session player so the
            // app state and future hand-offs stay consistent.
            next.shuffleModeEnabled = wasShuffled
        } else {
            next.stop()
            next.release()
        }
        crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
    }

    /** Cancels a fade in progress and hands control back to [current]. */
    private fun abortCrossfade(current: ExoPlayer, next: ExoPlayer) {
        next.stop()
        next.release()
        // Restore full volume: unpausing must never come back silent, and a
        // newly picked song must not inherit the ramped-down volume of the
        // aborted fade.
        current.volume = 1f
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
