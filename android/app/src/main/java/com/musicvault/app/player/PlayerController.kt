package com.musicvault.app.player

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Non-blocking MediaController facade with seeking, queue and crossfade. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerController(context: Context) {
    private val appContext = context.applicationContext
    data class PlaybackProgress(
        val trackId: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        // Monotonic timestamp (elapsedRealtimeNanos) of when this snapshot was
        // measured. Lyrics extrapolation anchors on it so UI latency can never
        // skew the perceived sync.
        val sampledAtElapsed: Long = 0L,
        val speed: Float = 1f
    )

    private val settings: SharedPreferences = com.musicvault.app.settings.AppPreferences.prefs(context)
    val restoredQueueState = PlaybackQueueState.decode(settings.getString(PlaybackQueueState.KEY, null))
    val restoredSnapshot = restoredQueueState?.let { PlaybackSnapshot(it.tracks[it.index], it.positionMs) }
        ?: PlaybackSnapshot.decode(settings.getString(PlaybackSnapshot.KEY, null))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context, MusicPlaybackService::class.java))
    ).setConnectionHints(android.os.Bundle().apply { putBoolean("musicvault.internal_playback", true) }).buildAsync()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _progress = MutableStateFlow(restoredSnapshot?.let {
        PlaybackProgress(trackId = it.track.id, positionMs = it.positionMs, durationMs = it.track.durationMs)
    } ?: PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress
    private val _shuffle = MutableStateFlow(restoredQueueState?.shuffle ?: false)
    val shuffle: StateFlow<Boolean> = _shuffle
    val repeatMode: StateFlow<Int> = PlaybackOptions.repeatMode
    val sleepRemainingMs: StateFlow<Long> = PlaybackOptions.sleepRemainingMs
    private val _crossfadeSeconds = MutableStateFlow(settings.getInt("crossfade_seconds", 5).coerceIn(0, 12))
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds
    private val _queue = MutableStateFlow<List<Track>>(restoredQueueState?.tracks ?: emptyList())
    private val knownTracks = mutableMapOf<String, Track>()
    val queue: StateFlow<List<Track>> = _queue
    private val _queueIndex = MutableStateFlow(restoredQueueState?.index ?: -1)
    val queueIndex: StateFlow<Int> = _queueIndex
    private val _ended = MutableStateFlow(0L)
    val ended: StateFlow<Long> = _ended
    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId
    // Invoked on the main thread when the current item has no successor and the
    // app should append one (continuous playback). Without a next item the
    // crossfade can never trigger.
    var onNextItemNeeded: ((finishedTrackId: String?) -> Unit)? = null
    // Used only for recoverable remote-stream failures (for example an expired
    // signed googlevideo URL). The ViewModel can re-resolve the URL and replace
    // the current media item without destroying the rest of the queue.
    var onPlaybackError: ((trackId: String?) -> Unit)? = null
    private var pendingRefillFor: String? = null
    // Last (trackId, isPlaying) pair pushed to the playback-UI listener. The
    // widget only mirrors those two values, so publishing on every progress
    // tick would cost a binder round-trip each 500 ms even with the widget
    // gone. Invoking only on change keeps the widget live at transitions.
    private var lastUiStateKey: Pair<String?, Boolean>? = null
    // Invoked on the main thread whenever playback UI state is re-published
    // (transitions, ticks). Used by the home-screen widget to stay in sync.
    var onPlaybackUiChanged: (() -> Unit)? = null
    var onTrackSkipped: ((String) -> Unit)? = null
    var listeningEpoch = 0L
        private set
    private var ticker: Job? = null
    private var released = false
    private var queueRestored = false
    private var queueRestoring = false
    private var queueGeneration = 0L

    init {
        Futures.addCallback(controllerFuture, object : FutureCallback<MediaController> {
            override fun onSuccess(controller: MediaController?) {
                if (controller == null || released) return
                controller.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        publishProgress(controller)
                        if (isPlaying) startTicker(controller) else stopTicker()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        listeningEpoch++
                        publishQueue(controller)
                        publishProgress(controller)
                        // MEDIA_ITEM_TRANSITION_REASON_REPEAT reaches this listener
                        // after a crossfade hand-off: MediaSession.setPlayer() re-bundles
                        // the player info with the default transition reason whenever the
                        // current item changed. Real repeat restarts also emit REPEAT;
                        // consumption of the one-shot repeat belongs to the service.
                        // Accepting it here keeps smart-next refill alive after the swap.
                        // SEEK is the notification/hardware "next" button: skipping onto
                        // the last item must refill too, otherwise the queue dead-ends
                        // and the session hides the next button from the notification.
                        val refillReason = reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                            reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        if (refillReason && !controller.hasNextMediaItem() && QueueRestoration.state == null) {
                            onNextItemNeeded?.invoke(mediaItem?.mediaId)
                        }
                    }
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        publishQueue(controller)
                    }
                    override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                        if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                            oldPosition.mediaItem?.mediaId?.let { onTrackSkipped?.invoke(it) }
                        }
                        publishQueue(controller)
                        publishProgress(controller)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publishProgress(controller)
                        if (playbackState == androidx.media3.common.Player.STATE_ENDED) _ended.value = System.nanoTime()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("MusicVaultPlayback",
                            "Playback failed track=${controller.currentMediaItem?.mediaId} code=${error.errorCodeName}", error)
                        onPlaybackError?.invoke(controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() })
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { _shuffle.value = shuffleModeEnabled }
                })
                _isPlaying.value = controller.isPlaying
                if (controller.currentMediaItem != null) _shuffle.value = controller.shuffleModeEnabled
                _audioSessionId.value = AudioSessionBridge.sessionId
                publishQueue(controller)
                if (controller.currentMediaItem != null) publishProgress(controller)
                if (controller.isPlaying) startTicker(controller)
            }
            override fun onFailure(t: Throwable) { _isPlaying.value = false }
        }, MoreExecutors.directExecutor())
    }

    private fun startTicker(controller: MediaController) {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive && !released) {
                publishProgress(controller)
                delay(500L)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publishProgress(controller: MediaController) {
        // An empty new session must not erase the paused restored UI snapshot.
        if (controller.currentMediaItem == null) return
        _progress.value = PlaybackProgress(
            trackId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() },
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = controller.duration.takeIf { it > 0L } ?: 0L,
            isPlaying = controller.isPlaying,
            sampledAtElapsed = android.os.SystemClock.elapsedRealtimeNanos(),
            speed = controller.playbackParameters.speed
        )
        _audioSessionId.value = AudioSessionBridge.sessionId
        _queueIndex.value = QueueRestoration.state?.tracks?.indexOfFirst { it.id == controller.currentMediaItem?.mediaId }
            ?: controller.currentMediaItemIndex
        // Safety net for the continuous-playback refill: transition events can
        // be missed after a crossfade hand-off (MediaSession.setPlayer re-binds
        // the session to a new player), which left the queue without a successor
        // and made Bluetooth/hardware Next stop responding until the track
        // ended naturally. Any progress tick with no successor asks for one,
        // deduped per track so a failing lookup never spams.
        val currentId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (!controller.hasNextMediaItem() && currentId != null && QueueRestoration.state == null) {
            if (pendingRefillFor != currentId) {
                pendingRefillFor = currentId
                onNextItemNeeded?.invoke(currentId)
            }
        } else {
            pendingRefillFor = null
        }
        val uiKey = currentId to controller.isPlaying
        if (uiKey != lastUiStateKey) {
            lastUiStateKey = uiKey
            onPlaybackUiChanged?.invoke()
        }
    }

    private fun publishQueue(controller: MediaController) {
        QueueRestoration.state?.let { state ->
            _queue.value = state.tracks
            _queueIndex.value = state.tracks.indexOfFirst { it.id == controller.currentMediaItem?.mediaId }.takeIf { it >= 0 } ?: state.index
            return
        }
        if (controller.mediaItemCount == 0 && restoredQueueState != null && !queueRestored) return
        _queue.value = List(controller.mediaItemCount) { index ->
            val item = controller.getMediaItemAt(index)
            knownTracks[item.mediaId] ?: PlaybackSnapshot.decode(
                item.mediaMetadata.extras?.getString(PlaybackSnapshot.KEY)
            )?.track?.copy(audioUri = item.localConfiguration?.uri?.toString()) ?: Track(
                id = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artistName = item.mediaMetadata.artist?.toString().orEmpty(),
                albumId = "unknown",
                albumTitle = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                durationMs = 0L,
                audioUri = item.localConfiguration?.uri?.toString(),
                coverUri = item.mediaMetadata.artworkUri?.toString()
            )
        }
        _queueIndex.value = if (controller.mediaItemCount == 0) -1 else controller.currentMediaItemIndex
    }

    private fun mediaItem(track: Track): MediaItem? = PlaybackItems.build(appContext, track)

    fun play(track: Track, startPositionMs: Long = 0L) {
        queueGeneration++
        QueueRestoration.state = null
        queueRestored = true
        val item = mediaItem(track) ?: return
        knownTracks[track.id] = track
        withController { controller ->
            // Standalone playback uses the app-managed smart successor queue.
            // Keep Media3's shuffle order off so dynamically appended items stay
            // reachable by the system Next command.
            controller.shuffleModeEnabled = false
            controller.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
            controller.prepare()
            controller.play()
            publishQueue(controller)
            publishProgress(controller)
        }
    }

    fun hasCurrentMediaItem(): Boolean = if (controllerFuture.isDone && !controllerFuture.isCancelled) {
        runCatching { controllerFuture.get().currentMediaItem != null }.getOrDefault(false)
    } else false

    val isConnected: Boolean get() = controllerFuture.isDone && !controllerFuture.isCancelled
    fun whenConnected(action: () -> Unit) = withController { action() }

    fun retryCurrent(track: Track) {
        val item = mediaItem(track) ?: return
        knownTracks[track.id] = track
        withController { controller ->
            val index = controller.currentMediaItemIndex
            if (index < 0 || controller.currentMediaItem?.mediaId != track.id) return@withController
            val position = controller.currentPosition.coerceAtLeast(0L)
            controller.replaceMediaItem(index, item)
            controller.prepare()
            controller.seekTo(index, position)
            controller.play()
            publishQueue(controller)
            publishProgress(controller)
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = _shuffle.value) {
        queueGeneration++
        QueueRestoration.state = null
        queueRestored = true
        val playable = tracks.filter { it.audioUri != null }
        val items = playable.mapNotNull(::mediaItem)
        if (items.isEmpty()) return
        playable.forEach { knownTracks[it.id] = it }
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        withController { controller ->
            controller.setMediaItems(items, safeIndex, 0L)
            controller.shuffleModeEnabled = shuffle
            controller.prepare()
            controller.play()
            publishQueue(controller)
            publishProgress(controller)
        }
    }

    /** Resolve the saved selection first; rebuild the rest asynchronously without fake URLs. */
    suspend fun restoreQueue(resolveTrack: suspend (Track) -> Track?, playWhenReady: Boolean = false): Boolean {
        val state = restoredQueueState ?: return false
        if (queueRestoring || queueRestored) return false
        queueRestoring = true
        val generation = queueGeneration
        suspend fun resolve(index: Int): Track? {
            val track = state.tracks[index]
            return (if (PlaybackItems.isReadableLocal(appContext, track)) track else resolveTrack(track))
                ?.takeIf { ready -> ready.audioUri?.let { android.net.Uri.parse(it).scheme in listOf("http", "https") } == true || PlaybackItems.isReadableLocal(appContext, ready) }
        }
        try {
            val seed = resolve(state.index) ?: return false
            val seedItem = mediaItem(seed) ?: return false
            if (generation != queueGeneration || released) return false
            knownTracks[seed.id] = seed
            queueRestored = true
            QueueRestoration.state = state
            val installed = mutableListOf(state.index)
            withController { controller ->
                if (generation != queueGeneration) return@withController
                controller.setMediaItem(seedItem, state.positionMs)
                controller.shuffleModeEnabled = state.shuffle
                controller.prepare()
                controller.playWhenReady = playWhenReady
                publishQueue(controller); publishProgress(controller)
            }
            scope.launch {
                // Follow the saved traversal first so a successor is prepared before the song ends.
                val traversal = if (state.shuffle) state.shuffleOrder else state.tracks.indices.toList()
                val at = traversal.indexOf(state.index)
                val priority = traversal.drop(at + 1) + traversal.take(at)
                for (originalIndex in priority) {
                    coroutineContext.ensureActive()
                    if (generation != queueGeneration || released) break
                    val track = runCatching { resolve(originalIndex) }.getOrNull() ?: continue
                    val item = mediaItem(track) ?: continue
                    if (generation != queueGeneration || released) break
                    knownTracks[track.id] = track
                    withController { controller ->
                        if (generation != queueGeneration) return@withController
                        val insertion = installed.count { it < originalIndex }
                        installed.add(insertion, originalIndex)
                        controller.addMediaItem(insertion, item)
                        val order = state.shuffleOrder.mapNotNull { old -> installed.indexOf(old).takeIf { it >= 0 } }
                        controller.sendCustomCommand(androidx.media3.session.SessionCommand("musicvault.restore_order", android.os.Bundle.EMPTY),
                            android.os.Bundle().apply { putIntArray("order", order.toIntArray()); putInt("repeat", state.repeatMode) })
                        publishQueue(controller)
                    }
                }
                if (generation == queueGeneration) {
                    QueueRestoration.state = null
                    withController { controller ->
                        val order = state.shuffleOrder.mapNotNull { old -> installed.indexOf(old).takeIf { it >= 0 } }
                        controller.sendCustomCommand(androidx.media3.session.SessionCommand("musicvault.restore_order", android.os.Bundle.EMPTY),
                            android.os.Bundle().apply { putIntArray("order", order.toIntArray()); putInt("repeat", state.repeatMode) })
                        publishQueue(controller)
                    }
                }
            }
            return true
        } finally { queueRestoring = false }
    }

    fun toggle() = withController { controller ->
        if (controller.isPlaying) controller.pause() else controller.play()
        publishProgress(controller)
    }

    /**
     * Reasserts the live session play state after returning to the foreground.
     * Volume remains owned by service normalization and crossfade.
     */
    fun refreshPlaybackState() = withController { controller ->
        if (controller.isPlaying) {
            controller.play()
        }
        publishProgress(controller)
    }

    fun resume() = withController { controller -> controller.play(); publishProgress(controller) }

    fun hasNext(): Boolean = if (controllerFuture.isDone && !controllerFuture.isCancelled) {
        runCatching { controllerFuture.get().hasNextMediaItem() }.getOrDefault(false)
    } else false

    fun pause() = withController { controller -> controller.pause(); publishProgress(controller) }

    fun next() = withController { controller ->
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
        } else {
            // No successor in the explicit queue: ask the app for one so the
            // system media notification and Bluetooth/AVRCP keep working. The
            // app appends the suggested track and the next progress tick will
            // see hasNextMediaItem() == true. Without this, pressing Next on
            // the headset at the end of a list silently did nothing and
            // Media3 hid the next button from the notification.
            val currentId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            pendingRefillFor = null
            onNextItemNeeded?.invoke(currentId)
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

    fun append(track: Track, afterTrackId: String? = null) {
        queueGeneration++
        QueueRestoration.state = null
        val item = mediaItem(track) ?: return
        knownTracks[track.id] = track
        withController { controller ->
            if (afterTrackId != null && (controller.currentMediaItem?.mediaId != afterTrackId || controller.hasNextMediaItem())) return@withController
            controller.addMediaItem(item)
            publishQueue(controller)
        }
    }

    fun trimAfterCurrent() = withController { controller ->
        queueGeneration++
        QueueRestoration.state = null
        val current = controller.currentMediaItemIndex
        if (current >= 0 && current + 1 < controller.mediaItemCount) {
            controller.removeMediaItems(current + 1, controller.mediaItemCount)
            publishQueue(controller)
        }
    }

    fun clearQueue() = withController { controller ->
        queueGeneration++
        QueueRestoration.state = null
        queueRestored = true
        controller.clearMediaItems()
        controller.pause()
        _queue.value = emptyList()
        _queueIndex.value = -1
        _progress.value = PlaybackProgress()
        onPlaybackUiChanged?.invoke()
        publishProgress(controller)
    }

    fun removeQueueItem(index: Int) = withController { controller ->
        queueGeneration++
        QueueRestoration.state = null
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
            publishQueue(controller)
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { controller ->
        queueGeneration++
        QueueRestoration.state = null
        if (from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount && from != to) {
            controller.moveMediaItem(from, to)
            publishQueue(controller)
        }
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        withController { controller -> controller.shuffleModeEnabled = enabled }
    }

    /** Cycles repeat: off → repeat this track once → repeat forever → off. */
    fun cycleRepeatMode() = withController { PlaybackOptions.cycleRepeat() }

    fun setSleepTimer(minutes: Int) = withController { PlaybackOptions.setTimer(minutes) }

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
