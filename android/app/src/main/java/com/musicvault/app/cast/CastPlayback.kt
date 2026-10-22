package com.musicvault.app.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.musicvault.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Receiver playback uses a resolved, publicly reachable URL; local URIs stay on the phone. */
class CastPlayback(context: Context) {
    private val cast = CastContext.getSharedInstance(context.applicationContext)
    private val connected = MutableStateFlow(cast.sessionManager.currentCastSession?.isConnected == true)
    val isConnected = connected.asStateFlow()
    private val status = MutableStateFlow<String?>(null)
    val message = status.asStateFlow()
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) { connected.value = true }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { connected.value = true }
        override fun onSessionEnded(session: CastSession, error: Int) { connected.value = false }
        override fun onSessionStartFailed(session: CastSession, error: Int) { connected.value = false; status.value = "No se pudo conectar ($error)" }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { connected.value = false; status.value = "No se pudo recuperar la sesión ($error)" }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) { connected.value = false }
    }
    init { cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java) }
    fun load(track: Track, positionMs: Long, onLoaded: () -> Unit) {
        val reference = track.audioUri
        val uri = reference?.let(Uri::parse)
        if (uri?.scheme !in listOf("https", "http") || track.isDownloaded) {
            status.value = "Este archivo local no tiene una URL accesible para Chromecast."
            return
        }
        val client = cast.sessionManager.currentCastSession?.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title); putString(MediaMetadata.KEY_ARTIST, track.artistName)
            putString(MediaMetadata.KEY_ALBUM_TITLE, track.albumTitle)
            track.coverUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val mime = uri!!.getQueryParameter("mime")?.takeIf { it.startsWith("audio/") } ?: when (uri.path?.substringAfterLast('.')) { "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; else -> "audio/webm" }
        val info = MediaInfo.Builder(reference!!).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime).setMetadata(metadata).apply { if (track.durationMs > 0) setStreamDuration(track.durationMs) }.build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(positionMs).setAutoplay(true).build())
            .setResultCallback { result -> if (result.status.isSuccess) { status.value = "Reproduciendo en Chromecast"; onLoaded() }
                else status.value = "El receptor no pudo abrir el audio (${result.status.statusCode})." }
    }
    fun toggle() { cast.sessionManager.currentCastSession?.remoteMediaClient?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(positionMs: Long) { cast.sessionManager.currentCastSession?.remoteMediaClient?.seek(positionMs) }
    fun positionMs(): Long = cast.sessionManager.currentCastSession?.remoteMediaClient?.approximateStreamPosition ?: 0L
    fun disconnect() { cast.sessionManager.endCurrentSession(true) }
    fun release() { cast.sessionManager.removeSessionManagerListener(listener, CastSession::class.java) }
}
