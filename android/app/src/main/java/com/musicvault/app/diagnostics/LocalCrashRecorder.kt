package com.musicvault.app.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Chains Android's crash handler; records only type and app stack symbols locally, never exception messages. */
object LocalCrashRecorder {
    private val installed = AtomicBoolean()
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val frames = JSONArray()
                error.stackTrace.filter { it.className.startsWith("com.musicvault.app.") }.take(12).forEach { frame ->
                    frames.put(JSONObject().put("class", frame.className).put("method", frame.methodName).put("line", frame.lineNumber))
                }
                val crash = JSONObject().put("time", System.currentTimeMillis()).put("type", error.javaClass.name).put("appFrames", frames)
                    .put("policy", "Local only; excludes messages, thread names, paths, provider responses and credentials")
                File(app.noBackupFilesDir, "last-crash.json").writeText(crash.toString())
            } catch (_: Throwable) { /* Preserve the original crash, including out-of-memory failures. */ }
            finally { previous?.uncaughtException(thread, error) ?: android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }
    fun snapshot(context: Context): JSONObject? =
        runCatching { JSONObject(File(context.noBackupFilesDir, "last-crash.json").readText()) }.getOrNull()
    fun clear(context: Context) { File(context.noBackupFilesDir, "last-crash.json").delete() }
}
