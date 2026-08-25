package com.musicvault.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    secondary = Color(0xFFB9C5E8),
    background = Color(0xFF090B0F),
    surface = Color(0xFF101319)
)

@Composable
fun MusicVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
