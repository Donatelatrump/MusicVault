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

@Immutable
internal data class SearchScreenState(
    val downloaded: List<Track>,
    val remoteTracks: List<CatalogTrack>,
    val remoteAlbums: List<CatalogAlbum>,
    val isSearching: Boolean,
    val selectedTrackId: String?,
    val playlists: List<Playlist>,
    val active: Boolean
)

internal enum class SearchTab { TRACKS, ALBUMS, PLAYLISTS }

@Composable
internal fun SearchScreen(
    state: SearchScreenState,
    isPlaying: Boolean,
    vm: MusicVaultViewModel,
    prewarm: Boolean = false
) {
    val online by vm.isOnline.collectAsStateWithLifecycle()
    val offlineTracks by vm.offlineSearchResults.collectAsStateWithLifecycle()
    val offlineAdapter = remember { LibraryFeedAdapter(vm) }
    val hybrid = LocalHybridDesign.current
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    var actionTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var actionAction by remember { mutableStateOf<MoreAction?>(null) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var deleteSearchTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var searchTab by rememberSaveable { mutableStateOf(SearchTab.TRACKS) }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var remotePlaylists by remember { mutableStateOf<List<CatalogPlaylist>>(emptyList()) }
    var playlistsLoading by remember { mutableStateOf(false) }
    var loadedPlaylistQuery by remember { mutableStateOf("") }
    var selectedRemotePlaylist by remember { mutableStateOf<CatalogPlaylist?>(null) }
    var remotePlaylistTracks by remember { mutableStateOf<List<CatalogTrack>>(emptyList()) }
    var remotePlaylistLoading by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestSelectedTrackId by rememberUpdatedState(state.selectedTrackId)

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
            },
            onPlaylistClick = { playlist -> selectedRemotePlaylist = playlist }
        )
    }
    // The downloaded-id set only changes when the library changes; rebuilding it
    // on every unrelated recomposition was pure churn for the search list.
    var searchText by rememberSaveable { mutableStateOf("") }
    val requestedQuery by vm.submittedQuery.collectAsStateWithLifecycle()
    LaunchedEffect(requestedQuery) {
        if (requestedQuery.isNotBlank() && requestedQuery != submittedQuery) {
            searchText = requestedQuery
            submittedQuery = requestedQuery
            searchTab = SearchTab.TRACKS
        }
    }
    val latestSearchTab by rememberUpdatedState(searchTab)
    val latestSubmittedQuery by rememberUpdatedState(submittedQuery)
    fun submitCurrentSearch() {
        val query = searchText.trim()
        if (query.isBlank()) return
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        submittedQuery = query
        remotePlaylists = emptyList()
        loadedPlaylistQuery = ""
        playlistsLoading = false
        // Every fresh search opens on songs; albums/playlists remain one tap away.
        searchTab = SearchTab.TRACKS
        vm.submitSearch(query)
    }

    LaunchedEffect(submittedQuery, searchTab, online) {
        if (!online) { playlistsLoading = false; return@LaunchedEffect }
        val query = submittedQuery.trim()
        if (searchTab != SearchTab.PLAYLISTS || query.isBlank() || loadedPlaylistQuery == query) return@LaunchedEffect
        playlistsLoading = true
        try {
            remotePlaylists = vm.searchYouTubePlaylists(query, 20)
            loadedPlaylistQuery = query
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            remotePlaylists = emptyList()
            loadedPlaylistQuery = query
        } finally {
            playlistsLoading = false
        }
    }

    LaunchedEffect(selectedRemotePlaylist?.id, online) {
        if (!online) { remotePlaylistLoading = false; return@LaunchedEffect }
        val playlist = selectedRemotePlaylist ?: run {
            remotePlaylistTracks = emptyList()
            remotePlaylistLoading = false
            return@LaunchedEffect
        }
        remotePlaylistTracks = emptyList()
        remotePlaylistLoading = true
        try {
            remotePlaylistTracks = vm.loadYouTubePlaylistTracks(playlist, 500)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            remotePlaylistTracks = emptyList()
        } finally {
            remotePlaylistLoading = false
        }
    }
    val downloadedIds = remember(state.downloaded) {
        state.downloaded
            .asSequence()
            .filter { it.isDownloaded }
            .map { it.id }
            .toSet()
    }

    val resultItems = remember(submittedQuery, searchTab, state.remoteAlbums, state.remoteTracks, remotePlaylists, state.isSearching, playlistsLoading) {
        buildList<RemoteResultItem> {
            if (submittedQuery.isBlank()) return@buildList
            when (searchTab) {
                SearchTab.TRACKS -> {
                    if (state.remoteTracks.isNotEmpty()) {
                        state.remoteTracks.forEach { add(RemoteResultItem.TrackItem(it)) }
                    } else if (!state.isSearching) {
                        add(RemoteResultItem.HeaderItem("No encontramos canciones para “$submittedQuery”."))
                    }
                }
                SearchTab.ALBUMS -> {
                    if (state.remoteAlbums.isNotEmpty()) {
                        state.remoteAlbums.forEach { add(RemoteResultItem.AlbumItem(it)) }
                    } else if (!state.isSearching) {
                        add(RemoteResultItem.HeaderItem("No encontramos álbumes para “$submittedQuery”."))
                    }
                }
                SearchTab.PLAYLISTS -> {
                    if (remotePlaylists.isNotEmpty()) {
                        remotePlaylists.forEach { add(RemoteResultItem.PlaylistItem(it)) }
                    } else if (!playlistsLoading) {
                        add(RemoteResultItem.HeaderItem("No encontramos playlists para “$submittedQuery”."))
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = if (hybrid) 10.dp else 20.dp)
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = VaultColors.TextSecondary) },
            trailingIcon = {
                IconButton(onClick = ::submitCurrentSearch, enabled = searchText.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ui_buscar_a62405), tint = VaultColors.Accent)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitCurrentSearch() }),
            shape = RoundedCornerShape(if (hybrid) 16.dp else 10.dp),
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

        // The mode switch belongs to a result set, not to the empty search
        // screen. It appears immediately after the first submitted query and
        // sits directly below the field as a single full-width segmented control.
        AnimatedVisibility(
            visible = submittedQuery.isNotBlank() && online,
            enter = fadeIn(tween(160)) + expandVertically(tween(190)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(120))
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                SearchSegmentedControl(
                    selected = searchTab,
                    trackCount = state.remoteTracks.size,
                    albumCount = state.remoteAlbums.size,
                    playlistCount = remotePlaylists.size,
                    onSelected = { tab ->
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        searchTab = tab
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = submittedQuery.isNotBlank() && (
                (searchTab != SearchTab.PLAYLISTS && state.isSearching) ||
                    (searchTab == SearchTab.PLAYLISTS && playlistsLoading)
            ),
            enter = fadeIn(tween(150)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(120)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
        ) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                color = VaultColors.Accent,
                trackColor = VaultColors.Surface2
            )
        }

        Spacer(Modifier.height(8.dp))

        if (!online) {
            Text(stringResource(R.string.ui_biblioteca_sin_conexion_3f2054), color = VaultColors.Accent)
            if (submittedQuery.isNotBlank() && offlineTracks.isEmpty() && !state.isSearching) {
                Text(stringResource(R.string.ui_no_hay_archivos_disponibles_para_esta_busqueda_09ef87), color = VaultColors.TextSecondary)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context -> RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    this.adapter = offlineAdapter
                    setHasFixedSize(false)
                } },
                update = { offlineAdapter.update(offlineTracks, emptyList(), emptyList(), state.selectedTrackId, isPlaying) }
            )
        } else
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Skeleton placeholders while the search is in flight and no rows
            // exist yet: the shape of the final content instead of a blank gap.
            if (submittedQuery.isNotBlank() &&
                (((searchTab == SearchTab.TRACKS && state.isSearching && state.remoteTracks.isEmpty()) ||
                    (searchTab == SearchTab.ALBUMS && state.isSearching && state.remoteAlbums.isEmpty()) ||
                    (searchTab == SearchTab.PLAYLISTS && playlistsLoading && remotePlaylists.isEmpty())))
            ) {
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
                        recycledViewPool.setMaxRecycledViews(4, 10)
                        overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                        addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                                if (latestSearchTab != SearchTab.TRACKS || latestSubmittedQuery.isBlank()) return
                                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                                val first = lm.findFirstVisibleItemPosition()
                                val last = lm.findLastVisibleItemPosition()
                                if (first != RecyclerView.NO_POSITION) {
                                    vm.prewarmVisibleSearchWindow(first, last)
                                }
                            }
                        })
                    }
                },
                update = { recyclerView ->
                    // Hidden-tab guard: don't rebuild items while the tab is
                    // hidden; results only change while the tab is visible.
                    if (state.active || prewarm) {
                        adapter.updateItems(resultItems)

                        // Skip state pushes while the tab is hidden (alpha 0): the
                        // screen keeps observing flows (download progress ticks),
                        // but pushing state into a hidden RecyclerView is wasted
                        // work. The first update after reactivation repushes all.
                        adapter.updateState(
                            downloadedIds = downloadedIds,
                            progress = downloadProgress,
                            selectedTrackId = state.selectedTrackId,
                            isPlaying = isPlaying
                        )
                    }
                }
            )
        }
    }

    val selectedTrack = actionTrack
    when (actionAction) {
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
            deleteSearchTrack = selectedTrack
            actionTrack = null
            actionAction = null
        }
        null -> Unit
    }

    deleteSearchTrack?.let { remote ->
        AlertDialog(
            onDismissRequest = { deleteSearchTrack = null },
            title = { Text(stringResource(R.string.ui_eliminar_cancion_d65308)) },
            text = { Text("¿Quieres eliminar \"${remote.title}\" de las descargas de MusicVault?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDownloadedTrack("${remote.provider}:recording:${remote.id}")
                    deleteSearchTrack = null
                }) { Text(stringResource(R.string.ui_eliminar_54e140), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSearchTrack = null }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } }
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
                        actionAction = null
                        newAlbumName = ""
                    }
                ) { Text(stringResource(R.string.ui_crear_48a068)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbum = false; actionTrack = null }) { Text(stringResource(R.string.ui_cancelar_c111e0)) }
            }
        )
    }

    selectedRemotePlaylist?.let { playlist ->
        YoutubePlaylistPreviewDialog(
            playlist = playlist,
            tracks = remotePlaylistTracks,
            loading = remotePlaylistLoading,
            onDismiss = { selectedRemotePlaylist = null },
            onTrackClick = vm::playRemote,
            onImport = {
                vm.importYouTubePlaylist(playlist, remotePlaylistTracks)
                selectedRemotePlaylist = null
            }
        )
    }
}

@Composable
internal fun YoutubePlaylistPreviewDialog(
    playlist: CatalogPlaylist,
    tracks: List<CatalogTrack>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onTrackClick: (CatalogTrack) -> Unit,
    onImport: () -> Unit
) {
    val adapter = remember { YoutubePlaylistTracksAdapter(onTrackClick) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
            color = VaultColors.Surface1,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CachedCover(
                        url = playlist.coverUri,
                        contentDescription = playlist.title,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                        targetSizePx = 180,
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, color = VaultColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(playlist.uploaderName.ifBlank { "YouTube" })
                                val count = if (playlist.trackCount > 0) playlist.trackCount else tracks.size.toLong()
                                if (count > 0) append(" · $count elementos")
                            },
                            color = VaultColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.ui_cerrar_4b0816), tint = VaultColors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onImport,
                    enabled = tracks.isNotEmpty() && !loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = VaultColors.OnAccent)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_agregar_a_musicvault_d3e061))
                }
                if (loading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = VaultColors.Accent, trackColor = VaultColors.Surface2)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ui_cargando_canciones_de_youtube_9431e4), color = VaultColors.TextTertiary, fontSize = 11.sp)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                    factory = { context ->
                        RecyclerView(context).apply {
                            layoutManager = LinearLayoutManager(context)
                            this.adapter = adapter
                            setHasFixedSize(true)
                            itemAnimator = null
                            setItemViewCacheSize(12)
                        }
                    },
                    update = { adapter.submitList(tracks) }
                )
            }
        }
    }
}

@Composable
internal fun CachedCover(
    url: String?,
    contentDescription: String?,
    modifier: Modifier,
    targetSizePx: Int,
    contentScale: ContentScale = ContentScale.Fit
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
            .diskCacheKey(url)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = CoverImageCache.loader(context),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

@Composable
internal fun RemoteTrackRow(
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
    val hybrid = LocalHybridDesign.current
    val displayedProgress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val progressFraction = ((displayedProgress ?: 0) / 100f).coerceIn(0f, 1f)
    val downloading = displayedProgress != null && !downloaded
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(if (hybrid) 14.dp else 10.dp))
            .then(
                if (hybrid) Modifier
                    .background(VaultColors.Surface1)
                    .border(1.dp, VaultColors.Divider.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (hybrid) 10.dp else 4.dp, vertical = 9.dp),
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
        if (downloading) {
            Box(
                Modifier.width(38.dp).height(48.dp).clickable(onClick = onCancelDownload),
                contentAlignment = Alignment.Center
            ) {
                ThinDownloadProgress(progress = progressFraction, modifier = Modifier.width(28.dp))
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.ui_mas_opciones_977c75), tint = VaultColors.TextPrimary)
        }
    }
}

