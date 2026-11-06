package com.musicvault.app.jams

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.musicvault.app.data.Track
import com.musicvault.app.player.PlayerController
import com.musicvault.app.providers.NewPipeStreamResolver
import com.musicvault.app.providers.Providers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared transport, with immediate local intent and a readiness barrier for new songs. */
internal class JamsController(
    private val context: Context, private val scope: CoroutineScope, private val player: PlayerController,
    private val online: () -> Boolean, private val onEnter: () -> Unit, private val onTrack: (Track) -> Unit,
    private val onPreparing: (Track?) -> Unit, private val onLeave: () -> Unit
) {
    private val mutable = MutableStateFlow(JamState(InstallationIdentity.get(context)))
    val state = mutable.asStateFlow()
    private val feedback = MutableStateFlow<Map<String, JamTrackFeedback>>(emptyMap())
    val trackFeedback = feedback.asStateFlow()
    val active get() = mutable.value.active
    private val authMutex = Mutex()
    private val clock = JamServerClock()
    private val pending = JamPendingPlayback()
    private val random = SecureRandom()
    private var database: FirebaseDatabase? = null
    private var room: DatabaseReference? = null
    private var roomListener: ValueEventListener? = null
    private val globalListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var identityJob: Job? = null
    private var syncJob: Job? = null
    private var preparation: Job? = null
    private var selectionJob: Job? = null
    private var loadedSong: String? = null
    private var loadedTrack: Track? = null
    private var preparingKey: String? = null
    private var appliedPlayback: JamPlayback? = null
    private var preparationToken = 0L
    private var selectionToken = 0L
    private var currentRoomGeneration = 0L
    private var repeatBeforeJam = 0
    private var readyPublishedKey: String? = null
    private var lastSeekElapsed = -10_000L
    private var lastAppliedCommand = ""
    private var autoStartPending = false
    private data class Request(val plan: JamPlayback, val generation: Long, val expectedRevision: Long? = null, val optimistic: Boolean = true)
    private val commands = Channel<Request>(Channel.UNLIMITED)
    private val commandJob = scope.launch {
        for (request in commands) {
            try {
                if (request.generation == currentRoomGeneration && active) withTimeout(8_000) { publish(request) }
                else fail(request)
            } catch (_: TimeoutCancellationException) { fail(request); message("JAMS: no se pudo enviar el control. Reintenta.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(request); message("JAMS: no se pudo sincronizar el control. Reintenta.") }
            finally { if (!request.optimistic) autoStartPending = false }
        }
    }
    private fun serverNow() = if (clock.ready) clock.now(SystemClock.elapsedRealtime()) else System.currentTimeMillis()
    fun connectIdentity() {
        if (!online() || identityJob?.isActive == true || mutable.value.uid != null) return
        identityJob = scope.launch {
            try { withTimeout(30_000) { authenticate() } }
            catch (_: TimeoutCancellationException) { message("No se pudo conectar JAMS. Reintenta.") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("No se pudo conectar JAMS. Comprueba la conexión y reintenta.") }
        }
    }
    internal suspend fun authenticatedDatabase() = authenticate()
    private suspend fun authenticate(): FirebaseDatabase = authMutex.withLock {
        check(online()) { "JAMS necesita conexión a Internet." }
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context) ?: error("JAMS aún no tiene configurado Firebase en esta versión.")
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: error("No se obtuvo el UID anónimo.")
        mutable.value = mutable.value.copy(uid = user.uid)
        if (database == null) {
            val db = FirebaseDatabase.getInstance(app)
            database = db
            listen(db.getReference(".info/serverTimeOffset")) { snapshot ->
                val offset = (snapshot.value as? Number)?.toLong() ?: return@listen
                clock.anchor(System.currentTimeMillis() + offset, SystemClock.elapsedRealtime())
            }
            listen(db.getReference(".info/connected")) { snapshot ->
                val connected = snapshot.getValue(Boolean::class.java) == true
                mutable.value = mutable.value.copy(connected = connected)
                if (!connected && active) { player.pause(); readyPublishedKey = null }
                if (connected) room?.let { registerPresence(it, user.uid) }
            }
            db.getReference("installations/${user.uid}").setValue(mapOf("installationUid" to mutable.value.installationUid)).await()
        }
        database!!
    }
    private fun listen(ref: DatabaseReference, apply: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = apply(snapshot)
            override fun onCancelled(error: DatabaseError) { message("Se perdió la conexión con JAMS.") }
        }
        globalListeners += ref to listener; ref.addValueEventListener(listener)
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch {
            try { withTimeout(30_000) { block() } }
            catch (_: TimeoutCancellationException) { message("JAMS tardó demasiado en responder. Reintenta.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message?.takeIf { it.startsWith("JAMS") } ?: "No se pudo completar la acción de JAMS.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun createRoom() = operation {
        check(!active) { "JAMS ya está en una sala." }
        val db = authenticate()
        while (!clock.ready) delay(25)
        val uid = mutable.value.uid!!
        repeat(8) {
            val code = UUID.randomUUID().toString().replace("-", "")
            val short = random.nextInt(1_000_000).toString().padStart(6, '0')
            val expiry = serverNow() + 86_340_000L
            val ref = db.getReference("rooms/$code")
            ref.setValue(mapOf("meta" to mapOf("hostUid" to uid, "expiresAt" to expiry, "shortCode" to short, "protocolVersion" to 2),
                "members" to mapOf(uid to true), "playback" to mapOf("songKey" to "", "playing" to false,
                    "positionMs" to 0, "effectiveAt" to serverNow(), "revision" to 0))).await()
            try {
                db.getReference("roomCodes/$short").setValue(mapOf("roomId" to code, "ownerUid" to uid, "expiresAt" to expiry)).await()
                enter(ref, code, uid)
                return@operation
            } catch (e: DatabaseException) { ref.removeValue().await() }
        }
        error("JAMS: no se pudo reservar el código. Reintenta.")
    }
    fun joinRoom(invitation: String) = operation {
        check(!active) { "JAMS ya está en una sala." }
        val db = authenticate()
        val uid = mutable.value.uid!!
        val raw = invitation.trim().replace(" ", "")
        val code = if (Regex("[0-9]{6}").matches(raw)) {
            try { db.getReference("roomLookups/$uid").setValue(mapOf("code" to raw, "requestedAt" to ServerValue.TIMESTAMP)).await() }
            catch (_: DatabaseException) { error("JAMS: espera un momento antes de probar otro código.") }
            val value = try { db.getReference("roomCodes/$raw").get().await() }
                catch (_: DatabaseException) { error("JAMS: ese código ya no está disponible.") }
            value.child("roomId").getValue(String::class.java) ?: error("JAMS: ese código no está disponible.")
        } else JamSync.roomCode(invitation) ?: error("JAMS: el enlace o código no es válido.")
        val ref = db.getReference("rooms/$code")
        ref.child("members/$uid").setValue(true).await()
        enter(ref, code, uid)
    }
    private suspend fun enter(ref: DatabaseReference, code: String, uid: String) {
        val snapshot = ref.get().await()
        val host = snapshot.child("meta/hostUid").getValue(String::class.java) ?: error("JAMS: la sala ya no existe.")
        onEnter(); pending.reset()
        repeatBeforeJam = player.repeatMode.value
        repeat(3) { if (player.repeatMode.value != 0) player.cycleRepeatMode() }
        loadedSong = null; room = ref; currentRoomGeneration++
        JamTransportBridge.listener = { command -> when (command) {
            JamTransportBridge.Command.Toggle -> toggle()
            is JamTransportBridge.Command.Play -> setPlaying(command.playing)
            JamTransportBridge.Command.Next -> next()
            JamTransportBridge.Command.Previous -> previous()
            is JamTransportBridge.Command.Seek -> seek(command.positionMs)
        } }
        mutable.value = mutable.value.copy(roomCode = code, shortCode = snapshot.child("meta/shortCode").getValue(String::class.java),
            hostUid = host, songs = emptyList(), members = listOf(uid), memberCount = 1, message = null)
        registerPresence(ref, uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (room != ref) return
                if (!snapshot.exists()) { detach(); message("La sala JAMS se cerró."); return }
                val songs = snapshot.child("queue").children.mapNotNull { child ->
                    val video = child.child("videoId").getValue(String::class.java) ?: return@mapNotNull null
                    JamSong(child.key!!, video, child.child("title").getValue(String::class.java).orEmpty(), child.child("artist").getValue(String::class.java).orEmpty(),
                        child.child("durationMs").getValue(Long::class.java) ?: 0, child.child("sourceTrackId").getValue(String::class.java))
                }.sortedBy { it.key }
                val members = snapshot.child("members").children.filter { it.getValue(Boolean::class.java) == true }.mapNotNull { it.key }.sorted()
                val ready = snapshot.child("ready").children.mapNotNull { child -> child.getValue(String::class.java)?.let { child.key!! to it } }.toMap()
                mutable.value = mutable.value.copy(songs = songs, playback = pending.receive(parsePlayback(snapshot.child("playback"))),
                    leaderUid = members.firstOrNull(), hostOnline = host in members, members = members, memberCount = members.size, readySongs = ready)
                JamTransportBridge.updateNavigation(mutable.value.canPrevious, mutable.value.canNext)
                if (songs.isEmpty()) { preparation?.cancel(); player.pause(); player.clearQueue(); onPreparing(null) }
                prepareCurrent(); alignPlayback()
            }
            override fun onCancelled(error: DatabaseError) { if (room == ref) { detach(); message("La sala JAMS ya no está disponible.") } }
        }
        roomListener = listener; ref.addValueEventListener(listener)
        syncJob?.cancel()
        syncJob = scope.launch { while (isActive && room == ref) { alignPlayback(); delay(100) } }
    }
    private fun registerPresence(ref: DatabaseReference, uid: String) {
        readyPublishedKey = null
        ref.child("ready/$uid").onDisconnect().removeValue().addOnSuccessListener {
            ref.child("ready/$uid").removeValue().addOnSuccessListener {
                ref.child("members/$uid").onDisconnect().setValue(false).addOnSuccessListener { if (room == ref) ref.child("members/$uid").setValue(true) }
            }
        }
    }
    private fun parsePlayback(p: DataSnapshot) = JamPlayback(p.child("songKey").getValue(String::class.java).orEmpty(),
        p.child("playing").getValue(Boolean::class.java) == true, p.child("positionMs").getValue(Long::class.java) ?: 0,
        p.child("effectiveAt").getValue(Long::class.java) ?: 0, p.child("revision").getValue(Long::class.java) ?: 0,
        p.child("buffering").getValue(Boolean::class.java) == true, p.child("commandId").getValue(String::class.java).orEmpty(), p.child("action").getValue(String::class.java).orEmpty())
    private fun prepareCurrent() {
        val song = mutable.value.currentSong
        if (song == null) { preparation?.cancel(); loadedSong = null; loadedTrack = null; player.pause(); onPreparing(null); return }
        if (loadedSong == song.key || preparation?.isActive == true && preparingKey == song.key) return
        preparation?.cancel(); loadedSong = null; readyPublishedKey = null
        mutable.value.uid?.let { room?.child("ready/$it")?.removeValue() }
        mutable.value = mutable.value.copy(audioError = false)
        preparingKey = song.key
        val token = ++preparationToken
        appliedPlayback = null; player.pause(); onPreparing(song.track())
        val generation = currentRoomGeneration
        preparation = scope.launch {
            try {
                val stream = withTimeout(25_000) { Providers.resolveStream(song.title, song.artist, "https://www.youtube.com/watch?v=${song.videoId}", "jam:${song.videoId}") }
                    ?: error("JAMS: no se pudo reproducir esta canción en este teléfono.")
                ensureActive()
                if (generation != currentRoomGeneration || mutable.value.playback.songKey != song.key) return@launch
                val track = song.track().copy(audioUri = stream.url)
                player.preparePaused(track, JamSync.targetPosition(mutable.value.playback, serverNow(), song.durationMs))
                loadedTrack = track; onTrack(track); loadedSong = song.key; lastAppliedCommand = ""; lastSeekElapsed = -10_000
                alignPlayback()
            } catch (_: TimeoutCancellationException) { playbackFailed() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { playbackFailed() }
            finally { if (token == preparationToken) preparingKey = null }
        }
    }
    private fun alignPlayback() {
        val s = mutable.value
        val song = s.currentSong ?: return
        if (!s.connected || !clock.ready) { if (player.isPlaying.value) player.pause(); return }
        val progress = player.liveProgress()
        val ready = loadedSong == song.key && progress.trackId == song.track().id && player.playbackState.value == Player.STATE_READY
        if (ready && readyPublishedKey != song.key) {
            readyPublishedKey = song.key
            room?.child("ready/${s.uid}")?.setValue(song.key)?.addOnFailureListener { readyPublishedKey = null }
        }
        if (s.playback.buffering) {
            if (player.isPlaying.value) player.pause()
            if (s.isLeader && !autoStartPending && (s.allReady || serverNow() - s.playback.effectiveAt > 12_000)) {
                val anyReady = s.members.any { s.readySongs[it] == song.key }
                autoStartPending = true
                enqueue(s.playback.copy(playing = s.playback.playing && anyReady, buffering = false,
                    effectiveAt = serverNow() + 180, action = "ready"), s.playback.revision, optimistic = false)
            }
            return
        }
        if (!ready) return
        if (serverNow() >= s.playback.effectiveAt) appliedPlayback = s.playback
        val timeline = appliedPlayback?.takeIf { it.songKey == song.key && !it.buffering } ?: return
        val duration = progress.durationMs.takeIf { it > 0 } ?: song.durationMs
        val target = JamSync.targetPosition(timeline, serverNow(), duration)
        val elapsed = SystemClock.elapsedRealtime()
        val newCommand = timeline.commandId.isNotEmpty() && timeline.commandId != lastAppliedCommand
        val correction = JamDriftPolicy.correction(progress.positionMs, target, ready, timeline.playing,
            elapsed - lastSeekElapsed, forceSeek = newCommand && timeline.action == "seek")
        when (correction) {
            is JamDriftPolicy.Correction.Seek -> { player.seekTo(correction.positionMs); lastSeekElapsed = elapsed; player.setSyncSpeed(1f) }
            is JamDriftPolicy.Correction.Speed -> player.setSyncSpeed(correction.value)
            JamDriftPolicy.Correction.None -> {}
        }
        lastAppliedCommand = timeline.commandId
        if (!timeline.playing) { if (player.isPlaying.value) player.pause(); onPreparing(null) }
        else if (!player.isPlaying.value && (duration <= 0 || target < duration)) player.resume()
    }
    private fun status(id: String, value: JamTrackStatus): JamTrackFeedback {
        val entry = JamTrackFeedback(value, System.nanoTime())
        feedback.value = feedback.value + (id to entry)
        if (value != JamTrackStatus.LOADING) scope.launch { delay(2200); if (feedback.value[id] == entry) feedback.value = feedback.value - id }
        return entry
    }
    fun addTrack(track: Track) { queueTrack(track, false) }
    fun addAndPlay(track: Track) { queueTrack(track, true) }
    private fun queueTrack(track: Track, playNow: Boolean) {
        if (!active) return
        if (playNow) {
            selectionJob?.cancel(); selectionToken++
            if (mutable.value.playback.playing) enqueue(mutable.value.playback.copy(playing = false, buffering = false,
                positionMs = player.liveProgress().positionMs, effectiveAt = serverNow(), action = "pause"))
            onPreparing(track)
            mutable.value.songs.firstOrNull { it.sourceTrackId == track.id }?.let { status(track.id, JamTrackStatus.ADDED); playSong(it.key); return }
        }
        val loading = status(track.id, JamTrackStatus.LOADING)
        val generation = currentRoomGeneration
        val selection = selectionToken
        val ref = room ?: return
        val job = scope.launch {
            try {
                withTimeout(25_000) {
                    check(mutable.value.connected && online()) { "JAMS necesita conexión a Internet." }
                    check(mutable.value.songs.size < 100) { "JAMS: la cola admite hasta 100 canciones." }
                    val video = track.sourceId?.takeIf { track.source.equals("youtube", true) && Regex("[A-Za-z0-9_-]{11}").matches(it) }
                        ?: track.url?.let { Regex("[?&]v=([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }
                        ?: NewPipeStreamResolver.findYoutubeMatch(track.title, track.artistName)?.let { Regex("[?&]v=([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }
                        ?: error("JAMS: no se encontró una versión de esta canción.")
                    ensureActive()
                    if (generation != currentRoomGeneration || room != ref) return@withTimeout
                    val songRef = ref.child("queue").push()
                    songRef.setValue(mapOf("videoId" to video, "title" to track.title.take(300), "artist" to track.artistName.take(300),
                        "durationMs" to track.durationMs.coerceIn(0, 86_400_000), "addedBy" to mutable.value.uid, "sourceTrackId" to track.id.take(256))).await()
                    if (generation != currentRoomGeneration) return@withTimeout
                    if (mutable.value.songs.none { it.key == songRef.key }) mutable.value = mutable.value.copy(songs = mutable.value.songs +
                        JamSong(songRef.key!!, video, track.title, track.artistName, track.durationMs, track.id))
                    status(track.id, JamTrackStatus.ADDED)
                    if (playNow && selection == selectionToken) selectSong(songRef.key!!, cancelLookup = false)
                }
            } catch (_: TimeoutCancellationException) { status(track.id, JamTrackStatus.FAILED); if (playNow) onPreparing(null); message("JAMS: no se pudo preparar la canción a tiempo.") }
            catch (e: CancellationException) { if (feedback.value[track.id] == loading) feedback.value = feedback.value - track.id; throw e }
            catch (e: Exception) { status(track.id, JamTrackStatus.FAILED); if (playNow) onPreparing(null); message(e.message?.takeIf { it.startsWith("JAMS") } ?: "No se pudo añadir la canción.") }
        }
        if (playNow) selectionJob = job
    }
    fun playSong(key: String) = selectSong(key, cancelLookup = true)
    private fun selectSong(key: String, cancelLookup: Boolean) {
        if (cancelLookup) { selectionJob?.cancel(); selectionToken++ }
        val s = mutable.value
        if (s.songs.none { it.key == key }) return
        if (loadedSong == key) loadedTrack?.let(onPreparing)
        enqueue(JamPlayback(key, true, 0, serverNow(), s.playback.revision + 1, buffering = true, action = "select"))
    }
    fun toggle() = setPlaying(!mutable.value.playback.playing)
    private fun setPlaying(playing: Boolean) {
        if (!playing) { selectionJob?.cancel(); selectionToken++ }
        val s = mutable.value
        val key = s.playback.songKey.ifEmpty { s.songs.firstOrNull()?.key ?: run { message("Añade una canción a la sala."); return } }
        val position = if (loadedSong == key && player.playbackState.value == Player.STATE_READY) player.liveProgress().positionMs else JamSync.targetPosition(s.playback, serverNow())
        enqueue(s.playback.copy(songKey = key, playing = playing, positionMs = position, effectiveAt = serverNow(),
            buffering = playing && (!s.allReady || loadedSong != key), action = if (playing) "play" else "pause"))
        if (playing && s.audioError) retryAudio()
    }
    fun seek(positionMs: Long) {
        val s = mutable.value
        if (s.playback.songKey.isEmpty()) return
        enqueue(s.playback.copy(positionMs = positionMs.coerceAtLeast(0), effectiveAt = serverNow(), action = "seek"))
    }
    fun next() {
        val s = mutable.value
        val next = s.songs.getOrNull(s.songs.indexOfFirst { it.key == s.playback.songKey } + 1)
        if (next != null) playSong(next.key) else setPlaying(false)
    }
    fun previous() {
        val s = mutable.value
        s.songs.getOrNull((s.songs.indexOfFirst { it.key == s.playback.songKey } - 1).coerceAtLeast(0))?.let { playSong(it.key) }
    }
    fun ended(trackId: String?) {
        val s = mutable.value
        val song = s.currentSong ?: return
        if (!s.isLeader || song.track().id != trackId || s.playback.buffering || !s.playback.playing) return
        val next = s.songs.getOrNull(s.songs.indexOf(song) + 1)
        enqueue(JamPlayback(next?.key ?: song.key, next != null, if (next == null) player.liveProgress().positionMs else 0,
            serverNow(), s.playback.revision + 1, buffering = next != null, action = "select"), s.playback.revision)
    }
    private fun enqueue(plan: JamPlayback, expectedRevision: Long? = null, optimistic: Boolean = true) {
        val s = mutable.value
        if (!s.active || !s.connected || !online() || !clock.ready) { autoStartPending = false; message("JAMS necesita conexión para sincronizar."); return }
        val command = plan.copy(commandId = UUID.randomUUID().toString(), revision = s.playback.revision + 1)
        if (optimistic) {
            pending.stage(command); mutable.value = mutable.value.copy(playback = pending.current)
            if (!command.playing) { player.pause(); onPreparing(null) }
            prepareCurrent(); alignPlayback()
        }
        commands.trySend(Request(command, currentRoomGeneration, expectedRevision, optimistic))
    }
    private fun receive(playback: JamPlayback) {
        mutable.value = mutable.value.copy(playback = pending.receive(playback))
        prepareCurrent(); alignPlayback()
    }
    private fun fail(request: Request) {
        if (request.generation != currentRoomGeneration) return
        mutable.value = mutable.value.copy(playback = pending.fail(request.plan.commandId))
        prepareCurrent(); alignPlayback()
    }
    private suspend fun publish(request: Request) {
        val ref = room ?: return
        check(mutable.value.connected && online())
        suspendCancellableCoroutine<Unit> { continuation ->
            ref.child("playback").runTransaction(object : Transaction.Handler {
                override fun doTransaction(current: MutableData): Transaction.Result {
                    val revision = current.child("revision").getValue(Long::class.java) ?: 0
                    if (request.expectedRevision != null && revision != request.expectedRevision) return Transaction.abort()
                    val plan = request.plan
                    current.value = mapOf("songKey" to plan.songKey, "playing" to plan.playing, "positionMs" to plan.positionMs,
                        "effectiveAt" to plan.effectiveAt, "revision" to revision + 1, "buffering" to plan.buffering,
                        "commandId" to plan.commandId, "action" to plan.action)
                    return Transaction.success(current)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (!continuation.isActive) return
                    if (error != null) continuation.resumeWithException(error.toException())
                    else { if (committed && snapshot != null && request.generation == currentRoomGeneration) receive(parsePlayback(snapshot)); else fail(request); continuation.resume(Unit) }
                }
            }, false)
        }
    }
    fun removeSong(key: String) = operation {
        val s = mutable.value
        check(s.active && s.connected && s.uid in s.members) { "JAMS necesita conexión." }
        val ref = room ?: return@operation
        if (key == s.playback.songKey) {
            val next = s.songAfterRemoval(key)
            ref.updateChildren(mapOf("queue/$key" to null, "playback" to mapOf(
                "songKey" to (next?.key ?: ""), "playing" to (next != null && s.playback.playing),
                "positionMs" to 0, "effectiveAt" to serverNow(), "revision" to s.playback.revision + 1,
                "buffering" to (next != null), "commandId" to UUID.randomUUID().toString(), "action" to "select"
            ))).await()
        } else ref.child("queue/$key").removeValue().await()
    }
    fun playbackFailed() {
        preparation?.cancel(); loadedSong = null; readyPublishedKey = null; player.pause(); onPreparing(null)
        mutable.value.uid?.let { room?.child("ready/$it")?.removeValue() }
        mutable.value = mutable.value.copy(audioError = true)
        message("No se pudo reproducir el audio en este teléfono. Reintenta el audio de JAMS.")
    }
    fun retryAudio() { preparation?.cancel(); loadedSong = null; preparingKey = null; prepareCurrent() }
    fun leave() = operation {
        val ref = room ?: return@operation
        val s = mutable.value
        ref.child("members/${s.uid}").onDisconnect().cancel().await()
        ref.child("ready/${s.uid}").onDisconnect().cancel().await()
        if (s.isHost) {
            s.shortCode?.let { database?.getReference("roomCodes/$it")?.removeValue()?.await() }
            ref.removeValue().await()
        } else ref.child("members/${s.uid}").removeValue().await()
        detach()
    }
    private fun detach() {
        val old = room ?: return
        room = null; currentRoomGeneration++; JamTransportBridge.listener = null
        roomListener?.let { old.removeEventListener(it) }; roomListener = null
        preparation?.cancel(); selectionJob?.cancel(); syncJob?.cancel(); loadedSong = null; loadedTrack = null; preparingKey = null
        pending.reset(); feedback.value = emptyMap(); readyPublishedKey = null; appliedPlayback = null; autoStartPending = false
        player.pause(); player.clearQueue(); onPreparing(null)
        repeat(3) { if (player.repeatMode.value != repeatBeforeJam) player.cycleRepeatMode() }
        mutable.value = JamState(mutable.value.installationUid, uid = mutable.value.uid, connected = mutable.value.connected, busy = mutable.value.busy)
        onLeave()
    }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    private fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
    fun close() {
        if (active) player.pause()
        JamTransportBridge.listener = null
        room?.let { ref -> mutable.value.uid?.let { ref.child("members/$it").setValue(false); ref.child("ready/$it").removeValue() } }
        commandJob.cancel(); commands.close(); preparation?.cancel(); selectionJob?.cancel(); syncJob?.cancel()
        roomListener?.let { listener -> room?.removeEventListener(listener) }
        globalListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }; globalListeners.clear()
    }
}
