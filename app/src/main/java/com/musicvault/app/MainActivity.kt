package com.musicvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.providers.Providers
import com.musicvault.app.ui.MusicVaultApp
import com.musicvault.app.ui.MusicVaultViewModel
import com.musicvault.app.ui.theme.MusicVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Lazy database initialization - solo cuando se necesite
    private val database by lazy {
        MusicDatabase.get(applicationContext)
    }

    private val viewModel: MusicVaultViewModel by viewModels {
        MusicVaultViewModel.Factory(applicationContext, database)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System-driven splash: branding shows instantly while the process
        // finishes starting, instead of a blank window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Warm the font files off the main thread so the first frame never
        // pays for reading/parsing the TTFs (runs in parallel with Providers).
        lifecycleScope.launch(Dispatchers.Default) {
            listOf(
                R.font.spacegrotesk,
                R.font.inter,
                R.font.plexmono_regular,
                R.font.plexmono_medium
            ).forEach { id ->
                runCatching { androidx.core.content.res.ResourcesCompat.getFont(applicationContext, id) }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            Providers.init(applicationContext)
        }

        setContent {
            MusicVaultTheme {
                MusicVaultApp(viewModel)
            }
        }
    }
}
