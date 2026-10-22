package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.musicvault.app.diagnostics.LocalDiagnostics
import com.musicvault.app.diagnostics.PerformanceDiagnostics
import com.musicvault.app.diagnostics.DiagnosticsScreen
import com.musicvault.app.providers.ProviderMaintenance
import com.musicvault.app.providers.Providers
import com.musicvault.app.scrobble.ListenBrainz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProviderToolsScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val update by ProviderMaintenance.state.collectAsState()
    val scrobble by ListenBrainz.state.collectAsState()
    val captureFrames by PerformanceDiagnostics.enabled.collectAsState()
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { LocalDiagnostics.export(context, uri); message = context.getString(R.string.ui_diagnostico_guardado_fd5898) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = context.getString(R.string.ui_no_se_pudo_guardar_vuelve_a_elegir_un_document_58452f) }
        }
    }
    LaunchedEffect(Unit) { Providers.init(context); ListenBrainz.initialize(context); PerformanceDiagnostics.initialize(context); PerformanceDiagnostics.setScreen(DiagnosticsScreen.PROVIDER_TOOLS) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onClose) { Text(context.getString(R.string.ui_atras_0e715a)) }
        Text(context.getString(R.string.ui_proveedores_y_privacidad_d73ba0), style = MaterialTheme.typography.headlineSmall)
        Text(context.getString(R.string.ui_extractor_yt_dlp_de6407), style = MaterialTheme.typography.titleMedium)
        Text("Versión: ${update.version ?: "incluida en la aplicación"}")
        Text(update.message)
        Button(enabled = !update.checking, onClick = { scope.launch { ProviderMaintenance.check(context) } }) {
            Text(if (update.checking) context.getString(R.string.ui_comprobando_338dc7) else context.getString(R.string.ui_comprobar_actualizacion_846514))
        }
        Text(context.getString(R.string.ui_la_comprobacion_automatica_ocurre_cada_24_hora_ea62e9))
        HorizontalDivider()
        Text(context.getString(R.string.ui_diagnostico_local_051e59), style = MaterialTheme.typography.titleMedium)
        Text(context.getString(R.string.ui_exporta_version_api_de_android_y_eventos_tecni_521d52))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(context.getString(R.string.ui_medir_fluidez_localmente_c66d9a), modifier = Modifier.weight(1f))
            Switch(checked = captureFrames, onCheckedChange = { PerformanceDiagnostics.setEnabled(context, it) },
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.ui_medir_fluidez_localmente_c66d9a) })
        }
        Text(context.getString(R.string.ui_opcional_guarda_conteos_de_frames_lentos_y_dur_55bb83))
        Button(onClick = { export.launch("MusicVault-diagnostico.json") }) { Text(context.getString(R.string.ui_exportar_diagnostico_2ddcb8)) }
        OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { LocalDiagnostics.clear(context) }; message = context.getString(R.string.ui_eventos_locales_borrados_4c5bfa) } }) { Text(context.getString(R.string.ui_borrar_eventos_locales_fa8f92)) }
        HorizontalDivider()
        Text(stringResource(R.string.ui_listenbrainz_dbc0b3), style = MaterialTheme.typography.titleMedium)
        Text(scrobble.message)
        Text("${scrobble.pending} escuchas pendientes")
        Text(context.getString(R.string.ui_al_conectar_compartes_artista_cancion_album_y_ca5a98))
        if (!scrobble.enabled) {
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(context.getString(R.string.ui_token_de_listenbrainz_08fb14)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(context.getString(R.string.ui_obten_tu_token_en_listenbrainz_org_settings_e_e6cd0c))
            Button(enabled = !connecting && token.isNotBlank(), onClick = {
                scope.launch {
                    connecting = true
                    try { message = ListenBrainz.connect(context, token); token = "" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = if (error is IllegalArgumentException) error.message else context.getString(R.string.ui_no_se_pudo_conectar_comprueba_tu_conexion_y_to_3a4e1a) }
                    finally { connecting = false }
                }
            }) { Text(if (connecting) context.getString(R.string.ui_conectando_dc8abc) else context.getString(R.string.ui_conectar_y_compartir_escuchas_e0adeb)) }
        } else {
            OutlinedButton(onClick = { scope.launch { ListenBrainz.disconnect(context); token = ""; message = context.getString(R.string.ui_listenbrainz_desconectado_6b06cf) } }) { Text(context.getString(R.string.ui_desconectar_y_borrar_cola_bad6da)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
    }
}
