package com.musicvault.app.scrobble

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.SystemClock
import androidx.work.*
import com.musicvault.app.data.Track
import com.musicvault.app.diagnostics.LocalDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ScrobbleStatus(val enabled: Boolean = false, val pending: Int = 0, val message: String = "Desactivado: no se comparte tu historial")

object ListenBrainz {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(ScrobbleStatus())
    val state = mutableState.asStateFlow()
    private var previousTrack: String? = null
    private var previousPosition = 0L
    private var lastTick = 0L
    private var previousPlaying = false
    private var listenedMs = 0L
    private var startedAt = 0L
    private var submitted = false

    fun initialize(context: Context) {
        scope.launch {
            if (enabled(context) && token(context) == null) settings(context).edit().putBoolean("enabled", false).commit()
            refresh(context)
            if (enabled(context)) schedule(context)
        }
    }

    private fun settings(context: Context) = context.getSharedPreferences("musicvault_scrobble", Context.MODE_PRIVATE)
    fun enabled(context: Context) = settings(context).getBoolean("enabled", false)
    private fun credential(context: Context) = File(context.noBackupFilesDir, "listenbrainz.credential")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("musicvault.listenbrainz", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("musicvault.listenbrainz", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun saveToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val file = credential(context)
        val staging = File(file.parentFile, "listenbrainz.credential.pending")
        staging.writeBytes(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
        check(staging.renameTo(file)) { "No se pudo guardar la credencial" }
    }
    private fun token(context: Context): String? = try {
        val bytes = credential(context).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    } catch (_: Exception) { null }

    /** Only an explicit successful token setup turns sharing on. The token never enters a URL. */
    suspend fun connect(context: Context, rawToken: String): String = withContext(Dispatchers.IO) {
        require(!com.musicvault.app.settings.AppPreferences.prefs(context).getBoolean("offline_mode", false)) { "Desactiva el modo sin conexión en Ajustes para conectar ListenBrainz." }
        val newToken = rawToken.trim()
        require(newToken.matches(Regex("[A-Za-z0-9-]{20,100}"))) { "Introduce tu token de ListenBrainz" }
        val valid = runInterruptible {
            http.newCall(Request.Builder().url("https://api.listenbrainz.org/1/validate-token")
                .header("Authorization", "Token $newToken").build()).execute().use { response ->
                response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optBoolean("valid")
            }
        }
        require(valid) { "El token no es válido. Comprueba tu cuenta de ListenBrainz." }
        uploadLock.withLock {
            // Never send the prior account's offline history under a different token.
            if (token(context) != newToken) Queue(context).use { it.writableDatabase.delete("listens", null, null) }
            saveToken(context, newToken)
            settings(context).edit().putBoolean("enabled", true).commit()
        }
        synchronized(this@ListenBrainz) { previousTrack = null; listenedMs = 0; submitted = false }
        refresh(context, "Conectado: se enviarán escuchas de media canción o 4 minutos")
        schedule(context)
        "ListenBrainz conectado"
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            settings(context).edit().putBoolean("enabled", false).commit()
            credential(context).delete()
            Queue(context).use { it.writableDatabase.delete("listens", null, null) }
        }
        WorkManager.getInstance(context).cancelUniqueWork("listenbrainz-upload")
        WorkManager.getInstance(context).cancelUniqueWork("listenbrainz-periodic")
        refresh(context)
    }

    /** Call on playback ticks: real playing wall-time counts, seeking forward does not. */
    @Synchronized fun observe(context: Context, track: Track?, isPlaying: Boolean, positionMs: Long) {
        if (!enabled(context)) { previousTrack = null; lastTick = 0; return }
        val now = SystemClock.elapsedRealtime()
        val repeat = track != null && previousTrack == track.id && track.durationMs > 0 &&
            previousPosition >= track.durationMs - 3000 && positionMs < 3000 && previousPosition - positionMs > 5000
        if (track?.id != previousTrack || repeat) {
            previousTrack = track?.id
            listenedMs = 0
            submitted = false
            startedAt = System.currentTimeMillis() / 1000
            lastTick = now
        } else if (previousPlaying && isPlaying && track != null) {
            listenedMs += (now - lastTick).coerceIn(0, 10_000)
        }
        previousPlaying = isPlaying
        previousPosition = positionMs
        lastTick = now
        val threshold = listenThreshold(track?.durationMs ?: 0)
        if (track != null && isPlaying && !submitted && listenedMs >= threshold) {
            submitted = true
            val timestamp = startedAt
            val app = context.applicationContext
            scope.launch {
                try {
                    uploadLock.withLock {
                        if (!enabled(app)) return@withLock
                        Queue(app).use { queue ->
                            val values = android.content.ContentValues().apply {
                                put("track", track.id); put("started", timestamp); put("title", track.title)
                                put("artist", track.artistName); put("album", track.albumTitle); put("duration", track.durationMs)
                            }
                            queue.writableDatabase.insertWithOnConflict("listens", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    refresh(app)
                    schedule(app)
                } catch (failure: Exception) { LocalDiagnostics.record(app, "scrobble_queue", failure.javaClass.simpleName) }
            }
        }
    }

    internal fun listenThreshold(durationMs: Long): Long = if (durationMs > 0) minOf(durationMs / 2, 240_000).coerceAtLeast(1) else 240_000

    private fun schedule(context: Context) {
        if (!enabled(context)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<ListenBrainzWorker>().setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("listenbrainz-upload", ExistingWorkPolicy.KEEP, work)
        val periodic = PeriodicWorkRequestBuilder<ListenBrainzWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("listenbrainz-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    suspend fun flush(context: Context): Boolean = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            if (!enabled(context)) return@withLock true
            if (com.musicvault.app.settings.AppPreferences.prefs(context).getBoolean("offline_mode", false)) return@withLock false
            val token = token(context) ?: run { settings(context).edit().putBoolean("enabled", false).commit(); refresh(context, "Vuelve a introducir tu token"); return@withLock true }
            Queue(context).use { queue ->
                val db = queue.writableDatabase
                val payload = JSONArray()
                val ids = mutableListOf<Long>()
                db.query("listens", null, "sent=0", null, null, null, "started ASC", "100").use { cursor ->
                    while (cursor.moveToNext()) {
                        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
                        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                        ids += number("id")
                        val metadata = JSONObject().put("artist_name", string("artist")).put("track_name", string("title"))
                            .put("additional_info", JSONObject().put("submission_client", "MusicVault").put("duration_ms", number("duration")))
                        string("album").takeIf { it.isNotBlank() }?.let { metadata.put("release_name", it) }
                        payload.put(JSONObject().put("listened_at", number("started")).put("track_metadata", metadata))
                    }
                }
                if (ids.isEmpty()) { refresh(context); return@withLock true }
                val body = JSONObject().put("listen_type", "import").put("payload", payload).toString()
                val code = runInterruptible {
                    http.newCall(Request.Builder().url("https://api.listenbrainz.org/1/submit-listens")
                        .header("Authorization", "Token $token").post(body.toRequestBody("application/json".toMediaType())).build())
                        .execute().use { it.code }
                }
                if (code in 200..299) {
                    db.execSQL("UPDATE listens SET sent=1 WHERE id IN (${ids.joinToString(",")})")
                    db.delete("listens", "sent=1 AND started<?", arrayOf((System.currentTimeMillis()/1000 - 30*86400L).toString()))
                    refresh(context, "Escuchas enviadas")
                    return@withLock pending(context) == 0
                }
                if (code == 401 || code == 403) {
                    settings(context).edit().putBoolean("enabled", false).commit()
                    refresh(context, "Token caducado. Reconecta ListenBrainz para enviar la cola.")
                    return@withLock true
                }
                LocalDiagnostics.record(context, "scrobble_upload", "HTTP $code")
                refresh(context, if (code == 429) "Límite temporal; la cola se enviará más tarde" else "Envío pendiente; historial conservado localmente")
                false
            }
        }
    }

    private fun pending(context: Context): Int = Queue(context).use { queue ->
        queue.readableDatabase.rawQuery("SELECT COUNT(*) FROM listens WHERE sent=0", null).use { it.moveToFirst(); it.getInt(0) }
    }
    private fun refresh(context: Context, message: String? = null) {
        val active = enabled(context)
        mutableState.value = ScrobbleStatus(active, pending(context), message ?: if (active) "Conectado a ListenBrainz" else "Desactivado: no se comparte tu historial")
    }

    private class Queue(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "listenbrainz-queue.db").absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE listens (id INTEGER PRIMARY KEY AUTOINCREMENT, track TEXT NOT NULL, started INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, duration INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0, UNIQUE(track,started))") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

class ListenBrainzWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (ListenBrainz.flush(applicationContext)) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
