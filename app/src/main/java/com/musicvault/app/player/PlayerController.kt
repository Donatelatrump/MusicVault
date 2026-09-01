package com.musicvault.app.player

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musicvault.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Non-blocking MediaController facade with seeking, queue and crossfade. */
@OptIn(UnstableApi::class)
class PlayerController(context: Context) {
    data class PlaybackProgress(
        val trackId: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        // Monotonic timestamp (elapsedRealtimeNanos) of when this snapshot was
        // measured. Lyrics extrapolation anchors on it so UI latency can never
        // skew the perceived sync.
        val sampledAtElapsed: Long = 0L
    )

    private val settings: SharedPreferences = context.getSharedPreferences("musicvault.settings", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context, MusicPlaybackService::class.java))
    ).buildAsync()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode
    private val _crossfadeSeconds = MutableStateFlow(settings.getInt("crossfade_seconds", 5).coerceIn(0, 12))
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue
    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex
    private val _ended = MutableStateFlow(0L)
    val ended: StateFlow<Long> = _ended
    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId
    // Invoked on the main thread when the current item has no successor and the
    // app should append one (continuous playback). Without a next item the
    // crossfade can never trigger.
    var onNextItemNeeded: ((finishedTrackId: String?) -> Unit)? = null
    private var pendingRefillFor: String? = null
    // Invoked on the main thread whenever playback UI state is re-published
    // (transitions, ticks). Used by the home-screen widget to stay in sync.
    var onPlaybackUiChanged: (() -> Unit)? = null
    private var ticker: Job? = null
    private var released = false

    init {
        Futures.addCallback(controllerFuture, object : FutureCallback<MediaController> {
            override fun onSuccess(controller: MediaController?) {
                if (controller == null || released) return
                controller.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying; publishProgress(controller) }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        publishQueue(controller)
                        publishProgress(controller)
                        // MEDIA_ITEM_TRANSITION_REASON_REPEAT reaches this listener
                        // after a crossfade hand-off: MediaSession.setPlayer() re-bundles
                        // the player info with the default transition reason whenever the
                        // current item changed. ExoPlayer itself never emits REPEAT (a
                        // repeat-one restart keeps the same item), so accepting it here is
                        // safe and keeps the smart-next refill alive after the swap.
                        // SEEK is the notification/hardware "next" button: skipping onto
                        // the last item must refill too, otherwise the queue dead-ends
                        // and the session hides the next button from the notification.
                        val refillReason = reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        if (refillReason && !controller.hasNextMediaItem()) {
                            onNextItemNeeded?.invoke(mediaItem?.mediaId)
                        }
                    }
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        publishQueue(controller)
                    }
                    override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                        publishQueue(controller)
                        publishProgress(controller)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publishProgress(controller)
                        if (playbackState == androidx.media3.common.Player.STATE_ENDED) _ended.value = System.nanoTime()
                    }
                    override fun onRepeatModeChanged(repeatMode: Int) { _repeatMode.value = repeatMode }
                })
                _isPlaying.value = controller.isPlaying
                _shuffle.value = controller.shuffleModeEnabled
                _repeatMode.value = controller.repeatMode
                _audioSessionId.value = AudioSessionBridge.sessionId
                publishQueue(controller)
                startTicker(controller)
            }
            override fun onFailure(t: Throwable) { _isPlaying.value = false }
        }, MoreExecutors.directExecutor())
    }

    private fun startTicker(controller: MediaController) {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive && !released) {
                publishProgress(controller)
                // ✅ Adaptive polling based on playback state and UI visibility
                delay(when {
                    !controller.isPlaying -> 2000L           // Pausado: cada 2s  
                    controller.isPlaying -> 500L             // Reproduciendo: cada 500ms (antes 250ms)
                    else -> 1000L                            // Otros estados: cada 1s
                })
            }
        }
    }

    private fun publishProgress(controller: MediaController) {
        _progress.value = PlaybackProgress(
            trackId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() },
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = controller.duration.takeIf { it > 0L } ?: 0L,
            isPlaying = controller.isPlaying,
            sampledAtElapsed = android.os.SystemClock.elapsedRealtimeNanos()
        )
        _audioSessionId.value = AudioSessionBridge.sessionId
        _queueIndex.value = controller.currentMediaItemIndex
        // Safety net for the continuous-playback refill: transition events can
        // be missed after a crossfade hand-off (MediaSession.setPlayer re-binds
        // the session to a new player), which left the queue without a successor
        // and made Bluetooth/hardware Next stop responding until the track
        // ended naturally. Any progress tick with no successor asks for one,
        // deduped per track so a failing lookup never spams.
        val currentId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (!controller.hasNextMediaItem() && currentId != null) {
            if (pendingRefillFor != currentId) {
                pendingRefillFor = currentId
                onNextItemNeeded?.invoke(currentId)
            }
        } else {
            pendingRefillFor = null
        }
        onPlaybackUiChanged?.invoke()
    }

    private fun publishQueue(controller: MediaController) {
        val current = _queue.value
        if (current.isEmpty()) return
        _queueIndex.value = controller.currentMediaItemIndex
    }

    private fun mediaItem(track: Track): MediaItem? {
        val audioUri = track.audioUri ?: return null
        return MediaItem.Builder()
            .setUri(Uri.parse(audioUri))
            .setMediaId(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistName)
                    .setAlbumTitle(track.albumTitle)
                    .setArtworkUri(track.coverUri?.let(Uri::parse))
                    .build()
            ).build()
    }

    fun play(track: Track) {
        val item = mediaItem(track) ?: return
        withController { controller ->
            controller.setVolume(1f)
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            _queue.value = listOf(track)
            publishProgress(controller)
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = _shuffle.value) {
        val playable = tracks.filter { it.audioUri != null }
        val items = playable.mapNotNull(::mediaItem)
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        withController { controller ->
            controller.setVolume(1f)
            controller.setMediaItems(items, safeIndex, 0L)
            controller.shuffleModeEnabled = shuffle
            controller.prepare()
            controller.play()
            _queue.value = playable
            _queueIndex.value = safeIndex
            publishProgress(controller)
        }
    }

    fun toggle() = withController { controller ->
        if (controller.isPlaying) controller.pause() else controller.play()
        publishProgress(controller)
    }

    fun pause() = withController { controller -> controller.pause(); publishProgress(controller) }

    fun next() = withController { controller ->
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
        } else {
            // Do not escape the explicit queue when it reaches its last item.
            // Keep the queue intact and simply pause at the end.
            controller.pause()
        }
        publishQueue(controller)
        publishProgress(controller)
    }

    fun previous() = withController { controller ->
        if (controller.currentPosition > 3000L || !controller.hasPreviousMediaItem()) controller.seekTo(0L)
        else controller.seekToPreviousMediaItem()
        controller.play()
        publishProgress(controller)
    }

    fun seekTo(positionMs: Long) = withController { controller ->
        controller.seekTo(positionMs.coerceAtLeast(0L))
        publishProgress(controller)
    }

    fun append(track: Track) {
        val item = mediaItem(track) ?: return
        withController { controller ->
            controller.addMediaItem(item)
            _queue.value = _queue.value + track
            publishQueue(controller)
        }
    }

    fun clearQueue() = withController { controller ->
        controller.clearMediaItems()
        controller.pause()
        _queue.value = emptyList()
        _queueIndex.value = -1
        publishProgress(controller)
    }

    fun removeQueueItem(index: Int) = withController { controller ->
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
            _queue.value = _queue.value.toMutableList().also { it.removeAt(index) }
            publishQueue(controller)
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { controller ->
        if (from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount && from != to) {
            controller.moveMediaItem(from, to)
            _queue.value = _queue.value.toMutableList().also {
                val item = it.removeAt(from)
                it.add(to, item)
            }
            publishQueue(controller)
        }
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        withController { controller -> controller.shuffleModeEnabled = enabled }
    }

    /** Cycles repeat: off → repeat this track once → repeat forever → off. */
    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = next
        withController { controller -> controller.repeatMode = next }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val coerced = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = coerced
        settings.edit().putInt("crossfade_seconds", coerced).apply()
    }

    private fun withController(action: (MediaController) -> Unit) {
        if (released) return
        Futures.addCallback(controllerFuture, object : FutureCallback<MediaController> {
            override fun onSuccess(result: MediaController?) { if (!released && result != null) runCatching { action(result) } }
            override fun onFailure(t: Throwable) {}
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        if (released) return
        released = true
        ticker?.cancel()
        ticker = null
        MediaController.releaseFuture(controllerFuture)
        scope.coroutineContext[Job]?.cancel()
    }
}
