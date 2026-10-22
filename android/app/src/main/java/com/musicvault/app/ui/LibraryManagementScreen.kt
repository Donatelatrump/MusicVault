package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.musicvault.app.backup.*
import com.musicvault.app.data.*
import com.musicvault.app.download.AutoDownloads
import com.musicvault.app.library.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Self-contained SAF, storage and background-library controls. */
@Composable
fun LibraryManagementScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val db = remember { MusicDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val playlists by db.playlistDao().observeAll().collectAsState(initial = emptyList())
    var snapshot by remember { mutableStateOf<LibraryStorage.Snapshot?>(null) }
    var duplicates by remember { mutableStateOf<List<LibraryStorage.Duplicate>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var password by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(context.getString(R.string.ui_playlist_importada_473e02)) }
    var musicTree by remember { mutableStateOf<Uri?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var selectedAuto by remember { mutableStateOf(AutoDownloads.prefs(context).getStringSet("playlists", emptySet())?.toSet() ?: emptySet()) }
    var quota by remember { mutableStateOf(AutoDownloads.prefs(context).getInt("quota_mb", 1024).toString()) }
    var rotation by remember { mutableStateOf(BackupSchedule.prefs(context).getInt("keep", 5).toString()) }
    var interval by remember { mutableStateOf(BackupSchedule.prefs(context).getLong("interval_days", 1).toString()) }
    var confirmPurge by remember { mutableStateOf(false) }
    var removalIds by remember { mutableStateOf<Set<String>?>(null) }
    var scheduleEnabled by remember { mutableStateOf(BackupSchedule.enabled(context)) }
    fun runAction(action: suspend () -> String) {
        if (busy) return
        job = scope.launch {
            busy = true
            try { message = action(); snapshot = LibraryStorage.snapshot(context) }
            catch (cancelled: CancellationException) { message = context.getString(R.string.ui_operacion_cancelada_los_cambios_de_restauracio_9cdc6d); throw cancelled }
            catch (failure: Exception) { message = failure.message ?: context.getString(R.string.ui_no_se_pudo_completar_la_operacion_5f98e8) }
            finally { busy = false }
        }
    }
    fun grant(uri: Uri, write: Boolean = false) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
    }
    val musicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { grant(uri); musicTree = uri }.onFailure { message = context.getString(R.string.ui_no_se_pudo_conservar_el_permiso_de_musica_75380d) }
    }
    val importPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            runCatching { grant(uri) }
            val result = M3uStore.import(context, uri, title, musicTree)
            "${result.imported} canciones importadas; ${result.duplicates} duplicadas; ${result.missing.size} archivos ausentes o sin permiso." +
                result.missing.take(3).joinToString(prefix = if (result.missing.isEmpty()) "" else "\n", separator = "\n")
        }
    }
    val exportPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val id = exportId
        if (uri != null && id != null) runAction { "${M3uStore.export(context, uri, id)} canciones accesibles exportadas en UTF-8." }
    }
    val manualBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runAction {
            val secret = password.toCharArray()
            try { LibraryBackupStore.export(context, uri, secret.takeIf { it.isNotEmpty() }); context.getString(R.string.ui_respaldo_guardado_conserva_la_contrasena_para_6dc2a8) }
            finally { secret.fill('\u0000') }
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            val secret = password.toCharArray()
            try {
                val count = LibraryBackupStore.import(context, uri, secret.takeIf { it.isNotEmpty() }) { done, total -> message = "Restaurando $done de $total registros…" }
                "$count playlists restauradas. Las descargas y permisos existentes se conservan; el respaldo portable contiene metadatos."
            } finally { secret.fill('\u0000') }
        }
    }
    val exportAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runAction {
            val result = ReleaseMigrationStore.exportFromDebug(context, uri) { phase, done, total ->
                message = if (total < 0) "$phase · ${bytes(done)}" else "$phase: $done/$total"
            }
            "${result.audioFiles} archivos de audio y ${result.playlists} playlists exportados."
        }
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            val result = ReleaseMigrationStore.importIntoRelease(context, uri) { phase, done, total ->
                message = if (total < 0) "$phase · ${bytes(done)}" else "$phase: $done/$total"
            }
            "Paquete restaurado: ${result.audioFiles} archivos de audio y ${result.playlists} playlists."
        }
    }
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runAction {
            grant(uri, true)
            val secret = password.toCharArray()
            try { BackupSchedule.configure(context, uri, secret.takeIf { it.isNotEmpty() }, rotation.toInt(), interval.toLong()); scheduleEnabled = true; context.getString(R.string.ui_respaldo_programado_guarda_la_contrasena_fuera_a6f508) }
            finally { secret.fill('\u0000') }
        }
    }
    LaunchedEffect(Unit) { snapshot = LibraryStorage.snapshot(context) }
    BackHandler { onClose() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp)) {
        item { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_volver_84a715)) }; Text(context.getString(R.string.ui_administrar_biblioteca_aa5f86), style = MaterialTheme.typography.headlineSmall) }
        item {
            if (message.isNotBlank()) Text(message)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { job?.cancel() }) { Text(context.getString(R.string.ui_cancelar_operacion_c3745b)) } }
        }
        item {
            Text(context.getString(R.string.ui_playlists_m3u_m3u8_3f7c46), style = MaterialTheme.typography.titleLarge)
            Text(context.getString(R.string.ui_rutas_relativas_selecciona_su_carpeta_de_music_af41e2))
            OutlinedTextField(title, { title = it }, label = { Text(context.getString(R.string.ui_nombre_al_importar_c75b5b)) }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { musicFolder.launch(musicTree) }) { Text(if (musicTree == null) context.getString(R.string.ui_seleccionar_carpeta_de_musica_378000) else context.getString(R.string.ui_cambiar_carpeta_de_musica_bbe520)) }
            Button(enabled = !busy, onClick = { importPlaylist.launch(arrayOf("audio/*", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream")) }) { Text(context.getString(R.string.ui_importar_playlist_76abda)) }
        }
        items(playlists, key = { "export-${it.id}" }) { p ->
            TextButton(enabled = !busy, onClick = { exportId = p.id; exportPlaylist.launch("${p.title.replace(Regex("[^\\p{L}\\p{Nd} _-]"), "_")}.m3u8") }) { Text("Exportar ${p.title}") }
        }
        item {
            HorizontalDivider(); Text(stringResource(R.string.ui_almacenamiento_ff9345), style = MaterialTheme.typography.titleLarge)
            snapshot?.let { Text("Audio: ${bytes(it.bytes)} · Papelera: ${bytes(it.trashBytes)} · ${it.unavailable} archivos sin acceso") }
            Text(context.getString(R.string.ui_el_audio_compartido_se_cuenta_una_sola_vez_ret_9e5c76))
            TextButton(enabled = !busy, onClick = { runAction { "${LibraryStorage.undo(context)} canciones recuperadas de la papelera." } }) { Text(context.getString(R.string.ui_deshacer_retiradas_2886c6)) }
            TextButton(enabled = !busy, onClick = { confirmPurge = true }) { Text(context.getString(R.string.ui_vaciar_papelera_da0939)) }
        }
        items(snapshot?.collections ?: emptyList(), key = { "storage-${it.id}" }) { c ->
            Column {
                Text("${c.title}: ${c.tracks} canciones · ${bytes(c.bytes)} (${bytes(c.sharedBytes)} compartidos)")
                TextButton(enabled = !busy && c.bytes > 0, onClick = { scope.launch {
                    removalIds = (if (c.id == "all") db.trackDao().exportAll() else db.playlistDao().getTracks(c.id)).mapTo(hashSetOf()) { it.id }
                } }) { Text("Retirar audio de ${c.title}") }
            }
        }
        item { Button(enabled = !busy, onClick = { runAction {
            duplicates = LibraryStorage.duplicates(context) { done, total -> message = "Comparando contenido $done de $total…" }
            "${duplicates.size} grupos de archivos idénticos (SHA-256)."
        } }) { Text(context.getString(R.string.ui_detectar_audio_duplicado_f7c3a5)) } }
        items(duplicates, key = { "duplicate-${it.checksum}" }) { d ->
            Column { Text(d.tracks.joinToString("\n") { "${it.artistName} — ${it.title}" }); Text("${bytes(d.bytesEach)} por archivo")
                TextButton(enabled = !busy, onClick = { removalIds = d.tracks.drop(1).mapTo(hashSetOf()) { it.id } }) { Text(context.getString(R.string.ui_conservar_primero_y_retirar_duplicados_cc1514)) } }
        }
        item {
            HorizontalDivider(); Text(context.getString(R.string.ui_descargas_automaticas_595e7f), style = MaterialTheme.typography.titleLarge)
            Text(context.getString(R.string.ui_solo_con_wi_fi_y_cargando_la_seleccion_se_revi_b74ede))
            OutlinedTextField(quota, { quota = it.filter(Char::isDigit) }, label = { Text(context.getString(R.string.ui_cuota_total_de_audio_en_mb_f192e8)) }, modifier = Modifier.fillMaxWidth())
        }
        items((listOf(PlaylistEntity(MusicRepository.FAVORITES_PLAYLIST_ID, "Favoritos", "")) + playlists).distinctBy { it.id }, key = { "auto-${it.id}" }) { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.title, Modifier.weight(1f).padding(vertical = 12.dp))
                Checkbox(p.id in selectedAuto, { checked -> selectedAuto = if (checked) selectedAuto + p.id else selectedAuto - p.id })
            }
        }
        item {
            Button(enabled = !busy, onClick = { runAction { AutoDownloads.configure(context, selectedAuto, quota.toInt()); context.getString(R.string.ui_seleccion_de_descargas_guardada_e78b19) } }) { Text(context.getString(R.string.ui_guardar_descargas_automaticas_420b85)) }
            Text(AutoDownloads.prefs(context).getString("status", context.getString(R.string.ui_sin_ejecucion_todavia_668e6a)) ?: "")
        }
        item {
            HorizontalDivider(); Text(context.getString(R.string.ui_respaldo_portable_60eec3), style = MaterialTheme.typography.titleLarge)
            Text(context.getString(R.string.ui_incluye_biblioteca_orden_favoritos_y_ajustes_p_e8f7c7))
            OutlinedTextField(password, { password = it }, label = { Text(context.getString(R.string.ui_contrasena_opcional_de_restauracion_11812d)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = { manualBackup.launch("MusicVault.mvbackup") }) { Text(context.getString(R.string.ui_guardar_respaldo_ahora_139c46)) }
            TextButton(enabled = !busy, onClick = { restoreBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text(context.getString(R.string.ui_restaurar_respaldo_823a44)) }
            if (context.packageName.endsWith(".debug")) TextButton(enabled = !busy, onClick = { exportAudio.launch("MusicVault-audio.zip") }) { Text(context.getString(R.string.ui_exportar_paquete_con_audio_para_release_e36010)) }
            if (context.packageName == "com.musicvault.app") TextButton(enabled = !busy, onClick = { importAudio.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(context.getString(R.string.ui_restaurar_paquete_con_audio_de_debug_ef76de)) }
            OutlinedTextField(rotation, { rotation = it.filter(Char::isDigit) }, label = { Text(context.getString(R.string.ui_respaldos_a_conservar_130_f76142)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text(context.getString(R.string.ui_intervalo_en_dias_130_5bddb4)) }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = { backupFolder.launch(null) }) { Text(context.getString(R.string.ui_seleccionar_carpeta_y_programar_933499)) }
            if (scheduleEnabled) {
                TextButton(enabled = !busy, onClick = { BackupSchedule.runNow(context); message = context.getString(R.string.ui_respaldo_anadido_a_trabajos_en_segundo_plano_ef33cc) }) { Text(context.getString(R.string.ui_ejecutar_respaldo_programado_cb7db5)) }
                TextButton(enabled = !busy, onClick = { BackupSchedule.disable(context); scheduleEnabled = false; message = context.getString(R.string.ui_respaldo_programado_desactivado_d7808d) }) { Text(context.getString(R.string.ui_desactivar_programacion_18daae)) }
            }
            val prefs = BackupSchedule.prefs(context)
            val last = prefs.getLong("last_success", 0)
            if (last > 0) Text("Último respaldo: ${DateFormat.getDateTimeInstance().format(Date(last))}")
            prefs.getString("error", null)?.let { Text(it) }
        }
    }
    if (confirmPurge) AlertDialog(onDismissRequest = { confirmPurge = false }, title = { Text(context.getString(R.string.ui_vaciar_papelera_da0939)) }, text = { Text(context.getString(R.string.ui_el_audio_retirado_se_eliminara_definitivamente_d67107)) },
        confirmButton = { TextButton(onClick = { confirmPurge = false; runAction { "${bytes(LibraryStorage.emptyTrash(context))} liberados." } }) { Text(context.getString(R.string.ui_eliminar_definitivamente_191f15)) } }, dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text(context.getString(R.string.ui_cancelar_c111e0)) } })
    if (removalIds != null) AlertDialog(onDismissRequest = { removalIds = null }, title = { Text(context.getString(R.string.ui_retirar_audio_f8fdd5)) }, text = { Text(context.getString(R.string.ui_se_guardara_el_audio_privado_seleccionado_en_l_15def6)) },
        confirmButton = { TextButton(onClick = { val ids = removalIds!!; removalIds = null; runAction { val result = LibraryStorage.remove(context, ids); "${result.removed} canciones retiradas; ${result.skipped} externas o compartidas conservadas." } }) { Text(stringResource(R.string.ui_retirar_a8e196)) } }, dismissButton = { TextButton(onClick = { removalIds = null }) { Text(context.getString(R.string.ui_cancelar_c111e0)) } })
}
private fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024) "%.2f GB".format(value / (1024.0 * 1024 * 1024)) else "%.1f MB".format(value / (1024.0 * 1024))
