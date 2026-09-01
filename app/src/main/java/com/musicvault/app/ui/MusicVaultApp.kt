package com.musicvault.app.ui

import kotlin.math.roundToInt

import kotlinx.coroutines.flow.Flow
import android.Manifest
import android.content.Context
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
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
import com.musicvault.app.ui.theme.VaultFonts
import kotlin.math.abs

/** Deterministic spine accent for a key. Tone-on-tone pair for subtle placeholder fills. */
private fun gradientFor(key: String): Pair<Color, Color> {
    val idx = abs(key.hashCode()) % VaultColors.SpineAccents.size
    val accent = VaultColors.SpineAccents[idx]
    return accent to accent.copy(alpha = 0.25f)
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
    val downloadDirLabel by vm.downloadDirLabel.collectAsState()
    // SAF directory picker for the user-selected download destination.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::chooseDownloadTree) }
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
                    downloadDirLabel = downloadDirLabel,
                    onCrossfadeChange = vm::setCrossfadeSeconds,
                    onEqualizerEnabled = vm::setEqualizerEnabled,
                    onEqualizerBand = vm::setEqualizerBand,
                    onPickDownloadFolder = { folderPicker.launch(null) },
                    onUseInternalFolder = vm::useInternalDownloads,
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
                            fontWeight = FontWeight.Bold,
                            fontFamily = VaultFonts.Display,
                            letterSpacing = (-0.75).sp,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(VaultColors.Surface1)
                                .border(1.dp, VaultColors.Divider, CircleShape)
                                .pressScale { showSettings = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, "Ajustes", tint = VaultColors.TextSecondary, modifier = Modifier.size(18.dp))
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
                    // Keep all four main screens mounted. Switching sections only changes
                    // which already-created tree is visible, so RecyclerViews/adapters are
                    // not destroyed and recreated on every bottom-bar tap.
                    Box(Modifier.fillMaxSize()) {
                        PersistentSection(
                            active = state.section == AppSection.HOME,
                            label = "Inicio"
                        ) {
                            HomeScreen(state, albumDownloads, isPlaying, vm)
                        }
                        PersistentSection(
                            active = state.section == AppSection.SEARCH,
                            label = "Buscar"
                        ) {
                            SearchScreen(state, isPlaying, vm)
                        }
                        PersistentSection(
                            active = state.section == AppSection.LIBRARY,
                            label = "Biblioteca"
                        ) {
                            LibraryScreen(state, isPlaying, vm)
                        }
                        PersistentSection(
                            active = state.section == AppSection.PLAYLISTS,
                            label = "Álbumes"
                        ) {
                            PlaylistScreen(state, vm)
                        }

                    }
                }
            }

            // Detail screens are true full-content overlays. They must cover the
            // section header and the underlying Albums/Favorites list as well;
            // otherwise the persistent section remains visible around the detail.
            if (state.selectedLocalAlbum != null || state.selectedRemoteAlbum != null || state.selectedPlaylist != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f)
                        .background(VaultColors.Background)
                ) {
                state.selectedLocalAlbum?.let { album ->
                    LocalAlbumDetail(
                        album = album,
                        tracks = state.selectedLocalAlbumTracks,
                        isPlaying = isPlaying,
                        selectedTrackId = state.selectedTrack?.id,
                        playlists = state.playlists,
                        albums = state.albums,
                        vm = vm
                    )
                }
                state.selectedRemoteAlbum?.let { album ->
                    RemoteAlbumDetail(
                        album = album,
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
                }
                state.selectedPlaylist?.let { playlist ->
                    PlaylistDetail(
                        playlist = playlist,
                        tracks = state.selectedPlaylistTracks,
                        isPlaying = isPlaying,
                        selectedTrackId = state.selectedTrack?.id,
                        playlists = state.playlists,
                        albums = state.albums,
                        vm = vm
                    )
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
private fun PersistentSection(
    active: Boolean,
    label: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer {
                alpha = if (active) 1f else 0f
            }
            .semantics {
                if (!active) invisibleToUser()
            }
    ) {
        content()
    }
}

@Composable
private fun HomeScreen(state: MusicUiState, albumDownloads: Set<String>, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val context = LocalContext.current
    val adapter = remember { HomeFeedAdapter(vm) }
    AndroidView(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = {
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).also { lm ->
                    lm.isItemPrefetchEnabled = true
                    lm.initialPrefetchItemCount = 8
                }
                setItemViewCacheSize(12)
                recycledViewPool.setMaxRecycledViews(1, 4)
                recycledViewPool.setMaxRecycledViews(2, 2)
                recycledViewPool.setMaxRecycledViews(3, 2)
                recycledViewPool.setMaxRecycledViews(4, 2)
                recycledViewPool.setMaxRecycledViews(5, 12)
                itemAnimator = null
                setHasFixedSize(false)
                setPadding(20, 20, 20, 20)
                clipToPadding = true
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            adapter.update(
                recommendedTracks = state.recommendedTracks,
                recommendedAlbums = state.recommendedAlbums,
                recommendations = state.recommendedTracks,
                playlists = state.playlists,
                customAlbums = state.albums.filter { it.id.startsWith("album:custom:") },
                albumDownloads = albumDownloads,
                selectedTrackId = state.selectedTrack?.id,
                isPlaying = isPlaying
            )
        }
    )
}

@Composable
internal fun RecommendationTrackCard(
    track: Track,
    progressFlow: Flow<Int?>,
    onPlay: () -> Unit,
    actions: @Composable () -> Unit,
    isPlaying: Boolean
) {
    val progress by progressFlow.collectAsState(initial = null)
    val downloading = progress != null && !track.isDownloaded
    Column(Modifier.width(150.dp)) {
        Box(Modifier.size(150.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(track.coverUri)
                    .size(150)
                    .memoryCacheKey("home-track:${track.coverUri}")
                    .diskCacheKey("home-track:${track.coverUri}")
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable(onClick = onPlay),
                contentScale = ContentScale.Crop,
                imageLoader = CoverImageCache.loader(LocalContext.current)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (isPlaying) VaultColors.Accent else VaultColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                downloading -> DownloadDotsSpinner(modifier = Modifier.size(34.dp))
                track.isDownloaded -> actions()
                else -> {
                    IconButton(onClick = onPlay, modifier = Modifier.size(38.dp)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Descargar y reproducir", tint = VaultColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadDotsSpinner(modifier: Modifier = Modifier, dotColor: Color = VaultColors.Accent) {
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

internal fun greetingForNow(): String {
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
                focusedBorderColor = VaultColors.Accent,
                unfocusedBorderColor = VaultColors.Surface3,
                focusedContainerColor = VaultColors.Surface1,
                unfocusedContainerColor = VaultColors.Surface1,
                focusedTextColor = VaultColors.TextPrimary,
                unfocusedTextColor = VaultColors.TextPrimary,
                cursorColor = VaultColors.Accent
            )
        )

        AnimatedVisibility(
            visible = state.isSearching,
            enter = fadeIn(tween(150)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(120)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
        ) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                color = VaultColors.Accent,
                trackColor = VaultColors.Surface2
            )
        }

        Spacer(Modifier.height(16.dp))

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
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
    progressFlow: Flow<Int?>,
    downloaded: Boolean,
    onClick: () -> Unit,
    onCancelDownload: () -> Unit = {},
    onMore: () -> Unit = {},
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    modifier: Modifier = Modifier
) {
    val displayedProgress by progressFlow.collectAsState(initial = null)
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
                color = if (isPlaying) VaultColors.Accent else VaultColors.TextPrimary,
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
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.scanLocalAudio(context)
    }
    val adapter = remember { LibraryFeedAdapter(vm) }

    AndroidView(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = {
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).also { lm ->
                    lm.isItemPrefetchEnabled = true
                    lm.initialPrefetchItemCount = 10
                }
                setItemViewCacheSize(12)
                recycledViewPool.setMaxRecycledViews(0, 12)
                itemAnimator = null
                setHasFixedSize(false)
                setPadding(20, 20, 20, 20)
                clipToPadding = true
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            adapter.update(
                tracks = state.downloaded,
                playlists = state.playlists,
                customAlbums = state.albums.filter { a -> a.id.startsWith("album:custom:") },
                selectedTrackId = state.selectedTrack?.id,
                isPlaying = isPlaying
            )
        }
    )
}

@Composable
private fun PlaylistScreen(state: MusicUiState, vm: MusicVaultViewModel) {
    var showAlbumDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val adapter = remember { AlbumFeedAdapter(vm) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).also { lm ->
                    lm.isItemPrefetchEnabled = true
                    lm.initialPrefetchItemCount = 8
                }
                setItemViewCacheSize(10)
                recycledViewPool.setMaxRecycledViews(AlbumFeedAdapter.TYPE_HEADER, 2)
                recycledViewPool.setMaxRecycledViews(AlbumFeedAdapter.TYPE_ALBUM, 10)
                itemAnimator = null
                setHasFixedSize(false)
                setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
                clipToPadding = false
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            adapter.update(state.playlists)
        }
    )

    if (showAlbumDialog) {
        CreateCollectionDialog("Nuevo álbum", "Nombre del álbum", state.downloaded, vm::createPlaylist) {
            showAlbumDialog = false
        }
    }

    // The add button lives in the header holder; keep the dialog callback in sync.
    adapter.onAddAlbum = { showAlbumDialog = true }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).roundToInt()

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
private fun LocalAlbumDetail(
    album: Album,
    tracks: List<Track>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val context = LocalContext.current
    val albumAdapter = remember(vm) { LocalAlbumDetailAdapter(vm) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx).also {
                    it.isItemPrefetchEnabled = true
                    it.initialPrefetchItemCount = 6
                }
                setHasFixedSize(false)
                setItemViewCacheSize(10)
                recycledViewPool.setMaxRecycledViews(LocalAlbumDetailAdapter.TYPE_TRACK, 12)
                itemAnimator = null
                adapter = albumAdapter
            }
        },
        update = { rv ->
            albumAdapter.update(
                album = album,
                tracks = tracks,
                playlists = playlists,
                albums = albums.filter { it.id.startsWith("album:custom:") },
                selectedTrackId = selectedTrackId,
                isPlaying = isPlaying,
                onBack = vm::closeLocalAlbum
            )
            if (rv.adapter !== albumAdapter) rv.adapter = albumAdapter
        }
    )
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
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = VaultColors.Accent, trackColor = VaultColors.Surface3)
                tracks.isEmpty() -> Text("No hay canciones disponibles para este álbum.", color = VaultColors.TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
        }
        items(tracks, key = { "${it.provider}:${it.id}" }) { track ->
            val localId = "${track.provider}:recording:${track.id}"
            val downloaded = localId in downloadedIds
            RemoteTrackRow(
                track = track,
                progressFlow = remember(localId) { vm.downloadProgressFor(localId) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = Color.Black)
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
            TrackRowWithProgress(
                track = track,
                progressFlow = remember(track.id) { vm.downloadProgressFor(track.id) },
                onClick = { vm.selectTrack(track) },
                isPlaying = isPlaying && selectedTrackId == track.id,
                onPlayToggle = { if (selectedTrackId == track.id) vm.togglePlayback() else vm.selectTrack(track) },
                onCancelDownload = { vm.cancelDownload(track.id) },
                actions = { TrackActionsMenu(track, playlists, albums.filter { it.id.startsWith("album:custom:") }, vm, vm.isFavorite(track.id)) },
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
    // Progress ticks every 500 ms while playing; a matching linear tween between
    // samples renders as continuous motion instead of a sprint-and-stop retarget.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(VaultColors.Surface1)
            .border(1.dp, VaultColors.Divider, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = VaultColors.Accent,
            trackColor = Color.Transparent
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(track.coverUri, track.title, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, VaultColors.Divider, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = VaultColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFamily = VaultFonts.Display)
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                formatTime(positionMs),
                color = VaultColors.TextTertiary,
                fontSize = 11.sp,
                fontFamily = VaultFonts.Mono,
                modifier = Modifier.padding(end = 2.dp)
            )
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Me gusta",
                    tint = if (favorite) VaultColors.Favorite else VaultColors.TextPrimary
                )
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = VaultColors.OnAccent, modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(VaultColors.Accent)
                    .padding(4.dp)
                )
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
    // Deterministic archive number for the track, used as the catalog tag.
    val catalogNumber = remember(track.id) { "VAULT-%04d".format(abs(track.id.hashCode()) % 10000) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VaultColors.Background)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeFullPlayer) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", tint = VaultColors.TextPrimary, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "VAULT · FICHA DE AUDIO",
                    color = VaultColors.TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = VaultFonts.Mono,
                    letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            // Hairline "mat" frame around the artwork: the archive-card signature
            // replaces the old color-to-black glow.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .scale(scale)
                    .shadow(elevation = 20.dp, shape = RoundedCornerShape(16.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VaultColors.Surface1)
                    .border(1.dp, VaultColors.Divider, RoundedCornerShape(14.dp))
                    .padding(8.dp)
            ) {
                AsyncImage(
                    track.coverUri, track.title,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                catalogNumber,
                color = VaultColors.Accent,
                fontSize = 11.sp,
                fontFamily = VaultFonts.Mono,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
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
        Text(
            track.title, color = VaultColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, fontFamily = VaultFonts.Display, letterSpacing = (-0.5).sp
        )
        Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 15.sp, maxLines = 1)
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
                        color = VaultColors.Accent,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - trackHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(activeX, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                    )
                }
                drawCircle(
                    color = VaultColors.Accent.copy(alpha = 0.16f),
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
            Text(formatTime(if (sliderDragging) sliderPosition.toLong() else position), color = VaultColors.TextSecondary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
            Text(formatTime(duration), color = VaultColors.TextSecondary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
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

            // Brass seal: the play button is the engraved stamp of the vault.
            FilledIconButton(
                onClick = vm::togglePlayback,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = VaultColors.Accent,
                    contentColor = VaultColors.OnAccent
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
                    tint = if (shuffle) VaultColors.Accent else VaultColors.TextPrimary,
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
    downloadDirLabel: String,
    onCrossfadeChange: (Int) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerBand: (Int, Short) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onUseInternalFolder: () -> Unit,
    onClose: () -> Unit
) {
    var eqEnabled by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
                Text("Ajustes", color = VaultColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = VaultFonts.Display, letterSpacing = (-0.75).sp)
            }
        }
        item {
            SettingsSection("REPRODUCCIÓN") {
                Text("Crossfade", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                // Keep the drag value local so a 60 Hz gesture never recomposes the whole
                // app (crossfadeSeconds feeds the root state combine) nor hits prefs.
                var dragValue by remember { mutableStateOf<Float?>(null) }
                val shownSeconds = dragValue?.toInt() ?: crossfadeSeconds
                Text(
                    if (shownSeconds == 0) "Desactivado" else "Mezcla los últimos ${shownSeconds}s con el inicio de la siguiente canción.",
                    color = VaultColors.TextTertiary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = dragValue ?: crossfadeSeconds.toFloat(),
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.toInt()?.let(onCrossfadeChange)
                            dragValue = null
                        },
                        valueRange = 0f..12f,
                        steps = 11,
                        colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (shownSeconds == 0) "OFF" else "${shownSeconds}s",
                        color = VaultColors.Accent,
                        fontSize = 12.sp,
                        fontFamily = VaultFonts.Mono,
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
        item {
            SettingsSection("ALMACENAMIENTO") {
                Text("Ruta de descargas", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(downloadDirLabel, color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Los archivos nuevos se guardarán en esta carpeta. Los ya descargados no se mueven.",
                    color = VaultColors.TextTertiary, fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPickDownloadFolder,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = VaultColors.OnAccent),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Elegir carpeta", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onUseInternalFolder,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary)
                    ) {
                        Text("Usar carpeta interna", fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            SettingsSection("ECUALIZADOR") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ecualizador nativo", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            if (equalizerBands.isEmpty()) "Esperando sesión de audio..." else "Bandas: ${equalizerBands.size}",
                            color = VaultColors.TextSecondary, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = { eqEnabled = it; onEqualizerEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = VaultColors.OnAccent, checkedTrackColor = VaultColors.Accent)
                    )
                }
                equalizerBands.forEach { band ->
                    Column {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("${band.centerFrequencyHz} Hz", color = VaultColors.TextPrimary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
                            Text("${band.level / 100f} dB", color = VaultColors.TextSecondary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
                        }
                        Slider(
                            value = band.level.toFloat(),
                            onValueChange = { onEqualizerBand(band.index, it.toInt().toShort()) },
                            valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3)
                        )
                    }
                }
                Text(
                    "El ecualizador usa el efecto Equalizer nativo de Android y la sesión de audio de Media3.",
                    color = VaultColors.TextTertiary, fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VaultColors.Surface1)
            .border(1.dp, VaultColors.Divider, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, color = VaultColors.Accent, fontSize = 10.sp, fontFamily = VaultFonts.Mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        content()
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
                    itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
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
                                    color = if (index == currentIndex) VaultColors.Accent else VaultColors.TextPrimary,
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
    // Custom "filing drawer" bar: a short brass tab marks the active section,
    // labels are mono caps like archive drawer tags.
    Column(
        Modifier
            .fillMaxWidth()
            .background(VaultColors.SurfaceBase)
    ) {
        HorizontalDivider(color = VaultColors.Divider, thickness = 1.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(66.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(AppSection.HOME, "INICIO", section, onSelect) {
                Icon(if (section == AppSection.HOME) Icons.Default.Home else Icons.Outlined.Home, null)
            }
            BottomBarItem(AppSection.SEARCH, "BUSCAR", section, onSelect) {
                Icon(if (section == AppSection.SEARCH) Icons.Default.Search else Icons.Outlined.Search, null)
            }
            BottomBarItem(AppSection.LIBRARY, "BIBLIOTECA", section, onSelect) {
                Icon(if (section == AppSection.LIBRARY) Icons.Default.LibraryMusic else Icons.Outlined.LibraryMusic, null)
            }
            BottomBarItem(AppSection.PLAYLISTS, "ÁLBUMES", section, onSelect) {
                Icon(if (section == AppSection.PLAYLISTS) Icons.Default.QueueMusic else Icons.Outlined.QueueMusic, null)
            }
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    item: AppSection,
    label: String,
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    icon: @Composable () -> Unit
) {
    val active = section == item
    val tabWidth by animateDpAsState(
        targetValue = if (active) 22.dp else 0.dp,
        animationSpec = tween(200),
        label = "navTab"
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSelect(item) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .width(tabWidth)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(VaultColors.Accent)
        )
        Spacer(Modifier.height(4.dp))
        CompositionLocalProvider(LocalContentColor provides if (active) VaultColors.Accent else VaultColors.TextTertiary) {
            icon()
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontFamily = VaultFonts.Mono,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) VaultColors.TextPrimary else VaultColors.TextTertiary,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
}

@Composable
internal fun AlbumCard(
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
                    model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).size(150).memoryCacheKey("home-album:${album.coverUri}").diskCacheKey("home-album:${album.coverUri}").crossfade(false).build(),
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
internal fun TrackRowWithProgress(
    track: Track,
    progressFlow: Flow<Int?>,
    onClick: () -> Unit,
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    onCancelDownload: () -> Unit = {},
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val progress by progressFlow.collectAsState(initial = null)
    TrackRow(
        track = track,
        onClick = onClick,
        progress = progress,
        isPlaying = isPlaying,
        onPlayToggle = onPlayToggle,
        onCancelDownload = onCancelDownload,
        actions = actions,
        modifier = modifier
    )
}

@Composable
internal fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    progress: Int? = null,
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = onClick,
    onCancelDownload: () -> Unit = {},
    actions: @Composable () -> Unit = {},
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
                color = if (isPlaying) VaultColors.Accent else VaultColors.TextPrimary,
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
                actions()
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
internal fun TrackActionsMenu(
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
