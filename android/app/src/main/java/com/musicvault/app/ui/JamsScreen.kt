package com.musicvault.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicvault.app.R
import com.musicvault.app.data.Track
import com.musicvault.app.jams.JamsController
import com.musicvault.app.ui.theme.VaultColors

@Composable
internal fun JamsScreen(jams: JamsController, selected: Track?, onClose: () -> Unit) {
    val state by jams.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var invitation by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("JAMS", style = MaterialTheme.typography.headlineMedium, color = VaultColors.TextPrimary)
            TextButton(onClick = onClose) { Text(stringResource(R.string.jams_back)) }
        }
        Text(stringResource(R.string.jams_description), color = VaultColors.TextSecondary)
        Text(stringResource(R.string.jams_installation_uid, state.installationUid), color = VaultColors.TextSecondary)
        state.message?.let { Text(it, color = VaultColors.Accent) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.active) {
            Button(enabled = !state.busy, onClick = jams::createRoom) { Text(stringResource(R.string.jams_create)) }
            OutlinedTextField(value = invitation, onValueChange = { invitation = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.jams_invitation)) }, singleLine = true)
            Button(enabled = !state.busy && invitation.isNotBlank(), onClick = { jams.joinRoom(invitation) }) {
                Text(stringResource(R.string.jams_join))
            }
        } else {
            Text(stringResource(R.string.jams_members, state.memberCount), color = VaultColors.TextPrimary)
            if (!state.connected) Text(stringResource(R.string.jams_reconnecting), color = VaultColors.Accent)
            else if (!state.hostOnline) Text(stringResource(R.string.jams_host_offline), color = VaultColors.Accent)
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "MusicVault JAMS: ${state.roomCode}"), context.getString(R.string.jams_share)))
            }) { Text(stringResource(R.string.jams_share)) }
            Text(state.roomCode.orEmpty(), color = VaultColors.TextSecondary)
            Text(stringResource(R.string.jams_shared_controls), color = VaultColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !state.busy && state.connected, onClick = jams::previous) { Text(stringResource(R.string.jams_previous)) }
                Button(enabled = !state.busy && state.connected && state.songs.isNotEmpty(), onClick = jams::toggle) {
                    Text(stringResource(if (state.playback.playing) R.string.jams_pause else R.string.jams_play))
                }
                TextButton(enabled = !state.busy && state.connected, onClick = jams::next) { Text(stringResource(R.string.jams_next)) }
            }
            Text(stringResource(R.string.jams_add_hint), color = VaultColors.TextSecondary)
            selected?.let { track ->
                OutlinedButton(enabled = !state.busy && state.connected, onClick = { jams.addTrack(track) }) {
                    Text(stringResource(R.string.jams_add_selected, track.title))
                }
            }
            TextButton(enabled = state.connected, onClick = jams::retryAudio) { Text(stringResource(R.string.jams_retry_audio)) }
            HorizontalDivider()
            Text(stringResource(R.string.jams_queue), color = VaultColors.TextPrimary)
            state.songs.forEach { song ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.weight(1f), enabled = !state.busy && state.connected,
                        onClick = { jams.playSong(song.key) }) {
                        Text((if (song.key == state.playback.songKey) "▶ " else "") + "${song.title} · ${song.artist}")
                    }
                    if (state.isHost && song.key != state.playback.songKey) TextButton(enabled = !state.busy,
                        onClick = { jams.removeSong(song.key) }) { Text(stringResource(R.string.jams_remove)) }
                }
            }
            OutlinedButton(enabled = !state.busy, onClick = jams::leave) {
                Text(stringResource(if (state.isHost) R.string.jams_close else R.string.jams_leave))
            }
        }
    }
}
