package com.musicvault.app.lyrics

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LyricsRequestTest {
    @Test
    fun `reads provider body and maps HTTP miss to null`() = runBlocking<Unit> {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            repeat(2) { index ->
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = if (index == 0) "Available lyrics" else ""
                    val status = if (index == 0) "200 OK" else "404 Not Found"
                    socket.getOutputStream().write(
                        "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()
                    )
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertEquals("Available lyrics", LyricsRequest.get("$base/found"))
            assertEquals(null, LyricsRequest.get("$base/missing"))
            task.get(2, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `canceling a waiting server response finishes without waiting for network timeout`() = runBlocking<Unit> {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val received = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        executor.submit {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                received.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            val job = async { LyricsRequest.get("http://127.0.0.1:${server.localPort}/slow") }
            withTimeout(2_000) {
                received.await()
                job.cancelAndJoin()
            }
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            server.close()
            executor.shutdownNow()
        }
    }
}
