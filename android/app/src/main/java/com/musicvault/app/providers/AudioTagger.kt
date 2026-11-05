package com.musicvault.app.providers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AudioTags(val title: String, val artist: String, val album: String? = null, val coverUri: String? = null)

/** Identical tagging for fresh downloads and completed streaming cache promotions. */
object AudioTagger {
    private val processes = ConcurrentHashMap<String, Process>()
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    fun cancel(requestId: String) { processes.remove(requestId)?.destroyForcibly() }

    suspend fun embed(context: Context, audio: File, tags: AudioTags, requestId: String?): File = withContext(Dispatchers.IO) {
        ensureActive()
        FFmpeg.getInstance().init(context.applicationContext)
        val ext = if (audio.extension.equals("mp3", true)) "mp3" else "m4a"
        val output = File(audio.parentFile, "${audio.nameWithoutExtension}.tagged.$ext")
        val artwork = File(audio.parentFile, "${audio.nameWithoutExtension}.cover.jpg")
        val cover = tags.coverUri?.let { uri ->
            try {
                val bytes = runInterruptible {
                    if (uri.startsWith("https://") && com.musicvault.app.settings.AppPreferences.prefs(context).getBoolean("offline_mode", false)) null
                    else if (uri.startsWith("https://")) {
                        val candidates = listOf(com.musicvault.app.catalog.CoverArt.highResolution(uri), uri).distinct()
                        var result: ByteArray? = null
                        for (candidate in candidates) {
                            result = runCatching {
                                http.newCall(Request.Builder().url(candidate).build()).execute().use { response ->
                                    if (!response.isSuccessful) null else response.body?.byteStream()?.use { readLimited(it) }
                                }
                            }.getOrNull()
                            if (result != null) break
                        }
                        result
                    } else context.contentResolver.openInputStream(Uri.parse(uri))?.use { readLimited(it) }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1200) }
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) }
                bitmap?.let { image ->
                    try { artwork.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }; artwork }
                    finally { image.recycle() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // Optional artwork may disappear without discarding the audio.
        }
        val command = mutableListOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
            "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", audio.absolutePath)
        if (cover != null) command.addAll(listOf("-i", cover.absolutePath))
        command.addAll(listOf("-map", "0:a:0"))
        if (cover != null) command.addAll(listOf("-map", "1:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
        val copyAudio = audio.extension.lowercase() in setOf("mp3", "m4a", "aac", "mp4")
        command.addAll(if (copyAudio) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "192k"))
        command.addAll(listOf("-metadata", "title=${tags.title}", "-metadata", "artist=${tags.artist}"))
        tags.album?.takeIf { it.isNotBlank() }?.let { command.addAll(listOf("-metadata", "album=$it")) }
        if (ext == "mp3") command.addAll(listOf("-id3v2_version", "3"))
        else command.addAll(listOf("-movflags", "+faststart"))
        command.add(output.absolutePath)
        var process: Process? = null
        try {
            runInterruptible {
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
                builder.environment()["LD_LIBRARY_PATH"] = File(packages, "ffmpeg/usr/lib").absolutePath + ":" + File(packages, "python/usr/lib").absolutePath
                builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
                val active = builder.start()
                process = active
                requestId?.let { processes[it] = active }
                active.inputStream.use { input -> while (input.read(ByteArray(4096)) >= 0) { if (Thread.currentThread().isInterrupted) throw InterruptedException() } }
                check(active.waitFor() == 0 && output.length() > 0) { "No se pudieron guardar los tags de audio" }
            }
            ensureActive()
            audio.delete()
            output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            process?.destroyForcibly()
            requestId?.let { processes.remove(it) }
            artwork.delete()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 4 * 1024 * 1024) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
