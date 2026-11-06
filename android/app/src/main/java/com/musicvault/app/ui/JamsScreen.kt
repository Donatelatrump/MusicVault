package com.musicvault.app.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.musicvault.app.R
import com.musicvault.app.data.Track
import com.musicvault.app.jams.*
import com.musicvault.app.ui.theme.VaultColors
import com.musicvault.app.ui.theme.VaultIcons
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JamsScreen(jams: JamsController, friends: JamFriendsController, selected: Track?,
    searchSongs: suspend (String) -> List<Track>, onOpenRoom: (String) -> Unit, favoriteIds: Set<String> = emptySet(), playlists: List<com.musicvault.app.data.Playlist> = emptyList(), onAddToPlaylist: (Track, String) -> Unit = { _, _ -> }, onFavorite: (Track) -> Unit = {}, onClose: () -> Unit) {
    val state by jams.state.collectAsStateWithLifecycle()
    val contacts by friends.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val trackFeedback by jams.trackFeedback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showSongs by rememberSaveable { mutableStateOf(false) }
    var showPeople by rememberSaveable { mutableStateOf(false) }
    var invitation by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var leaveConfirmation by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    fun share(link: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link), context.getString(R.string.jams_share)))
    }
    fun invite() { state.roomCode?.let { share(JamInvitation.roomUrl(it)) } }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); if (jams.state.value.message == it) jams.clearMessage() }
    }
    LaunchedEffect(contacts.message) {
        contacts.message?.let { snackbar.showSnackbar(it); if (friends.state.value.message == it) friends.clearMessage() }
    }
    LaunchedEffect(contacts.shareLink) {
        contacts.shareLink?.let { friends.consumeShare(); share(it) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.jams_back)) }
                    Column(Modifier.weight(1f)) {
                        Text("JAMS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (state.active) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(if (state.connected) Color(0xFF9AB88B) else VaultColors.Accent, CircleShape))
                            Text(stringResource(if (state.connected) R.string.jams_room_live else R.string.jams_connecting),
                                style = MaterialTheme.typography.labelSmall, color = VaultColors.TextSecondary)
                        }
                    }
                    BadgedBox(badge = { val count = contacts.requests.size + contacts.invitations.size; if (count > 0) Badge { Text(count.toString()) } }) {
                        IconButton(onClick = { showPeople = true }) { Icon(Icons.Default.Person, stringResource(R.string.jams_friends)) }
                    }
                    state.shortCode?.let { code ->
                        TextButton(onClick = {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("JAMS", code))
                        }) { Text(code.take(3) + " " + code.takeLast(3), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                    }
                    if (state.active) Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.jams_room_options)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (state.isHost) R.string.jams_close else R.string.jams_leave)) },
                                onClick = { more = false; leaveConfirmation = true })
                        }
                    }
                }
            }
            if (state.busy || contacts.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { JamRoomStage(state, onInvite = { if (state.active) invite() else jams.createRoom() }, onMember = { if (it != state.uid) person = it }) }
            if (!state.active) {
                item {
                    Text(stringResource(R.string.jams_lobby_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = jams::createRoom, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.jams_create))
                    }
                    TextButton(onClick = { showJoin = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jams_join_link)) }
                }
            } else {
                item {
                    Button(onClick = ::invite, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.jams_invite_people))
                    }
                }
                item { JamNowPlaying(state, jams) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.jams_queue), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(state.songs.size.toString(), style = MaterialTheme.typography.labelLarge, color = VaultColors.TextSecondary)
                        IconButton(onClick = { showSongs = true }) { Icon(Icons.Default.Add, stringResource(R.string.jams_add_music)) }
                    }
                    if (state.songs.isEmpty()) {
                        OutlinedButton(onClick = { showSongs = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(VaultIcons.LibraryMusic, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.jams_add_music))
                        }
                    }
                }
                items(state.songs, key = { it.key }) { song ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (song.key == state.playback.songKey) VaultColors.AccentSoft else VaultColors.Surface1)
                        .clickable(enabled = !state.busy && state.connected) { jams.playSong(song.key) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage("https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg", null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = VaultColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (song.key == state.playback.songKey) Icon(VaultIcons.GraphicEq, null, tint = VaultColors.Accent)
                        IconButton(onClick = { onFavorite(song.track()) }) {
                            Icon(if (song.track().id in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                stringResource(R.string.jams_like), tint = if (song.track().id in favoriteIds) VaultColors.Accent else VaultColors.TextSecondary)
                        }
                        if (playlists.isNotEmpty()) Box {
                            var choosePlaylist by remember(song.key) { mutableStateOf(false) }
                            IconButton(onClick = { choosePlaylist = true }) { Icon(Icons.Default.Add, stringResource(R.string.jams_add_local_playlist)) }
                            DropdownMenu(expanded = choosePlaylist, onDismissRequest = { choosePlaylist = false }) {
                                playlists.forEach { playlist -> DropdownMenuItem(text = { Text(playlist.title) }, onClick = {
                                    onAddToPlaylist(song.track(), playlist.id); choosePlaylist = false
                                }) }
                            }
                        }
                        IconButton(enabled = !state.busy && state.connected, onClick = { jams.removeSong(song.key) }) { Icon(Icons.Default.Close, stringResource(R.string.jams_remove), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.jams_friends), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showPeople = true }) { Text(stringResource(R.string.jams_view_all)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp).clickable(enabled = !contacts.busy) { friends.shareFriendLink() }) {
                            Box(Modifier.size(54.dp).border(1.dp, VaultColors.Divider, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = VaultColors.Accent) }
                            Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.jams_add_friend), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    items(contacts.friends, key = { it }) { uid -> JamAvatar(uid, guestLabel(uid), false, false, onClick = { person = uid }) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    if (showJoin) AlertDialog(onDismissRequest = { showJoin = false }, title = { Text(stringResource(R.string.jams_join)) },
        text = { OutlinedTextField(invitation, { invitation = it }, label = { Text(stringResource(R.string.jams_six_digit_code)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = invitation.isNotBlank() && !state.busy, onClick = { jams.joinRoom(invitation); showJoin = false }) { Text(stringResource(R.string.jams_join)) } },
        dismissButton = { TextButton(onClick = { showJoin = false }) { Text(stringResource(R.string.jams_cancel)) } })
    if (showSongs) JamSongPicker(searchSongs, selected, trackFeedback, jams::addAndPlay, jams::addTrack) { showSongs = false }
    if (showPeople) ModalBottomSheet(onDismissRequest = { showPeople = false }, containerColor = VaultColors.Surface1) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(stringResource(R.string.jams_friends), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { Button(enabled = !contacts.busy, onClick = friends::shareFriendLink, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.jams_add_friend)) } }
            items(contacts.invitations, key = { "invite:${it.fromUid}" }) { invitation ->
                ContactRow(invitation.fromUid, stringResource(R.string.jams_invites_you)) {
                    TextButton(onClick = { showPeople = false; onOpenRoom(invitation.roomCode); friends.dismissInvite(invitation.fromUid) }, enabled = !state.busy) { Text(stringResource(R.string.jams_join)) }
                    IconButton(onClick = { friends.dismissInvite(invitation.fromUid) }) { Icon(Icons.Default.Close, stringResource(R.string.jams_decline)) }
                }
            }
            if (contacts.requests.isNotEmpty()) item { Text(stringResource(R.string.jams_requests), color = VaultColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
            items(contacts.requests, key = { "request:$it" }) { uid ->
                ContactRow(uid, null) {
                    IconButton(enabled = !contacts.busy, onClick = { friends.accept(uid) }) { Icon(Icons.Default.Check, stringResource(R.string.jams_accept), tint = VaultColors.Accent) }
                    IconButton(enabled = !contacts.busy, onClick = { friends.reject(uid) }) { Icon(Icons.Default.Close, stringResource(R.string.jams_decline)) }
                }
            }
            items(contacts.friends, key = { "friend:$it" }) { uid ->
                ContactRow(uid, null) {
                    if (state.active && uid !in state.members) TextButton(enabled = !contacts.busy, onClick = { friends.inviteToRoom(uid, state.roomCode!!) }) { Text(stringResource(R.string.jams_invite)) }
                    IconButton(onClick = { person = uid }) { Icon(Icons.Default.MoreVert, stringResource(R.string.jams_friend_options)) }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
    person?.let { uid ->
        AlertDialog(onDismissRequest = { person = null }, icon = { JamAvatar(uid, "", false, uid in state.members) {} },
            title = { Text(guestLabel(uid)) }, confirmButton = {
                if (uid in contacts.friends) {
                    if (state.active && uid !in state.members) TextButton(onClick = { friends.inviteToRoom(uid, state.roomCode!!); person = null }) { Text(stringResource(R.string.jams_invite)) }
                    else TextButton(onClick = { friends.remove(uid); person = null }) { Text(stringResource(R.string.jams_remove_friend)) }
                } else if (state.active) TextButton(enabled = !contacts.busy, onClick = { friends.requestFromRoom(uid, state.roomCode!!); person = null }) { Text(stringResource(R.string.jams_add_friend)) }
            }, dismissButton = { TextButton(onClick = { person = null }) { Text(stringResource(R.string.jams_cancel)) } })
    }
    if (leaveConfirmation) AlertDialog(onDismissRequest = { leaveConfirmation = false }, title = { Text(stringResource(if (state.isHost) R.string.jams_close_confirm else R.string.jams_leave)) },
        confirmButton = { TextButton(onClick = { jams.leave(); leaveConfirmation = false }) { Text(stringResource(if (state.isHost) R.string.jams_close else R.string.jams_leave)) } },
        dismissButton = { TextButton(onClick = { leaveConfirmation = false }) { Text(stringResource(R.string.jams_cancel)) } })
}

@Composable
private fun ContactRow(uid: String, subtitle: String?, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        JamFace(uid, Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(guestLabel(uid), fontWeight = FontWeight.SemiBold); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VaultColors.TextSecondary) } }
        actions()
    }
}
private fun guestLabel(uid: String) = "@" + uid.takeLast(6).lowercase()

@Composable
internal fun JamRoomStage(state: JamState, onInvite: () -> Unit, onMember: (String) -> Unit) {
    val self = state.uid ?: state.installationUid
    val people = (listOf(self) + state.members.filterNot { it == self }).distinct()
    val visible = people.take(8)
    val seats = visible.size.coerceAtLeast(4)
    BoxWithConstraints(Modifier.fillMaxWidth().height(278.dp).clip(RoundedCornerShape(28.dp)).background(VaultColors.Surface1)) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, 128.dp.toPx())
            drawOval(VaultColors.Surface2, Offset(size.width * .065f, 61.dp.toPx()), Size(size.width * .87f, 142.dp.toPx()))
            drawOval(VaultColors.Surface3, Offset(size.width * .065f, 61.dp.toPx()), Size(size.width * .87f, 142.dp.toPx()), style = Stroke(1.dp.toPx()))
            repeat(3) { i ->
                val y = (90 + i * 31).dp.toPx()
                drawLine(VaultColors.Divider.copy(alpha = .55f), Offset(size.width * .15f, y), Offset(size.width * .85f, y), 1.dp.toPx())
            }
            drawCircle(VaultColors.Surface3, 47.dp.toPx(), center)
            drawCircle(VaultColors.Accent.copy(alpha = .25f), 47.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
        Row(Modifier.align(Alignment.TopStart).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp), tint = VaultColors.TextSecondary)
            Text(people.size.toString(), style = MaterialTheme.typography.labelMedium, color = VaultColors.TextSecondary)
        }
        val song = state.songs.firstOrNull { it.key == state.playback.songKey }
        Box(Modifier.offset(x = width / 2 - 38.dp, y = 90.dp).size(76.dp).clip(CircleShape).background(VaultColors.Background), contentAlignment = Alignment.Center) {
            if (song != null) AsyncImage("https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg", null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Icon(VaultIcons.Album, null, modifier = Modifier.size(44.dp), tint = VaultColors.Accent)
            Box(Modifier.size(11.dp).background(VaultColors.Surface1, CircleShape).border(1.dp, VaultColors.Accent, CircleShape))
        }
        repeat(seats) { index ->
            val angle = Math.toRadians(90.0 + index * (360.0 / seats))
            val x = width / 2 + width * (.35f * cos(angle).toFloat()) - 34.dp
            val y = (128 + 80 * sin(angle)).dp - 29.dp
            val uid = visible.getOrNull(index)
            if (uid != null) JamAvatar(uid, if (uid == self) stringResource(R.string.jams_you) else guestLabel(uid), uid == self,
                state.connected && state.active, Modifier.offset(x, y)) { onMember(uid) }
            else Column(Modifier.offset(x, y).width(68.dp).clickable(onClick = onInvite), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).border(1.dp, VaultColors.Divider, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, stringResource(R.string.jams_invite), tint = VaultColors.TextTertiary) }

            }
        }
        if (people.size > 8) Text("+${people.size - 8}", Modifier.align(Alignment.TopEnd).padding(18.dp), color = VaultColors.Accent)
    }
}

@Composable
private fun JamAvatar(uid: String, label: String, self: Boolean, online: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(68.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp)) {
            JamFace(uid, Modifier.size(54.dp).align(Alignment.Center).then(if (self) Modifier.border(2.dp, VaultColors.Accent, CircleShape).padding(3.dp) else Modifier))
            if (online) Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(11.dp).background(Color(0xFF9AB88B), CircleShape).border(2.dp, VaultColors.Surface1, CircleShape))
        }
        if (label.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(label, style = MaterialTheme.typography.labelSmall, color = if (self) VaultColors.Accent else VaultColors.TextSecondary, maxLines = 1) }
    }
}

@Composable
private fun JamFace(uid: String, modifier: Modifier = Modifier) {
    val tone = VaultColors.SpineAccents[uid.hashCode().ushr(1) % VaultColors.SpineAccents.size]
    Canvas(modifier.clip(CircleShape).background(tone.copy(alpha = .18f).compositeOver(VaultColors.Surface1))) {
        val w = size.width
        drawCircle(tone, w * .18f, Offset(w * .5f, w * .37f))
        drawOval(tone, Offset(w * .19f, w * .6f), Size(w * .62f, w * .5f))
        drawCircle(VaultColors.Surface1, w * .018f, Offset(w * .44f, w * .36f))
        drawCircle(VaultColors.Surface1, w * .018f, Offset(w * .56f, w * .36f))
    }
}

@Composable
private fun JamNowPlaying(state: JamState, jams: JamsController) {
    val song = state.songs.firstOrNull { it.key == state.playback.songKey }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(VaultColors.Surface1).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(song?.title ?: stringResource(R.string.jams_ready_to_listen), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        song?.let { Text(it.artist, color = VaultColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconButton(enabled = !state.busy && state.connected && state.songs.isNotEmpty(), onClick = jams::previous) { Icon(VaultIcons.SkipPrevious, stringResource(R.string.jams_previous)) }
            FilledIconButton(onClick = jams::toggle, enabled = state.connected && state.songs.isNotEmpty(), modifier = Modifier.size(58.dp)) {
                Icon(if (state.playback.playing) VaultIcons.Pause else Icons.Default.PlayArrow, stringResource(if (state.playback.playing) R.string.jams_pause else R.string.jams_play), modifier = Modifier.size(30.dp))
            }
            IconButton(enabled = !state.busy && state.connected && state.songs.isNotEmpty(), onClick = jams::next) { Icon(VaultIcons.SkipNext, stringResource(R.string.jams_next)) }
            if (state.audioError) IconButton(onClick = jams::retryAudio) { Icon(Icons.Default.Refresh, stringResource(R.string.jams_retry_audio), tint = VaultColors.Error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JamSongPicker(searchSongs: suspend (String) -> List<Track>, selected: Track?, feedback: Map<String, JamTrackFeedback>, play: (Track) -> Unit, add: (Track) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        results = emptyList(); failed = false
        if (query.trim().length < 2) { searching = false; return@LaunchedEffect }
        searching = true
        delay(350)
        try { results = searchSongs(query) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { searching = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VaultColors.Surface1) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.jams_add_music), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.jams_search_music)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp))
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val visible = if (query.isBlank()) listOfNotNull(selected) else results
                items(visible, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { play(track) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(track.coverUri, null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artistName, style = MaterialTheme.typography.bodySmall, color = VaultColors.TextSecondary) }
                        when (feedback[track.id]?.status) {
                            JamTrackStatus.LOADING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            JamTrackStatus.ADDED -> Icon(Icons.Default.Check, stringResource(R.string.jams_track_added), tint = VaultColors.Accent)
                            else -> IconButton(onClick = { add(track) }) { Icon(Icons.Default.Add, stringResource(R.string.jams_add_music)) }
                        }
                    }
                }
                if (failed) item { Text(stringResource(R.string.jams_search_failed), color = VaultColors.Error) }
            }
        }
    }
}
