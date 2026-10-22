package com.musicvault.app.lyrics

import com.musicvault.app.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicvault.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LyricsEditorDialog(track: Track, lyrics: Lyrics?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember(track.id) { mutableStateOf(lyrics?.let(LyricsProvider::buildLrc).orEmpty()) }
    var offset by remember(track.id) { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        withContext(Dispatchers.IO) { LyricsEdits.raw(context, track.id) to LyricsEdits.offsetMs(context, track.id) }.let {
            if (it.first != null) text = it.first!!
            else if (it.second != 0L && lyrics != null) {
                // The player passes displayed, offset-adjusted lines. Store the underlying timeline.
                text = LyricsProvider.buildLrc(lyrics.copy(synced = lyrics.synced.map { line ->
                    line.copy(timeMs = (line.timeMs - it.second).coerceAtLeast(0L))
                }))
            }
            offset = it.second.toString()
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editar letras · ${track.title}") },
        text = { Column {
            Text(context.getString(R.string.ui_texto_o_lrc_mm_ss_xx_un_desfase_positivo_retra_0ea6ad))
            OutlinedTextField(text, { text = it }, label = { Text(context.getString(R.string.ui_letras_c1d0bf)) }, modifier = Modifier.fillMaxWidth().heightIn(180.dp, 320.dp))
            OutlinedTextField(offset, { offset = it }, label = { Text(context.getString(R.string.ui_desfase_en_milisegundos_30000_4332e7)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val value = offset.toLongOrNull()
            if (value == null || value !in -30_000L..30_000L) error = context.getString(R.string.ui_introduce_un_desfase_entre_30000_y_30000_b37390)
            else { saving = true; scope.launch {
                error = withContext(Dispatchers.IO) { LyricsEdits.save(context, track.id, text, value) }
                saving = false
                if (error == null) { onSaved(); onDismiss() }
            } }
        }) { Text(context.getString(R.string.ui_guardar_a0b626)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.ui_cancelar_c111e0)) } })
}
