package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import kotlin.math.roundToInt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.musicvault.app.data.UserPreferences
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogPlaylist
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.ui.search.RemoteResultItem
import com.musicvault.app.ui.search.RemoteResultsAdapter
import com.musicvault.app.ui.search.YoutubePlaylistTracksAdapter
import com.musicvault.app.ui.search.MoreAction
import com.musicvault.app.ui.theme.VaultColors
import com.musicvault.app.ui.theme.VaultIcons
import com.musicvault.app.ui.theme.VaultFonts
import com.musicvault.app.lyrics.LyricLine
import com.musicvault.app.spotify.SpotifyLink
import com.musicvault.app.spotify.SpotifyLinkKind
import com.musicvault.app.spotify.SpotifyPlaylist
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.vector.ImageVector
import com.musicvault.app.ui.onboarding.OnboardingScreen
import com.musicvault.app.ui.onboarding.RecommendationLoadingScreen

@Composable
internal fun LocalAlbumDetail(
    album: Album,
    tracks: List<Track>,
    isPlaying: Boolean,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val context = LocalContext.current
    val hybrid = LocalHybridDesign.current
    val albumAdapter = remember(vm, hybrid) { LocalAlbumDetailAdapter(vm, hybrid) }
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
internal fun RemoteAlbumDetail(
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
    val hybrid = LocalHybridDesign.current
    val appState by vm.state.collectAsStateWithLifecycle()
    val favoriteIds = appState.favoriteIds
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val downloadedIds = remember(localTracks) {
        localTracks.asSequence()
            .filter { it.isDownloaded }
            .mapTo(HashSet()) { it.id }
    }
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestSelectedTrackId by rememberUpdatedState(selectedTrackId)

    var actionTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var actionAction by remember { mutableStateOf<MoreAction?>(null) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var deleteTrack by remember { mutableStateOf<CatalogTrack?>(null) }

    // Reuse the same native, recycled rows as Search. The previous album detail
    // was a Compose scrolling list where every visible row collected its own progress
    // Flow and hosted multiple Material controls. On albums with many tracks that
    // made fast flings noticeably janky. This adapter keeps only the visible
    // Android Views alive, uses stable IDs + payload updates and never recomposes
    // the whole album when playback/download state changes.
    val adapter = remember {
        RemoteResultsAdapter(
            onAlbumClick = {},
            onTrackClick = vm::playRemote,
            onCancelDownload = vm::cancelDownload,
            onMoreAction = { track, action ->
                actionTrack = track
                actionAction = action
            },
            onPlayToggle = { track ->
                val localId = "${track.provider}:recording:${track.id}"
                if (latestIsPlaying && latestSelectedTrackId == localId) vm.togglePlayback()
                else vm.playRemote(track)
            }
        )
    }
    val trackItems = remember(tracks) { tracks.map { RemoteResultItem.TrackItem(it) } }
    val albumKey = remember(album.provider, album.id) { "${album.provider}:${album.id}" }
    val albumDownloading = albumKey in albumDownloads

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (hybrid) 16.dp else 20.dp, vertical = if (hybrid) 8.dp else 12.dp)
    ) {
        CollectionBackButton(vm::closeRemoteAlbum)
        Spacer(Modifier.height(12.dp))

        val (top, bottom) = remember(album.id) { gradientFor(album.id) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(if (hybrid) 112.dp else 120.dp)
                    .shadow(8.dp, RoundedCornerShape(if (hybrid) 18.dp else 12.dp), ambientColor = top.copy(alpha = 0.42f))
                    .clip(RoundedCornerShape(if (hybrid) 18.dp else 12.dp))
                    .background(Brush.linearGradient(listOf(top, bottom))),
                contentAlignment = Alignment.Center
            ) {
                if (album.coverUri != null) {
                    // 256 px is enough for the on-screen card and avoids the old
                    // 360 px decode while the list is being opened/scrolled.
                    CachedCover(
                        url = album.coverUri,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize(),
                        targetSizePx = 256,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        VaultIcons.Album,
                        null,
                        tint = VaultColors.TextPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    album.title,
                    color = VaultColors.TextPrimary,
                    fontSize = if (hybrid) 20.sp else 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    album.artistName,
                    color = VaultColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                album.year?.let {
                    Text(it.toString(), color = VaultColors.TextTertiary, fontSize = 12.sp)
                }
                if (tracks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${tracks.size} canciones",
                        color = VaultColors.TextTertiary,
                        fontSize = 12.sp,
                        fontFamily = VaultFonts.Mono
                    )
                }
            }
        }

        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            var showAlbumPlaylistChoice by remember { mutableStateOf(false) }
            var showCreateAlbumForAlbum by remember { mutableStateOf(false) }
            var newAlbumNameForAlbum by remember { mutableStateOf("") }
            val allAlbumTracksLiked = remember(tracks, favoriteIds) {
                tracks.isNotEmpty() && tracks.all { "${it.provider}:recording:${it.id}" in favoriteIds }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (!albumDownloading) vm.downloadRemoteAlbum(album, tracks) },
                    enabled = !albumDownloading,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Surface2,
                        contentColor = VaultColors.TextPrimary,
                        disabledContainerColor = VaultColors.Surface2,
                        disabledContentColor = VaultColors.TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (albumDownloading) ThinDownloadProgress(modifier = Modifier.width(22.dp))
                    else Icon(VaultIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (albumDownloading) "Descargando" else "Descargar", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = { showAlbumPlaylistChoice = true },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Surface2,
                        contentColor = VaultColors.TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(VaultIcons.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Añadir a playlist", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                IconButton(
                    onClick = { vm.toggleAlbumFavorites(tracks) },
                    modifier = Modifier
                        .size(38.dp)
                        .background(VaultColors.Surface2, CircleShape)
                ) {
                    Icon(
                        imageVector = if (allAlbumTracksLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (allAlbumTracksLiked) "Quitar de Me gusta" else "Me gusta",
                        tint = if (allAlbumTracksLiked) VaultColors.Accent else VaultColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (showAlbumPlaylistChoice) {
                AlbumChoiceDialog(
                    albums = playlists.map { Album(it.id, it.title, "custom", it.description, 0, it.coverUri) },
                    onDismiss = { showAlbumPlaylistChoice = false },
                    onSelect = { playlistId ->
                        vm.addRemoteTracksToPlaylist(tracks, playlistId)
                        showAlbumPlaylistChoice = false
                    },
                    onCreate = {
                        showCreateAlbumForAlbum = true
                        showAlbumPlaylistChoice = false
                    },
                    onDelete = vm::deletePlaylist
                )
            }

            if (showCreateAlbumForAlbum) {
                AlertDialog(
                    onDismissRequest = { showCreateAlbumForAlbum = false },
                    title = { Text(stringResource(R.string.ui_crear_nuevo_album_5d97f7)) },
                    text = {
                        OutlinedTextField(
                            value = newAlbumNameForAlbum,
                            onValueChange = { newAlbumNameForAlbum = it },
                            label = { Text(stringResource(R.string.ui_nombre_e68491)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = newAlbumNameForAlbum.isNotBlank(),
                            onClick = {
                                vm.createAlbumFromRemote(newAlbumNameForAlbum, tracks)
                                showCreateAlbumForAlbum = false
                                newAlbumNameForAlbum = ""
                            }
                        ) { Text(stringResource(R.string.ui_crear_48a068)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateAlbumForAlbum = false }) { Text(stringResource(R.string.ui_cancelar_c111e0)) }
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading && tracks.isEmpty() -> {
                    Column(Modifier.fillMaxSize()) { repeat(6) { SkeletonRow() } }
                }
                !loading && tracks.isEmpty() -> {
                    Text(
                        stringResource(R.string.ui_no_se_encontraron_canciones_para_este_album_61a7be),
                        color = VaultColors.TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context).apply {
                            isItemPrefetchEnabled = true
                            initialPrefetchItemCount = 10
                        }
                        this.adapter = adapter
                        setHasFixedSize(true)
                        setItemViewCacheSize(16)
                        // RemoteResultsAdapter TYPE_TRACK = 3. Keep a generous
                        // recycled pool so a fast fling almost never inflates a row.
                        recycledViewPool.setMaxRecycledViews(3, 24)
                        // Row state changes are frequent while playing/downloading;
                        // animations only add layout work here.
                        itemAnimator = null
                        overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                    }
                },
                update = { recyclerView ->
                    adapter.updateItems(trackItems)
                    adapter.updateState(
                        downloadedIds = downloadedIds,
                        progress = downloadProgress,
                        selectedTrackId = selectedTrackId,
                        isPlaying = isPlaying,
                        favoriteIds = favoriteIds
                    )
                    if (recyclerView.adapter !== adapter) recyclerView.adapter = adapter
                }
            )
        }
    }

    val selectedTrack = actionTrack
    when (actionAction) {
        MoreAction.FAVORITE -> if (selectedTrack != null) {
            vm.toggleRemoteFavorite(selectedTrack)
            actionTrack = null
            actionAction = null
        }
        MoreAction.RADIO -> if (selectedTrack != null) {
            LaunchedEffect(selectedTrack.id, actionAction) {
                vm.startRadio(selectedTrack)
                actionTrack = null
                actionAction = null
            }
        }
        MoreAction.QUEUE -> if (selectedTrack != null) {
            vm.addRemoteTrackToQueue(selectedTrack)
            actionTrack = null
            actionAction = null
        }
        MoreAction.ALBUM -> Unit // handled by the chooser below
        MoreAction.DOWNLOAD -> if (selectedTrack != null) {
            vm.requestRemoteDownload(selectedTrack)
            actionTrack = null
            actionAction = null
        }
        MoreAction.CANCEL_DOWNLOAD -> if (selectedTrack != null) {
            vm.cancelDownload("${selectedTrack.provider}:recording:${selectedTrack.id}")
            actionTrack = null
            actionAction = null
        }
        MoreAction.DELETE -> if (selectedTrack != null) {
            deleteTrack = selectedTrack
            actionTrack = null
            actionAction = null
        }
        null -> Unit
    }

    if (actionAction == MoreAction.ALBUM && selectedTrack != null) {
        AlbumChoiceDialog(
            albums = playlists.map { Album(it.id, it.title, "custom", it.description, 0, it.coverUri) },
            onDismiss = { actionTrack = null; actionAction = null },
            onSelect = { id ->
                vm.addRemoteTrackToPlaylist(selectedTrack, id)
                actionTrack = null
                actionAction = null
            },
            onCreate = {
                showCreateAlbum = true
                actionAction = null
            },
            onDelete = vm::deletePlaylist
        )
    }

    if (showCreateAlbum && selectedTrack != null) {
        AlertDialog(
            onDismissRequest = { showCreateAlbum = false; actionTrack = null },
            title = { Text(stringResource(R.string.ui_crear_nuevo_album_5d97f7)) },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text(stringResource(R.string.ui_nombre_e68491)) },
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
                        newAlbumName = ""
                    }
                ) { Text(stringResource(R.string.ui_crear_48a068)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbum = false; actionTrack = null }) { Text(stringResource(R.string.ui_cancelar_c111e0)) }
            }
        )
    }

    deleteTrack?.let { remote ->
        AlertDialog(
            onDismissRequest = { deleteTrack = null },
            title = { Text(stringResource(R.string.ui_eliminar_cancion_d65308)) },
            text = { Text("¿Quieres eliminar \"${remote.title}\" de las descargas de MusicVault?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDownloadedTrack("${remote.provider}:recording:${remote.id}")
                    deleteTrack = null
                }) { Text(stringResource(R.string.ui_eliminar_54e140), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTrack = null }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } }
        )
    }
}

@Composable
internal fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    isDownloading: Boolean,
    isPlaying: Boolean,
    isOnline: Boolean,
    offlinePlayableIds: Set<String>,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val isDj = playlist.id == DJ_MIX_ID
    val dj by vm.djState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hybrid = LocalHybridDesign.current
    // A playlist detail must start from its header every time it is opened.
    // Key the adapter/reset state to the playlist so RecyclerView/Compose cannot
    // carry a scroll anchor from a previously opened detail screen.
    val adapter = remember(playlist.id, vm, hybrid) { PlaylistDetailAdapter(vm, hybrid, allowRemoval = !isDj) }
    val resetScrollOnOpen = remember(playlist.id) { mutableStateOf(true) }
    val customAlbums = remember(albums) { albums.filter { it.id.startsWith("album:custom:") } }
    val pendingDownloads = remember(tracks) { tracks.count { !it.isDownloaded || it.audioUri == null } }
    // Header identity only changes when what it renders changes; playback
    // ticks rebind rows via payload instead of rebuilding the header.
    val headerContent = remember(playlist, tracks.size, pendingDownloads, isDownloading, dj.loading, dj.message) {
        @Composable {
            PlaylistDetailHeader(
                playlist = playlist,
                trackCount = tracks.size,
                isDownloading = isDownloading,
                pendingDownloads = pendingDownloads,
                onBack = vm::closePlaylist,
                onPlay = { vm.playPlaylist(playlist, shuffle = false) },
                onShuffle = { vm.playPlaylist(playlist, shuffle = true) },
                onDownload = { vm.downloadPlaylist(playlist) },
                isDj = isDj,
                loading = isDj && dj.loading,
                message = if (isDj) dj.message else null,
                onRefresh = { vm.openDjAlbum(refresh = true) }
            )
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            RecyclerView(ctx).apply {
                // Configure the header before attaching the adapter. Attaching
                // first lets RecyclerView bind a zero-height ComposeView for one
                // frame, so the first track briefly appears at the top and then
                // jumps down when the header finishes composing.
                adapter.update(
                    tracks = tracks,
                    playlists = playlists,
                    albums = customAlbums,
                    selectedTrackId = selectedTrackId,
                    isPlaying = isPlaying,
                    isOnline = isOnline,
                    offlinePlayableIds = offlinePlayableIds,
                    onRowClick = { vm.playPlaylistTrack(playlist, it) },
                    onPlayNew = { vm.playPlaylistTrack(playlist, it) },
                    onRemoveFromPlaylist = { vm.removeTrackFromPlaylist(playlist.id, it.id) },
                    header = headerContent
                )
                layoutManager = LinearLayoutManager(ctx).also {
                    it.isItemPrefetchEnabled = true
                    it.initialPrefetchItemCount = 6
                }
                setHasFixedSize(false)
                setItemViewCacheSize(10)
                recycledViewPool.setMaxRecycledViews(PlaylistDetailAdapter.TYPE_TRACK, 12)
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                setPadding(
                    libraryPixels(context, if (hybrid) 16 else 20), 0,
                    libraryPixels(context, if (hybrid) 16 else 20), libraryPixels(context, if (hybrid) 16 else 20)
                )
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                // The detail is transient navigation UI; restoring RecyclerView's
                // hierarchy state can reopen a playlist at an old/bottom position.
                isSaveEnabled = false
                this.adapter = adapter
            }
        },
        update = { recyclerView ->
            adapter.update(
                tracks = tracks,
                playlists = playlists,
                albums = customAlbums,
                selectedTrackId = selectedTrackId,
                isPlaying = isPlaying,
                isOnline = isOnline,
                offlinePlayableIds = offlinePlayableIds,
                onRowClick = { vm.playPlaylistTrack(playlist, it) },
                onPlayNew = { vm.playPlaylistTrack(playlist, it) },
                onRemoveFromPlaylist = { vm.removeTrackFromPlaylist(playlist.id, it.id) },
                header = headerContent,
                onListCommitted = {
                    if (resetScrollOnOpen.value) {
                        resetScrollOnOpen.value = false
                        recyclerView.post {
                            (recyclerView.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(0, 0)
                        }
                    }
                }
            )
        }
    )
}

@Composable
internal fun PlaylistDetailHeader(
    playlist: Playlist,
    trackCount: Int,
    isDownloading: Boolean,
    pendingDownloads: Int,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    isDj: Boolean = false,
    loading: Boolean = false,
    message: String? = null,
    onRefresh: () -> Unit = {}
) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier.fillMaxWidth().padding(
            start = if (hybrid) 16.dp else 20.dp,
            top = if (hybrid) 16.dp else 20.dp,
            end = if (hybrid) 16.dp else 20.dp
        )
    ) {
        CollectionBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        playlist.coverUri?.let { coverUri ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CachedCover(
                    url = coverUri,
                    contentDescription = "Portada de ${playlist.title}",
                    targetSizePx = 420,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(if (hybrid) 18.dp else 12.dp))
                        .border(1.dp, VaultColors.Divider, RoundedCornerShape(if (hybrid) 18.dp else 12.dp))
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(playlist.title, color = VaultColors.TextPrimary, fontSize = if (hybrid) 26.sp else 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Text(
            buildString {
                if (playlist.description.isNotBlank()) append(playlist.description).append("  •  ")
                append(trackCount).append(if (trackCount == 1) " canción" else " canciones")
            },
            color = VaultColors.TextSecondary,
            fontSize = if (hybrid) 12.sp else 14.sp,
            fontFamily = if (hybrid) VaultFonts.Mono else VaultFonts.Body
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlay,
                enabled = trackCount > 0 && !loading,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_reproducir_d1a188), fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(
                onClick = onShuffle,
                enabled = trackCount > 0 && !loading,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            ) {
                Icon(VaultIcons.Shuffle, null, tint = VaultColors.TextPrimary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_aleatorio_a7567a), color = VaultColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (isDj) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = VaultColors.Accent)
            message?.let {
                Text(stringResource(if (it == "dj_no_candidates") R.string.dj_no_candidates else R.string.dj_unavailable), color = VaultColors.TextSecondary)
            }
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(stringResource(R.string.dj_new_mix), color = VaultColors.Accent)
            }
        } else OutlinedButton(
            onClick = onDownload,
            enabled = pendingDownloads > 0 && !isDownloading,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isDownloading) {
                ThinDownloadProgress(modifier = Modifier.width(26.dp), color = VaultColors.TextPrimary)
            } else {
                Icon(VaultIcons.Download, null, tint = if (pendingDownloads > 0) VaultColors.TextPrimary else VaultColors.TextTertiary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    isDownloading -> "Descargando playlist…"
                    pendingDownloads == 0 -> "Playlist descargada"
                    else -> "Descargar playlist ($pendingDownloads)"
                },
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}


@Composable
private fun CollectionBackButton(onBack: () -> Unit) {
    Surface(
        onClick = onBack,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = VaultColors.Surface1,
        border = BorderStroke(1.dp, VaultColors.Divider)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ui_atras_0e715a),
                tint = VaultColors.TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
