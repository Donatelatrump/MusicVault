@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.musicvault.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.musicvault.app.R

/**
 * Vault identity â€” "BÃ³veda".
 * Warm near-black surfaces (never pure #000000), engraved-brass accent,
 * bone-white type. Archive/technical labels use a monospace. No
 * color-to-black gradients: flat surfaces separated by hairlines.
 */
object VaultColors {
    val Background = Color(0xFF121110)
    val SurfaceBase = Color(0xFF171514)
    val Surface1 = Color(0xFF1E1B18)
    val Surface2 = Color(0xFF292420)
    val Surface3 = Color(0xFF363028)
    val TextPrimary = Color(0xFFF1EAD9)
    val TextSecondary = Color(0xFFB3A992)
    val TextTertiary = Color(0xFF7E7666)
    val Divider = Color(0xFF322C25)
    val Accent = Color(0xFFE0A458)      // latÃ³n
    val AccentDeep = Color(0xFFB97F35)  // pressed
    val AccentSoft = Color(0xFF3A2E1D)  // chip/fondo latÃ³n apagado
    val OnAccent = Color(0xFF191207)    // tinta sobre latÃ³n
    val Error = Color(0xFFD96A55)
    val Favorite = Color(0xFFC96A5E)    // cobre rosado

    // Solid accents used for catalog spines/tags (replaces the old
    // color-to-black shelf gradients).
    val SpineAccents = listOf(
        Color(0xFFE0A458), Color(0xFFC97B4F), Color(0xFF7E93A6),
        Color(0xFF7E8F6E), Color(0xFFA67E93), Color(0xFF5F8B85)
    )
}

private val SpaceGrotesk = FontFamily(
    Font(R.font.spacegrotesk, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.spacegrotesk, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.spacegrotesk, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.spacegrotesk, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val Inter = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val PlexMono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
)

/** Public family handles so composables can mix display/body/mono consistently. */
object VaultFonts {
    val Display = SpaceGrotesk
    val Body = Inter
    val Mono = PlexMono
}

private val VaultTypography = Typography(
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.75).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    // Archive/technical overline: mono, spaced, uppercase-ready.
    labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

private val DarkColors = darkColorScheme(
    primary = VaultColors.Accent,
    onPrimary = VaultColors.OnAccent,
    primaryContainer = VaultColors.AccentSoft,
    onPrimaryContainer = VaultColors.TextPrimary,
    secondary = Color(0xFFC97B4F),
    onSecondary = VaultColors.OnAccent,
    secondaryContainer = VaultColors.Surface2,
    onSecondaryContainer = VaultColors.TextPrimary,
    tertiary = Color(0xFF7E93A6),
    onTertiary = VaultColors.OnAccent,
    background = VaultColors.Background,
    onBackground = VaultColors.TextPrimary,
    surface = VaultColors.Surface1,
    onSurface = VaultColors.TextPrimary,
    surfaceVariant = VaultColors.Surface2,
    onSurfaceVariant = VaultColors.TextSecondary,
    surfaceContainer = VaultColors.Surface1,
    surfaceContainerHigh = VaultColors.Surface2,
    error = VaultColors.Error,
    onError = VaultColors.OnAccent,
    outline = VaultColors.Divider,
    outlineVariant = VaultColors.Divider,
)

@Composable
fun MusicVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = VaultTypography, content = content)
}
