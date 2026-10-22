package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.musicvault.app.cast.CastPlayback
import com.musicvault.app.player.PlayerController
import com.musicvault.app.player.PlaybackRouteState
import java.util.Locale

/** Persisted service-owned audio options and an explicit Chromecast output transfer. */
@Composable
fun PlaybackToolsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { com.musicvault.app.settings.AppPreferences.prefs(context) }
    val player = remember { PlayerController(context) }
    val castResult = remember { runCatching { CastPlayback(context) } }
    val cast = castResult.getOrNull()
    var speed by remember { mutableStateOf(prefs.getFloat("playback_speed", 1f).coerceIn(0.5f, 2f)) }
    var skip by remember { mutableStateOf(prefs.getBoolean("skip_silence", false)) }
    var normalize by remember { mutableStateOf(prefs.getBoolean("normalize_volume", false)) }
    var crossfade by remember { mutableStateOf(prefs.getInt("crossfade_seconds", 5).coerceIn(0, 12).toFloat()) }
    val activeTrack by PlaybackRouteState.track.collectAsState()
    val progress by player.progress.collectAsState()
    DisposableEffect(Unit) { onDispose { player.release(); cast?.release() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(context.getString(R.string.ui_reproduccion_y_dispositivos_5dfd22), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text(context.getString(R.string.ui_cerrar_4b0816)) }
        }
        Text("Velocidad · ${String.format(Locale.getDefault(), "%.2f", speed)}×")
        Slider(speed, { speed = it }, onValueChangeFinished = { prefs.edit().putFloat("playback_speed", speed).apply() }, valueRange = 0.5f..2f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(context.getString(R.string.ui_saltar_silencios_0d406c))
            Switch(skip, { skip = it; prefs.edit().putBoolean("skip_silence", it).apply() })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(context.getString(R.string.ui_replaygain_nivel_local_494134))
            Switch(normalize, { normalize = it; prefs.edit().putBoolean("normalize_volume", it).apply() })
        }
        Text(context.getString(R.string.ui_usa_las_etiquetas_replaygain_de_la_cancion_con_cae69d), style = MaterialTheme.typography.bodySmall)
        Text("Crossfade · ${crossfade.toInt()} s")
        Slider(crossfade, { crossfade = it }, onValueChangeFinished = {
            prefs.edit().putInt("crossfade_seconds", crossfade.toInt()).apply()
        }, valueRange = 0f..12f, steps = 11)
        Text(context.getString(R.string.ui_con_crossfade_en_0_la_cola_preparada_usa_repro_6a3294), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.ui_chromecast_a5ad47), style = MaterialTheme.typography.titleMedium)
        if (cast == null) {
            Text(context.getString(R.string.ui_chromecast_no_esta_disponible_en_este_disposit_f79dc1))
        } else {
            val connected by cast.isConnected.collectAsState()
            val message by cast.message.collectAsState()
            AndroidView(factory = { viewContext -> MediaRouteButton(viewContext).apply {
                contentDescription = context.getString(R.string.ui_elegir_dispositivo_chromecast_ba334c)
                CastButtonFactory.setUpMediaRouteButton(viewContext, this)
            } })
            Text(if (connected) context.getString(R.string.ui_dispositivo_conectado_870755) else context.getString(R.string.ui_elige_un_dispositivo_en_la_misma_red_wi_fi_da60a0))
            if (connected) {
                Button(enabled = activeTrack != null, onClick = { activeTrack?.let { track ->
                    cast.load(track, progress.positionMs) { player.pause() }
                } }) { Text(context.getString(R.string.ui_enviar_cancion_actual_fcccbb)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { cast.toggle() }) { Text(context.getString(R.string.ui_reproducir_pausar_6f5752)) }
                    TextButton(onClick = {
                        val position = cast.positionMs()
                        cast.disconnect()
                        player.seekTo(position)
                        player.resume()
                    }) { Text(context.getString(R.string.ui_volver_al_telefono_3935ed)) }
                }
                TextButton(onClick = { cast.seekTo(0L) }) { Text(context.getString(R.string.ui_reiniciar_cancion_en_chromecast_d3e6a4)) }
            }
            message?.let { Text(it) }
            Text(context.getString(R.string.ui_solo_urls_http_https_accesibles_por_el_recepto_30591e), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text(stringResource(R.string.ui_android_auto_beec0a), style = MaterialTheme.typography.titleMedium)
        Text(context.getString(R.string.ui_conecta_el_telefono_para_navegar_y_reproducir_d7d9d3))
        Text(stringResource(R.string.ui_wear_os_5f0aef), style = MaterialTheme.typography.titleMedium)
        Text(context.getString(R.string.ui_el_reloj_puede_controlar_la_sesion_multimedia_c7fb52))
    }
}
