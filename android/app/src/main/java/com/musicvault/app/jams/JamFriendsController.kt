package com.musicvault.app.jams

import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID

internal data class JamRoomInvite(val fromUid: String, val roomCode: String)
internal data class JamFriendsState(val uid: String? = null, val friends: List<String> = emptyList(),
    val requests: List<String> = emptyList(), val invitations: List<JamRoomInvite> = emptyList(),
    val busy: Boolean = false, val shareLink: String? = null, val message: String? = null)

/** Private contact lists and explicit friend requests; no names, public directory or discovery. */
internal class JamFriendsController(private val scope: CoroutineScope,
    private val backend: suspend () -> Pair<FirebaseDatabase, String>) {
    private val mutable = MutableStateFlow(JamFriendsState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var database: FirebaseDatabase? = null
    private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var connection: Job? = null
    fun connect() {
        if (mutable.value.uid != null || connection?.isActive == true) return
        connection = scope.launch {
            try { withTimeout(30_000) { authenticated() } }
            catch (_: TimeoutCancellationException) {}
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {}
        }
    }
    private suspend fun authenticated(): Pair<FirebaseDatabase, String> = mutex.withLock {
        val (db, uid) = backend()
        if (mutable.value.uid != uid || database == null) {
            close()
            database = db
            mutable.value = mutable.value.copy(uid = uid)
            listen(db.getReference("friends/$uid")) { value ->
                mutable.value = mutable.value.copy(friends = value.children.filter { it.getValue(Boolean::class.java) == true }.mapNotNull { it.key }.sorted())
            }
            listen(db.getReference("friendRequests/$uid")) { value ->
                mutable.value = mutable.value.copy(requests = value.children.mapNotNull { it.key }.sorted())
            }
            listen(db.getReference("roomInvites/$uid")) { value ->
                mutable.value = mutable.value.copy(invitations = value.children.mapNotNull { child ->
                    val from = child.key ?: return@mapNotNull null
                    val code = child.child("roomCode").getValue(String::class.java) ?: return@mapNotNull null
                    JamRoomInvite(from, code)
                })
            }
        }
        db to uid
    }
    private fun listen(ref: DatabaseReference, apply: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = apply(snapshot)
            override fun onCancelled(error: DatabaseError) { message("No se pudieron cargar tus amigos. Reintenta con conexión.") }
        }
        listeners += ref to listener
        ref.addValueEventListener(listener)
    }
    private fun operation(block: suspend (FirebaseDatabase, String) -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            try { withTimeout(30_000) { val (db, uid) = authenticated(); block(db, uid) } }
            catch (_: TimeoutCancellationException) { message("La acción tardó demasiado. Reintenta.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("MusicVaultFriends", "Contact action failed", e)
                message(e.message?.takeIf { it.startsWith("Amigos:") } ?: "No se pudo completar la acción. Reintenta.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun shareFriendLink() = operation { db, uid ->
        val code = UUID.randomUUID().toString().replace("-", "")
        db.getReference("friendInvites/$code").setValue(mapOf("ownerUid" to uid,
            "expiresAt" to System.currentTimeMillis() + 6 * 86_400_000L)).await()
        mutable.value = mutable.value.copy(shareLink = JamInvitation.friendUrl(code))
    }
    fun requestFromLink(code: String) = operation { db, uid ->
        val invite = db.getReference("friendInvites/$code").get().await()
        val target = invite.child("ownerUid").getValue(String::class.java) ?: error("Amigos: la invitación ya no está disponible.")
        check(target != uid) { "Amigos: este es tu propio enlace." }
        if (target in mutable.value.friends) { message("Ya son amigos."); return@operation }
        db.getReference("friendRequests/$target/$uid").setValue(mapOf("inviteCode" to code, "createdAt" to ServerValue.TIMESTAMP)).await()
        message("Solicitud de amistad enviada.")
    }
    fun requestFromRoom(target: String, roomCode: String) = operation { db, uid ->
        check(target != uid) { "Amigos: no puedes agregarte a ti mismo." }
        db.getReference("friendRequests/$target/$uid").setValue(mapOf("roomCode" to roomCode, "createdAt" to ServerValue.TIMESTAMP)).await()
        message("Solicitud de amistad enviada.")
    }
    fun accept(from: String) = operation { db, uid ->
        db.reference.updateChildren(mapOf("friends/$uid/$from" to true, "friends/$from/$uid" to true,
            "friendRequests/$uid/$from" to null)).await()
        message("Amigo agregado.")
    }
    fun reject(from: String) = operation { db, uid -> db.getReference("friendRequests/$uid/$from").removeValue().await() }
    fun remove(friend: String) = operation { db, uid ->
        db.reference.updateChildren(mapOf("friends/$uid/$friend" to null, "friends/$friend/$uid" to null)).await()
    }
    fun inviteToRoom(friend: String, roomCode: String) = operation { db, uid ->
        db.getReference("roomInvites/$friend/$uid").setValue(mapOf("roomCode" to roomCode, "createdAt" to ServerValue.TIMESTAMP)).await()
        message("Invitación enviada.")
    }
    fun dismissInvite(from: String) = operation { db, uid -> db.getReference("roomInvites/$uid/$from").removeValue().await() }
    fun consumeShare() { mutable.value = mutable.value.copy(shareLink = null) }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    private fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
    fun close() { listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }; listeners.clear() }
}
