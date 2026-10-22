package com.musicvault.app.player

import android.util.Log

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import android.view.KeyEvent
import android.os.SystemClock
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.data.MusicRepository
import com.musicvault.app.data.Track
import kotlinx.coroutines.withContext
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.musicvault.app.MainActivity

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MusicPlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private var localCatalog: List<Track> = emptyList()
    private var queueInstalled = false
    private var loudnessJob: Job? = null
    private val normalizedGains = mutableMapOf<String, Float>()
    private val replayGains = mutableMapOf<String, Float>()
    private lateinit var settings: SharedPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fadingPlayer: ExoPlayer? = null
    private var sleepTimerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var crossfadeMonitorJob: Job? = null
    private var snapshotJob: Job? = null
    private var lastPlayedMediaId: String? = null
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "offline_mode" && settings.getBoolean("offline_mode", false)) {
            val uri = player.currentMediaItem?.localConfiguration?.uri
            if (uri?.scheme in listOf("http", "https")) { player.pause(); player.stop() }
            fadingPlayer?.let { target ->
                if (target.currentMediaItem?.localConfiguration?.uri?.scheme in listOf("http", "https")) { target.pause(); target.stop() }
            }
        }
        if (key == "crossfade_seconds") updateCrossfadeMonitor()
        if (key in listOf("playback_speed", "skip_silence", "normalize_volume")) {
            applyAudioOptions(player); fadingPlayer?.let(::applyAudioOptions); applyNormalization(player)
        }
    }
    private var crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
    private val bufferingStartedAt = java.util.WeakHashMap<ExoPlayer, Long>()
    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    override fun onCreate() {
        super.onCreate()

        settings = com.musicvault.app.settings.AppPreferences.prefs(this)
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        player = buildPlayer()
        attachPlayerListeners(player)
        restoreLocalQueue()
        serviceScope.launch {
            MusicRepository(MusicDatabase.get(this@MusicPlaybackService)).tracks().collect { tracks ->
                localCatalog = withContext(Dispatchers.IO) { tracks.filter { PlaybackItems.isReadableLocal(this@MusicPlaybackService, it) } }
                mediaSession?.notifyChildrenChanged("musicvault.local", localCatalog.size, null)
            }
        }
        com.musicvault.app.scrobble.ListenBrainz.initialize(this)
        snapshotJob = serviceScope.launch {
            while (isActive) {
                if (player.isPlaying) savePlaybackSnapshot()
                observeScrobble()
                delay(2_000L)
            }
        }
        PlaybackOptions.onRepeatChanged = {
            player.repeatMode = if (PlaybackOptions.policy.repeatMode == 0) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        }
        PlaybackOptions.onRepeatChanged?.invoke()
        PlaybackOptions.onTimerChanged = ::scheduleSleepTimer
        scheduleSleepTimer()
        AudioSessionBridge.sessionId = player.audioSessionId
        updateCrossfadeMonitor()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val result = super.onConnect(session, controller)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(result.availableSessionCommands.buildUpon()
                            .add(SessionCommand("musicvault.restore_order", Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction != "musicvault.restore_order" || controller.packageName != packageName)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    val order = args.getIntArray("order") ?: intArrayOf()
                    if (order.sorted() != (0 until player.mediaItemCount).toList())
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, 0L))
                    PlaybackOptions.restoreRepeat(args.getInt("repeat", 0))
                    savePlaybackSnapshot()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(LibraryResult.ofItem(libraryRoot(), params))
                override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    if (parentId != "musicvault.local" || page < 0 || pageSize <= 0)
                        return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    return Futures.immediateFuture(LibraryResult.ofItemList(localCatalog.drop(offset).take(pageSize).mapNotNull { PlaybackItems.build(this@MusicPlaybackService, it) }, params))
                }
                override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
                    val item = if (mediaId == "musicvault.local") libraryRoot() else localCatalog.firstOrNull { it.id == mediaId }?.let { PlaybackItems.build(this@MusicPlaybackService, it) }
                    return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
                override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                    return if (parentId == "musicvault.local") {
                        session.notifyChildrenChanged(browser, parentId, localCatalog.size, params)
                        Futures.immediateFuture(LibraryResult.ofVoid())
                    } else Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
                override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                    session.notifySearchResultChanged(browser, query, searchLocal(query).size, params)
                    return Futures.immediateFuture(LibraryResult.ofVoid())
                }
                override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    if (page < 0 || pageSize <= 0) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    return Futures.immediateFuture(LibraryResult.ofItemList(searchLocal(query).drop(offset).take(pageSize).mapNotNull { PlaybackItems.build(this@MusicPlaybackService, it) }, params))
                }
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    return Futures.immediateFuture(mediaItems.flatMap { item ->
                        if (controller.packageName == packageName && item.localConfiguration != null) listOf(item)
                        else if (!item.requestMetadata.searchQuery.isNullOrBlank()) searchLocal(item.requestMetadata.searchQuery!!).mapNotNull { PlaybackItems.build(this@MusicPlaybackService, it) }
                        else listOfNotNull(localCatalog.firstOrNull { it.id == item.mediaId }?.let { PlaybackItems.build(this@MusicPlaybackService, it) })
                    })
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    if (playerCommand == Player.COMMAND_SEEK_TO_NEXT || playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                        if (!player.hasNextMediaItem()) {
                            Log.d("MusicPlaybackService", "Bluetooth Next requested with no next item: preventing song restart")
                            return SessionError.ERROR_NOT_SUPPORTED
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
            .setSessionActivity(pendingIntent)
            .build()
    }


    private fun attachPlayerListeners(target: ExoPlayer) {
        target.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val id = target.currentMediaItem?.mediaId ?: return
                for (group in tracks.groups) {
                    for (index in 0 until group.length) {
                        if (!group.isTrackSelected(index)) continue
                        val metadata = group.getTrackFormat(index).metadata ?: continue
                        ReplayGain.gain(metadata)?.let { replayGains[id] = it }
                    }
                }
                if (target === player && crossfadeJob?.isActive != true) target.volume = normalizedGain(target)
            }
            override fun onEvents(changedPlayer: Player, events: Player.Events) {
                if (target === player) {
                    if (target.currentMediaItem == null) PlaybackRouteState.current.value = null
                    target.currentMediaItem?.let { item ->
                        PlaybackRouteState.current.value = PlaybackSnapshot.decode(item.mediaMetadata.extras?.getString(PlaybackSnapshot.KEY))?.track
                            ?.copy(audioUri = item.localConfiguration?.uri?.toString())
                    }
                }
                if (target === player && events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED
                )) { savePlaybackSnapshot(); observeScrobble() }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (target === player && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    PlaybackOptions.trackRepeated()
                }
                // During an active crossfade the fade ramp owns both players'
                // volumes and the target index. A transition fired by the
                // fading player (auto-advance when it runs out of track) or by
                // the hand-off player (prepare) must not reset either, or the
                // old player audibly jumps back to full volume mid-fade.
                if (crossfadeJob?.isActive == true) return
                crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
                target.volume = normalizedGain(target)
                if (target === player) applyNormalization(target)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> bufferingStartedAt.putIfAbsent(target, SystemClock.elapsedRealtime())
                    Player.STATE_READY -> {
                        val mediaId = target.currentMediaItem?.mediaId
                        bufferingStartedAt.remove(target)?.let { started ->
                            Log.d(
                                "MusicVaultStreamPerf",
                                "media3 READY ${SystemClock.elapsedRealtime() - started}ms id=$mediaId"
                            )
                        }
                        mediaId?.takeIf { it.isNotBlank() }?.let(StreamingCache::markPlaybackReady)
                    }
                }
            }
        })
    }

    private fun observeScrobble() {
        val track = player.currentMediaItem?.let { PlaybackSnapshot.decode(it.mediaMetadata.extras?.getString(PlaybackSnapshot.KEY))?.track }
        com.musicvault.app.scrobble.ListenBrainz.observe(this, track, player.isPlaying, player.currentPosition.coerceAtLeast(0L))
    }

    private fun searchLocal(query: String): List<Track> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return localCatalog.filter { track -> words.all { word ->
            (track.title + " " + track.artistName + " " + track.albumTitle).contains(word, ignoreCase = true)
        } }
    }

    private fun libraryRoot(): MediaItem = MediaItem.Builder().setMediaId("musicvault.local")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Música local").setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    private fun restoreLocalQueue() {
        val state = PlaybackQueueState.decode(settings.getString(PlaybackQueueState.KEY, null)) ?: return
        // A mixed/remote queue needs the application's resolver; never reuse expired URLs.
        if (!state.tracks.all { PlaybackItems.isReadableLocal(this, it) }) return
        val items = state.tracks.mapNotNull { PlaybackItems.build(this, it) }
        if (items.size != state.tracks.size) return
        player.setMediaItems(items, state.index, state.positionMs)
        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(state.shuffleOrder.toIntArray(), 0L))
        player.shuffleModeEnabled = state.shuffle
        PlaybackOptions.restoreRepeat(state.repeatMode)
        player.prepare()
        player.pause()
    }

    private fun savePlaybackSnapshot() {
        if (player.mediaItemCount == 0) {
            if (!queueInstalled) return
            settings.edit().remove(PlaybackQueueState.KEY).remove(PlaybackSnapshot.KEY).apply()
            return
        }
        queueInstalled = true
        val tracks = (0 until player.mediaItemCount).map { index ->
            PlaybackSnapshot.decode(player.getMediaItemAt(index).mediaMetadata.extras?.getString(PlaybackSnapshot.KEY))?.track ?: return
        }
        val order = mutableListOf<Int>()
        val timeline = player.currentTimeline
        var next = timeline.getFirstWindowIndex(true)
        while (next != C.INDEX_UNSET && next !in order && order.size < tracks.size) {
            order += next
            next = timeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
        }
        QueueRestoration.state?.let { restoring ->
            val savedIndex = restoring.tracks.indexOfFirst { it.id == player.currentMediaItem?.mediaId }.takeIf { it >= 0 } ?: restoring.index
            val saved = restoring.copy(index = savedIndex, positionMs = player.currentPosition.coerceAtLeast(0L))
            settings.edit().putString(PlaybackQueueState.KEY, saved.encode())
                .putString(PlaybackSnapshot.KEY, PlaybackSnapshot(saved.tracks[saved.index], saved.positionMs).encode()).apply()
            return
        }
        val index = player.currentMediaItemIndex.coerceIn(tracks.indices)
        val position = player.currentPosition.coerceAtLeast(0L)
        settings.edit().putString(PlaybackQueueState.KEY, PlaybackQueueState(tracks, index, position,
            player.shuffleModeEnabled, order, PlaybackOptions.policy.repeatMode).encode())
            .putString(PlaybackSnapshot.KEY, PlaybackSnapshot(tracks[index], position).encode()).apply()
    }

    private fun applyAudioOptions(target: ExoPlayer) {
        target.setPlaybackSpeed(settings.getFloat("playback_speed", 1f).coerceIn(0.5f, 2f))
        target.skipSilenceEnabled = settings.getBoolean("skip_silence", false)
    }
    private fun normalizedGain(target: ExoPlayer): Float = if (settings.getBoolean("normalize_volume", false))
        replayGains[target.currentMediaItem?.mediaId] ?: normalizedGains[target.currentMediaItem?.mediaId] ?: 1f else 1f
    private fun applyNormalization(target: ExoPlayer) {
        loudnessJob?.cancel()
        if (crossfadeJob?.isActive != true) target.volume = normalizedGain(target)
        if (!settings.getBoolean("normalize_volume", false)) return
        val item = target.currentMediaItem ?: return
        val reference = item.localConfiguration?.uri?.toString() ?: return
        if (replayGains.containsKey(item.mediaId) || normalizedGains.containsKey(item.mediaId)) return
        loudnessJob = serviceScope.launch {
            val gain = withContext(Dispatchers.IO) { LocalLoudness.gain(this@MusicPlaybackService, reference) } ?: return@launch
            normalizedGains[item.mediaId] = gain
            if (target === player && target.currentMediaItem?.mediaId == item.mediaId && crossfadeJob?.isActive != true) target.volume = normalizedGain(target)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackSnapshot()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildPlayer(requestAudioFocus: Boolean = true): ExoPlayer {
        // Remote music can start safely with a much smaller startup buffer than
        // Media3's general-purpose streaming defaults. Keep local/offline files
        // on Media3's own local-playback defaults and tune streaming only.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                10_000, // minBufferMs
                30_000, // maxBufferMs
                500,    // bufferForPlaybackMs
                1_500   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .build()
        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(StreamingCache.dataSourceFactory(this)))
            .setAudioAttributes(mediaAudioAttributes, requestAudioFocus)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .build().also(::applyAudioOptions)
    }

    private fun scheduleSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (PlaybackOptions.policy.timerDeadlineMs == 0L) return
        sleepTimerJob = serviceScope.launch {
            while (isActive) {
                if (PlaybackOptions.policy.consumeTimerExpiry(SystemClock.elapsedRealtime())) {
                    // Pausing the session player also makes an active fade abort.
                    player.pause()
                    fadingPlayer?.pause()
                    PlaybackOptions.updateRemaining()
                    break
                }
                PlaybackOptions.updateRemaining()
                delay(PlaybackOptions.policy.remainingMs(SystemClock.elapsedRealtime()).coerceIn(1L, 1000L))
            }
        }
    }

    private fun startCrossfadeMonitor() {
        if (crossfadeMonitorJob?.isActive == true) return
        crossfadeMonitorJob = serviceScope.launch {
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
                if (crossfadeSeconds > 0 && player.repeatMode != Player.REPEAT_MODE_ONE && player.hasNextMediaItem() && player.duration > 0L) {
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

    private fun updateCrossfadeMonitor() {
        if (settings.getInt("crossfade_seconds", 5).coerceIn(0, 12) == 0) {
            crossfadeJob?.cancel()
            crossfadeJob = null
            fadingPlayer?.let { abortCrossfade(player, it) }
            crossfadeMonitorJob?.cancel()
            crossfadeMonitorJob = null
        } else {
            startCrossfadeMonitor()
        }
    }

    private fun maybeCrossfade() {
        val seconds = settings.getInt("crossfade_seconds", 5).coerceIn(0, 12)
        if (seconds <= 0 || crossfadeJob?.isActive == true) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
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
        fadingPlayer = next
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
            fadingPlayer = null
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
            current.volume = (1f - t) * normalizedGain(current)
            next.volume = t * normalizedGain(next)
            delay(40L)
        }
        if (currentCoroutineContext().isActive) {
            current.volume = 0f
            next.volume = normalizedGain(next)
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
            applyNormalization(next)
            savePlaybackSnapshot()
            // Restore the user's shuffle setting on the session player so the
            // app state and future hand-offs stay consistent.
            next.shuffleModeEnabled = wasShuffled
        } else {
            next.stop()
            next.release()
        }
        fadingPlayer = null
        crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
    }

    /** Cancels a fade in progress and hands control back to [current]. */
    private fun abortCrossfade(current: ExoPlayer, next: ExoPlayer) {
        next.stop()
        next.release()
        fadingPlayer = null
        // Restore full volume: unpausing must never come back silent, and a
        // newly picked song must not inherit the ramped-down volume of the
        // aborted fade.
        current.volume = normalizedGain(current)
        crossfadeTargetIndex = androidx.media3.common.C.INDEX_UNSET
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        savePlaybackSnapshot()
        com.musicvault.app.scrobble.ListenBrainz.observe(this, PlaybackRouteState.track.value, false, player.currentPosition.coerceAtLeast(0L))
        snapshotJob?.cancel()
        loudnessJob?.cancel()
        PlaybackOptions.reset()
        sleepTimerJob?.cancel()
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        crossfadeMonitorJob?.cancel()
        crossfadeJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        mediaSession?.release()
        mediaSession = null
        fadingPlayer?.release()
        fadingPlayer = null
        player.release()
        AudioSessionBridge.sessionId = 0
        PlaybackRouteState.current.value = null
        super.onDestroy()
    }
}
