package com.musicvault.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musicvault.app.data.Album
import com.musicvault.app.data.Playlist
import com.musicvault.app.data.Track
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogTrack

@Composable
fun MusicVaultApp(vm: MusicVaultViewModel) {
    val state by vm.state.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val playbackProgress by vm.playbackProgress.collectAsState()
    val queue by vm.queue.collectAsState()
    val queueIndex by vm.queueIndex.collectAsState()
    val favoriteIds = state.favoriteIds
    val equalizerBands by vm.equalizerBands.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF090B0F),
        bottomBar = {
            if (!state.fullPlayer && !showSettings) {
                Column {
                    state.selectedTrack?.let { MiniPlayer(it, isPlaying, vm::togglePlayback, vm::openFullPlayer, it.id in favoriteIds, { vm.toggleFavorite(it) }) }
                    BottomBar(state.section, vm::selectSection)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showSettings && !state.fullPlayer) {
                SettingsScreen(
                    crossfadeSeconds = state.crossfadeSeconds,
                    equalizerBands = equalizerBands,
                    onCrossfadeChange = vm::setCrossfadeSeconds,
                    onEqualizerEnabled = vm::setEqualizerEnabled,
                    onEqualizerBand = vm::setEqualizerBand,
                    onClose = { showSettings = false }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                state.section == AppSection.SEARCH -> "Buscar"
                                state.section == AppSection.LIBRARY -> "Biblioteca"
                                state.section == AppSection.PLAYLISTS -> "Listas"
                                else -> "MusicVault"
                            },
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, "Ajustes", tint = Color.White)
                        }
                    }
                    state.errorMessage?.let {
                        AssistChip(
                            onClick = vm::clearError,
                            label = { Text(it, maxLines = 3) },
                            trailingIcon = { Icon(Icons.Default.Close, null) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    when {
                        state.selectedLocalAlbum != null -> LocalAlbumDetail(state.selectedLocalAlbum!!, state.selectedLocalAlbumTracks, downloadProgress, isPlaying, state.selectedTrack?.id, state.playlists, state.albums, vm)
                        state.selectedRemoteAlbum != null -> RemoteAlbumDetail(
                            album = state.selectedRemoteAlbum!!,
                            tracks = state.remoteAlbumTracks,
                            downloadProgress = downloadProgress,
                            isPlaying = isPlaying,
                            selectedTrackId = state.selectedTrack?.id,
                            loading = state.isLoadingRemoteAlbum,
                            localTracks = state.tracks,
                            playlists = state.playlists,
                            albums = state.albums,
                            vm = vm
                        )
                        state.selectedPlaylist != null -> PlaylistDetail(
                            playlist = state.selectedPlaylist!!,
                            tracks = state.selectedPlaylistTracks,
                            downloadProgress = downloadProgress,
                            isPlaying = isPlaying,
                            selectedTrackId = state.selectedTrack?.id,
                            playlists = state.playlists,
                            albums = state.albums,
                            vm = vm
                        )
                        else -> when (state.section) {
                            AppSection.HOME -> HomeScreen(state, downloadProgress, isPlaying, vm)
                            AppSection.SEARCH -> SearchScreen(state, downloadProgress, isPlaying, vm)
                            AppSection.LIBRARY -> LibraryScreen(state, downloadProgress, isPlaying, vm)
                            AppSection.PLAYLISTS -> PlaylistScreen(state, vm)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            modifier = Modifier.fillMaxSize(),
            visible = state.fullPlayer && state.selectedTrack != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180))
        ) {
            state.selectedTrack?.let { track ->
                FullPlayerScreen(track, isPlaying, playbackProgress, state.shuffle, queue, queueIndex, favoriteIds, vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: MusicUiState, downloadProgress: Map<String, Int>, isPlaying: Boolean, vm: MusicVaultViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Text("Buenas tardes", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Tu música, sin depender de streaming.", color = Color(0xFF9AA1AA))
        }
        item {
            SectionTitle("Hecho para ti")
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.albums, key = { it.id }) { AlbumCard(it) { vm.openLocalAlbum(it) } }
            }
        }
        item {
            SectionTitle("Playlists recomendadas")
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.playlists, key = { it.id }) { PlaylistCard(it) { vm.openPlaylist(it) } }
            }
        }
        item { SectionTitle("Tus canciones") }
        items(state.tracks, key = { it.id }) { track ->
            TrackRow(track, onClick = { vm.selectTrack(track) }, progress = downloadProgress[track.id], onCancelDownload = { vm.cancelDownload(track.id) }, isPlaying = isPlaying && state.selectedTrack?.id == track.id, onPlayToggle = { if (state.selectedTrack?.id == track.id) vm.togglePlayback() else vm.selectTrack(track) }, playlists = state.playlists, albums = state.albums, vm = vm)
        }
    }
}


@Composable
private fun SearchScreen(state: MusicUiState, downloadProgress: Map<String, Int>, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val downloadedIds = remember(state.tracks) {
        state.tracks.filter { it.isDownloaded }.mapTo(HashSet()) { it.id }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        OutlinedTextField(
            value = state.search,
            onValueChange = vm::search,
            Modifier.fillMaxWidth(),
            placeholder = { Text("Canción, artista o álbum") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        if (state.isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.remoteAlbums.isNotEmpty()) {
                item {
                    SectionTitle("Álbumes")
                    Spacer(Modifier.height(8.dp))
                }
                items(state.remoteAlbums, key = { "${it.provider}:${it.id}" }) { album ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.openRemoteAlbum(album) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(50.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1B2029)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (album.coverUri != null) {
                                AsyncImage(
                                    model = album.coverUri,
                                    contentDescription = album.title,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.Album, null, tint = Color(0xFF9EA7B5))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.title, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(
                                "${album.artistName}${album.year?.let { " · $it" } ?: ""}",
                                color = Color(0xFF858D99), fontSize = 12.sp, maxLines = 1
                            )
                        }
                        Icon(Icons.Default.ChevronRight, "Abrir álbum", tint = Color(0xFF858D99))
                    }
                }
            }
            if (state.remoteTracks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("Canciones")
                    Spacer(Modifier.height(8.dp))
                }
                items(state.remoteTracks, key = { "${it.provider}:${it.id}" }) { track ->
                    val localId = "${track.provider}:recording:${track.id}"
                    val downloaded = localId in downloadedIds
                    RemoteTrackRow(
                        track = track,
                        progress = downloadProgress[localId],
                        downloaded = downloaded,
                        onClick = { vm.playRemote(track) },
                        onDownload = { vm.requestRemoteDownload(track) },
                        onCancelDownload = { vm.cancelDownload(localId) },
                        playlists = state.playlists,
                        albums = state.albums,
                        vm = vm,
                        isPlaying = isPlaying && state.selectedTrack?.id == localId,
                        onPlayToggle = {
                            if (isPlaying && state.selectedTrack?.id == localId) vm.togglePlayback()
                            else vm.playRemote(track)
                        }
                    )
                }
            }
            if (!state.isSearching && state.search.isNotBlank() &&
                state.remoteTracks.isEmpty() && state.remoteAlbums.isEmpty()) {
                item {
                    Text("No se encontraron resultados.", color = Color(0xFF9299A4))
                }
            }
        }
    }
}

@Composable
private fun RemoteTrackRow(
    track: com.musicvault.app.catalog.CatalogTrack,
    progress: Int?,
    downloaded: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit = {},
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    playlists: List<Playlist> = emptyList(),
    albums: List<Album> = emptyList(),
    vm: MusicVaultViewModel? = null,
    favorite: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.coverUri,
            contentDescription = track.albumTitle,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)),
            placeholder = null,
            error = null
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(
                "${track.artistName} · ${track.albumTitle ?: "Álbum desconocido"}",
                color = Color(0xFF858D99), fontSize = 12.sp, maxLines = 1
            )
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFF8BCF9B),
                    trackColor = Color(0xFF303640)
                )
            }
        }
        IconButton(onClick = onPlayToggle) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Reproducir", tint = Color.White)
        }
        if (downloaded) {
            Icon(Icons.Default.CheckCircle, "Descargada", tint = Color(0xFF8BCF9B))
        } else if (progress != null) {
            IconButton(onClick = onCancelDownload) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF8BCF9B)
                )
            }
        }
        if (vm != null) {
            RemoteTrackActionsMenu(track, playlists, albums.filter { it.id.startsWith("album:custom:") }, vm)
        }
    }
}

@Composable
private fun LibraryScreen(state: MusicUiState, downloadProgress: Map<String, Int>, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.scanLocalAudio(context)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Biblioteca", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Archivos locales y contenido descargado", color = Color(0xFF9AA1AA))
        }
        item {
            Button(onClick = {
                val permission = if (Build.VERSION.SDK_INT >= 33)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
                launcher.launch(permission)
            }) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isScanning) "Escaneando..." else "Escanear música del dispositivo")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Disponibles", state.downloaded.size.toString())
                StatCard("Álbumes", state.albums.size.toString())
            }
        }
        items(state.downloaded, key = { it.id }) {
            TrackRow(
                track = it,
                onClick = { vm.selectTrack(it) },
                progress = downloadProgress[it.id],
                isPlaying = isPlaying && state.selectedTrack?.id == it.id,
                onPlayToggle = { if (state.selectedTrack?.id == it.id) vm.togglePlayback() else vm.selectTrack(it) },
                onCancelDownload = { vm.cancelDownload(it.id) },
                playlists = state.playlists,
                albums = state.albums,
                vm = vm
            )
        }
    }
}

@Composable
private fun PlaylistScreen(state: MusicUiState, vm: MusicVaultViewModel) {
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showAlbumDialog by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colecciones", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Crea tus propias listas y álbumes locales", color = Color(0xFF9AA1AA))
                }
                IconButton(onClick = { showPlaylistDialog = true }) { Icon(Icons.Default.QueueMusic, "Nueva playlist", tint = Color.White) }
                IconButton(onClick = { showAlbumDialog = true }) { Icon(Icons.Default.Album, "Nuevo álbum", tint = Color.White) }
            }
        }
        item { SectionTitle("Playlists") }
        items(state.playlists, key = { it.id }) { PlaylistCard(it) { vm.openPlaylist(it) } }
        item { SectionTitle("Álbumes") }
        items(state.albums, key = { it.id }) { AlbumCard(it) { vm.openLocalAlbum(it) } }
    }
    if (showPlaylistDialog) {
        CreateCollectionDialog("Nueva playlist", "Nombre de la playlist", state.downloaded, vm::createPlaylist) { showPlaylistDialog = false }
    }
    if (showAlbumDialog) {
        CreateCollectionDialog("Nuevo álbum", "Nombre del álbum", state.downloaded, vm::createAlbum) { showAlbumDialog = false }
    }
}

@Composable
private fun CreateCollectionDialog(
    title: String,
    placeholder: String,
    tracks: List<Track>,
    onCreate: (String, List<Track>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(name, { name = it }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Canciones", fontWeight = FontWeight.Bold)
                LazyColumn {
                    items(tracks, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().clickable { if (selected.contains(track.id)) selected.remove(track.id) else selected.add(track.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected.contains(track.id), { checked -> if (checked) selected.add(track.id) else selected.remove(track.id) })
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1)
                                Text(track.artistName, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, tracks.filter { selected.contains(it.id) }); onDismiss() }, enabled = name.isNotBlank()) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun LocalAlbumDetail(album: Album, tracks: List<Track>, downloadProgress: Map<String, Int>, isPlaying: Boolean, selectedTrackId: String?, playlists: List<Playlist>, albums: List<Album>, vm: MusicVaultViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            IconButton(onClick = vm::closeLocalAlbum) { Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2029)), contentAlignment = Alignment.Center) {
                    if (album.coverUri != null) AsyncImage(album.coverUri, album.title, Modifier.fillMaxSize()) else Icon(Icons.Default.Album, null, tint = Color.White, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column { Text(album.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(album.artistName, color = Color(0xFF9AA1AA)); if (album.year > 0) Text(album.year.toString(), color = Color.Gray) }
            }
            Spacer(Modifier.height(18.dp))
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(track, { vm.selectTrack(track) }, downloadProgress[track.id], isPlaying && selectedTrackId == track.id, { if (selectedTrackId == track.id) vm.togglePlayback() else vm.selectTrack(track) }, { vm.cancelDownload(track.id) }, playlists, albums, vm)
        }
    }
}

@Composable
private fun RemoteAlbumDetail(
    album: CatalogAlbum,
    tracks: List<CatalogTrack>,
    downloadProgress: Map<String, Int>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    loading: Boolean,
    localTracks: List<Track>,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val downloadedIds = remember(localTracks) {
        localTracks.filter { it.isDownloaded }.mapTo(HashSet()) { it.id }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            IconButton(onClick = vm::closeRemoteAlbum) {
                Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(128.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1B2029)),
                    contentAlignment = Alignment.Center
                ) {
                    if (album.coverUri != null) {
                        AsyncImage(
                            model = album.coverUri,
                            contentDescription = album.title,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Album, null, tint = Color(0xFF9EA7B5), modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(album.artistName, color = Color(0xFF9AA1AA), fontSize = 14.sp)
                    album.year?.let { Text(it.toString(), color = Color(0xFF707987), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                tracks.isEmpty() -> Text("No hay canciones disponibles para este álbum.", color = Color(0xFF9299A4))
            }
            Spacer(Modifier.height(10.dp))
        }
        items(tracks, key = { "${it.provider}:${it.id}" }) { track ->
            val localId = "${track.provider}:recording:${track.id}"
            val downloaded = localId in downloadedIds
            RemoteTrackRow(
                track = track,
                progress = downloadProgress[localId],
                downloaded = downloaded,
                onClick = { vm.playRemote(track) },
                onDownload = { vm.requestRemoteDownload(track) },
                onCancelDownload = { vm.cancelDownload(localId) },
                playlists = playlists,
                albums = albums,
                vm = vm,
                isPlaying = isPlaying && selectedTrackId == localId,
                onPlayToggle = {
                    if (isPlaying && selectedTrackId == localId) vm.togglePlayback()
                    else vm.playRemote(track)
                }
            )
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    downloadProgress: Map<String, Int>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp)
    ) {
        item {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White)
            }
            Text(playlist.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(playlist.description, color = Color(0xFF9AA1AA))
            Spacer(Modifier.height(20.dp))
        }
        items(
            items = tracks,
            key = { it.id }
        ) { track ->
            TrackRow(
                track = track,
                onClick = { vm.selectTrack(track) },
                progress = downloadProgress[track.id],
                isPlaying = isPlaying && selectedTrackId == track.id,
                onPlayToggle = { if (selectedTrackId == track.id) vm.togglePlayback() else vm.selectTrack(track) },
                onCancelDownload = { vm.cancelDownload(track.id) },
                playlists = playlists,
                albums = albums,
                vm = vm
            )
        }
    }
}

@Composable
private fun MiniPlayer(track: Track, isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, favorite: Boolean = false, onFavorite: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF171B22)).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(track.coverUri, track.title, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(track.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium); Text(track.artistName, color = Color(0xFF8D95A1), fontSize = 12.sp) }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Me gusta", tint = if (favorite) Color(0xFFFF5C7A) else Color.White) }
        IconButton(onClick = onToggle) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
    }
}

@Composable
private fun FullPlayerScreen(track: Track, isPlaying: Boolean, progress: com.musicvault.app.player.PlayerController.PlaybackProgress, shuffle: Boolean, queue: List<Track>, queueIndex: Int, favoriteIds: Set<String>, vm: MusicVaultViewModel) {
    val scale by animateFloatAsState(if (isPlaying) 1f else 0.96f, tween(450, easing = FastOutSlowInEasing), label = "coverScale")
    val duration = progress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val position = progress.positionMs.coerceIn(0L, duration)
    var sliderPosition by remember(track.id) { mutableFloatStateOf(position.toFloat()) }
    var sliderDragging by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(position, track.id) { if (!sliderDragging) sliderPosition = position.toFloat() }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF202735), Color(0xFF090B0F)))).padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeFullPlayer) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Text("REPRODUCIENDO", color = Color(0xFFB8C0CC), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::toggleShuffle) {
                Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (shuffle) Color(0xFF8BCF9B) else Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AsyncImage(track.coverUri, track.title, Modifier.fillMaxWidth().aspectRatio(1f).scale(scale).clip(RoundedCornerShape(22.dp)))
        }
        Spacer(Modifier.height(24.dp))
        Text(track.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(track.artistName, color = Color(0xFF9AA1AA), fontSize = 16.sp, maxLines = 1)
        Spacer(Modifier.height(18.dp))
        Slider(
            value = sliderPosition,
            onValueChange = { sliderDragging = true; sliderPosition = it },
            onValueChangeFinished = { sliderDragging = false; vm.seekTo(sliderPosition.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(if (sliderDragging) sliderPosition.toLong() else position), color = Color.Gray, fontSize = 12.sp); Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previousTrack, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(38.dp)) }
            Spacer(Modifier.width(18.dp))
            FilledIconButton(onClick = vm::togglePlayback, modifier = Modifier.size(72.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = vm::nextTrack, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipNext, "Siguiente", tint = Color.White, modifier = Modifier.size(38.dp)) }
        }
    }
}

@Composable
private fun SettingsScreen(
    crossfadeSeconds: Int,
    equalizerBands: List<com.musicvault.app.player.NativeEqualizer.Band>,
    onCrossfadeChange: (Int) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerBand: (Int, Short) -> Unit,
    onClose: () -> Unit
) {
    var eqEnabled by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Atrás", tint = Color.White) }
                Text("Ajustes", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("Audio y reproducción", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Crossfade", color = Color(0xFF9AA1AA))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = crossfadeSeconds.toFloat(), onValueChange = { onCrossfadeChange(it.toInt()) }, valueRange = 0f..12f, steps = 11, modifier = Modifier.weight(1f))
                Text("${crossfadeSeconds}s", color = Color.White, modifier = Modifier.width(42.dp))
            }
        }
        item {
            Text("Ecualizador nativo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (equalizerBands.isEmpty()) "Esperando sesión de audio..." else "Bandas: ${equalizerBands.size}", color = Color(0xFF9AA1AA), modifier = Modifier.weight(1f))
                Switch(checked = eqEnabled, onCheckedChange = { eqEnabled = it; onEqualizerEnabled(it) })
            }
            equalizerBands.forEach { band ->
                Column {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${band.centerFrequencyHz} Hz", color = Color.White, fontSize = 12.sp)
                        Text("${band.level / 100f} dB", color = Color(0xFF9AA1AA), fontSize = 12.sp)
                    }
                    Slider(value = band.level.toFloat(), onValueChange = { onEqualizerBand(band.index, it.toInt().toShort()) }, valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat())
                }
            }
        }
        item {
            Text("El ecualizador usa el efecto Equalizer nativo de Android y la sesión de audio de Media3.", color = Color(0xFF707987), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(queue: List<Track>, currentIndex: Int, vm: MusicVaultViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF151922)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("A continuación", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (queue.isEmpty()) {
                Text("La cola está vacía.", color = Color(0xFF9299A4), modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DragHandle, "Reordenar", tint = Color(0xFF7E8795))
                            AsyncImage(track.coverUri, track.title, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = if (index == currentIndex) Color(0xFF8BCF9B) else Color.White, maxLines = 1)
                                Text(track.artistName, color = Color(0xFF858D99), fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(enabled = index > 0, onClick = { vm.moveQueueItem(index, index - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Subir", tint = Color.White) }
                            IconButton(enabled = index < queue.lastIndex, onClick = { vm.moveQueueItem(index, index + 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Bajar", tint = Color.White) }
                            IconButton(onClick = { vm.removeFromQueue(index) }) { Icon(Icons.Default.DeleteOutline, "Quitar", tint = Color(0xFFFF7D8E)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

@Composable
private fun BottomBar(section: AppSection, onSelect: (AppSection) -> Unit) {
    NavigationBar(containerColor = Color(0xFF101319)) {
        NavigationBarItem(section == AppSection.HOME, { onSelect(AppSection.HOME) }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
        NavigationBarItem(section == AppSection.SEARCH, { onSelect(AppSection.SEARCH) }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Buscar") })
        NavigationBarItem(section == AppSection.LIBRARY, { onSelect(AppSection.LIBRARY) }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Biblioteca") })
        NavigationBarItem(section == AppSection.PLAYLISTS, { onSelect(AppSection.PLAYLISTS) }, icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Listas") })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit = {}) {
    Column(Modifier.width(150.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(150.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF252B38), Color(0xFF12151C)))),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Album, null, tint = Color(0xFF9FA8B7), modifier = Modifier.size(54.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(album.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(album.artistName, color = Color(0xFF9299A4), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        Modifier.width(190.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151922)).clickable(onClick = onClick).padding(14.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF303847), Color(0xFF171B23)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(playlist.title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(playlist.description, color = Color(0xFF9299A4), fontSize = 12.sp, maxLines = 2)
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    progress: Int? = null,
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    onCancelDownload: () -> Unit = {},
    playlists: List<Playlist> = emptyList(),
    albums: List<Album> = emptyList(),
    vm: MusicVaultViewModel? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.coverUri,
            contentDescription = track.albumTitle,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(9.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.Medium)
            Text("${track.artistName} · ${track.albumTitle}", color = Color(0xFF858D99), fontSize = 12.sp)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFF8BCF9B),
                    trackColor = Color(0xFF303640)
                )
            }
        }
        IconButton(onClick = onPlayToggle) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pausar" else "Reproducir",
                tint = Color.White
            )
        }
        if (track.isDownloaded) {
            Icon(Icons.Default.CheckCircle, "Descargada", tint = Color(0xFF8BCF9B))
        } else if (progress != null) {
            IconButton(onClick = onCancelDownload) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF8BCF9B)
                )
            }
        }
        if (vm != null) TrackActionsMenu(track, playlists, albums.filter { it.id.startsWith("album:custom:") }, vm, vm.isFavorite(track.id))
    }
}

@Composable
private fun TrackActionsMenu(track: Track, playlists: List<Playlist>, albums: List<Album>, vm: MusicVaultViewModel, favorite: Boolean = false) {
    val currentFavorite = vm.isFavorite(track.id)
    var expanded by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }

    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(leadingIcon = { Icon(if (currentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) }, text = { Text(if (currentFavorite) "Quitar de Me gusta" else "Me gusta") }, onClick = { vm.toggleFavorite(track); expanded = false })
            DropdownMenuItem(text = { Text("Agregar a lista de reproducción") }, onClick = { expanded = false; showPlaylists = true })
            DropdownMenuItem(text = { Text("Agregar a álbum") }, onClick = { expanded = false; showAlbums = true })
        }
    }

    if (showPlaylists) {
        ChoiceDialog("Agregar a playlist", playlists.map { it.id to it.title }, onDismiss = { showPlaylists = false }) { id ->
            vm.addTrackToPlaylist(track, id)
            showPlaylists = false
        }
    }
    if (showAlbums) {
        AlbumChoiceDialog(
            albums = albums,
            onDismiss = { showAlbums = false },
            onSelect = { id -> vm.addTrackToAlbum(track, id); showAlbums = false },
            onCreate = { showCreateAlbum = true; showAlbums = false },
            onDelete = { id -> vm.deleteAlbum(id) }
        )
    }
    if (showCreateAlbum) {
        AlertDialog(
            onDismissRequest = { showCreateAlbum = false },
            title = { Text("Crear nuevo álbum") },
            text = { OutlinedTextField(newAlbumName, { newAlbumName = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newAlbumName.isNotBlank(), onClick = {
                    vm.createAlbum(newAlbumName, listOf(track))
                    showCreateAlbum = false
                    newAlbumName = ""
                }) { Text("Crear y agregar") }
            },
            dismissButton = { TextButton(onClick = { showCreateAlbum = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun RemoteTrackActionsMenu(track: CatalogTrack, playlists: List<Playlist>, albums: List<Album>, vm: MusicVaultViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Agregar a lista de reproducción") }, onClick = { expanded = false; showPlaylists = true })
            DropdownMenuItem(text = { Text("Agregar a álbum") }, onClick = { expanded = false; showAlbums = true })
            DropdownMenuItem(text = { Text("Descargar") }, onClick = { expanded = false; vm.requestRemoteDownload(track) })
        }
    }
    if (showPlaylists) {
        ChoiceDialog("Agregar a playlist", playlists.map { it.id to it.title }, { showPlaylists = false }) { id -> vm.addRemoteTrackToPlaylist(track, id); showPlaylists = false }
    }
    if (showAlbums) {
        AlbumChoiceDialog(albums, { showAlbums = false }, { id -> vm.addRemoteTrackToAlbum(track, id); showAlbums = false }, { showCreateAlbum = true; showAlbums = false }, { id -> vm.deleteAlbum(id) })
    }
    if (showCreateAlbum) {
        AlertDialog(
            onDismissRequest = { showCreateAlbum = false },
            title = { Text("Crear nuevo álbum") },
            text = { OutlinedTextField(newAlbumName, { newAlbumName = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = { TextButton(enabled = newAlbumName.isNotBlank(), onClick = { vm.createAlbumFromRemote(newAlbumName, track); showCreateAlbum = false; newAlbumName = "" }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { showCreateAlbum = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        if (choices.isEmpty()) Text("No tienes listas creadas.") else LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(choices, key = { it.first }) { (id, label) ->
                Text(label, Modifier.fillMaxWidth().clickable { onSelect(id) }.padding(14.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun AlbumChoiceDialog(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agregar a álbum") }, text = {
        Column {
            TextButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Crear nuevo álbum") }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(albums, key = { it.id }) { album ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(album.title, Modifier.weight(1f).clickable { onSelect(album.id) }.padding(10.dp))
                        IconButton(onClick = { deleteId = album.id }) { Icon(Icons.Default.DeleteOutline, "Eliminar álbum") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Eliminar álbum") }, text = { Text("¿Eliminar este álbum de MusicVault? Las canciones no se borrarán.") }, confirmButton = { TextButton(onClick = { onDelete(id); deleteId = null }) { Text("Eliminar") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun StatCard(title: String, value: String) {
    Card(
        Modifier.width(150.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color(0xFF8D95A1))
        }
    }
}
