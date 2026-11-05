package com.musicvault.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.permissions.StoragePermissions
import com.musicvault.app.providers.Providers
import com.musicvault.app.ui.MusicVaultApp
import com.musicvault.app.ui.MusicVaultViewModel
import com.musicvault.app.ui.theme.MusicVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    // Lazy database initialization - solo cuando se necesite
    private val database by lazy {
        MusicDatabase.get(applicationContext)
    }

    private val viewModel: MusicVaultViewModel by viewModels {
        MusicVaultViewModel.Factory(applicationContext, database)
    }

    /**
     * Lanzador de permisos múltiples. Se invoca en dos momentos:
     *
     *  - [onCreate] si al iniciar faltan permisos de almacenamiento.
     *  - [onResume] si el usuario acaba de volver desde Ajustes / desde el
     *    diálogo del sistema, para reintentar los que sigan pendientes.
     *
     * Si tras dos denegaciones (Android marca el permiso como "no volver a
     * preguntar") el launcher termina con resultados vacíos, el ViewModel
     * recibe [onStoragePermissionResult] con granted=false y evita seguir
     * lanzando diálogos innecesarios.
     */
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val totalRequested = results.size
            val denied = results.values.count { granted -> !granted }
            viewModel.onStoragePermissionResult(
                granted = totalRequested > 0 && denied == 0,
                totalRequested = totalRequested,
                denied = denied
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System-driven splash: branding shows instantly while the process
        // finishes starting, instead of a blank window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) viewModel.openJamLink(intent.dataString)

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
                MusicVaultApp(
                    vm = viewModel,
                    onAppReadyForPermissions = ::requestStoragePermissionsIfNeeded
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) viewModel.openJamLink(intent.dataString)
    }

    override fun onResume() {
        super.onResume()
        com.musicvault.app.diagnostics.PerformanceDiagnostics.onResume(this)
        // Si el usuario volvió desde Ajustes después de habilitar manualmente
        // el permiso, lo volvemos a evaluar para mantener el estado interno
        // del ViewModel sincronizado.
        viewModel.refreshStoragePermissionState()
        // Reassert the playback session when the app returns to the foreground
        // so a background/foreground round-trip cannot leave the session alive
        // but muted on some devices.
        viewModel.refreshPlaybackState()
        requestStoragePermissionsIfNeeded()
    }

    override fun onPause() {
        com.musicvault.app.diagnostics.PerformanceDiagnostics.onPause(this)
        super.onPause()
    }

    override fun onDestroy() {
        com.musicvault.app.diagnostics.PerformanceDiagnostics.onDestroy(this)
        super.onDestroy()
    }

    private fun requestStoragePermissionsIfNeeded() {
        // Do not interrupt first-run onboarding with a system permission dialog.
        if (!viewModel.estadoInicialCargado.value || viewModel.esPrimeraVez.value) return

        // Evita relanzar el diálogo cuando el usuario ya denegó el permiso
        // dos veces (Android marca el permiso como "permanently denied" y el
        // diálogo dejaría de tener efecto). El usuario tendría que ir a
        // Ajustes para rehabilitarlo manualmente.
        if (!viewModel.shouldPromptForStoragePermissions()) return
        val missing = StoragePermissions.missing(this)
        if (missing.isNotEmpty()) {
            storagePermissionLauncher.launch(missing)
        }
    }
}
