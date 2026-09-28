package com.musicvault.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicvault.app.ui.theme.VaultColors
import com.musicvault.app.ui.theme.VaultFonts
import com.musicvault.app.ui.theme.VaultIcons

@Composable
internal fun SettingsScreen(
    crossfadeSeconds: Int,
    playerStyle: String,
    appDesign: String,
    downloadDirLabel: String,
    spotifyConnected: Boolean,
    spotifyBusy: Boolean,
    onCrossfadeChange: (Int) -> Unit,
    onPlayerStyleChange: (String) -> Unit,
    onAppDesignChange: (String) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onUseInternalFolder: () -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onClose: () -> Unit
) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (hybrid) 16.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (hybrid) 12.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
            Text("Ajustes", color = VaultColors.TextPrimary, fontSize = if (hybrid) 24.sp else 26.sp, fontWeight = FontWeight.Bold, fontFamily = VaultFonts.Display, letterSpacing = (-0.75).sp)
        }
        SettingsSection("APARIENCIA") {
            Text("Diseño de la aplicación", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Puedes volver al diseño anterior en cualquier momento. Tus canciones y ajustes no cambian.",
                color = VaultColors.TextTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOption(
                    title = "Híbrido",
                    description = "Más claro, compacto y fácil de recorrer.",
                    icon = Icons.Default.Home,
                    selected = appDesign == APP_DESIGN_HYBRID,
                    onClick = { onAppDesignChange(APP_DESIGN_HYBRID) },
                    modifier = Modifier.weight(1f)
                )
                StyleOption(
                    title = "Clásico",
                    description = "Restaura la apariencia anterior de MusicVault.",
                    icon = VaultIcons.LibraryMusic,
                    selected = appDesign == APP_DESIGN_CLASSIC,
                    onClick = { onAppDesignChange(APP_DESIGN_CLASSIC) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = VaultColors.Divider)
            Spacer(Modifier.height(18.dp))
            Text("Estilo del reproductor", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOption(
                    title = "Tornamesa / Vinilo",
                    description = "Disco giratorio con brazo, control de RPM y hardware del deck.",
                    icon = VaultIcons.Album,
                    selected = playerStyle == "vinyl",
                    onClick = { onPlayerStyleChange("vinyl") },
                    modifier = Modifier.weight(1f)
                )
                StyleOption(
                    title = "Visualizador circular",
                    description = "Portada central rodeada por anillos de graves, medios y agudos.",
                    icon = VaultIcons.GraphicEq,
                    selected = playerStyle == "circular",
                    onClick = { onPlayerStyleChange("circular") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SettingsSection("REPRODUCCIÓN") {
            Text("Crossfade", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            // Keep the drag value local so a 60 Hz gesture never recomposes the whole
            // app (crossfadeSeconds feeds the root state combine) nor hits prefs.
            var dragValue by remember { mutableStateOf<Float?>(null) }
            val shownSeconds = dragValue?.toInt() ?: crossfadeSeconds
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = dragValue ?: crossfadeSeconds.toFloat(),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.toInt()?.let(onCrossfadeChange)
                        dragValue = null
                    },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (shownSeconds == 0) "OFF" else "${shownSeconds}s",
                    color = VaultColors.Accent,
                    fontSize = 12.sp,
                    fontFamily = VaultFonts.Mono,
                    modifier = Modifier.width(48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
        SettingsSection("ALMACENAMIENTO") {
            Text("Ruta de descargas", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VaultIcons.FolderOpen, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(downloadDirLabel, color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPickDownloadFolder,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = VaultColors.OnAccent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(VaultIcons.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Elegir carpeta", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onUseInternalFolder,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary)
                ) {
                    Text("Usar carpeta interna", fontSize = 13.sp)
                }
            }
        }
        LibraryToolsSettings()
        SettingsSection("SPOTIFY") {
            Text(
                if (spotifyConnected) "Cuenta conectada en este dispositivo" else "Spotify no está conectado",
                color = VaultColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            if (spotifyConnected) {
                OutlinedButton(
                    onClick = onDisconnectSpotify,
                    enabled = !spotifyBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary)
                ) { Text("Desconectar Spotify", fontSize = 13.sp) }
            } else {
                Button(
                    onClick = onConnectSpotify,
                    enabled = !spotifyBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    )
                ) { Text("Conectar Spotify", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
    }
}
@Composable
internal fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (hybrid) 18.dp else 14.dp))
            .background(if (hybrid) VaultColors.Surface2 else VaultColors.Surface1)
            .border(1.dp, VaultColors.Divider, RoundedCornerShape(if (hybrid) 18.dp else 14.dp))
            .padding(if (hybrid) 18.dp else 16.dp)
    ) {
        Text(title, color = VaultColors.Accent, fontSize = 10.sp, fontFamily = VaultFonts.Mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Selectable card for the player-style setting (two skins, same playback engine). */
@Composable
private fun StyleOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hybrid = LocalHybridDesign.current
    Column(
        modifier
            .clip(RoundedCornerShape(if (hybrid) 16.dp else 12.dp))
            .background(if (selected) VaultColors.AccentSoft else VaultColors.Surface2)
            .border(1.dp, if (selected) VaultColors.Accent else VaultColors.Divider, RoundedCornerShape(if (hybrid) 16.dp else 12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(icon, null, tint = if (selected) VaultColors.Accent else VaultColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = VaultColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(description, color = VaultColors.TextTertiary, fontSize = 10.sp, lineHeight = 13.sp)
    }
}
