package com.musicvault.app.updates

import android.app.Application
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.settings.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class AppUpdatePersistenceTest {
    @Test fun declinedUpdateRemainsAvailableAfterRecreationAndOfflineCheckDoesNotDownload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = AppPreferences.prefs(app)
        val oldManifest = prefs.getString("app_update_manifest", null)
        val oldDismissed = prefs.getLong("app_update_dismissed_code", 0)
        val oldOffline = prefs.getBoolean("offline_mode", false)
        val store = ViewModelStore()
        val code = PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)) + 1
        val release = AndroidRelease(code, "test-future", app.packageName, "",
            AndroidAsset("universal", "${RELEASE_DOWNLOAD_PREFIX}test/app-universal-release.apk", "a".repeat(64), 1024))
        try {
            prefs.edit().putString("app_update_manifest", release.toManifest()).putBoolean("offline_mode", true).commit()
            instrumentation.runOnMainSync {
                val before = AppUpdateViewModel(app)
                store.put("before", before)
                assertEquals(release, before.state.value.release)
                before.dismiss()
                assertFalse(before.state.value.prompt)
                assertEquals(code, prefs.getLong("app_update_dismissed_code", 0))
                assertEquals(release, before.state.value.release)
                store.clear()
                val after = AppUpdateViewModel(app)
                store.put("after", after)
                assertEquals(release, after.state.value.release)
                assertFalse(after.state.value.prompt)
                after.check(manual = true)
                assertFalse(after.state.value.checking)
                after.download()
                assertFalse(after.state.value.downloading)
                assertNull(after.state.value.readyPath)
                assertNotNull(after.state.value.message)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            prefs.edit().putString("app_update_manifest", oldManifest).putLong("app_update_dismissed_code", oldDismissed)
                .putBoolean("offline_mode", oldOffline).commit()
        }
    }
}
