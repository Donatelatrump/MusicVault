package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicvault.app.backup.LibraryBackupStore
import com.musicvault.app.backup.ReleaseMigrationStore
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.download.*
import com.musicvault.app.player.StreamingCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun LibraryToolsSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { MusicDatabase.get(context) }
    val queue by remember { db.downloadQueueDao().observeAll() }.collectAsState(initial = emptyList())
    var streamQuality by remember { mutableStateOf(DownloadPreferences.streamQuality(context)) }
    var quality by remember { mutableStateOf(DownloadPreferences.quality(context)) }
    var wifiOnly by remember { mutableStateOf(DownloadPreferences.wifiOnly(context)) }
    var cacheLimit by remember { mutableIntStateOf(DownloadPreferences.cacheMb(context)) }
    var storage by remember { mutableStateOf(StorageUsage(0, 0, 0)) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> String) {
        scope.launch {
            busy = true
            try { message = action(); revision++ }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: context.getString(R.string.ui_no_se_pudo_completar_la_operacion_5f98e8) }
            finally { busy = false }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform {
            LibraryBackupStore.export(context, uri)
            context.getString(R.string.ui_respaldo_guardado_incluye_biblioteca_playlists_54cdcd)
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val count = LibraryBackupStore.import(context, uri)
            quality = DownloadPreferences.quality(context)
            wifiOnly = DownloadPreferences.wifiOnly(context)
            cacheLimit = DownloadPreferences.cacheMb(context)
            "Respaldo combinado: $count playlists. Vuelve a abrir la app para aplicar todos los ajustes de apariencia y reproducción."
        }
    }
    val migrationExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform {
            val result = ReleaseMigrationStore.exportFromDebug(context, uri)
            "Paquete listo: ${result.audioFiles} audios y ${result.playlists} playlists. Consérvalo hasta verificar la release."
        }
    }
    val migrationImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val result = ReleaseMigrationStore.importIntoRelease(context, uri)
            "Migración recibida: ${result.audioFiles} audios y ${result.playlists} playlists. Comprueba la reproducción antes de quitar debug."
        }
    }
    LaunchedEffect(revision, queue.count { it.state == "complete" }) {
        runCatching { withContext(Dispatchers.IO) { StorageUsage.read(context) } }
            .onSuccess { storage = it }
    }
    SettingsSection(stringResource(R.string.streaming_quality_title)) {
        listOf(DownloadQuality.SAVER, DownloadQuality.BEST).forEach { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = streamQuality == option, onClick = {
                    streamQuality = option
                    DownloadPreferences.prefs(context).edit().putString(DownloadPreferences.STREAM_QUALITY, option.name).apply()
                })
                Text(stringResource(if (option == DownloadQuality.BEST) R.string.streaming_quality_best else R.string.streaming_quality_fast))
            }
        }
    }
    SettingsSection(stringResource(R.string.ui_descargas_2b98b7)) {
        Text(context.getString(R.string.ui_calidad_del_audio_954893), style = MaterialTheme.typography.titleSmall)
        listOf(DownloadQuality.SAVER, DownloadQuality.BEST).forEach { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = quality == option, onClick = {
                    quality = option
                    DownloadPreferences.prefs(context).edit().putString(DownloadPreferences.QUALITY, option.name).apply()
                })
                Text(if (option == DownloadQuality.BEST) stringResource(com.musicvault.app.R.string.ui_mejor_calidad_disponible_719e83) else stringResource(com.musicvault.app.R.string.ui_ahorrar_datos_59622d))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(context.getString(R.string.ui_descargar_solo_por_wi_fi_76a32b), modifier = Modifier.weight(1f))
            Switch(checked = wifiOnly, onCheckedChange = {
                wifiOnly = it
                DownloadPreferences.prefs(context).edit().putBoolean(DownloadPreferences.WIFI_ONLY, it).apply()
            })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showDownloads = true }) {
            Text("Ver descargas · ${queue.count { DownloadQueue.isActive(it.state) }} pendientes")
        }
    }
    SettingsSection(context.getString(R.string.ui_espacio_en_el_dispositivo_b6ef3c)) {
        Text("Caché de streaming: ${formatBytes(storage.cacheBytes)}")
        Text("Audio descargado: ${formatBytes(storage.downloadBytes)}${if (storage.unknownFiles > 0) " (más ${storage.unknownFiles} archivos sin tamaño disponible)" else ""}")
        Spacer(Modifier.height(8.dp))
        Text(context.getString(R.string.ui_limite_de_cache_ee5364), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DownloadPreferences.cacheSizes.forEach { size ->
                FilterChip(selected = cacheLimit == size, enabled = !busy,
                    onClick = { perform {
                        withContext(Dispatchers.IO) { StreamingCache.resize(context, size) }
                        cacheLimit = size
                        context.getString(R.string.ui_limite_de_cache_actualizado_aaff0c)
                    } }, label = { Text(if (size >= 1024) "${size / 1024} GB" else "$size MB") })
            }
        }
        Row {
            TextButton(enabled = !busy && queue.none { DownloadQueue.isActive(it.state) }, onClick = { confirmClear = true }) { Text(context.getString(R.string.ui_vaciar_cache_d6ee09)) }
            TextButton(enabled = !busy, onClick = { revision++ }) { Text(context.getString(R.string.ui_actualizar_espacio_1dd9d2)) }
        }
    }
    SettingsSection(context.getString(R.string.ui_respaldo_de_biblioteca_b8f7e1)) {
        Row {
            TextButton(enabled = !busy, onClick = { export.launch("MusicVault-biblioteca.json") }) { Text(stringResource(R.string.ui_exportar_9a8523)) }
            TextButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.ui_importar_b28a54)) }
        }
    }
    SettingsSection(context.getString(R.string.ui_migrar_de_debug_a_release_5dd37f)) {
        if (context.packageName.endsWith(".debug")) {
            Text(context.getString(R.string.ui_copia_tus_canciones_y_biblioteca_a_un_archivo_e5b8a0))
            TextButton(enabled = !busy && queue.none { DownloadQueue.isActive(it.state) },
                onClick = { migrationExport.launch("MusicVault-migracion.zip") }) {
                Text(context.getString(R.string.ui_crear_paquete_para_release_010a71))
            }
        } else {
            Text(context.getString(R.string.ui_selecciona_el_paquete_creado_en_debug_se_copia_c33db5))
            TextButton(enabled = !busy, onClick = { migrationImport.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                Text(context.getString(R.string.ui_importar_paquete_de_debug_6cbf97))
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (showDownloads) DownloadsDialog(queue = queue, onDismiss = { showDownloads = false })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(context.getString(R.string.ui_vaciar_cache_de_streaming_003106)) },
        text = { Text(context.getString(R.string.ui_se_borrara_el_audio_temporal_tus_canciones_des_7d63e3)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            perform {
                check(!context.getSystemService(AudioManager::class.java).isMusicActive) { context.getString(R.string.ui_pausa_la_reproduccion_antes_de_vaciar_la_cache_31af89) }
                withContext(Dispatchers.IO) { StreamingCache.clearStreamingCache(context) }
                context.getString(R.string.ui_cache_vaciada_109cea)
            }
        }) { Text(stringResource(R.string.ui_vaciar_40a9e5)) } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(context.getString(R.string.ui_cancelar_c111e0)) } })
}

internal fun formatBytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

private data class StorageUsage(val cacheBytes: Long, val downloadBytes: Long, val unknownFiles: Int) {
    companion object {
        suspend fun read(context: Context): StorageUsage {
            val uris = MusicDatabase.get(context).trackDao().exportAll().filter { it.isDownloaded }
                .mapNotNull { it.audioUri }.distinct()
            var total = 0L
            var unknown = 0
            uris.forEach { uri ->
                val length = runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } }.getOrNull()
                if (length == null || length < 0) unknown++ else total += length
            }
            return StorageUsage(StreamingCache.usedBytes(context), total, unknown)
        }
    }
}
