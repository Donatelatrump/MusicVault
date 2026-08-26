package com.musicvault.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Spotify-inspired dark palette.
 * Near-black backgrounds with layered surfaces + the signature
 * high-contrast green accent for interactive/active states.
 */
object VaultColors {
    val Green = Color(0xFF1ED760)
    val GreenDark = Color(0xFF1AA34A)
    val Background = Color(0xFF000000)
    val SurfaceBase = Color(0xFF121212)
    val Surface1 = Color(0xFF181818)
    val Surface2 = Color(0xFF232323)
    val Surface3 = Color(0xFF2A2A2A)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF7A7A7A)
    val Divider = Color(0xFF2A2A2A)
    val Error = Color(0xFFFF5C5C)
    val Favorite = Color(0xFFFF4D6D)

    // Shelf gradient pairs used across cards/headers for depth without images
    val ShelfGradients = listOf(
        Color(0xFF5038A6) to Color(0xFF121212),
        Color(0xFF1E3264) to Color(0xFF121212),
        Color(0xFF8C1932) to Color(0xFF121212),
        Color(0xFF148A08) to Color(0xFF121212),
        Color(0xFF477D95) to Color(0xFF121212),
        Color(0xFF7358A6) to Color(0xFF121212),
        Color(0xFFB05A1F) to Color(0xFF121212),
    )
}

private val DarkColors = darkColorScheme(
    primary = VaultColors.Green,
    onPrimary = Color(0xFF04150A),
    secondary = VaultColors.TextSecondary,
    background = VaultColors.Background,
    onBackground = VaultColors.TextPrimary,
    surface = VaultColors.Surface1,
    onSurface = VaultColors.TextPrimary,
    surfaceVariant = VaultColors.Surface2,
    onSurfaceVariant = VaultColors.TextSecondary,
    error = VaultColors.Error,
    outline = VaultColors.Divider,
)

private val VaultTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    )
}

@Composable
fun MusicVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = VaultTypography, content = content)
}
