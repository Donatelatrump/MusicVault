package com.musicvault.app.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import com.musicvault.app.providers.ProviderMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local bounded events only; export is initiated by the person selecting a SAF document. */
object LocalDiagnostics {
    private val lock = Any()
    fun redact(value: String): String = value
        .replace(Regex("https?://[^\\s\\\"<>]+", RegexOption.IGNORE_CASE), "[URL omitida]")
        .replace(Regex("(?i)(cookie|authorization|token|password|secret|signature|sig|session)[\\s:=]+[^\\s,;]+"), "$1=[oculto]")
        .replace(Regex("(?i)(bearer|token)\\s+[^\\s]+"), "$1 [oculto]")
        .take(300)

    fun record(context: Context, category: String, result: String) = synchronized(lock) {
        try {
            val file = File(context.noBackupFilesDir, "diagnostic-events.json")
            val prior = if (file.isFile) JSONArray(file.readText()) else JSONArray()
            val next = JSONArray()
            for (i in maxOf(0, prior.length() - 99) until prior.length()) next.put(prior.getJSONObject(i))
            next.put(JSONObject().put("time", System.currentTimeMillis()).put("category", redact(category)).put("result", redact(result)))
            val staging = File(file.parentFile, "diagnostic-events.pending")
            staging.writeText(next.toString())
            if (!staging.renameTo(file)) { staging.copyTo(file, overwrite = true); staging.delete() }
        } catch (_: Exception) { /* Diagnostic writes must never fail a user action. */ }
    }

    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val events = synchronized(lock) {
            val file = File(context.noBackupFilesDir, "diagnostic-events.json")
            if (file.isFile) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        }
        val update = ProviderMaintenance.state.value
        val report = JSONObject().put("format", 1).put("application", "MusicVault")
            .put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("androidApi", Build.VERSION.SDK_INT).put("extractor", update.version ?: "bundled")
            .put("extractorLastCheck", update.lastCheck).put("events", events)
            .put("frameAggregates", PerformanceDiagnostics.snapshot())
            .put("lastCrash", LocalCrashRecorder.snapshot(context) ?: JSONObject.NULL)
            .put("privacy", "No cookies, signed URLs, account identifiers, library titles or file paths are collected")
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "No se pudo abrir el documento" }
            .bufferedWriter().use { it.write(report.toString(2)) }
    }

    fun clear(context: Context) {
        synchronized(lock) { File(context.noBackupFilesDir, "diagnostic-events.json").delete() }
        PerformanceDiagnostics.clear(context)
        LocalCrashRecorder.clear(context)
    }
}
