package com.musicvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.providers.Providers
import com.musicvault.app.ui.MusicVaultApp
import com.musicvault.app.ui.MusicVaultViewModel
import com.musicvault.app.ui.theme.MusicVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // ✅ Lazy database initialization - solo cuando se necesite
    private val database by lazy { 
        MusicDatabase.get(applicationContext)
    }
    
    // ✅ Lazy ViewModel initialization 
    private val viewModel: MusicVaultViewModel by viewModels {
        MusicVaultViewModel.Factory(applicationContext, database)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // ✅ Inicialización no bloqueante de Providers en background
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
