package com.musicvault.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.data.Album
import com.musicvault.app.data.Playlist
import com.musicvault.app.data.Track
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.ui.search.RemoteResultItem
import com.musicvault.app.ui.search.RemoteResultsAdapter
import com.musicvault.app.ui.search.MoreAction
import com.musicvault.app.ui.theme.VaultColors
import kotlin.math.abs

/** Deterministic gradient pair for a given key, used so album/playlist placeholders feel alive. */
private fun gradientFor(key: String): Pair<Color, Color> {
    val idx = abs(key.hashCode()) % VaultColors.ShelfGradients.size
    return VaultColors.ShelfGradients[idx]
}

/**
 * Adds a subtle Spotify-style "press" reaction: the element scales down slightly
 * and settles back with a spring, instead of relying only on the ripple.
 */
@Composable
private fun Modifier.pressScale(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

@Composable
fun MusicVaultApp(vm: MusicVaultViewModel) {
    val state by vm.state.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val albumDownloads by vm.albumDownloads.collectAsState()
    val queue by vm.queue.collectAsState()
    val queueIndex by vm.queueIndex.collectAsState()
    val favoriteIds = state.favoriteIds
    val equalizerBands by vm.equalizerBands.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // App-level back navigation: close the full player/details before allowing
    // Android to leave the activity. This keeps the system Back button useful
    // for navigating inside MusicVault.
    val canHandleBack = state.fullPlayer || showSettings ||
        state.selectedLocalAlbum != null || state.selectedRemoteAlbum != null ||
        state.selectedPlaylist != null || state.section != AppSection.HOME
    BackHandler(enabled = canHandleBack) {
        when {
            state.fullPlayer -> vm.closeFullPlayer()
            showSettings -> showSettings = false
            state.selectedLocalAlbum != null -> vm.closeLocalAlbum()
            state.selectedRemoteAlbum != null -> vm.closeRemoteAlbum()
            state.selectedPlaylist != null -> vm.closePlaylist()
            state.section != AppSection.HOME -> vm.selectSection(AppSection.HOME)
        }
    }

    Scaffold(
        containerColor = VaultColors.Background,
        bottomBar = {
            if (!state.fullPlayer && !showSettings) {
                Column {
                    state.selectedTrack?.let {
                        MiniPlayer(
                            track = it,
                            isPlaying = isPlaying,
                            onToggle = vm::togglePlayback,
                            onOpen = vm::openFullPlayer,
                            favorite = it.id in favoriteIds,
                            onFavorite = { vm.toggleFavorite(it) },
                            vm = vm
                        )
                    }
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
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                state.section == AppSection.SEARCH -> "Buscar"
                                state.section == AppSection.LIBRARY -> "Biblioteca"
                                state.section == AppSection.PLAYLISTS -> "Listas"
                                else -> "MusicVault"
                            },
                            color = VaultColors.TextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(VaultColors.Surface2)
                                .pressScale { showSettings = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, "Ajustes", tint = VaultColors.TextPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                    AnimatedVisibility(
                        visible = state.errorMessage != null,
                        enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
                    ) {
                        state.errorMessage?.let {
                            AssistChip(
                                onClick = vm::clearError,
                                label = { Text(it, maxLines = 3) },
                                trailingIcon = { Icon(Icons.Default.Close, null) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = VaultColors.Surface2,
                                    labelColor = VaultColors.TextPrimary,
                                    trailingIconContentColor = VaultColors.TextSecondary
                                ),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                    when {
                        state.selectedRemoteAlbum != null -> RemoteAlbumDetail(
                            album = state.selectedRemoteAlbum!!,
                            tracks = state.remoteAlbumTracks,
                            albumDownloads = albumDownloads,
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
                            isPlaying = isPlaying,
                            selectedTrackId = state.selectedTrack?.id,
                            playlists = state.playlists,
                            albums = state.albums,
                            vm = vm
                        )
                        else -> when (state.section) {
                            AppSection.HOME -> HomeScreen(state, albumDownloads, isPlaying, vm)
                            AppSection.SEARCH -> SearchScreen(state, isPlaying, vm)
                            AppSection.LIBRARY -> LibraryScreen(state, isPlaying, vm)
                            AppSection.PLAYLISTS -> PlaylistScreen(state, vm)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            modifier = Modifier.fillMaxSize(),
            visible = state.fullPlayer && state.selectedTrack != null,
            enter = fadeIn(tween(260)) + slideInVertically(
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                initialOffsetY = { it / 6 }
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                targetOffsetY = { it / 6 }
            )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VaultColors.Background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                state.selectedTrack?.let { track ->
                    FullPlayerScreen(track, isPlaying, state.shuffle, queue, queueIndex, favoriteIds, vm)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(state: MusicUiState, albumDownloads: Set<String>, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val downloadProgress by vm.downloadProgress.collectAsState()
    val recommendationTracks = state.recommendedTracks
    val recommendationAlbums = state.recommendedAlbums
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "greeting", contentType = "header") {
            Column {
                Text(greetingForNow(), color = VaultColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                Text("Tu música, sin depender de streaming.", color = VaultColors.TextSecondary, fontSize = 14.sp)
            }
        }
        if (recommendationTracks.isNotEmpty()) {
            item(key = "recommended_tracks", contentType = "recommendation") {
                SectionTitle("Para ti")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(recommendationTracks, key = { "rec-track:${it.id}" }, contentType = { "track" }) { track ->
                        RecommendationTrackCard(track, downloadProgress, state.playlists, state.albums, vm, isPlaying && state.selectedTrack?.id == track.id)
                    }
                }
            }
        }
        if (recommendationAlbums.isNotEmpty()) {
            item(key = "recommended_albums", contentType = "recommendation") {
                SectionTitle("Álbumes para ti")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(recommendationAlbums, key = { "rec-album:${it.id}" }, contentType = { "album" }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { vm.openRecommendedAlbum(album) },
                            onDownloadAll = if (album.id.contains(":release-group:")) { { vm.downloadRecommendedAlbum(album) } } else null,
                            isDownloading = album.id in albumDownloads
                        )
                    }
                }
            }
        }
        if (state.tracks.isNotEmpty()) {
            item(key = "songs_title", contentType = "section") { SectionTitle("Tus canciones") }
            items(state.tracks, key = { it.id }, contentType = { "track" }) { track ->
                TrackRow(
                    track,
                    onClick = { vm.selectTrack(track) },
                    progress = downloadProgress[track.id],
                    onCancelDownload = { vm.cancelDownload(track.id) },
                    isPlaying = isPlaying && state.selectedTrack?.id == track.id,
                    onPlayToggle = { if (state.selectedTrack?.id == track.id) vm.togglePlayback() else vm.selectTrack(track) },
                    playlists = state.playlists, albums = state.albums, vm = vm
                )
            }
        }
    }
}

@Composable
private fun RecommendationTrackCard(
    track: Track,
    downloadProgress: Map<String, Int>,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel,
    isPlaying: Boolean
) {
    val downloading = downloadProgress.containsKey(track.id) && !track.isDownloaded
    Column(Modifier.width(150.dp)) {
        Box(Modifier.size(150.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(track.coverUri)
                    .size(180)
                    .memoryCacheKey("home-track:${track.coverUri}")
                    .diskCacheKey("home-track:${track.coverUri}")
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable { vm.playRecommendation(track) },
                contentScale = ContentScale.Crop,
                imageLoader = CoverImageCache.loader(LocalContext.current)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (isPlaying) VaultColors.Green else VaultColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                downloading -> DownloadDotsSpinner(modifier = Modifier.size(34.dp))
                track.isDownloaded -> TrackActionsMenu(track, playlists, albums.filter { it.id.startsWith("album:custom:") }, vm, vm.isFavorite(track.id))
                else -> {
                    IconButton(onClick = { vm.playRecommendation(track) }, modifier = Modifier.size(38.dp)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Descargar y reproducir", tint = VaultColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadDotsSpinner(modifier: Modifier = Modifier, dotColor: Color = VaultColors.Green) {
    val transition = rememberInfiniteTransition(label = "download-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "download-dots-phase"
    )
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.30f
        val dotRadius = size.minDimension * 0.065f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0 - 90.0)
            val x = cx + kotlin.math.cos(angle).toFloat() * radius
            val y = cy + kotlin.math.sin(angle).toFloat() * radius
            val distance = ((i - phase.toInt()) + 8) % 8
            val alpha = 0.28f + 0.72f * ((8 - distance) / 8f)
            drawCircle(dotColor.copy(alpha = alpha), dotRadius, androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

private fun greetingForNow(): String {
    val hour = java.time.LocalTime.now().hour
    return when {
        hour < 12 -> "Buenos días"
        hour < 19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}


@Composable
private fun SearchScreen(
    state: MusicUiState,
    isPlaying: Boolean,
    vm: MusicVaultViewModel
) {
    val downloadProgress by vm.downloadProgress.collectAsState()
    var actionTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var actionAction by remember { mutableStateOf<MoreAction?>(null) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var deleteSearchTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestSelectedTrackId by rememberUpdatedState(state.selectedTrack?.id)

    val adapter = remember {
        RemoteResultsAdapter(
            onAlbumClick = vm::openRemoteAlbum,
            onTrackClick = vm::playRemote,
            onCancelDownload = vm::cancelDownload,
            onMoreAction = { track, action ->
                // The native search popup owns the first-level menu. After an
                // action is selected, open only the appropriate chooser/dialog;
                // never mount the old Compose dropdown again.
                actionTrack = track
                actionAction = action
            },
            onPlayToggle = { track ->
                if (latestIsPlaying && latestSelectedTrackId == "${track.provider}:recording:${track.id}") {
                    vm.togglePlayback()
                } else {
                    vm.playRemote(track)
                }
            }
        )
    }

    val resultItems = remember(state.remoteAlbums, state.remoteTracks) {
        buildList<RemoteResultItem> {
            if (state.remoteAlbums.isNotEmpty()) {
                add(RemoteResultItem.HeaderItem("Álbumes"))
                state.remoteAlbums.forEach { add(RemoteResultItem.AlbumItem(it)) }
            }
            if (state.remoteTracks.isNotEmpty()) {
                add(RemoteResultItem.HeaderItem("Canciones"))
                state.remoteTracks.forEach { add(RemoteResultItem.TrackItem(it)) }
            }
            if (state.remoteAlbums.isEmpty() && state.remoteTracks.isEmpty() &&
                !state.isSearching && state.search.isNotBlank()
            ) {
                add(RemoteResultItem.HeaderItem("No se encontraron resultados."))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        OutlinedTextField(
            value = state.search,
            onValueChange = vm::search,
            Modifier.fillMaxWidth(),
            placeholder = { Text("Canción, artista o álbum", color = VaultColors.TextTertiary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = VaultColors.TextSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VaultColors.Green,
                unfocusedBorderColor = VaultColors.Surface3,
                focusedContainerColor = VaultColors.Surface1,
                unfocusedContainerColor = VaultColors.Surface1,
                focusedTextColor = VaultColors.TextPrimary,
                unfocusedTextColor = VaultColors.TextPrimary,
                cursorColor = VaultColors.Green
            )
        )

        AnimatedVisibility(
            visible = state.isSearching,
            enter = fadeIn(tween(150)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(120)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
        ) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                color = VaultColors.Green,
                trackColor = VaultColors.Surface2
            )
        }

        Spacer(Modifier.height(16.dp))

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context).apply {
                        isItemPrefetchEnabled = true
                        initialPrefetchItemCount = 8
                    }
                    this.adapter = adapter
                    setHasFixedSize(true)
                    itemAnimator = null
                    setItemViewCacheSize(12)
                    recycledViewPool.setMaxRecycledViews(1, 8)
                    recycledViewPool.setMaxRecycledViews(2, 12)
                    recycledViewPool.setMaxRecycledViews(3, 18)
                    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                }
            },
            update = { recyclerView ->
                adapter.updateItems(resultItems)

                // Use the repository's authoritative downloaded list. Its IDs are
                // already the exact provider:recording:<id> keys used by remote
                // downloads, so search rows switch to the post-download state as
                // soon as persistence completes. Do not guess a provider here.
                val downloadedIds = state.downloaded
                    .asSequence()
                    .filter { it.isDownloaded }
                    .map { it.id }
                    .toSet()

                adapter.updateState(
                    downloadedIds = downloadedIds,
                    progress = downloadProgress,
                    selectedTrackId = state.selectedTrack?.id,
                    isPlaying = isPlaying
                )
                recyclerView.itemAnimator = null
            }
        )
    }

    val selectedTrack = actionTrack
    when (actionAction) {
        MoreAction.QUEUE -> if (selectedTrack != null) {
            vm.addRemoteTrackToQueue(selectedTrack)
            actionTrack = null
            actionAction = null
        }
        MoreAction.ALBUM -> if (selectedTrack != null) {
            ChoiceDialog(
                title = "Agregar a álbum",
                choices = state.playlists.map { it.id to it.title },
                onDismiss = { actionTrack = null; actionAction = null },
                onSelect = { id ->
                    vm.addRemoteTrackToPlaylist(selectedTrack, id)
                    actionTrack = null
                    actionAction = null
                }
            )
        }
        MoreAction.DELETE -> if (selectedTrack != null) {
            deleteSearchTrack = selectedTrack
            actionTrack = null
            actionAction = null
        }
        null -> Unit
    }

    deleteSearchTrack?.let { remote ->
        AlertDialog(
            onDismissRequest = { deleteSearchTrack = null },
            title = { Text("Eliminar canción") },
            text = { Text("¿Quieres eliminar \"${remote.title}\" de las descargas de MusicVault?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDownloadedTrack("${remote.provider}:recording:${remote.id}")
                    deleteSearchTrack = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSearchTrack = null }) { Text("Cancelar") } }
        )
    }

    if (showCreateAlbum && selectedTrack != null) {
        AlertDialog(
            onDismissRequest = { showCreateAlbum = false; actionTrack = null },
            title = { Text("Crear nuevo álbum") },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newAlbumName.isNotBlank(),
                    onClick = {
                        vm.createAlbumFromRemote(newAlbumName, selectedTrack)
                        showCreateAlbum = false
                        actionTrack = null
                        actionAction = null
                        newAlbumName = ""
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbum = false; actionTrack = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CachedCover(
    url: String?,
    contentDescription: String?,
    modifier: Modifier,
    targetSizePx: Int
) {
    if (url.isNullOrBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, tint = VaultColors.TextTertiary)
        }
        return
    }
    val context = LocalContext.current
    val request = remember(url, targetSizePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size(targetSizePx, targetSizePx))
            .memoryCacheKey("musicvault:$url:$targetSizePx")
            .diskCacheKey("musicvault:$url:$targetSizePx")
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = CoverImageCache.loader(context),
        contentDescription = contentDescription,
        modifier = modifier
    )
}

@Composable
private fun RemoteTrackRow(
    track: com.musicvault.app.catalog.CatalogTrack,
    progress: Int?,
    downloaded: Boolean,
    onClick: () -> Unit,
    onCancelDownload: () -> Unit = {},
    onMore: () -> Unit = {},
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    modifier: Modifier = Modifier
) {
    val displayedProgress = progress
    val progressFraction = ((displayedProgress ?: 0) / 100f).coerceIn(0f, 1f)
    val downloading = displayedProgress != null && !downloaded
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CachedCover(
            url = track.coverUri,
            contentDescription = track.albumTitle,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
            targetSizePx = 72
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isPlaying) VaultColors.Green else VaultColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${track.artistName} · ${track.albumTitle ?: "Álbum desconocido"}",
                color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )

        }
        when {
            downloading -> {
                IconButton(onClick = onCancelDownload) {
                    DownloadDotsSpinner(modifier = Modifier.size(28.dp))
                }
            }
            downloaded -> {
                IconButton(onClick = onMore) {
                    Icon(Icons.Default.MoreVert, "Más opciones", tint = VaultColors.TextPrimary)
                }
            }
            else -> {
                IconButton(onClick = onPlayToggle) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Reproducir", tint = VaultColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(state: MusicUiState, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val downloadProgress by vm.downloadProgress.collectAsState()
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
            Text("Biblioteca", color = VaultColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Text("Archivos locales y contenido descargado", color = VaultColors.TextSecondary, fontSize = 14.sp)
        }
        item {
            Button(
                onClick = {
                    val permission = if (Build.VERSION.SDK_INT >= 33)
                        Manifest.permission.READ_MEDIA_AUDIO
                    else
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    launcher.launch(permission)
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Green, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isScanning) "Escaneando..." else "Escanear música del dispositivo", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Disponibles", state.downloaded.size.toString())
                StatCard("Álbumes", state.playlists.size.toString())
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
                vm = vm,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun PlaylistScreen(state: MusicUiState, vm: MusicVaultViewModel) {
    var showAlbumDialog by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Álbumes", color = VaultColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                    Text("Tus colecciones de canciones descargadas", color = VaultColors.TextSecondary, fontSize = 14.sp)
                }
                IconButton(onClick = { showAlbumDialog = true }) {
                    Icon(Icons.Default.Add, "Nuevo álbum", tint = VaultColors.TextPrimary)
                }
            }
        }
        if (state.playlists.isEmpty()) {
            item {
                Text("Aún no tienes álbumes creados.", color = VaultColors.TextSecondary, modifier = Modifier.padding(vertical = 20.dp))
            }
        } else {
            items(state.playlists, key = { it.id }) { playlist ->
                PlaylistCard(playlist) { vm.openPlaylist(playlist) }
            }
        }
    }
    if (showAlbumDialog) {
        CreateCollectionDialog("Nuevo álbum", "Nombre del álbum", state.downloaded, vm::createPlaylist) { showAlbumDialog = false }
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
                                Text(track.artistName, fontSize = 12.sp, color = VaultColors.TextSecondary, maxLines = 1)
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
private fun LocalAlbumDetail(album: Album, tracks: List<Track>, isPlaying: Boolean, selectedTrackId: String?, playlists: List<Playlist>, albums: List<Album>, vm: MusicVaultViewModel) {
    val downloadProgress by vm.downloadProgress.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            IconButton(onClick = vm::closeLocalAlbum) { Icon(Icons.Default.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
            val (top, bottom) = remember(album.id) { gradientFor(album.id) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(150.dp)
                        .shadow(12.dp, RoundedCornerShape(12.dp), ambientColor = top.copy(alpha = 0.5f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(top, bottom))),
                    contentAlignment = Alignment.Center
                ) {
                    if (album.coverUri != null) AsyncImage(album.coverUri, album.title, Modifier.fillMaxSize()) else Icon(Icons.Default.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(album.title, color = VaultColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(album.artistName, color = VaultColors.TextSecondary)
                    if (album.year > 0) Text(album.year.toString(), color = VaultColors.TextTertiary)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                onClick = { vm.selectTrack(track) },
                progress = downloadProgress[track.id],
                isPlaying = isPlaying && selectedTrackId == track.id,
                onPlayToggle = { if (selectedTrackId == track.id) vm.togglePlayback() else vm.selectTrack(track) },
                onCancelDownload = { vm.cancelDownload(track.id) },
                playlists = playlists,
                albums = albums,
                vm = vm,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun RemoteAlbumDetail(
    album: CatalogAlbum,
    tracks: List<CatalogTrack>,
    albumDownloads: Set<String>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    loading: Boolean,
    localTracks: List<Track>,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloadedIds = remember(localTracks) {
        localTracks.filter { it.isDownloaded }.mapTo(HashSet()) { it.id }
    }
    var actionTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var actionAction by remember { mutableStateOf<MoreAction?>(null) }
    var actionMenuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            IconButton(onClick = vm::closeRemoteAlbum) {
                Icon(Icons.Default.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
            }
            val (top, bottom) = remember(album.id) { gradientFor(album.id) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(128.dp)
                        .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = top.copy(alpha = 0.5f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(top, bottom))),
                    contentAlignment = Alignment.Center
                ) {
                    if (album.coverUri != null) {
                        CachedCover(
                            url = album.coverUri,
                            contentDescription = album.title,
                            modifier = Modifier.fillMaxSize(),
                            targetSizePx = 360
                        )
                    } else {
                        Icon(Icons.Default.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, color = VaultColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(album.artistName, color = VaultColors.TextSecondary, fontSize = 14.sp)
                    album.year?.let { Text(it.toString(), color = VaultColors.TextTertiary, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (tracks.isNotEmpty()) {
                val albumKey = "${album.provider}:${album.id}"
                val albumDownloading = albumKey in albumDownloads
                Button(
                    onClick = { if (!albumDownloading) vm.downloadRemoteAlbum(album, tracks) },
                    enabled = !albumDownloading,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Surface2,
                        contentColor = VaultColors.TextPrimary,
                        disabledContainerColor = VaultColors.Surface2,
                        disabledContentColor = VaultColors.TextPrimary
                    )
                ) {
                    if (albumDownloading) {
                        DownloadDotsSpinner(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (albumDownloading) "Descargando álbum" else "Descargar álbum", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = VaultColors.Green, trackColor = VaultColors.Surface3)
                tracks.isEmpty() -> Text("No hay canciones disponibles para este álbum.", color = VaultColors.TextSecondary)
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
                onCancelDownload = { vm.cancelDownload(localId) },
                onMore = { actionTrack = track; actionMenuExpanded = true },
                isPlaying = isPlaying && selectedTrackId == localId,
                onPlayToggle = {
                    if (isPlaying && selectedTrackId == localId) vm.togglePlayback()
                    else vm.playRemote(track)
                },
                modifier = Modifier
            )
        }
    }
    actionTrack?.let { track ->
        RemoteAlbumDetailActionsMenu(
            track = track,
            playlists = playlists,
            albums = albums,
            vm = vm,
            expanded = actionMenuExpanded,
            onDismiss = { actionMenuExpanded = false; actionTrack = null }
        )
    }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val downloadProgress by vm.downloadProgress.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp)
    ) {
        item {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Default.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
            }
            Text(playlist.title, color = VaultColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Text(playlist.description, color = VaultColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.playPlaylist(playlist, shuffle = false) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Green, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reproducir", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { vm.playPlaylist(playlist, shuffle = true) },
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = VaultColors.TextPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("Aleatorio", color = VaultColors.TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
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
                vm = vm,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    favorite: Boolean = false,
    onFavorite: () -> Unit = {},
    vm: MusicVaultViewModel
) {
    // Observe rapidly changing playback progress only inside the mini-player.
    // Keeping it out of MusicVaultApp prevents the entire navigation/search tree
    // from recomposing on every playback tick.
    val playbackProgress by vm.playbackProgress.collectAsState()
    val durationMs = playbackProgress.durationMs.coerceAtLeast(track.durationMs)
    val positionMs = playbackProgress.positionMs
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "miniPlayerProgress"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.985f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "miniPlayerScale"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(VaultColors.Surface2)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = VaultColors.Green,
            trackColor = Color.Transparent
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(track.coverUri, track.title, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = VaultColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Me gusta",
                    tint = if (favorite) VaultColors.Favorite else VaultColors.TextPrimary
                )
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = VaultColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun FullPlayerScreen(track: Track, isPlaying: Boolean, shuffle: Boolean, queue: List<Track>, queueIndex: Int, favoriteIds: Set<String>, vm: MusicVaultViewModel) {
    val progress by vm.playbackProgress.collectAsState()
    val scale by animateFloatAsState(
        if (isPlaying) 1f else 0.94f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "coverScale"
    )
    val duration = progress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val position = progress.positionMs.coerceIn(0L, duration)
    var sliderPosition by remember(track.id) { mutableFloatStateOf(position.toFloat()) }
    var sliderDragging by remember(track.id) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(position, track.id) { if (!sliderDragging) sliderPosition = position.toFloat() }
    val favorite = track.id in favoriteIds
    val (accentTop, _) = remember(track.id) { gradientFor(track.id) }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(accentTop.copy(alpha = 0.78f), VaultColors.Background, VaultColors.Background)
                )
            )
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeFullPlayer) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", tint = VaultColors.TextPrimary, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REPRODUCIENDO", color = VaultColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            AsyncImage(
                track.coverUri, track.title,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(top = 4.dp)
                    .scale(scale)
                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp), ambientColor = accentTop, spotColor = accentTop)
                    .clip(RoundedCornerShape(16.dp))
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = VaultColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 15.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            val favScale by animateFloatAsState(
                if (favorite) 1.15f else 1f,
                spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                label = "favScale"
            )
            IconButton(onClick = { vm.toggleFavorite(track) }) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Me gusta",
                    tint = if (favorite) VaultColors.Favorite else VaultColors.TextPrimary,
                    modifier = Modifier.size(26.dp).graphicsLayer { scaleX = favScale; scaleY = favScale }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Custom seek bar: compact, precise and visually lighter than the default Material slider.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat().coerceAtLeast(1f)

                        fun setFromX(x: Float) {
                            sliderPosition = (x.coerceIn(0f, width) / width) * duration.toFloat()
                        }

                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            sliderDragging = true
                            change.consume()
                            setFromX(change.position.x)
                        }

                        if (slopChange != null) {
                            drag(slopChange.id) { change ->
                                change.consume()
                                sliderDragging = true
                                setFromX(change.position.x)
                            }
                        } else {
                            setFromX(down.position.x)
                        }

                        sliderDragging = false
                        vm.seekTo(sliderPosition.toLong())
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val trackHeight = 5.dp.toPx()
                val thumbRadius = 7.dp.toPx()
                val progressFraction = (sliderPosition / duration.toFloat()).coerceIn(0f, 1f)
                val activeX = size.width * progressFraction

                drawRoundRect(
                    color = VaultColors.Surface3,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - trackHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                )
                if (activeX > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(VaultColors.Green.copy(alpha = 0.72f), VaultColors.Green)
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - trackHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(activeX, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                    )
                }
                drawCircle(
                    color = VaultColors.Green.copy(alpha = 0.16f),
                    radius = thumbRadius * 1.9f,
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY)
                )
                drawCircle(
                    color = VaultColors.TextPrimary,
                    radius = thumbRadius,
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (sliderDragging) sliderPosition.toLong() else position), color = VaultColors.TextSecondary, fontSize = 12.sp)
            Text(formatTime(duration), color = VaultColors.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        // Transport controls: Play is anchored exactly at screen center.
        // Previous/Next are equally spaced from Play; Shuffle continues the same spacing.
        Box(
            Modifier.fillMaxWidth().height(72.dp)
        ) {
            val playScale by animateFloatAsState(
                if (isPlaying) 1f else 0.92f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "playButtonScale"
            )

            // Play is the absolute center of the available screen width.
            FilledIconButton(
                onClick = vm::togglePlayback,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = VaultColors.TextPrimary,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    modifier = Modifier.size(38.dp)
                )
            }

            // Same center-to-center distance on both sides of Play.
            IconButton(
                onClick = vm::previousTrack,
                modifier = Modifier.align(Alignment.Center).offset(x = (-54).dp).size(54.dp)
            ) {
                Icon(Icons.Default.SkipPrevious, "Anterior", tint = VaultColors.TextPrimary, modifier = Modifier.size(34.dp))
            }

            IconButton(
                onClick = vm::nextTrack,
                modifier = Modifier.align(Alignment.Center).offset(x = 54.dp).size(54.dp)
            ) {
                Icon(Icons.Default.SkipNext, "Siguiente", tint = VaultColors.TextPrimary, modifier = Modifier.size(34.dp))
            }

            // Shuffle uses exactly the same spacing from Next as Next uses from Play.
            IconButton(
                onClick = vm::toggleShuffle,
                modifier = Modifier.align(Alignment.Center).offset(x = 108.dp).size(54.dp)
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    "Aleatorio",
                    tint = if (shuffle) VaultColors.Green else VaultColors.TextPrimary,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
        // The queue button is independent from the centered transport controls.
        // It stays at the lower-right edge and respects the navigation gesture area.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            IconButton(
                onClick = { showQueue = true },
                modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)
            ) {
                Icon(
                    Icons.Default.QueueMusic,
                    "Cola de reproducción",
                    tint = VaultColors.TextSecondary,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    }

    if (showQueue) {
        QueueBottomSheet(queue, queueIndex, vm) { showQueue = false }
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
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
                Text("Ajustes", color = VaultColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            }
        }
        item {
            Text("Audio y reproducción", color = VaultColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Crossfade", color = VaultColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = crossfadeSeconds.toFloat(),
                    onValueChange = { onCrossfadeChange(it.toInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = VaultColors.Green, activeTrackColor = VaultColors.Green, inactiveTrackColor = VaultColors.Surface3),
                    modifier = Modifier.weight(1f)
                )
                Text("${crossfadeSeconds}s", color = VaultColors.TextPrimary, modifier = Modifier.width(42.dp))
            }
        }
        item {
            Text("Ecualizador nativo", color = VaultColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (equalizerBands.isEmpty()) "Esperando sesión de audio..." else "Bandas: ${equalizerBands.size}", color = VaultColors.TextSecondary, modifier = Modifier.weight(1f))
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { eqEnabled = it; onEqualizerEnabled(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = VaultColors.Green)
                )
            }
            equalizerBands.forEach { band ->
                Column {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${band.centerFrequencyHz} Hz", color = VaultColors.TextPrimary, fontSize = 12.sp)
                        Text("${band.level / 100f} dB", color = VaultColors.TextSecondary, fontSize = 12.sp)
                    }
                    Slider(
                        value = band.level.toFloat(),
                        onValueChange = { onEqualizerBand(band.index, it.toInt().toShort()) },
                        valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = VaultColors.Green, activeTrackColor = VaultColors.Green, inactiveTrackColor = VaultColors.Surface3)
                    )
                }
            }
        }
        item {
            Text("El ecualizador usa el efecto Equalizer nativo de Android y la sesión de audio de Media3.", color = VaultColors.TextTertiary, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(queue: List<Track>, currentIndex: Int, vm: MusicVaultViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VaultColors.Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("A continuación", color = VaultColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            if (queue.isEmpty()) {
                Text("La cola está vacía.", color = VaultColors.TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    itemsIndexed(queue, key = { index, track -> "${index}_${track.id}" }) { index, track ->
                        Row(
                            Modifier.fillMaxWidth().animateItem().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DragHandle, "Reordenar", tint = VaultColors.TextTertiary)
                            AsyncImage(track.coverUri, track.title, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = if (index == currentIndex) VaultColors.Green else VaultColors.TextPrimary,
                                    maxLines = 1,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(enabled = index > 0, onClick = { vm.moveQueueItem(index, index - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Subir", tint = VaultColors.TextPrimary) }
                            IconButton(enabled = index < queue.lastIndex, onClick = { vm.moveQueueItem(index, index + 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Bajar", tint = VaultColors.TextPrimary) }
                            IconButton(onClick = { vm.removeFromQueue(index) }) { Icon(Icons.Default.DeleteOutline, "Quitar", tint = VaultColors.Error) }
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
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = VaultColors.TextPrimary,
        selectedTextColor = VaultColors.TextPrimary,
        unselectedIconColor = VaultColors.TextTertiary,
        unselectedTextColor = VaultColors.TextTertiary,
        indicatorColor = Color.Transparent
    )
    NavigationBar(containerColor = VaultColors.Background, tonalElevation = 0.dp) {
        NavigationBarItem(
            section == AppSection.HOME, { onSelect(AppSection.HOME) },
            icon = { Icon(if (section == AppSection.HOME) Icons.Default.Home else Icons.Outlined.Home, null) },
            label = { Text("Inicio", fontWeight = if (section == AppSection.HOME) FontWeight.Bold else FontWeight.Normal) },
            colors = itemColors
        )
        NavigationBarItem(
            section == AppSection.SEARCH, { onSelect(AppSection.SEARCH) },
            icon = { Icon(if (section == AppSection.SEARCH) Icons.Default.Search else Icons.Outlined.Search, null) },
            label = { Text("Buscar", fontWeight = if (section == AppSection.SEARCH) FontWeight.Bold else FontWeight.Normal) },
            colors = itemColors
        )
        NavigationBarItem(
            section == AppSection.LIBRARY, { onSelect(AppSection.LIBRARY) },
            icon = { Icon(if (section == AppSection.LIBRARY) Icons.Default.LibraryMusic else Icons.Outlined.LibraryMusic, null) },
            label = { Text("Biblioteca", fontWeight = if (section == AppSection.LIBRARY) FontWeight.Bold else FontWeight.Normal) },
            colors = itemColors
        )
        NavigationBarItem(
            section == AppSection.PLAYLISTS, { onSelect(AppSection.PLAYLISTS) },
            icon = { Icon(if (section == AppSection.PLAYLISTS) Icons.Default.QueueMusic else Icons.Outlined.QueueMusic, null) },
            label = { Text("Álbumes", fontWeight = if (section == AppSection.PLAYLISTS) FontWeight.Bold else FontWeight.Normal) },
            colors = itemColors
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit = {},
    onDownloadAll: (() -> Unit)? = null,
    isDownloading: Boolean = false
) {
    val (top, bottom) = remember(album.id) { gradientFor(album.id) }
    Column(Modifier.width(150.dp)) {
        Box(
            Modifier
                .size(150.dp)
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp), ambientColor = top.copy(alpha = 0.4f))
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(top, bottom)))
                .pressScale(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).size(180).memoryCacheKey("home-album:${album.coverUri}").diskCacheKey("home-album:${album.coverUri}").crossfade(false).build(),
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(54.dp))
            }
            if (onDownloadAll != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = CircleShape,
                    color = VaultColors.Background.copy(alpha = 0.88f),
                    tonalElevation = 4.dp
                ) {
                    IconButton(onClick = onDownloadAll, modifier = Modifier.size(40.dp)) {
                        if (isDownloading) {
                            DownloadDotsSpinner(modifier = Modifier.size(26.dp), dotColor = VaultColors.TextPrimary)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Descargar álbum", tint = VaultColors.TextPrimary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(album.title, color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    val (top, bottom) = remember(playlist.id) { gradientFor(playlist.id) }
    Column(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VaultColors.Surface1)
            .pressScale(onClick = onClick)
            .padding(14.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(top, bottom))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.9f), modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(playlist.title, color = VaultColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(playlist.description, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
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
    vm: MusicVaultViewModel? = null,
    modifier: Modifier = Modifier
) {
    val displayedProgress = progress
    val progressFraction = ((displayedProgress ?: 0) / 100f).coerceIn(0f, 1f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CachedCover(
            url = track.coverUri,
            contentDescription = track.albumTitle,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
            targetSizePx = 104
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isPlaying) VaultColors.Green else VaultColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${track.artistName} · ${track.albumTitle}",
                color = VaultColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

        }
        when {
            displayedProgress != null -> {
                IconButton(onClick = onCancelDownload) {
                    DownloadDotsSpinner(modifier = Modifier.size(28.dp))
                }
            }
            track.isDownloaded -> {
                if (vm != null) TrackActionsMenu(track, playlists, albums.filter { it.id.startsWith("album:custom:") }, vm, vm.isFavorite(track.id))
            }
            else -> {
                IconButton(onClick = onPlayToggle) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pausar" else "Reproducir",
                        tint = VaultColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackActionsMenu(
    track: Track,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel,
    favorite: Boolean = false
) {
    val currentFavorite = vm.isFavorite(track.id)
    var expanded by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, "Más opciones", tint = VaultColors.TextPrimary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Agregar a cola de reproducción") },
                onClick = { vm.addToQueue(track); expanded = false }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(if (currentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                text = { Text(if (currentFavorite) "Quitar de Me gusta" else "Me gusta") },
                onClick = { vm.toggleFavorite(track); expanded = false }
            )
            DropdownMenuItem(text = { Text("Agregar a álbum") }, onClick = { expanded = false; showAlbums = true })
            if (track.isDownloaded) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    text = { Text("Eliminar canción", color = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false; confirmDelete = true }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar canción") },
            text = { Text("¿Quieres eliminar \"${track.title}\" de las descargas de MusicVault?") },
            confirmButton = { TextButton(onClick = { vm.deleteTrack(track); confirmDelete = false }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }

    if (showAlbums) {
        AlbumChoiceDialog(
            albums = playlists.map { Album(it.id, it.title, "custom", it.description, 0, null) },
            onDismiss = { showAlbums = false },
            onSelect = { id -> vm.addTrackToPlaylist(track, id); showAlbums = false },
            onCreate = { showCreateAlbum = true; showAlbums = false },
            onDelete = { id -> vm.deletePlaylist(id) }
        )
    }
    if (showCreateAlbum) {
        AlertDialog(
            onDismissRequest = { showCreateAlbum = false },
            title = { Text("Crear nuevo álbum") },
            text = { OutlinedTextField(newAlbumName, { newAlbumName = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newAlbumName.isNotBlank(), onClick = {
                    vm.createPlaylist(newAlbumName, listOf(track))
                    showCreateAlbum = false
                    newAlbumName = ""
                }) { Text("Crear y agregar") }
            },
            dismissButton = { TextButton(onClick = { showCreateAlbum = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun RemoteAlbumDetailActionsMenu(
    track: CatalogTrack,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    var showAlbums by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Agregar a cola de reproducción") }, onClick = { onDismiss(); vm.addRemoteTrackToQueue(track) })
        DropdownMenuItem(text = { Text("Agregar a álbum") }, onClick = { onDismiss(); showAlbums = true })
    }
    if (showAlbums) {
        AlbumChoiceDialog(
            albums = playlists.map { Album(it.id, it.title, "custom", it.description, 0, null) },
            onDismiss = { showAlbums = false },
            onSelect = { id -> vm.addRemoteTrackToPlaylist(track, id); showAlbums = false },
            onCreate = { showCreateAlbum = true; showAlbums = false },
            onDelete = { id -> vm.deletePlaylist(id) }
        )
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
        if (choices.isEmpty()) Text("No tienes álbumes creados.") else LazyColumn(Modifier.heightIn(max = 360.dp)) {
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
                        if (album.id != "playlist:favorites") {
                            IconButton(onClick = { deleteId = album.id }) { Icon(Icons.Default.DeleteOutline, "Eliminar álbum") }
                        }
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = VaultColors.Surface1)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, color = VaultColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(title, color = VaultColors.TextSecondary, fontSize = 13.sp)
        }
    }
}
