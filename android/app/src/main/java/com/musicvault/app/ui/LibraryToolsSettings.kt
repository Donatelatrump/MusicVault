package com.musicvault.app.ui

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
            catch (error: Exception) { message = error.message ?: "No se pudo completar la operación." }
            finally { busy = false }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform {
            LibraryBackupStore.export(context, uri)
            "Respaldo guardado. Incluye biblioteca, playlists, favoritos y ajustes; los archivos de audio se conservan aparte."
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
    LaunchedEffect(revision, queue.count { it.state == "complete" }) {
        runCatching { withContext(Dispatchers.IO) { StorageUsage.read(context) } }
            .onSuccess { storage = it }
    }
    SettingsSection("DESCARGAS") {
        Text("Calidad del audio", style = MaterialTheme.typography.titleSmall)
        DownloadQuality.entries.forEach { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = quality == option, onClick = {
                    quality = option
                    DownloadPreferences.prefs(context).edit().putString(DownloadPreferences.QUALITY, option.name).apply()
                })
                Text(option.label)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Descargar solo por Wi-Fi", modifier = Modifier.weight(1f))
            Switch(checked = wifiOnly, onCheckedChange = {
                wifiOnly = it
                DownloadPreferences.prefs(context).edit().putBoolean(DownloadPreferences.WIFI_ONLY, it).apply()
            })
        }
        Text("Se aplica a nuevas descargas y reintentos.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showDownloads = true }) {
            Text("Ver descargas · ${queue.count { DownloadQueue.isActive(it.state) }} pendientes")
        }
    }
    SettingsSection("ESPACIO EN EL DISPOSITIVO") {
        Text("Caché de streaming: ${formatBytes(storage.cacheBytes)}")
        Text("Audio descargado: ${formatBytes(storage.downloadBytes)}${if (storage.unknownFiles > 0) " (más ${storage.unknownFiles} archivos sin tamaño disponible)" else ""}")
        Spacer(Modifier.height(8.dp))
        Text("Límite de caché", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DownloadPreferences.cacheSizes.forEach { size ->
                FilterChip(selected = cacheLimit == size, enabled = !busy,
                    onClick = { perform {
                        withContext(Dispatchers.IO) { StreamingCache.resize(context, size) }
                        cacheLimit = size
                        "Límite de caché actualizado."
                    } }, label = { Text(if (size >= 1024) "${size / 1024} GB" else "$size MB") })
            }
        }
        Text("La caché acelera el streaming. Las canciones descargadas se guardan por separado.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy && queue.none { DownloadQueue.isActive(it.state) }, onClick = { confirmClear = true }) { Text("Vaciar caché") }
            TextButton(enabled = !busy, onClick = { revision++ }) { Text("Actualizar espacio") }
        }
    }
    SettingsSection("RESPALDO DE BIBLIOTECA") {
        Text("Exporta playlists, favoritos, canciones y ajustes. Al importar se combinan con tu biblioteca actual y se restauran los ajustes incluidos.", style = MaterialTheme.typography.bodyMedium)
        Text("Los archivos de audio y las sesiones de Spotify no se incluyen.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy, onClick = { export.launch("MusicVault-biblioteca.json") }) { Text("Exportar") }
            TextButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Importar") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (showDownloads) DownloadsDialog(queue = queue, onDismiss = { showDownloads = false })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Vaciar caché de streaming") },
        text = { Text("Se borrará el audio temporal. Tus canciones descargadas y playlists se conservarán.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            perform {
                check(!context.getSystemService(AudioManager::class.java).isMusicActive) { "Pausa la reproducción antes de vaciar la caché." }
                withContext(Dispatchers.IO) { StreamingCache.clearStreamingCache(context) }
                "Caché vaciada."
            }
        }) { Text("Vaciar") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
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
