package com.musicvault.app.jams

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.musicvault.app.data.Track
import com.musicvault.app.player.PlayerController
import com.musicvault.app.providers.NewPipeStreamResolver
import com.musicvault.app.providers.Providers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Room transport only carries video identity and a server-clock playback timeline, never audio/files. */
internal class JamsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: PlayerController,
    private val online: () -> Boolean,
    private val onEnter: () -> Unit,
    private val onTrack: (Track) -> Unit,
    private val onLeave: () -> Unit
) {
    private val mutable = MutableStateFlow(JamState(InstallationIdentity.get(context)))
    val state = mutable.asStateFlow()
    val active get() = mutable.value.active
    private var database: FirebaseDatabase? = null
    private var room: DatabaseReference? = null
    private var roomListener: ValueEventListener? = null
    private val globalListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var offsetMs = 0L
    private var clockReady = false
    private var identityJob: Job? = null
    private var syncJob: Job? = null
    private var preparation: Job? = null
    private var loadedSong: String? = null
    private var appliedPlayback: JamPlayback? = null
    private var preparationToken = 0L
    private var currentRoomGeneration = 0L
    private var repeatBeforeJam = 0

    private fun serverNow() = System.currentTimeMillis() + offsetMs
    fun connectIdentity() {
        if (!online() || identityJob?.isActive == true || mutable.value.uid != null) return
        identityJob = scope.launch {
            try { authenticate() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("No se pudo conectar JAMS. Comprueba la conexión y reintenta.") }
        }
    }
    private suspend fun authenticate(): FirebaseDatabase {
        check(online()) { "JAMS necesita conexión a Internet." }
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context)
            ?: error("JAMS aún no tiene configurado Firebase en esta versión.")
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: error("No se obtuvo el UID anónimo.")
        mutable.value = mutable.value.copy(uid = user.uid)
        if (database == null) {
            val db = FirebaseDatabase.getInstance(app)
            database = db
            listen(db.getReference(".info/serverTimeOffset")) { value ->
                offsetMs = (value.value as? Number)?.toLong() ?: 0
                clockReady = value.exists()
            }
            listen(db.getReference(".info/connected")) { value ->
                val connected = value.getValue(Boolean::class.java) == true
                mutable.value = mutable.value.copy(connected = connected)
                if (!connected && active) player.pause()
                if (connected) room?.let { registerPresence(it, user.uid) }
            }
            db.getReference("installations/${user.uid}").setValue(mapOf("installationUid" to mutable.value.installationUid)).await()
        }
        return database!!
    }
    private fun listen(ref: DatabaseReference, apply: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = apply(snapshot)
            override fun onCancelled(error: DatabaseError) { message("Se perdió la conexión con JAMS.") }
        }
        globalListeners += ref to listener
        ref.addValueEventListener(listener)
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch {
            try { withTimeout(30_000) { block() } }
            catch (e: TimeoutCancellationException) { message("JAMS tardó demasiado en responder. Reintenta.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("MusicVaultJams", "Room operation failed", e)
                message(e.message?.takeIf { it.startsWith("JAMS") } ?: "No se pudo completar la acción de JAMS. Reintenta.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun createRoom() = operation {
        check(!active) { "JAMS ya está en una sala." }
        val db = authenticate()
        val uid = mutable.value.uid!!
        val code = UUID.randomUUID().toString().replace("-", "")
        val ref = db.getReference("rooms/$code")
        ref.setValue(mapOf("meta" to mapOf("hostUid" to uid, "expiresAt" to serverNow() + 24 * 60 * 60_000L),
            "members" to mapOf(uid to true),
            "playback" to mapOf("songKey" to "", "playing" to false, "positionMs" to 0,
                "effectiveAt" to serverNow(), "revision" to 0))).await()
        enter(ref, code, uid)
    }
    fun joinRoom(invitation: String) = operation {
        check(!active) { "JAMS ya está en una sala." }
        val code = JamSync.roomCode(invitation) ?: error("JAMS: el código de invitación no es válido.")
        val db = authenticate()
        val uid = mutable.value.uid!!
        val ref = db.getReference("rooms/$code")
        ref.child("members/$uid").setValue(true).await()
        enter(ref, code, uid)
    }
    private suspend fun enter(ref: DatabaseReference, code: String, uid: String) {
        // Membership grants the read; codes are random 128-bit invitations, never listable.
        val snapshot = ref.get().await()
        val host = snapshot.child("meta/hostUid").getValue(String::class.java) ?: error("JAMS: la sala ya no existe.")
        onEnter()
        repeatBeforeJam = player.repeatMode.value
        repeat(3) { if (player.repeatMode.value != 0) player.cycleRepeatMode() }
        loadedSong = null
        room = ref
        JamTransportBridge.listener = { command ->
            when (command) {
                JamTransportBridge.Command.Toggle -> toggle()
                is JamTransportBridge.Command.Play -> setPlaying(command.playing)
                JamTransportBridge.Command.Next -> next()
                JamTransportBridge.Command.Previous -> previous()
                is JamTransportBridge.Command.Seek -> seek(command.positionMs)
            }
        }
        currentRoomGeneration++
        mutable.value = mutable.value.copy(roomCode = code, hostUid = host, songs = emptyList(), message = null)
        registerPresence(ref, uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (room != ref) return
                if (!snapshot.exists()) { detach(); message("La sala JAMS se cerró."); return }
                val songs = snapshot.child("queue").children.mapNotNull { child ->
                    val video = child.child("videoId").getValue(String::class.java) ?: return@mapNotNull null
                    JamSong(child.key!!, video, child.child("title").getValue(String::class.java).orEmpty(),
                        child.child("artist").getValue(String::class.java).orEmpty(), child.child("durationMs").getValue(Long::class.java) ?: 0)
                }.sortedBy { it.key }
                val p = snapshot.child("playback")
                val playback = JamPlayback(p.child("songKey").getValue(String::class.java).orEmpty(),
                    p.child("playing").getValue(Boolean::class.java) == true, p.child("positionMs").getValue(Long::class.java) ?: 0,
                    p.child("effectiveAt").getValue(Long::class.java) ?: 0, p.child("revision").getValue(Long::class.java) ?: 0)
                val hostOnline = snapshot.child("members/$host").getValue(Boolean::class.java) == true
                val onlineMembers = snapshot.child("members").children.filter { it.getValue(Boolean::class.java) == true }.mapNotNull { it.key }.sorted()
                mutable.value = mutable.value.copy(songs = songs, playback = playback, hostOnline = hostOnline, leaderUid = onlineMembers.firstOrNull(),
                    memberCount = snapshot.child("members").children.count { it.getValue(Boolean::class.java) == true })
                prepareCurrent()
            }
            override fun onCancelled(error: DatabaseError) {
                if (room == ref) { detach(); message("La sala JAMS caducó o ya no está disponible.") }
            }
        }
        roomListener = listener
        ref.addValueEventListener(listener)
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive && room == ref) { alignPlayback(); delay(250) }
        }
    }
    private fun registerPresence(ref: DatabaseReference, uid: String) {
        val presence = ref.child("members/$uid")
        presence.onDisconnect().setValue(false).addOnSuccessListener {
            if (room == ref) presence.setValue(true)
        }
    }
    private fun prepareCurrent() {
        val song = mutable.value.songs.firstOrNull { it.key == mutable.value.playback.songKey }
        if (song == null) { preparation?.cancel(); loadedSong = null; player.pause(); return }
        if (loadedSong == song.key || preparation?.isActive == true && preparingKey == song.key) return
        preparation?.cancel()
        loadedSong = null
        preparingKey = song.key
        val token = ++preparationToken
        appliedPlayback = null
        player.pause()
        val generation = currentRoomGeneration
        preparation = scope.launch {
            try {
                val stream = withTimeout(25_000) { Providers.resolveStream(song.title, song.artist,
                    "https://www.youtube.com/watch?v=${song.videoId}", "jam:${song.videoId}") }
                    ?: error("JAMS: no se pudo reproducir esta canción en este teléfono.")
                ensureActive()
                if (generation != currentRoomGeneration || mutable.value.playback.songKey != song.key) return@launch
                val track = song.track().copy(audioUri = stream.url)
                player.play(track, JamSync.targetPosition(mutable.value.playback, serverNow(), song.durationMs))
                player.pause()
                onTrack(track)
                loadedSong = song.key
                alignPlayback()
            } catch (e: TimeoutCancellationException) { message("JAMS: no se pudo preparar el audio a tiempo.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: "No se pudo preparar el audio de JAMS.") }
            finally { if (preparationToken == token) preparingKey = null }
        }
    }
    private var preparingKey: String? = null
    private fun alignPlayback() {
        val s = mutable.value
        val song = s.songs.firstOrNull { it.key == loadedSong } ?: return
        if (serverNow() >= s.playback.effectiveAt) appliedPlayback = s.playback
        val timeline = appliedPlayback?.takeIf { it.songKey == song.key }
        if (!s.connected || !clockReady || timeline == null) {
            if (player.isPlaying.value) player.pause()
            return
        }
        val progress = player.progress.value
        val duration = progress.durationMs.takeIf { it > 0 } ?: song.durationMs
        val target = JamSync.targetPosition(timeline, serverNow(), duration)
        if (progress.trackId != song.track().id) return
        val sampledElapsedMs = ((android.os.SystemClock.elapsedRealtimeNanos() - progress.sampledAtElapsed) / 1_000_000L).coerceAtLeast(0)
        val localPosition = progress.positionMs + if (progress.isPlaying) (sampledElapsedMs * progress.speed).toLong() else 0L
        if (JamSync.needsSeek(localPosition, target)) player.seekTo(target)
        if (timeline.playing && !player.isPlaying.value && (duration <= 0 || target < duration)) player.resume()
        if (!timeline.playing && player.isPlaying.value) player.pause()
    }
    fun addTrack(track: Track) = operation {
        val ref = room ?: error("JAMS: entra en una sala primero.")
        check(mutable.value.connected && online()) { "JAMS necesita conexión a Internet." }
        check(mutable.value.songs.size < 100) { "JAMS: la cola admite hasta 100 canciones." }
        val generation = currentRoomGeneration
        val direct = track.sourceId?.takeIf { track.source.equals("youtube", true) && Regex("[A-Za-z0-9_-]{11}").matches(it) }
            ?: track.url?.let { Regex("[?&]v=([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }
        val video = direct ?: withContext(Dispatchers.IO) {
            NewPipeStreamResolver.findYoutubeMatch(track.title, track.artistName)
                ?.let { Regex("[?&]v=([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }
        } ?: error("JAMS: no se encontró una versión compartible de esta canción.")
        check(generation == currentRoomGeneration && room == ref) { "JAMS: la sala cambió." }
        ref.child("queue").push().setValue(mapOf("videoId" to video, "title" to track.title.take(300),
            "artist" to track.artistName.take(300), "durationMs" to track.durationMs.coerceIn(0, 86_400_000), "addedBy" to mutable.value.uid)).await()
        message("Canción añadida a la cola de JAMS.")
    }
    fun playSong(key: String) = operation { publish(key, true, 0) }
    fun toggle() = operation {
        val s = mutable.value
        val key = s.playback.songKey.ifEmpty { s.songs.firstOrNull()?.key ?: error("JAMS: añade una canción a la cola.") }
        val position = JamSync.targetPosition(s.playback, serverNow()) + if (s.playback.playing) LEAD_MS else 0
        publish(key, !s.playback.playing, position)
    }
    private fun setPlaying(playing: Boolean) = operation {
        val s = mutable.value
        val key = s.playback.songKey.ifEmpty { s.songs.firstOrNull()?.key ?: error("JAMS: añade una canción a la cola.") }
        publish(key, playing, JamSync.targetPosition(s.playback, serverNow()) + if (s.playback.playing) LEAD_MS else 0)
    }
    fun seek(positionMs: Long) = operation { publish(mutable.value.playback.songKey, mutable.value.playback.playing, positionMs) }
    fun next() = operation {
        val s = mutable.value
        val index = s.songs.indexOfFirst { it.key == s.playback.songKey }
        val next = s.songs.getOrNull(index + 1)
        if (next != null) publish(next.key, true, 0)
        else if (s.playback.songKey.isNotEmpty()) publish(s.playback.songKey, false, player.progress.value.positionMs)
    }
    fun previous() = operation {
        val s = mutable.value
        val index = s.songs.indexOfFirst { it.key == s.playback.songKey }
        s.songs.getOrNull((index - 1).coerceAtLeast(0))?.let { publish(it.key, true, 0) }
    }
    fun ended(trackId: String?) {
        val s = mutable.value
        val song = s.songs.firstOrNull { it.key == s.playback.songKey } ?: return
        if (!s.isLeader || song.track().id != trackId) return
        operation {
            val next = s.songs.getOrNull(s.songs.indexOf(song) + 1)
            publish(next?.key ?: song.key, next != null, if (next != null) 0 else player.progress.value.positionMs,
                expectedRevision = s.playback.revision)
        }
    }
    private suspend fun publish(songKey: String, playing: Boolean, position: Long, expectedRevision: Long? = null) {
        val s = mutable.value
        check(s.active) { "JAMS: entra en una sala primero." }
        check(s.connected && online() && clockReady) { "JAMS necesita conexión para sincronizar." }
        val ref = room ?: return
        val at = serverNow() + LEAD_MS
        suspendCancellableCoroutine<Unit> { continuation ->
            ref.child("playback").runTransaction(object : Transaction.Handler {
                override fun doTransaction(current: MutableData): Transaction.Result {
                    val revision = current.child("revision").getValue(Long::class.java) ?: 0
                    if (expectedRevision != null && revision != expectedRevision) return Transaction.abort()
                    current.value = mapOf("songKey" to songKey, "playing" to playing, "positionMs" to position.coerceAtLeast(0),
                        "effectiveAt" to at, "revision" to revision + 1)
                    return Transaction.success(current)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (!continuation.isActive) return
                    if (error != null) continuation.resumeWithException(error.toException())
                    else continuation.resume(Unit)
                }
            }, false)
        }
    }
    fun removeSong(key: String) = operation {
        check(mutable.value.isHost) { "JAMS: solo el anfitrión puede quitar canciones." }
        check(key != mutable.value.playback.songKey) { "JAMS: cambia de canción antes de quitarla." }
        room?.child("queue/$key")?.removeValue()?.await()
    }
    fun playbackFailed() { preparation?.cancel(); loadedSong = null; player.pause(); message("No se pudo reproducir el audio en este teléfono. Reintenta el audio de JAMS.") }
    fun retryAudio() { preparation?.cancel(); loadedSong = null; preparingKey = null; prepareCurrent() }
    fun leave() = operation {
        val ref = room ?: return@operation
        val s = mutable.value
        ref.child("members/${s.uid}").onDisconnect().cancel().await()
        if (s.isHost) ref.removeValue().await()
        else ref.child("members/${s.uid}").removeValue().await()
        detach()
    }
    private fun detach() {
        val old = room ?: return
        room = null
        JamTransportBridge.listener = null
        currentRoomGeneration++
        roomListener?.let { old.removeEventListener(it) }
        roomListener = null
        preparation?.cancel(); syncJob?.cancel(); loadedSong = null; preparingKey = null
        player.pause(); player.clearQueue()
        repeat(3) { if (player.repeatMode.value != repeatBeforeJam) player.cycleRepeatMode() }
        mutable.value = JamState(mutable.value.installationUid, uid = mutable.value.uid, connected = mutable.value.connected)
        onLeave()
    }
    private fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
    fun close() {
        if (active) player.pause()
        JamTransportBridge.listener = null
        room?.let { ref -> mutable.value.uid?.let { ref.child("members/$it").setValue(false) } }
        preparation?.cancel(); syncJob?.cancel()
        roomListener?.let { listener -> room?.removeEventListener(listener) }
        globalListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        globalListeners.clear()
    }
    companion object { private const val LEAD_MS = 1500L }
}
