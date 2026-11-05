package com.musicvault.app.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidReleasePolicyTest {
    private fun manifest(code: Long = 40, pkg: String = "com.musicvault.app", abis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "universal")): String =
        JSONObject().put("schemaVersion", 1).put("packageName", pkg).put("versionCode", code)
            .put("versionName", "0.7.0").put("assets", JSONArray(abis.map { abi ->
                JSONObject().put("abi", abi).put("url", "${RELEASE_DOWNLOAD_PREFIX}android-v0.7.0/app-$abi-release.apk")
                    .put("size", 1024).put("sha256", "a".repeat(64))
            })).toString()

    @Test fun comparesApkVersionCodeInsteadOfUnrelatedReleaseTag() {
        assertNotNull(AndroidReleasePolicy.parseManifest(manifest(), "com.musicvault.app", 36, listOf("arm64-v8a")))
        assertNull(AndroidReleasePolicy.parseManifest(manifest(36), "com.musicvault.app", 36, listOf("arm64-v8a")))
        assertNull(AndroidReleasePolicy.parseManifest(manifest(35), "com.musicvault.app", 36, listOf("arm64-v8a")))
    }
    @Test fun productionApkCannotBeOfferedToDebugPackage() {
        assertNull(AndroidReleasePolicy.parseManifest(manifest(), "com.musicvault.app.debug", 36, listOf("arm64-v8a")))
    }
    @Test fun prefersDeviceArchitectureAndFallsBackToUniversal() {
        assertEquals("arm64-v8a", AndroidReleasePolicy.parseManifest(manifest(), "com.musicvault.app", 36,
            listOf("arm64-v8a", "armeabi-v7a"))?.asset?.abi)
        assertEquals("universal", AndroidReleasePolicy.parseManifest(manifest(abis = listOf("universal")),
            "com.musicvault.app", 36, listOf("x86_64"))?.asset?.abi)
        assertNull(AndroidReleasePolicy.parseManifest(manifest(abis = listOf("arm64-v8a")),
            "com.musicvault.app", 36, listOf("armeabi-v7a")))
    }
    @Test fun rejectsUntrustedAssetsAndMissingIntegrityData() {
        for (url in listOf("http://github.com/Donatelatrump/MusicVault/releases/download/v1/app.apk",
            "https://github.com.evil.test/Donatelatrump/MusicVault/releases/download/v1/app.apk",
            "https://github.com/another/repo/releases/download/v1/app.apk")) {
            val root = JSONObject(manifest(abis = listOf("arm64-v8a")))
            root.getJSONArray("assets").getJSONObject(0).put("url", url)
            assertNull(AndroidReleasePolicy.parseManifest(root.toString(), "com.musicvault.app", 36, listOf("arm64-v8a")))
        }
        val badDigest = manifest(abis = listOf("arm64-v8a")).replace("a".repeat(64), "bad")
        assertNull(AndroidReleasePolicy.parseManifest(badDigest, "com.musicvault.app", 36, listOf("arm64-v8a")))
    }
    @Test fun decliningVersionSuppressesOnlyThatVersionAndOlderVersions() {
        assertFalse(AndroidReleasePolicy.shouldPrompt(40, 40))
        assertFalse(AndroidReleasePolicy.shouldPrompt(39, 40))
        assertTrue(AndroidReleasePolicy.shouldPrompt(41, 40))
        // Dismissal has no effect on availability from Settings.
        assertNotNull(AndroidReleasePolicy.parseManifest(manifest(), "com.musicvault.app", 36, listOf("arm64-v8a")))
    }
}
