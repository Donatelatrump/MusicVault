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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.media3.common.Player
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
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
import com.musicvault.app.ui.theme.VaultIcons
import com.musicvault.app.ui.theme.VaultFonts
import com.musicvault.app.lyrics.LyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.vector.ImageVector

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
    val eqEnabled by vm.eqEnabled.collectAsState()
    val eqPreset by vm.eqPreset.collectAsState()
    val loudnessGainMb by vm.loudnessGainMb.collectAsState()
    val bassBoostAvailable by vm.bassBoostAvailable.collectAsState()
    val virtualizerAvailable by vm.virtualizerAvailable.collectAsState()
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
                    eqEnabled = eqEnabled,
                    eqPreset = eqPreset,
                    loudnessGainMb = loudnessGainMb,
                    bassBoostAvailable = bassBoostAvailable,
                    virtualizerAvailable = virtualizerAvailable,
                    playerStyle = state.playerStyle,
                    downloadDirLabel = downloadDirLabel,
                    onCrossfadeChange = vm::setCrossfadeSeconds,
                    onPlayerStyleChange = vm::setPlayerStyle,
                    onEqualizerEnabled = vm::setEqualizerEnabled,
                    onEqualizerBand = vm::setEqualizerBand,
                    onEqualizerPreset = vm::applyEqualizerPreset,
                    onLoudnessChange = vm::setLoudnessGain,
                    onBassBoostStrength = vm::setBassBoostStrength,
                    onVirtualizerStrength = vm::setVirtualizerStrength,
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
                        visible = state.errorMessage != null || state.providerMessage != null,
                        enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
                    ) {
                        val msg = state.errorMessage ?: state.providerMessage
                        val dismiss: () -> Unit = if (state.errorMessage != null) vm::clearError else vm::clearProviderMessage
                        msg?.let {
                            AssistChip(
                                onClick = dismiss,
                                label = { Text(it, maxLines = if (state.errorMessage != null) 8 else 3) },
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
                    FullPlayerScreen(track, isPlaying, state.shuffle, queue, queueIndex, favoriteIds, state.playerStyle, vm)
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
    // Filtered once per albums emission instead of once per recomposition.
    val customAlbums = remember(state.albums) { state.albums.filter { it.id.startsWith("album:custom:") } }
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
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                setHasFixedSize(false)
                setPadding(20, 20, 20, 20)
                clipToPadding = true
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            // Skip adapter pushes while the tab is hidden (alpha 0): this
            // screen recomposes on every root state emission, but pushing
            // into a hidden RecyclerView is wasted work. Reactivating the
            // section flips state.section, so the first update afterwards
            // repushes everything.
            if (state.section == AppSection.HOME) {
                adapter.update(
                    recommendedTracks = state.recommendedTracks,
                    recommendedAlbums = state.recommendedAlbums,
                    recommendations = state.recommendedTracks,
                    playlists = state.playlists,
                    customAlbums = customAlbums,
                    albumDownloads = albumDownloads,
                    selectedTrackId = state.selectedTrack?.id,
                    isPlaying = isPlaying
                )
            }
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
                        Icon(if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Descargar y reproducir", tint = VaultColors.TextPrimary)
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
    // The downloaded-id set only changes when the library changes; rebuilding it
    // on every unrelated recomposition was pure churn for the search list.
    val downloadedIds = remember(state.downloaded) {
        state.downloaded
            .asSequence()
            .filter { it.isDownloaded }
            .map { it.id }
            .toSet()
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
            onValueChange = vm::updateSearchInput,
            Modifier.fillMaxWidth(),
            placeholder = { Text("Canción, artista o álbum", color = VaultColors.TextTertiary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = VaultColors.TextSecondary) },
            trailingIcon = {
                IconButton(onClick = { vm.submitSearch() }, enabled = state.search.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Buscar", tint = VaultColors.Accent)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
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

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Skeleton placeholders while the search is in flight and no rows
            // exist yet: the shape of the final content instead of a blank gap.
            if (state.isSearching && state.remoteTracks.isEmpty() && state.remoteAlbums.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    repeat(7) { SkeletonRow() }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context).apply {
                            isItemPrefetchEnabled = true
                            initialPrefetchItemCount = 8
                        }
                        this.adapter = adapter
                        setHasFixedSize(true)
                        itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                        setItemViewCacheSize(12)
                        recycledViewPool.setMaxRecycledViews(1, 8)
                        recycledViewPool.setMaxRecycledViews(2, 12)
                        recycledViewPool.setMaxRecycledViews(3, 18)
                        overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                    }
                },
                update = { recyclerView ->
                    // Hidden-tab guard: don't rebuild items while the tab is
                    // hidden; results only change while the tab is visible.
                    if (state.section == AppSection.SEARCH) {
                        adapter.updateItems(resultItems)

                        // Skip state pushes while the tab is hidden (alpha 0): the
                        // screen keeps observing flows (download progress ticks),
                        // but pushing state into a hidden RecyclerView is wasted
                        // work. The first update after reactivation repushes all.
                        adapter.updateState(
                            downloadedIds = downloadedIds,
                            progress = downloadProgress,
                            selectedTrackId = state.selectedTrack?.id,
                            isPlaying = isPlaying
                        )
                    }
                }
            )
        }
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
    Box(modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(VaultIcons.Album, null, tint = VaultColors.TextTertiary)
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
                    Icon(if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Reproducir", tint = VaultColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(state: MusicUiState, isPlaying: Boolean, vm: MusicVaultViewModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.scanLocalAudio(context)
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val adapter = remember { LibraryFeedAdapter(vm) }
    val customAlbums = remember(state.albums) { state.albums.filter { a -> a.id.startsWith("album:custom:") } }

    LaunchedEffect(state.section) {
        if (state.section == AppSection.LIBRARY && !permissionRequested) {
            permissionRequested = true
            if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                vm.scanLocalAudio(context)
            } else {
                launcher.launch(permission)
            }
        }
    }

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
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                setHasFixedSize(false)
                setPadding(20, 20, 20, 20)
                clipToPadding = true
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            // Hidden-tab guard: same rationale as HomeScreen.
            if (state.section == AppSection.LIBRARY) {
                adapter.update(
                    tracks = state.downloaded,
                    playlists = state.playlists,
                    customAlbums = customAlbums,
                    selectedTrackId = state.selectedTrack?.id,
                    isPlaying = isPlaying
                )
            }
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
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                setHasFixedSize(false)
                setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
                clipToPadding = false
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = {
            // Hidden-tab guard: same rationale as HomeScreen.
            if (state.section == AppSection.PLAYLISTS) {
                adapter.update(state.playlists)
            }
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
    val customAlbums = remember(albums) { albums.filter { it.id.startsWith("album:custom:") } }

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
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                adapter = albumAdapter
            }
        },
        update = { rv ->
            albumAdapter.update(
                album = album,
                tracks = tracks,
                playlists = playlists,
                albums = customAlbums,
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
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
                        Icon(VaultIcons.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(48.dp))
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
                        Icon(VaultIcons.Download, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (albumDownloading) "Descargando álbum" else "Descargar álbum", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                loading -> Column(Modifier.fillMaxWidth()) {
                    repeat(4) { SkeletonRow() }
                }
                tracks.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        "No se encontraron canciones para este álbum.",
                        color = VaultColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Puede ser que el proveedor no tenga el catálogo completo. Intenta abrir otro álbum del mismo artista.",
                        color = VaultColors.TextTertiary,
                        fontSize = 12.sp
                    )
                }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
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
                    Icon(VaultIcons.Shuffle, null, tint = VaultColors.TextPrimary)
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
                onClick = { vm.playPlaylistTrack(playlist, track) },
                isPlaying = isPlaying && selectedTrackId == track.id,
                onPlayToggle = { if (selectedTrackId == track.id) vm.togglePlayback() else vm.playPlaylistTrack(playlist, track) },
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
    // Full-bleed bar: spans the screen edge to edge above the bottom navigation,
    // no floating margins or rounded island.
    Column(
        Modifier
            .fillMaxWidth()
            .background(VaultColors.Surface1)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = VaultColors.Accent,
            trackColor = Color.Transparent
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
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
                Icon(if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow, null, tint = VaultColors.OnAccent, modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(VaultColors.Accent)
                    .padding(4.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(VaultColors.Divider))
    }
}

// Constant lead that compensates the audio output latency: the player's
// position advances when samples reach the audio sink, ~100ms before the
// user actually hears them, so synced lyrics must run slightly early.
private const val LYRICS_LEAD_MS = 100L

/**
 * Extrapolates the controller's position with the frame clock so the synced
 * lyrics follow playback every frame instead of every 500ms ticker update.
 *
 * The anchor is the controller's own measurement timestamp
 * (PlaybackProgress.sampledAtElapsed, elapsedRealtimeNanos). The previous
 * design anchored "now" at recomposition time, so every sample drifted by the
 * scheduling latency between the measurement and the next frame — lyrics ran
 * ahead on some songs and behind on others. Anchoring on the sample clock and
 * re-anchoring on every published snapshot keeps sync exact for play, pause,
 * seek, track changes and background playback.
 */
@Composable
private fun rememberLyricPositionState(
    progress: com.musicvault.app.player.PlayerController.PlaybackProgress,
    isPlaying: Boolean
): State<Long> {
    val state = remember { mutableLongStateOf(0L) }
    var anchorPos by remember { mutableLongStateOf(0L) }
    var anchorElapsed by remember { mutableLongStateOf(0L) }
    var anchorSample by remember { mutableLongStateOf(-1L) }
    // Re-anchor whenever a fresher sample arrives (covers play, seek, track
    // change and returning from the background). Small deviations between the
    // sample and the frame-clock extrapolation are ABSORBED (no visual snap):
    // snapping every 500ms made the position jitter backwards across stanza
    // boundaries, which flickered the highlight.
    if (progress.sampledAtElapsed != anchorSample) {
        val nowElapsed = android.os.SystemClock.elapsedRealtimeNanos()
        val extrapolated = anchorPos + (nowElapsed - anchorElapsed) / 1_000_000L
        val jumped = kotlin.math.abs(progress.positionMs - extrapolated) > 400L
        anchorSample = progress.sampledAtElapsed
        if (jumped || !isPlaying) {
            // Real discontinuity (seek, track change, pause): snap to the sample.
            anchorPos = progress.positionMs
            anchorElapsed = progress.sampledAtElapsed
            state.longValue = (progress.positionMs - LYRICS_LEAD_MS).coerceAtLeast(0L)
        } else {
            // Clock noise: keep the extrapolation continuous but gently pull it
            // toward the sampled truth (⅓ per sample) — no visible snap, no
            // permanent drift/lag.
            anchorPos = extrapolated + (progress.positionMs - extrapolated) / 3
            anchorElapsed = nowElapsed
        }
    }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Resume: anchor at the currently frozen position, then extrapolate
            // per frame until the next sample re-anchors. All math uses the
            // elapsedRealtimeNanos clock: withFrameNanos reports the frame in a
            // DIFFERENT epoch (System.nanoTime), so mixing both clocks made the
            // extrapolated position garbage and froze the lyrics until the next
            // seek/pause.
            anchorElapsed = android.os.SystemClock.elapsedRealtimeNanos()
            while (true) {
                withFrameNanos {
                    val nowElapsed = android.os.SystemClock.elapsedRealtimeNanos()
                    val next = (anchorPos + (nowElapsed - anchorElapsed) / 1_000_000L - LYRICS_LEAD_MS)
                        .coerceAtLeast(0L)
                    // Monotonic while playing: lyrics never step back, so the
                    // highlight cannot flicker at a stanza boundary.
                    if (next >= state.longValue) state.longValue = next
                }
            }
        } else {
            // Pause: freeze. Store the raw position (without the lead) so a
            // later resume extrapolates from the right anchor.
            anchorPos = state.longValue + LYRICS_LEAD_MS
        }
    }
    return state
}

@Composable
private fun FullPlayerScreen(track: Track, isPlaying: Boolean, shuffle: Boolean, queue: List<Track>, queueIndex: Int, favoriteIds: Set<String>, playerStyle: String, vm: MusicVaultViewModel) {
    val progress by vm.playbackProgress.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    val duration = progress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val position = progress.positionMs.coerceIn(0L, duration)
    var sliderPosition by remember(track.id) { mutableFloatStateOf(position.toFloat()) }
    var sliderDragging by remember(track.id) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(position, track.id) { if (!sliderDragging) sliderPosition = position.toFloat() }
    val lyricPosition = rememberLyricPositionState(progress, isPlaying)
    val repeatMode by vm.repeatMode.collectAsState()
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
            Spacer(Modifier.weight(1f))
            // Queue integrated in the header (mirrors the close button) instead
            // of a floating button at the bottom edge.
            IconButton(onClick = { showQueue = true }) {
                Icon(VaultIcons.QueueMusic, "Cola de reproducción", tint = VaultColors.TextSecondary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        // Flexible stage area: the lyrics sheet peeks at the bottom; dragging it
        // up expands the lyrics (controls rise, the vinyl fades out) and dragging
        // it down brings the vinyl back. Both transitions are one continuous
        // gesture because the sheet and the stage share the available height.
        val density = LocalDensity.current
        // Two 2-line stanzas need more room than the old 1-line peek.
        val peekHeight = 122.dp
        val peekPx = with(density) { peekHeight.toPx() }
        var areaPx by remember { mutableStateOf(0f) }
        var fixedPx by remember { mutableStateOf(0f) }
        val scope = rememberCoroutineScope()
        // Plain snapshot state as the single source of truth: drag deltas write
        // it synchronously (zero dispatch latency between finger and pixels),
        // while the settle animation runs on the frame clock and writes it per
        // frame. The previous Animatable + snapTo-per-event design queued every
        // drag delta through a coroutine dispatch, adding a full frame of lag.
        var reveal by remember { mutableFloatStateOf(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        fun revealLyrics(target: Float, initialVelocity: Float = 0f) {
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(
                    initialValue = reveal,
                    targetValue = target,
                    initialVelocity = initialVelocity,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = 0.001f)
                ) { value, _ -> reveal = value }
            }
        }
        val revealDelta = rememberDraggableState { delta ->
            settleJob?.cancel()
            val range = (areaPx - fixedPx - peekPx).coerceAtLeast(1f)
            reveal = (reveal - delta / range).coerceIn(0f, 1f)
        }
        // Boolean projections: recomposition only happens when these flip, not
        // on every frame of the drag (the raw value stays layout/draw-only).
        val showVinyl by remember { derivedStateOf { reveal < 0.995f } }
        val panelOpen by remember { derivedStateOf { reveal > 0.95f } }
        val expanded by remember { derivedStateOf { reveal > 0.5f } }
        val stageMaxPx = (areaPx - fixedPx - peekPx).coerceAtLeast(0f)

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().onSizeChanged { areaPx = it.height.toFloat() }) {
                // Vinyl stage: shrinks while the sheet rises; the disc fades and
                // slides down instead of being squashed. Height and the
                // alpha/scale are resolved from the live reveal value inside the
                // layout/draw phases, so the drag never recomposes this tree.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val h = (stageMaxPx * (1f - reveal)).roundToInt()
                                .coerceIn(0, constraints.maxHeight)
                            val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(placeable.width, h) { placeable.place(0, 0) }
                        }
                        .clipToBounds(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (showVinyl) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val r = reveal
                                    alpha = (1f - r * 1.6f).coerceIn(0f, 1f)
                                    val s = 1f - 0.12f * r
                                    scaleX = s
                                    scaleY = s
                                }
                        ) {
                            // One player, two interchangeable skins chosen in
                            // Settings. Both consume the same track/isPlaying
                            // state; only the visuals differ.
                            val stageModifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .aspectRatio(1f)
                            if (playerStyle == "circular") {
                                CircularVisualizer(
                                    coverUri = track.coverUri,
                                    title = track.title,
                                    isPlaying = isPlaying,
                                    seed = abs(track.id.hashCode()),
                                    modifier = stageModifier
                                )
                            } else {
                                VinylDisc(
                                    coverUri = track.coverUri,
                                    title = track.title,
                                    isPlaying = isPlaying,
                                    modifier = stageModifier
                                )
                            }
                        }
                    }
                }
                // Fixed block: metadata, seek and transport. It rides up as the
                // sheet below grows.
                Column(Modifier.fillMaxWidth().onSizeChanged { fixedPx = it.height.toFloat() }) {
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
        // Transport controls: five equal slots keep the layout balanced on any
        // screen width — the old center-offset design pushed Repeat/Queue off
        // screen on narrow devices. Shuffle and Repeat show a clearly muted
        // inactive state and the accent when active.
        Row(
            Modifier.fillMaxWidth().height(76.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val playScale by animateFloatAsState(
                if (isPlaying) 1f else 0.92f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "playButtonScale"
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(44.dp)) {
                    Icon(
                        VaultIcons.Shuffle,
                        "Aleatorio",
                        tint = if (shuffle) VaultColors.Accent else VaultColors.TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::previousTrack, modifier = Modifier.size(48.dp)) {
                    Icon(VaultIcons.SkipPrevious, "Anterior", tint = VaultColors.TextPrimary, modifier = Modifier.size(32.dp))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Brass seal: the play button is the engraved stamp of the vault.
                FilledIconButton(
                    onClick = vm::togglePlayback,
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer { scaleX = playScale; scaleY = playScale },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    )
                ) {
                    Icon(
                        if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow,
                        null,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::nextTrack, modifier = Modifier.size(48.dp)) {
                    Icon(VaultIcons.SkipNext, "Siguiente", tint = VaultColors.TextPrimary, modifier = Modifier.size(32.dp))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::cycleRepeatMode, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) VaultIcons.RepeatOne else VaultIcons.Repeat,
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repetir una vez"
                            Player.REPEAT_MODE_ALL -> "Repetir siempre"
                            else -> "Repetir"
                        },
                        tint = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> VaultColors.Accent
                            Player.REPEAT_MODE_ALL -> VaultColors.RepeatAll
                            else -> VaultColors.TextTertiary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        }

        // Lyrics sheet: shows a peek of the lyrics at the bottom. Drag it up to
        // read the full lyrics (the vinyl hides and the controls rise); drag it
        // down to bring the vinyl back. Tap the handle toggles too.
        Column(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = (peekPx + stageMaxPx * reveal).roundToInt()
                        .coerceIn(0, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .background(VaultColors.Surface1)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = revealDelta,
                    onDragStopped = { velocity ->
                        val range = (areaPx - fixedPx - peekPx).coerceAtLeast(1f)
                        val target = when {
                            velocity < -900f -> 1f
                            velocity > 900f -> 0f
                            else -> if (reveal > 0.45f) 1f else 0f
                        }
                        revealLyrics(target, -velocity / range)
                    }
                )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { revealLyrics(if (reveal > 0.5f) 0f else 1f) }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Letra", color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(VaultColors.Divider)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    if (expanded) "Ocultar letra" else "Ver letra",
                    tint = VaultColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (panelOpen) {
                    LyricsPanel(
                        lyrics = lyrics,
                        positionState = lyricPosition,
                        onRetry = { vm.loadLyrics(track) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Collapsed peek: a compact synced view — the current line
                    // enlarged and highlighted with the previous and next lines
                    // dimmed around it. The lookup runs in derived state, so it
                    // only recomposes when the line actually changes.
                    val synced = lyrics.synced
                    val plain = lyrics.plain
                    val peek by remember(synced, plain) {
                        derivedStateOf {
                            if (synced.isEmpty()) null
                            else {
                                val index = synced.indexOfLast { it.timeMs <= lyricPosition.value }
                                // Mini view: current + upcoming only. The already
                                // sung line is dropped so both stanzas get two
                                // full lines (long stanzas are never truncated).
                                synced.getOrNull(index)?.text to synced.getOrNull(index + 1)?.text
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val view = peek
                        when {
                            view != null -> {
                                Text(
                                    view.first ?: " ",
                                    color = VaultColors.Accent,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 22.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Text(
                                    view.second ?: " ",
                                    color = VaultColors.TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            plain != null -> Text(
                                plain.lineSequence().firstOrNull { it.isNotBlank() } ?: "Letra no disponible",
                                color = VaultColors.TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            else -> Text(
                                "Letra no disponible",
                                color = VaultColors.TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        }
        }
    }

    if (showQueue) {
        QueueBottomSheet(queue, queueIndex, vm) { showQueue = false }
    }
}

@Composable
private fun SkeletonRow(modifier: Modifier = Modifier) {
    // Ghost row with the shape of a real result row (cover + two text lines).
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonPulse"
    )
    Row(modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(VaultColors.Surface2.copy(alpha = pulse)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.68f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(VaultColors.Surface2.copy(alpha = pulse)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.42f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(VaultColors.Surface2.copy(alpha = pulse)))
        }
    }
}

/**
 * Turntable deck: the record IS the album artwork, spinning on a slipmat over
 * the plinth. The tonearm rotates on its own pivot with constant length; when
 * paused it swings aside onto a rest clip beside the record, so it never
 * hovers over the artwork.
 *
 * The deck carries its own visual hardware: a pitch-style RPM fader that
 * retimes the platter animation (16–78 RPM, 33⅓ by default, detented at the
 * classic 33/45/78 speeds), a speed selector that snaps to those detents, and
 * decorative keys (power, stop, transport) that are engraved look-only — none
 * of them touch the playback engine.
 */
@Composable
private fun VinylDisc(coverUri: String?, title: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
    // Visual platter speed. Purely cosmetic: it scales the spin animation and
    // never reaches the audio pipeline. rememberSaveable keeps it across
    // configuration changes.
    var rpm by rememberSaveable { mutableFloatStateOf(33.33f) }
    // Angle accumulates per frame so a pause freezes the record mid-spin
    // instead of snapping back to zero. The rate is read from the RPM state
    // every frame, so dragging the fader retimes the platter fluidly.
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle = (angle + (now - lastFrame) / 1_000_000f * (rpm * 360f / 60_000f)) % 360f
                lastFrame = now
            }
        }
    }
    // 0 = tip over the record edge, 1 = parked on the rest clip.
    val armParked by animateFloatAsState(
        if (isPlaying) 0f else 1f,
        tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "armParked"
    )
    BoxWithConstraints(modifier.padding(10.dp)) {
        val recordDiameter = minOf(maxWidth, maxHeight) * 0.64f
        val centerX = maxWidth * 0.36f
        val centerY = maxHeight * 0.5f

        // Plinth: the deck body.
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(VaultColors.Surface1)
                .border(1.dp, VaultColors.Divider, RoundedCornerShape(20.dp))
        )

        // Deck markings: slipmat ring under the record.
        Canvas(Modifier.fillMaxSize()) {
            val ringCenter = Offset(size.width * 0.36f, size.height * 0.5f)
            val ringRadius = (size.minDimension * 0.64f) / 2f + 4.dp.toPx()
            drawCircle(Color(0xFF26221E), radius = ringRadius + 3.dp.toPx(), center = ringCenter)
            drawCircle(Color(0xFF141210), radius = ringRadius, center = ringCenter, style = Stroke(width = 1.dp.toPx()))
        }
        // Power key + status LED + model plate (top-left). The LED lights up
        // with playback. Decorative hardware: wired to nothing on purpose.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(VaultColors.Surface2)
                    .border(1.dp, VaultColors.Divider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(VaultIcons.Power, null, tint = VaultColors.TextTertiary, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(5.dp))
            val ledColor by animateColorAsState(
                if (isPlaying) Color(0xFF7E8F6E) else VaultColors.Divider,
                tween(320), label = "deckLed"
            )
            Box(Modifier.size(5.dp).clip(CircleShape).background(ledColor))
            Spacer(Modifier.width(7.dp))
            Text(
                "VAULT HV-01  ·  SIDE A",
                color = VaultColors.TextTertiary, fontSize = 9.sp, fontFamily = VaultFonts.Mono, letterSpacing = 1.sp
            )
        }
        // Hardware panel on the right edge: speed selector on top (snaps the
        // visual platter to 33/45/78) and transport keys under it. The keys
        // mirror the deck look only — playback stays with the controls below.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
        ) {
            listOf(33.33f to "33", 45f to "45", 78f to "78").forEach { (speed, label) ->
                val selected = abs(rpm - speed) < 1.5f
                Box(
                    Modifier
                        .width(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) VaultColors.AccentSoft else VaultColors.Surface2)
                        .border(1.dp, if (selected) VaultColors.Accent else VaultColors.Divider, RoundedCornerShape(7.dp))
                        .clickable { rpm = speed }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) VaultColors.Accent else VaultColors.TextTertiary,
                        fontSize = 10.sp,
                        fontFamily = VaultFonts.Mono
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            DeckKey(VaultIcons.Stop, tint = VaultColors.TextTertiary)
            DeckKey(VaultIcons.SkipPrevious, tint = VaultColors.TextTertiary)
            DeckKey(
                if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow,
                tint = if (isPlaying) VaultColors.Accent else VaultColors.TextTertiary
            )
            DeckKey(VaultIcons.SkipNext, tint = VaultColors.TextTertiary)
        }
        // Pitch-style RPM fader (bottom strip): a groove track with detents at
        // the classic speeds. Drag or tap it to retime the platter fluidly; the
        // current speed is engraved to the right in mono type.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun setFromX(x: Float) {
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val fraction = x.coerceIn(0f, width) / width
                                rpm = 16f + fraction * (78f - 16f)
                            }
                            setFromX(down.position.x)
                            val slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                                setFromX(change.position.x)
                            }
                            if (slop != null) {
                                drag(slop.id) { change ->
                                    change.consume()
                                    setFromX(change.position.x)
                                }
                            }
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val midY = size.height / 2f
                    fun xFor(value: Float) = (value - 16f) / (78f - 16f) * size.width
                    // Groove track.
                    drawLine(
                        Color(0xFF141210),
                        Offset(0f, midY), Offset(size.width, midY),
                        strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round
                    )
                    drawLine(
                        VaultColors.Divider,
                        Offset(0f, midY), Offset(size.width, midY),
                        strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round
                    )
                    // Detents at the classic speeds.
                    listOf(33.33f, 45f, 78f).forEach { detent ->
                        drawLine(
                            VaultColors.Surface3,
                            Offset(xFor(detent), midY - 6.dp.toPx()),
                            Offset(xFor(detent), midY + 6.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    // Filled run up to the thumb + brass fader knob.
                    val thumbX = xFor(rpm)
                    drawLine(
                        VaultColors.AccentDeep,
                        Offset(0f, midY), Offset(thumbX, midY),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
                    )
                    drawCircle(VaultColors.Accent, radius = 6.dp.toPx(), center = Offset(thumbX, midY))
                    drawCircle(Color(0xFF141210), radius = 2.dp.toPx(), center = Offset(thumbX, midY))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${rpm.roundToInt()} RPM",
                color = VaultColors.Accent,
                fontSize = 11.sp,
                fontFamily = VaultFonts.Mono,
                letterSpacing = 1.sp
            )
        }
        // The record: the album artwork, spinning on the slipmat.
        Box(
            Modifier
                .offset(x = centerX - recordDiameter / 2, y = centerY - recordDiameter / 2)
                .size(recordDiameter)
                .graphicsLayer { rotationZ = angle }
                .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .border(2.dp, VaultColors.Surface3, CircleShape)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUri)
                    // Cap the decode: the record never renders larger than
                    // ~300dp, a full-res decode here was pure memory waste.
                    .size(512)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Spindle over the artwork (static).
        Box(
            Modifier
                .offset(x = centerX - 5.dp, y = centerY - 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(VaultColors.Background)
                .border(1.dp, VaultColors.Divider, CircleShape)
        )
        // Tonearm rotates on its pivot (top-right of the deck) with CONSTANT
        // length. Playing: tip over the record edge (0.80R from the record
        // center, along the pivot→center line). Parked: it swings by an angle
        // solved from the law of cosines so the tip clears the record
        // (≥1.25R) and rests on a clip beside it — never over the artwork.
        Canvas(Modifier.fillMaxSize()) {
            val recordCenter = Offset(size.width * 0.36f, size.height * 0.5f)
            val recordRadius = (size.minDimension * 0.64f) / 2f
            val pivot = Offset(size.width * 0.88f, size.height * 0.14f)
            val toCenter = recordCenter - pivot
            val pivotToCenter = kotlin.math.sqrt(toCenter.x * toCenter.x + toCenter.y * toCenter.y)
            val armUnit = toCenter / pivotToCenter
            val armLength = pivotToCenter - recordRadius * 0.80f
            val clearance = recordRadius * 1.25f
            val cosSwing = (pivotToCenter * pivotToCenter + armLength * armLength - clearance * clearance) /
                (2f * pivotToCenter * armLength)
            val maxSwing = (40f * Math.PI.toFloat() / 180f)
            val swing = kotlin.math.acos(cosSwing.coerceIn(-1f, 1f)).coerceIn(0f, maxSwing)
            val sinSwing = kotlin.math.sin(swing)
            val armAngle = -swing * armParked
            val cosA = kotlin.math.cos(armAngle)
            val sinA = kotlin.math.sin(armAngle)
            val armDir = Offset(armUnit.x * cosA - armUnit.y * sinA, armUnit.x * sinA + armUnit.y * cosA)
            val tip = pivot + armDir * armLength
            val armVector = tip - pivot

            // Rest clip under the parked tip.
            val restDir = Offset(armUnit.x * cosSwing + armUnit.y * sinSwing, -armUnit.x * sinSwing + armUnit.y * cosSwing)
            val restTip = pivot + restDir * armLength
            drawLine(
                color = Color(0xFF26221E),
                start = restTip,
                end = restTip + Offset(0f, 14.dp.toPx()),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Counterweight stub behind the pivot, opposite the arm.
            drawLine(
                color = Color(0xFF8F897D),
                start = pivot,
                end = pivot - armVector * 0.16f,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFB8B2A6),
                start = pivot,
                end = tip,
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(Color(0xFF8F897D), radius = 7.dp.toPx(), center = pivot)
            drawCircle(Color(0xFF3A362F), radius = 3.dp.toPx(), center = pivot)
            drawCircle(VaultColors.Accent, radius = 5.dp.toPx(), center = tip)
        }
    }
}

/** Decorative deck key: pure turntable dressing, performs no action. */
@Composable
private fun DeckKey(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(VaultColors.Surface2)
            .border(1.dp, VaultColors.Divider, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
    }
}

/**
 * Circular visualizer player: the artwork is a large centered disc and the
 * music is drawn as a ring of radial bars around it. The ring is a stylized
 * spectrum — bass bars sit at the top and pulse wide and slow (a soft beat
 * ring hugs the artwork), mids run down the sides, treble bars near the
 * bottom flutter fast and short. Values are generated procedurally from the
 * playback state (energy eases in on play, out to a calm shimmer on pause),
 * so the visualization reads the same isPlaying/track data as the vinyl skin
 * and never touches the audio pipeline.
 */
@Composable
private fun CircularVisualizer(
    coverUri: String?,
    title: String,
    isPlaying: Boolean,
    seed: Int,
    modifier: Modifier = Modifier
) {
    // Song energy follows the play state; paused shrinks to a quiet idle glow.
    val energy by animateFloatAsState(
        if (isPlaying) 1f else 0.16f,
        tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "vizEnergy"
    )
    // Draw-phase clock: frameNs is only read inside Canvas, so updating it
    // invalidates just the draw pass — no recomposition at 60–120 Hz.
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> frameNs = now - t0 }
        }
    }
    BoxWithConstraints(modifier.padding(10.dp)) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side)) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val artRadius = size.minDimension * 0.24f
                val r0 = artRadius + 9.dp.toPx()
                val maxLen = size.minDimension / 2f - r0 - 5.dp.toPx()
                val t = frameNs / 1_000_000_000f
                val twoPi = 2f * Math.PI.toFloat()
                val s = (seed % 9973) / 9973f
                // Slow dynamics envelope: swells and dips like song sections.
                val swell = 0.72f + 0.28f * sin(t * 0.19f + s * 17f) * sin(t * 0.113f + s * 5f)
                // Beat pulse: a soft kick that mostly lifts the bass bars.
                val beatHz = 1f / (0.58f + s * 0.3f)
                val kickPhase = (t * beatHz + s * 3f) % 1f
                val kick = if (energy > 0.5f) {
                    maxOf(0f, cos(kickPhase * twoPi)).pow(20)
                } else 0f

                // Hairline ring where the bars start + a beat ring that
                // breathes with the kick around the artwork.
                drawCircle(VaultColors.Divider, radius = r0 - 1.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(
                    VaultColors.Accent.copy(alpha = 0.08f + 0.20f * kick * energy),
                    radius = r0 - 5.dp.toPx() + kick * 5.dp.toPx(),
                    center = center,
                    style = Stroke(1.5.dp.toPx())
                )

                val half = 72
                for (i in 0 until half) {
                    val p = i / (half - 1f) // 0 = bass (top) → 1 = treble (bottom)
                    // Deterministic per-bar phases from the song seed.
                    val n1 = sin(i * 12.9898f + seed) * 43758.5453f
                    val phase = (n1 - floor(n1)) * twoPi
                    val n2 = sin(i * 78.233f + seed * 1.7f) * 12543.21f
                    val phase2 = (n2 - floor(n2)) * twoPi
                    // Temporal speed: bass sways slowly, treble flutters fast.
                    val speed = 0.9f + p * p * 6.5f
                    val osc = 0.5f + 0.5f * sin(t * speed * twoPi * 0.5f + phase)
                    val shimmer = 0.5f + 0.5f * sin(t * (1.7f + p * 5f) * twoPi * 0.5f + phase2)
                    // Spatial traveling wave so the ring moves as a whole.
                    val wave = 0.82f + 0.18f * sin(p * 9f - t * 2.1f)
                    // Amplitude: bass wide, treble short.
                    val amp = (1.02f - 0.58f * p) * (0.55f + 0.45f * osc) * (0.62f + 0.38f * shimmer) * wave
                    val bassBoost = (1f - p) * (1f - p) * kick * 0.45f
                    val v = (energy * swell * (amp + bassBoost)).coerceIn(0.05f, 1f)
                    val len = v * maxLen
                    val color = when {
                        p < 0.34f -> VaultColors.AccentDeep
                        p < 0.67f -> VaultColors.Accent
                        else -> Color(0xFFD8CFBA)
                    }
                    val alpha = 0.35f + 0.65f * v
                    // Mirror the bar across the vertical axis: right + left.
                    for (deg in listOf(-90f + p * 180f, -90f - p * 180f)) {
                        val a = deg * (Math.PI.toFloat() / 180f)
                        val dir = Offset(cos(a), sin(a))
                        drawLine(
                            color.copy(alpha = alpha),
                            start = center + dir * r0,
                            end = center + dir * (r0 + len),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            // The artwork: a large centered disc the ring never covers.
            Box(
                Modifier
                    .size(side * 0.48f)
                    .align(Alignment.Center)
                    .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(CircleShape)
                    .border(1.5.dp, VaultColors.Surface3, CircleShape)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        // Same cap as VinylDisc: shares one decoded entry with
                        // the vinyl skin instead of decoding full-res.
                        .size(512)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** Lyrics stage: synced lines with the current one highlighted, or plain text. */
@Composable
private fun LyricsPanel(
    lyrics: LyricsUiState,
    positionState: State<Long>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        lyrics.loading -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp, color = VaultColors.Accent)
            Spacer(Modifier.height(10.dp))
            Text("Buscando letra…", color = VaultColors.TextTertiary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
        }
        lyrics.notFound -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Letra no disponible", color = VaultColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text("Reintentar", color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
            }
        }
        lyrics.synced.isNotEmpty() -> SyncedLyricsList(lyrics.synced, positionState, modifier)
        else -> Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                lyrics.plain.orEmpty(),
                color = VaultColors.TextSecondary,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun SyncedLyricsList(lines: List<LyricLine>, positionState: State<Long>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // The lookup runs every frame against the live position, but derived state
    // only invalidates the UI when the highlighted line actually changes.
    val currentLine by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionState.value }.takeIf { it >= 0 } }
    }
    // Manual scrolling holds the auto-scroll for a few seconds so the user can
    // browse freely before the highlight resumes steering.
    var holdAutoScroll by remember { mutableStateOf(false) }
    LaunchedEffect(currentLine) {
        val target = currentLine
        if (target != null && !holdAutoScroll) {
            listState.animateScrollToItem(index = (target - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(holdAutoScroll) {
        if (holdAutoScroll) {
            delay(4_000L)
            holdAutoScroll = false
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    holdAutoScroll = true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 40.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val activeIndex = currentLine
            val active = index == activeIndex
            // Karaoke zoom: the active stanza scales up and takes the accent;
            // sung lines fade back.
            val scale by animateFloatAsState(
                if (active) 1f else 0.94f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "lyricScale"
            )
            Text(
                line.text,
                color = when {
                    active -> VaultColors.Accent
                    activeIndex != null && index < activeIndex -> VaultColors.TextPrimary.copy(alpha = 0.4f)
                    else -> VaultColors.TextSecondary
                },
                fontSize = if (active) 22.sp else 15.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                lineHeight = if (active) 30.sp else 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = if (active) 10.dp else 6.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    crossfadeSeconds: Int,
    equalizerBands: List<com.musicvault.app.player.AudioEffects.Band>,
    eqEnabled: Boolean,
    eqPreset: com.musicvault.app.player.EqualizerPreset,
    loudnessGainMb: Int,
    bassBoostAvailable: Boolean,
    virtualizerAvailable: Boolean,
    playerStyle: String,
    downloadDirLabel: String,
    onCrossfadeChange: (Int) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerBand: (Int, Short) -> Unit,
    onEqualizerPreset: (com.musicvault.app.player.EqualizerPreset) -> Unit,
    onLoudnessChange: (Int) -> Unit,
    onBassBoostStrength: (Int) -> Unit,
    onVirtualizerStrength: (Int) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onUseInternalFolder: () -> Unit,
    onPlayerStyleChange: (String) -> Unit,
    onClose: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
                Text("Ajustes", color = VaultColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = VaultFonts.Display, letterSpacing = (-0.75).sp)
            }
        }
        item {
            SettingsSection("APARIENCIA") {
                Text("Estilo del reproductor", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Elige la interfaz del reproductor. La reproducción no cambia.",
                    color = VaultColors.TextTertiary, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyleOption(
                        title = "Tornamesa / Vinilo",
                        description = "Disco giratorio con brazo, control de RPM y hardware del deck.",
                        icon = VaultIcons.Album,
                        selected = playerStyle == "vinyl",
                        onClick = { onPlayerStyleChange("vinyl") },
                        modifier = Modifier.weight(1f)
                    )
                    StyleOption(
                        title = "Visualizador circular",
                        description = "Portada central rodeada por anillos de graves, medios y agudos.",
                        icon = VaultIcons.GraphicEq,
                        selected = playerStyle == "circular",
                        onClick = { onPlayerStyleChange("circular") },
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    Icon(VaultIcons.FolderOpen, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(downloadDirLabel, color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Las nuevas descargas se guardan en esta carpeta. Si eliges una fuera del almacenamiento de la app (Descargas, tarjeta SD…), los archivos sobrevivirán aunque desinstales MusicVault.",
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
                        Icon(VaultIcons.FolderOpen, null, modifier = Modifier.size(18.dp))
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
                        onCheckedChange = { onEqualizerEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = VaultColors.OnAccent, checkedTrackColor = VaultColors.Accent)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Presets", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PresetChips(
                    selected = eqPreset,
                    onSelect = { onEqualizerPreset(it) }
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "Volume Boost",
                    color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Aumenta la ganancia general sin distorsión. Independiente del preset.",
                    color = VaultColors.TextTertiary, fontSize = 11.sp
                )
                var loudnessDrag by remember(loudnessGainMb) { mutableStateOf<Float?>(null) }
                val shownLoudnessDb = (loudnessDrag ?: loudnessGainMb.toFloat()) / 1000f
                Slider(
                    value = loudnessDrag ?: loudnessGainMb.toFloat(),
                    onValueChange = { loudnessDrag = it },
                    onValueChangeFinished = {
                        loudnessDrag?.toInt()?.let(onLoudnessChange)
                        loudnessDrag = null
                    },
                    valueRange = 0f..3000f,
                    colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3)
                )
                Text(
                    String.format(java.util.Locale.US, "%+5.1f dB", shownLoudnessDb),
                    color = VaultColors.Accent,
                    fontSize = 12.sp,
                    fontFamily = VaultFonts.Mono
                )

                if (bassBoostAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bass Boost (efecto nativo)",
                        color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                    )
                    var bbDrag by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = bbDrag ?: eqPreset.bassBoost.toFloat(),
                        onValueChange = { bbDrag = it },
                        onValueChangeFinished = {
                            bbDrag?.toInt()?.let(onBassBoostStrength)
                            bbDrag = null
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3)
                    )
                }

                if (virtualizerAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Virtualizador",
                        color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                    )
                    var virtDrag by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = virtDrag ?: eqPreset.virtualizer.toFloat(),
                        onValueChange = { virtDrag = it },
                        onValueChangeFinished = {
                            virtDrag?.toInt()?.let(onVirtualizerStrength)
                            virtDrag = null
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = VaultColors.Accent, activeTrackColor = VaultColors.Accent, inactiveTrackColor = VaultColors.Surface3)
                    )
                }

                if (equalizerBands.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Bandas", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
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

/** Selectable card for the player-style setting (two skins, same playback engine). */
@Composable
private fun StyleOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VaultColors.AccentSoft else VaultColors.Surface2)
            .border(1.dp, if (selected) VaultColors.Accent else VaultColors.Divider, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(icon, null, tint = if (selected) VaultColors.Accent else VaultColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = VaultColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(description, color = VaultColors.TextTertiary, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

/** Horizontally-scrolling chips that select one of the curated EQ presets. */
@Composable
private fun PresetChips(
    selected: com.musicvault.app.player.EqualizerPreset,
    onSelect: (com.musicvault.app.player.EqualizerPreset) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(com.musicvault.app.player.EqualizerPreset.entries.toList()) { preset ->
            val isSelected = preset == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) VaultColors.Accent else VaultColors.Surface2)
                    .border(
                        1.dp,
                        if (isSelected) VaultColors.Accent else VaultColors.Divider,
                        RoundedCornerShape(50)
                    )
                    .clickable { onSelect(preset) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    preset.displayName,
                    color = if (isSelected) VaultColors.OnAccent else VaultColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
                    itemsIndexed(queue, key = { index, track -> "${track.id}#$index" }) { index, track ->
                        Row(
                            Modifier.fillMaxWidth().animateItem().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(VaultIcons.DragHandle, "Reordenar", tint = VaultColors.TextTertiary)
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
                            IconButton(onClick = { vm.removeFromQueue(index) }) { Icon(VaultIcons.DeleteOutline, "Quitar", tint = VaultColors.Error) }
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
                Icon(if (section == AppSection.LIBRARY) VaultIcons.LibraryMusic else VaultIcons.LibraryMusicOutline, null)
            }
            BottomBarItem(AppSection.PLAYLISTS, "ÁLBUMES", section, onSelect) {
                Icon(if (section == AppSection.PLAYLISTS) VaultIcons.QueueMusic else VaultIcons.QueueMusicOutline, null)
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
                Icon(VaultIcons.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(54.dp))
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
                            Icon(VaultIcons.Download, contentDescription = "Descargar álbum", tint = VaultColors.TextPrimary)
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
        ) { Icon(VaultIcons.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.9f), modifier = Modifier.size(40.dp)) }
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
                        if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow,
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
                    leadingIcon = { Icon(VaultIcons.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
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
                            IconButton(onClick = { deleteId = album.id }) { Icon(VaultIcons.DeleteOutline, "Eliminar álbum") }
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
