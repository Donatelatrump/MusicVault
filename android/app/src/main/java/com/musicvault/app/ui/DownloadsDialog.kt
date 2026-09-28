package com.musicvault.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musicvault.app.data.DownloadQueueEntity
import com.musicvault.app.download.DownloadQueue
import kotlinx.coroutines.launch

@Composable
internal fun DownloadsDialog(queue: List<DownloadQueueEntity>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { message = "No se pudo actualizar la cola: ${e.message.orEmpty()}" }
            finally { busy = false }
        }
    }
    val shown = queue.filter { when (filter) {
        0 -> DownloadQueue.isActive(it.state)
        1 -> it.state == "complete"
        else -> it.state == "failed" || it.state == "cancelled"
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Descargas", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                ScrollableTabRow(selectedTabIndex = filter, edgePadding = 0.dp) {
                    listOf("Pendientes", "Completadas", "Fallidas / canceladas").forEachIndexed { index, label ->
                        Tab(selected = filter == index, onClick = { filter = index }, text = { Text(label) })
                    }
                }
                TextButton(enabled = !busy && queue.any { it.state == "failed" }, onClick = { perform { DownloadQueue.retryFailed(context) } }) {
                    Text("Reintentar fallidas")
                }
                Text("Las pendientes se recuperan al volver la conexión. Tras forzar el cierre de la app, vuelve a abrirla para continuar.", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (shown.isEmpty()) Text("No hay descargas en esta sección.", Modifier.padding(vertical = 20.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shown, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.track.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.track.artistName, style = MaterialTheme.typography.bodySmall)
                                val label = when (item.state) {
                                    "queued" -> if (item.wifiOnly) "En cola · requiere Wi-Fi" else "En cola · esperando conexión o turno"
                                    "running" -> "Descargando ${item.progress}%"
                                    "waiting" -> "Pendiente de reintento"
                                    "complete" -> "Completada"
                                    "cancelled" -> "Cancelada"
                                    else -> "No se pudo descargar"
                                }
                                Text(label)
                                item.error?.let { Text(it, maxLines = 4, style = MaterialTheme.typography.bodySmall) }
                                if (item.state == "running") LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                if (DownloadQueue.isActive(item.state)) TextButton(enabled = !busy, onClick = { perform { DownloadQueue.cancel(context, item.id) } }) { Text("Cancelar") }
                                else if (item.state != "complete") TextButton(enabled = !busy, onClick = { perform { DownloadQueue.retry(context, item.id) } }) { Text("Reintentar") }
                            }
                        }
                    }
                }
            }
        }
    }
}
