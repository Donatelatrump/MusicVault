package com.musicvault.app.dj

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import com.musicvault.app.MainActivity
import com.musicvault.app.ui.onboarding.OnboardingScreen
import com.musicvault.app.ui.theme.MusicVaultTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ADB-driven visual test; replaces only the view tree, never clears the app's profile. */
class OnboardingVisualFlowTest {
    @Test fun completeGenreAndArtistSelection() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("musicvault.visualAdb") == "true")
        val completed = CountDownLatch(1)
        var genres = emptySet<String>()
        var artists = emptySet<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    MusicVaultTheme {
                        OnboardingScreen(
                            onCompleto = { g, a -> genres = g; artists = a; completed.countDown() },
                            onSkip = { completed.countDown() }
                        )
                    }
                }
            }
            assertTrue("ADB onboarding flow was not completed", completed.await(240, TimeUnit.SECONDS))
            assertTrue("No genres selected", genres.isNotEmpty())
            assertTrue("No artists selected", artists.isNotEmpty())
        }
    }
}
