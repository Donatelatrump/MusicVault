package com.musicvault.app.jams

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class InstallationIdentityTest {
    @Test fun identitySurvivesRecreationButIsDifferentForAnotherInstallation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "identity-test-${UUID.randomUUID()}")
        fun installation(name: String) = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, name).apply { mkdirs() }
        }
        try {
            val first = InstallationIdentity.get(installation("one"))
            assertEquals(first, UUID.fromString(first).toString())
            assertEquals(first, InstallationIdentity.get(installation("one")))
            assertNotEquals(first, InstallationIdentity.get(installation("two")))
            File(root, "one/installation-uid").writeText("corrupt")
            assertNotEquals(first, InstallationIdentity.get(installation("one")))
        } finally { root.deleteRecursively() }
    }
}
