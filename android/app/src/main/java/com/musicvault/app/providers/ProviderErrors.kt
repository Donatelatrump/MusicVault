package com.musicvault.app.providers

import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.UnknownHostException

enum class ProviderErrorKind { OFFLINE, SESSION_EXPIRED, RATE_LIMIT, UNAVAILABLE, EXTRACTOR, URL_EXPIRED, UNKNOWN }
enum class ProviderAction { RECONNECT, RETRY, WAIT, UPDATE_EXTRACTOR, CHOOSE_ANOTHER }
data class ProviderFailure(val kind: ProviderErrorKind, val message: String, val action: ProviderAction, val retryable: Boolean)

/** Pure classification boundary: neither cookies nor provider output reach the UI. */
object ProviderErrors {
    fun classify(error: Throwable, httpStatus: Int? = null): ProviderFailure {
        if (error is CancellationException) throw error
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is UnknownHostException || it is ConnectException } ||
                text.contains("network is unreachable") || text.contains("unable to resolve host") || text.contains("offline") ->
                ProviderFailure(ProviderErrorKind.OFFLINE, "Sin conexión. Reconecta y reintenta; tu audio local sigue disponible.", ProviderAction.RECONNECT, true)
            httpStatus == 429 || text.contains("429") || text.contains("rate limit") || text.contains("too many requests") ->
                ProviderFailure(ProviderErrorKind.RATE_LIMIT, "El proveedor limita las peticiones. Espera unos minutos antes de reintentar.", ProviderAction.WAIT, true)
            httpStatus == 401 || text.contains("session expired") || text.contains("token expired") || text.contains("login required") || text.contains("sign in") || text.contains("rechazó la sesión") || text.contains("conecta tu cuenta") ->
                ProviderFailure(ProviderErrorKind.SESSION_EXPIRED, "La sesión caducó o necesita autenticación. Reconecta el proveedor.", ProviderAction.RECONNECT, false)
            text.contains("video unavailable") || text.contains("private video") || text.contains("removed by") || text.contains("no encontró esa canción") || text.contains("no encontró esa playlist") || httpStatus == 404 ->
                ProviderFailure(ProviderErrorKind.UNAVAILABLE, "El contenido ya no está disponible. Elige otra versión.", ProviderAction.CHOOSE_ANOTHER, false)
            httpStatus == 403 || text.contains("url expired") || text.contains("signature expired") || text.contains("http error 403") ->
                ProviderFailure(ProviderErrorKind.URL_EXPIRED, "El enlace de audio caducó. Reintenta para obtener uno nuevo.", ProviderAction.RETRY, true)
            text.contains("extract") || text.contains("signature") || text.contains("nsig") || text.contains("player response") || text.contains("youtube") || text.contains("cambió su lector") ->
                ProviderFailure(ProviderErrorKind.EXTRACTOR, "No se pudo obtener el audio de YouTube. Reintenta; si el problema persiste, comprueba si hay una actualización de MusicVault.", ProviderAction.UPDATE_EXTRACTOR, false)
            else -> ProviderFailure(ProviderErrorKind.UNKNOWN, "No se pudo completar la petición. Reintenta.", ProviderAction.RETRY, true)
        }
    }
}
