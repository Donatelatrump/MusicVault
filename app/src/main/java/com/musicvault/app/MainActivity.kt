package com.musicvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.providers.Providers
import com.musicvault.app.ui.MusicVaultApp
import com.musicvault.app.ui.MusicVaultViewModel
import com.musicvault.app.ui.theme.MusicVaultTheme

class MainActivity : ComponentActivity() {
    private val database by lazy { MusicDatabase.get(applicationContext) }
    private val viewModel: MusicVaultViewModel by viewModels {
        MusicVaultViewModel.Factory(applicationContext, database)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Providers.init(applicationContext)


        setContent {
            MusicVaultTheme { MusicVaultApp(viewModel) }
        }
    }
}
