package com.musicvault.app.updates

import org.json.JSONObject
import java.net.URI

internal const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/Donatelatrump/MusicVault/releases/download/"
internal const val UPDATE_MANIFEST = "musicvault-android-update.json"

internal data class AndroidAsset(val abi: String, val url: String, val sha256: String, val size: Long)
internal data class AndroidRelease(val versionCode: Long, val versionName: String, val packageName: String,
    val notes: String, val asset: AndroidAsset) {
    fun toManifest(): String = JSONObject().put("schemaVersion", 1).put("packageName", packageName)
        .put("versionCode", versionCode).put("versionName", versionName).put("notes", notes)
        .put("assets", org.json.JSONArray().put(JSONObject().put("abi", asset.abi).put("url", asset.url)
            .put("sha256", asset.sha256).put("size", asset.size))).toString()
}

internal object AndroidReleasePolicy {
    fun trustedDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        url.startsWith(RELEASE_DOWNLOAD_PREFIX) && uri.scheme == "https" &&
            uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            !uri.rawPath.contains("..")
    }.getOrDefault(false)

    fun parseManifest(json: String, packageName: String, installedCode: Long, abis: List<String>): AndroidRelease? {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion") != 1 || root.optString("packageName") != packageName) return null
        val code = root.getLong("versionCode")
        if (code <= installedCode) return null
        val array = root.getJSONArray("assets")
        val assets = (0 until array.length()).mapNotNull { index ->
            val a = array.getJSONObject(index)
            val url = a.optString("url")
            val digest = a.optString("sha256").lowercase()
            val size = a.optLong("size")
            if (!trustedDownload(url) || !url.endsWith(".apk") ||
                !Regex("[a-f0-9]{64}").matches(digest) || size !in 1..MAX_APK_BYTES) null
            else AndroidAsset(a.getString("abi"), url, digest, size)
        }
        // The first ABI is the architecture of the installed process/device.
        val asset = (abis + "universal").firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi } } ?: return null
        return AndroidRelease(code, root.getString("versionName"), packageName, root.optString("notes").take(2000), asset)
    }

    fun shouldPrompt(code: Long, dismissedCode: Long): Boolean = code > dismissedCode
    const val MAX_APK_BYTES = 400L * 1024 * 1024
}
