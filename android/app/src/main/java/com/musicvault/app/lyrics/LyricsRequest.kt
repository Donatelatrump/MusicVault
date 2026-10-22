package com.musicvault.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Expected provider failures become a miss; cancellation must reach the caller. */
internal inline fun <T> lyricsAttempt(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: InterruptedException) {
    throw error
} catch (_: Exception) {
    null
}

internal object LyricsRequest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "MusicVault/1.0 (lyrics client)")
            .header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { if (it.isSuccessful) it.body?.string() else null }
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                    return
                }
                continuation.resume(body)
            }
        })
    }
}
