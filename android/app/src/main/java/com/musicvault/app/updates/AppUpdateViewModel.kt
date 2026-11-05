package com.musicvault.app.updates

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicvault.app.settings.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdateState(val checking: Boolean = false, val release: AndroidRelease? = null,
    val prompt: Boolean = false, val downloading: Boolean = false, val progress: Int = 0,
    val message: String? = null, val readyPath: String? = null)

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = AppPreferences.prefs(app)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    val installedVersion: String = installed.versionName.orEmpty()
    private val installedCode = PackageInfoCompat.getLongVersionCode(installed)
    private fun cachedRelease(): AndroidRelease? = runCatching {
        prefs.getString("app_update_manifest", null)?.let {
            AndroidReleasePolicy.parseManifest(it, app.packageName, installedCode, Build.SUPPORTED_ABIS.toList())
        }
    }.getOrNull()
    private val mutable = MutableStateFlow(AppUpdateState(release = cachedRelease()))
    val state = mutable.asStateFlow()
    private var lastAttempt = 0L

    fun check(manual: Boolean = false) {
        if (mutable.value.checking || mutable.value.downloading) return
        if (prefs.getBoolean("offline_mode", false)) {
            if (manual) mutable.value = mutable.value.copy(message = "Desactiva el modo sin conexión para buscar actualizaciones.")
            return
        }
        val now = System.currentTimeMillis()
        if (!manual && (now - prefs.getLong("app_update_checked_at", 0) in 0 until DAY ||
                now - lastAttempt in 0 until 60_000L)) return
        lastAttempt = now
        mutable.value = mutable.value.copy(checking = true, message = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { findRelease() }
                prefs.edit().putLong("app_update_checked_at", now).apply()
                if (result == null) prefs.edit().remove("app_update_manifest").apply()
                else prefs.edit().putString("app_update_manifest", result.toManifest()).apply()
                val previous = mutable.value
                mutable.value = previous.copy(checking = false, release = result,
                    prompt = result != null && AndroidReleasePolicy.shouldPrompt(result.versionCode,
                        prefs.getLong("app_update_dismissed_code", 0)),
                    message = if (result == null) "No hay una versión más reciente disponible para esta instalación." else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(checking = false,
                    message = "No se pudieron comprobar las actualizaciones. Reintenta más tarde.")
                android.util.Log.w("MusicVaultUpdate", "Release check failed", e)
            }
        }
    }

    private fun getJson(url: String): String = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("User-Agent", "MusicVault-Android/$installedVersion").build()).execute().use { response ->
        check(response.isSuccessful) { "Release metadata HTTP ${response.code}" }
        val body = response.body ?: error("Missing release metadata")
        check(body.contentLength() <= 2 * 1024 * 1024) { "Metadata too large" }
        val bytes = body.byteStream().readBytesBounded(2 * 1024 * 1024)
        bytes.toString(Charsets.UTF_8)
    }

    private fun findRelease(): AndroidRelease? {
        val candidates = mutableListOf<AndroidRelease>()
        // Releases include Windows builds; never treat /latest or a tag as an Android version.
        for (page in 1..3) {
            val releases = JSONArray(getJson("https://api.github.com/repos/Donatelatrump/MusicVault/releases?per_page=30&page=$page"))
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name") != UPDATE_MANIFEST) continue
                    val url = asset.optString("browser_download_url")
                    if (!AndroidReleasePolicy.trustedDownload(url)) continue
                    AndroidReleasePolicy.parseManifest(getJson(url), app.packageName, installedCode,
                        Build.SUPPORTED_ABIS.toList())?.let(candidates::add)
                }
            }
            if (releases.length() < 30) break
        }
        return candidates.maxByOrNull { it.versionCode }
    }

    fun dismiss() {
        mutable.value.release?.let { prefs.edit().putLong("app_update_dismissed_code", it.versionCode).apply() }
        mutable.value = mutable.value.copy(prompt = false)
    }

    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.downloading) return
        if (prefs.getBoolean("offline_mode", false)) {
            mutable.value = mutable.value.copy(message = "Desactiva el modo sin conexión para descargar la actualización.")
            return
        }
        mutable.value = mutable.value.copy(prompt = false, downloading = true, progress = 0, message = null, readyPath = null)
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadAndVerify(release) }
                mutable.value = mutable.value.copy(downloading = false, readyPath = apk.absolutePath)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("MusicVaultUpdate", "APK download failed", e)
                mutable.value = mutable.value.copy(downloading = false,
                    message = "No se pudo preparar la actualización. Reintenta desde aquí.")
            }
        }
    }

    private suspend fun downloadAndVerify(release: AndroidRelease): File {
        val directory = File(app.cacheDir, "app-updates").apply { mkdirs() }
        val partial = File(directory, "update.part")
        val target = File(directory, "update.apk")
        try {
            val downloadClient = client.newBuilder().callTimeout(15, TimeUnit.MINUTES).build()
            downloadClient.newCall(Request.Builder().url(release.asset.url).build()).execute().use { response ->
                check(response.isSuccessful) { "APK HTTP ${response.code}" }
                val body = response.body ?: error("Empty APK")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var progress = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= release.asset.size) { "APK size mismatch" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val next = (total * 100 / release.asset.size).toInt()
                        if (next != progress) {
                            progress = next
                            withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(progress = next) }
                        }
                    }
                } }
                check(total == release.asset.size) { "Incomplete APK" }
                check(digest.digest().joinToString("") { "%02x".format(it) } == release.asset.sha256) { "APK digest mismatch" }
            }
            // Validate the APK's own metadata; release labels cannot authorize a downgrade or another app.
            @Suppress("DEPRECATION")
            val archive = app.packageManager.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNATURES)
                ?: error("Invalid APK")
            check(archive.packageName == app.packageName && PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
                release.versionCode > installedCode) { "Incompatible APK" }
            @Suppress("DEPRECATION")
            val current = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            check(archive.signatures?.toSet() == current.signatures?.toSet() && !archive.signatures.isNullOrEmpty()) { "Signing certificate mismatch" }
            check(partial.renameTo(target)) { "Cannot stage APK" }
            return target
        } finally { partial.delete() }
    }

    fun installationMessage(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun consumeReady() { mutable.value = mutable.value.copy(readyPath = null) }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Metadata too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    companion object { private const val DAY = 24 * 60 * 60 * 1000L }
}
