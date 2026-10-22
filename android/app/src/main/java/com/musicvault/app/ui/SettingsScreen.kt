package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

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
    vm: MusicVaultViewModel,
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
    var tool by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val offline by vm.offlineMode.collectAsState()
    if (tool != null) {
        androidx.activity.compose.BackHandler { tool = null }
        when (tool) {
            "library" -> LibraryManagementScreen { tool = null }
            "playback" -> PlaybackToolsScreen { tool = null }
            "providers" -> ProviderToolsScreen { tool = null }
        }
        return
    }
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (hybrid) 16.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (hybrid) 12.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ui_atras_0e715a), tint = VaultColors.TextPrimary) }
            Text(stringResource(R.string.ui_ajustes_9751f8), color = VaultColors.TextPrimary, fontSize = if (hybrid) 24.sp else 26.sp, fontWeight = FontWeight.Bold, fontFamily = VaultFonts.Display, letterSpacing = (-0.75).sp)
        }
        SettingsSection(stringResource(R.string.ui_conexion_y_herramientas_baa597)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_modo_sin_conexion_f73a84), modifier = Modifier.weight(1f), color = VaultColors.TextPrimary)
                Switch(checked = offline, onCheckedChange = vm::setOfflineMode)
            }
            Text(stringResource(R.string.ui_busca_y_reproduce_archivos_disponibles_en_este_3d8022), color = VaultColors.TextSecondary)
            TextButton(onClick = { tool = "playback" }) { Text(stringResource(R.string.ui_reproduccion_y_chromecast_1abd2c)) }
            TextButton(onClick = { tool = "library" }) { Text(stringResource(R.string.ui_almacenamiento_m3u_y_respaldos_59647b)) }
            TextButton(onClick = { tool = "providers" }) { Text(stringResource(R.string.ui_proveedores_fallos_y_listenbrainz_801ea2)) }
        }
        SettingsSection(stringResource(R.string.ui_apariencia_0f2c3a)) {
            Text(stringResource(R.string.ui_diseno_de_la_aplicacion_c99ac9), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_puedes_volver_al_diseno_anterior_en_cualquier_3556d6),
                color = VaultColors.TextTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOption(
                    title = stringResource(com.musicvault.app.R.string.ui_hibrido_9cb40a),
                    description = stringResource(com.musicvault.app.R.string.ui_mas_claro_compacto_y_facil_de_recorrer_c1cb4d),
                    icon = Icons.Default.Home,
                    selected = appDesign == APP_DESIGN_HYBRID,
                    onClick = { onAppDesignChange(APP_DESIGN_HYBRID) },
                    modifier = Modifier.weight(1f)
                )
                StyleOption(
                    title = stringResource(com.musicvault.app.R.string.ui_clasico_5fff0e),
                    description = stringResource(com.musicvault.app.R.string.ui_restaura_la_apariencia_anterior_de_musicvault_f239b1),
                    icon = VaultIcons.LibraryMusic,
                    selected = appDesign == APP_DESIGN_CLASSIC,
                    onClick = { onAppDesignChange(APP_DESIGN_CLASSIC) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = VaultColors.Divider)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.ui_estilo_del_reproductor_43172e), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOption(
                    title = stringResource(com.musicvault.app.R.string.ui_tornamesa_vinilo_d11d6f),
                    description = stringResource(com.musicvault.app.R.string.ui_disco_giratorio_con_brazo_control_de_rpm_y_har_6f62f0),
                    icon = VaultIcons.Album,
                    selected = playerStyle == "vinyl",
                    onClick = { onPlayerStyleChange("vinyl") },
                    modifier = Modifier.weight(1f)
                )
                StyleOption(
                    title = stringResource(com.musicvault.app.R.string.ui_visualizador_circular_9a3ecc),
                    description = stringResource(com.musicvault.app.R.string.ui_portada_central_rodeada_por_anillos_de_graves_f92ccd),
                    icon = VaultIcons.GraphicEq,
                    selected = playerStyle == "circular",
                    onClick = { onPlayerStyleChange("circular") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SettingsSection(stringResource(R.string.ui_reproduccion_5e88a8)) {
            Text(stringResource(R.string.ui_crossfade_00acfc), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
        SettingsSection(stringResource(R.string.ui_almacenamiento_a825b8)) {
            Text(stringResource(R.string.ui_ruta_de_descargas_5cb409), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                    Text(stringResource(R.string.ui_elegir_carpeta_6df387), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onUseInternalFolder,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary)
                ) {
                    Text(stringResource(R.string.ui_usar_carpeta_interna_782032), fontSize = 13.sp)
                }
            }
        }
        LibraryToolsSettings()
        SettingsSection(stringResource(R.string.ui_spotify_d41904)) {
            Text(
                if (spotifyConnected) stringResource(com.musicvault.app.R.string.ui_cuenta_conectada_en_este_dispositivo_0995f6) else stringResource(com.musicvault.app.R.string.ui_spotify_no_esta_conectado_cdb07a),
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
                ) { Text(stringResource(R.string.ui_desconectar_spotify_547c56), fontSize = 13.sp) }
            } else {
                Button(
                    onClick = onConnectSpotify,
                    enabled = !spotifyBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    )
                ) { Text(stringResource(R.string.ui_conectar_spotify_ed2de8), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
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
