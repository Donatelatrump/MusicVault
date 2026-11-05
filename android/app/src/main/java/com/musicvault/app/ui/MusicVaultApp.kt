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
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.flow.collect
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

/** Deterministic spine accent for a key. Tone-on-tone pair for subtle placeholder fills. */
internal fun gradientFor(key: String): Pair<Color, Color> {
    val idx = abs(key.hashCode()) % VaultColors.SpineAccents.size
    val accent = VaultColors.SpineAccents[idx]
    return accent to accent.copy(alpha = 0.25f)
}

/**
 * Adds a subtle Spotify-style "press" reaction: the element scales down slightly
 * and settles back with a spring, instead of relying only on the ripple.
 */
@Composable
internal fun Modifier.pressScale(
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
internal fun SpotifyClipboardNotice(
    link: SpotifyLink,
    hybrid: Boolean,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val kind = if (link.kind == SpotifyLinkKind.PLAYLIST) "Playlist" else "Canción"
    Surface(
        color = if (hybrid) VaultColors.Surface2 else VaultColors.Surface1,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (hybrid) VaultColors.Divider else VaultColors.Accent.copy(alpha = 0.45f)),
        shadowElevation = if (hybrid) 1.dp else 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (hybrid) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(if (hybrid) 40.dp else 38.dp)
                    .clip(CircleShape)
                    .background(VaultColors.Accent.copy(alpha = if (hybrid) 0.10f else 0.14f))
                    .then(if (hybrid) Modifier.border(1.dp, VaultColors.Accent.copy(alpha = 0.20f), CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(VaultIcons.LibraryMusic, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hybrid) "$kind detectada" else "$kind de Spotify detectada",
                    color = VaultColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (hybrid) "SPOTIFY • PORTAPAPELES" else "Enlace válido en el portapapeles",
                    color = VaultColors.TextSecondary,
                    fontSize = if (hybrid) 9.sp else 11.sp,
                    fontFamily = if (hybrid) VaultFonts.Mono else VaultFonts.Body,
                    letterSpacing = 0.sp,
                    maxLines = 1
                )
            }
            if (hybrid) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text(stringResource(R.string.ui_importar_b28a54), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                TextButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(contentColor = VaultColors.Accent),
                    contentPadding = PaddingValues(horizontal = 9.dp)
                ) {
                    Text(stringResource(R.string.ui_importar_b28a54), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(if (hybrid) 44.dp else 34.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.ui_ignorar_enlace_e789df), tint = VaultColors.TextSecondary, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
fun MusicVaultApp(
    vm: MusicVaultViewModel,
    onAppReadyForPermissions: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devicePlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val offlinePlayableIds by vm.offlinePlayableIds.collectAsStateWithLifecycle()
    val albumDownloads by vm.albumDownloads.collectAsStateWithLifecycle()
    val favoriteIds = state.favoriteIds
    val incomingJamLink by vm.jamLink.collectAsStateWithLifecycle()
    val jamRoom by vm.jams.state.collectAsStateWithLifecycle()
    val jamContacts by vm.jamFriends.state.collectAsStateWithLifecycle()
    val isPlaying = if (jamRoom.active) jamRoom.playback.playing else devicePlaying
    val downloadDirLabel by vm.downloadDirLabel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appUpdates: com.musicvault.app.updates.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // SAF directory picker for the user-selected download destination.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::chooseDownloadTree) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showJams by rememberSaveable { mutableStateOf(false) }
    var libraryPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var showSpotifyLogin by rememberSaveable { mutableStateOf(false) }
    val searchState = remember(
        state.downloaded, state.remoteTracks, state.remoteAlbums,
        state.isSearching, state.selectedTrack?.id, state.playlists, state.section
    ) {
        SearchScreenState(
            state.downloaded, state.remoteTracks, state.remoteAlbums,
            state.isSearching, state.selectedTrack?.id, state.playlists, state.section == AppSection.SEARCH
        )
    }
    val libraryState = remember(
        state.downloaded, state.playlists, state.albums, state.selectedTrack?.id, state.section
    ) {
        LibraryScreenState(
            state.downloaded, state.playlists, state.albums,
            state.selectedTrack?.id, state.section == AppSection.LIBRARY
        )
    }

    val estadoInicialCargado by vm.estadoInicialCargado.collectAsStateWithLifecycle()
    val esPrimeraVez by vm.esPrimeraVez.collectAsStateWithLifecycle()
    val recommendationWarmupReady by vm.recommendationWarmupReady.collectAsStateWithLifecycle()
    var onboardingFinishing by rememberSaveable { mutableStateOf(false) }
    var onboardingGenres by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onboardingArtists by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Set only after Home has accepted the final recommendation list and the
    // RecyclerView has reached a real pre-draw with the artwork already warmed.
    // This prevents the loading animation from disappearing into a half-empty Home.
    var homeVisualReady by remember { mutableStateOf(false) }

    // After the artist step we persist preferences immediately. That lets the
    // real Home screen mount behind the loading surface and lets the ViewModel
    // start catalog/recommendation work before the user sees the feed.
    LaunchedEffect(onboardingFinishing) {
        if (onboardingFinishing) {
            // Keep the transition visible long enough to feel intentional, while
            // all four persistent main screens are already mounted underneath.
            delay(2_400)
            // Do not dismiss on the first network result. Keep the animation on
            // top until: (1) the recommendation batch is complete, (2) Home has
            // both rails populated, and (3) the RecyclerView has committed and
            // reached a pre-draw with those rows. Artwork is pre-warmed by the
            // ViewModel before recommendationWarmupReady becomes true.
            // A generous timeout is only an offline/error escape hatch.
            withTimeoutOrNull(18_000) {
                snapshotFlow {
                    recommendationWarmupReady &&
                        homeVisualReady &&
                        state.recommendedTracks.isNotEmpty() &&
                        state.recommendedAlbums.isNotEmpty()
                }.first { it }
            }
            // One extra frame lets Compose/RecyclerView present the cached images
            // before the overlay starts its fade-out.
            delay(120)
            onboardingFinishing = false
        }
    }

    // Permission dialogs should never cover onboarding or its recommendation
    // handoff. Ask only after the final loading surface has disappeared.
    LaunchedEffect(estadoInicialCargado, esPrimeraVez, onboardingFinishing) {
        if (estadoInicialCargado && !esPrimeraVez && !onboardingFinishing) {
            onAppReadyForPermissions()
        }
    }

    // Avoid flashing onboarding to existing users while Room resolves the
    // first-run state after process start.
    if (!estadoInicialCargado) {
        Box(Modifier.fillMaxSize().background(VaultColors.Background))
        return
    }

    // The two choice steps are true full-screen onboarding. On the last tap we
    // flip to `onboardingFinishing`, persist the tastes, and allow the real app
    // to compose underneath the recommendation animation.
    if (esPrimeraVez && !onboardingFinishing) {
        OnboardingScreen(
            onCompleto = { generos, artistas ->
                onboardingGenres = generos
                onboardingArtists = artistas
                homeVisualReady = false
                onboardingFinishing = true
                // Mark the recommendation batch first so the regular profile
                // collector cannot race this first-run preload with a duplicate request.
                vm.preloadRecommendations(generos, artistas)
                vm.guardarPreferencias(generos, artistas)
                vm.selectSection(AppSection.HOME)
            },
            onSkip = {
                onboardingGenres = emptySet()
                onboardingArtists = emptySet()
                vm.saltarOnboarding()
                vm.selectSection(AppSection.HOME)
            }
        )
        return
    }

    LaunchedEffect(incomingJamLink, jamRoom.active, jamRoom.roomCode, jamRoom.busy, jamContacts.busy, isOnline, onboardingFinishing) {
        val link = incomingJamLink ?: return@LaunchedEffect
        showJams = true
        showSettings = false
        vm.closeFullPlayer()
        if (!isOnline || onboardingFinishing) return@LaunchedEffect
        when (link.kind) {
            com.musicvault.app.jams.JamInvitation.Kind.ROOM -> {
                if (jamRoom.roomCode == link.code) vm.consumeJamLink()
                else if (!jamRoom.active && !jamRoom.busy) { vm.jams.joinRoom(link.code); vm.consumeJamLink() }
            }
            com.musicvault.app.jams.JamInvitation.Kind.FRIEND -> {
                if (!jamContacts.busy) { vm.jamFriends.requestFromLink(link.code); vm.consumeJamLink() }
            }
        }
    }
    val switchRoomLink = incomingJamLink?.takeIf { it.kind == com.musicvault.app.jams.JamInvitation.Kind.ROOM &&
        jamRoom.active && it.code != jamRoom.roomCode && !jamRoom.busy }
    if (switchRoomLink != null) AlertDialog(onDismissRequest = vm::consumeJamLink,
        title = { Text(stringResource(R.string.jams_switch_room)) },
        text = { Text(stringResource(if (jamRoom.isHost) R.string.jams_switch_host else R.string.jams_switch_guest)) },
        confirmButton = { TextButton(onClick = vm.jams::leave) { Text(stringResource(R.string.jams_join)) } },
        dismissButton = { TextButton(onClick = vm::consumeJamLink) { Text(stringResource(R.string.jams_cancel)) } })

    AppUpdateHost(appUpdates, online = isOnline, enabled = !onboardingFinishing)

    // Android only exposes clipboard contents while an app is in the foreground.
    // Register for exactly that lifecycle window and also inspect once on resume.
    DisposableEffect(context, lifecycleOwner, rootView, state.spotifyConnected) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        fun inspect() {
            // Reading before the activity has window focus can return an empty clip
            // on recent Android versions. Only publish a result when the platform
            // actually allowed the read, then retry once focus is restored.
            runCatching {
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            }.onSuccess(vm::inspectSpotifyClipboard)
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener(::inspect)
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) rootView.post(::inspect)
        }
        var listening = false
        fun start() {
            if (!listening) {
                clipboard.addPrimaryClipChangedListener(listener)
                if (rootView.viewTreeObserver.isAlive) {
                    rootView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                }
                listening = true
            }
            rootView.post(::inspect)
        }
        fun stop() {
            if (listening) {
                clipboard.removePrimaryClipChangedListener(listener)
                if (rootView.viewTreeObserver.isAlive) {
                    rootView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                }
            }
            listening = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.spotifyConnected) {
        if (state.spotifyConnected) showSpotifyLogin = false
    }

    // App-level back navigation: close the full player/details before allowing
    // Android to leave the activity. This keeps the system Back button useful
    // for navigating inside MusicVault.
    LaunchedEffect(state.section, state.fullPlayer, showSettings, state.selectedLocalAlbum, state.selectedRemoteAlbum, state.selectedPlaylist) {
        com.musicvault.app.diagnostics.PerformanceDiagnostics.setScreen(when {
            state.fullPlayer -> com.musicvault.app.diagnostics.DiagnosticsScreen.PLAYER
            showSettings -> com.musicvault.app.diagnostics.DiagnosticsScreen.SETTINGS
            state.selectedLocalAlbum != null || state.selectedRemoteAlbum != null || state.selectedPlaylist != null -> com.musicvault.app.diagnostics.DiagnosticsScreen.DETAIL
            state.section == AppSection.HOME -> com.musicvault.app.diagnostics.DiagnosticsScreen.HOME
            state.section == AppSection.SEARCH -> com.musicvault.app.diagnostics.DiagnosticsScreen.SEARCH
            else -> com.musicvault.app.diagnostics.DiagnosticsScreen.LIBRARY
        })
    }
    val sectionBackStack by vm.sectionBackStack.collectAsStateWithLifecycle()
    val canHandleBack = sectionBackStack.isNotEmpty() || state.fullPlayer || showSettings ||
        state.selectedLocalAlbum != null || state.selectedRemoteAlbum != null ||
        state.selectedPlaylist != null || state.section != AppSection.HOME
    PredictiveBackHandler(enabled = canHandleBack) { events ->
        events.collect { }
        when {
            state.fullPlayer -> vm.closeFullPlayer()
            showSettings -> showSettings = false
            state.selectedLocalAlbum != null -> vm.closeLocalAlbum()
            state.selectedRemoteAlbum != null -> vm.closeRemoteAlbum()
            state.selectedPlaylist != null -> vm.closePlaylist()
            else -> vm.navigateBack()
        }
    }

    CompositionLocalProvider(LocalHybridDesign provides (state.appDesign == APP_DESIGN_HYBRID)) {
    Scaffold(
        containerColor = VaultColors.Background,
        bottomBar = {
            if (!state.fullPlayer && !showSettings) {
                Column {
                    if (!showJams) state.selectedTrack?.let {
                        MiniPlayer(
                            track = it,
                            isPlaying = isPlaying,
                            onToggle = vm::togglePlayback,
                            onOpen = vm::openFullPlayer,
                            favorite = it.id in favoriteIds,
                            onFavorite = { vm.toggleFavorite(it) },
                            hybrid = state.appDesign == APP_DESIGN_HYBRID,
                            vm = vm
                        )
                    }
                    BottomBar(
                        section = state.section,
                        hybrid = state.appDesign == APP_DESIGN_HYBRID,
                        onSelect = { showJams = false; vm.selectSection(it) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showJams && !state.fullPlayer) {
                androidx.activity.compose.BackHandler { showJams = false }
                JamsScreen(vm.jams, vm.jamFriends, state.selectedTrack, vm::searchJamSongs,
                    onOpenRoom = { vm.openJamLink(com.musicvault.app.jams.JamInvitation.roomUrl(it)) }) { showJams = false }
            } else if (showSettings && !state.fullPlayer) {
                SettingsScreen(
                    appUpdates = appUpdates,
                    crossfadeSeconds = state.crossfadeSeconds,
                    playerStyle = state.playerStyle,
                    appDesign = state.appDesign,
                    downloadDirLabel = downloadDirLabel,
                    spotifyConnected = state.spotifyConnected,
                    spotifyBusy = state.spotifyImporting,
                    onCrossfadeChange = vm::setCrossfadeSeconds,
                    onPlayerStyleChange = vm::setPlayerStyle,
                    onAppDesignChange = vm::setAppDesign,
                    onPickDownloadFolder = { folderPicker.launch(null) },
                    onUseInternalFolder = vm::useInternalDownloads,
                    onConnectSpotify = { showSpotifyLogin = true },
                    onDisconnectSpotify = vm::disconnectSpotify,
                    vm = vm,
                    onClose = { showSettings = false }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(
                            horizontal = 20.dp,
                            vertical = if (state.appDesign == APP_DESIGN_HYBRID) 8.dp else 10.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                state.section == AppSection.SEARCH -> "Buscar"
                                state.section == AppSection.LIBRARY -> stringResource(com.musicvault.app.R.string.ui_biblioteca_497ac5)
                                state.section == AppSection.PLAYLISTS -> "Playlists"
                                else -> "MusicVault"
                            },
                            color = VaultColors.TextPrimary,
                            fontSize = if (state.appDesign == APP_DESIGN_HYBRID) 22.sp else 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = VaultFonts.Display,
                            letterSpacing = (-0.75).sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showJams = true }) { Text("JAMS"); if (jamContacts.requests.isNotEmpty() || jamContacts.invitations.isNotEmpty()) Badge(Modifier.padding(start = 5.dp)) }
                        Box(
                            Modifier
                                .size(if (state.appDesign == APP_DESIGN_HYBRID) 44.dp else 34.dp)
                                .clip(CircleShape)
                                .background(VaultColors.Surface1)
                                .border(1.dp, VaultColors.Divider, CircleShape)
                                .pressScale { showSettings = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, stringResource(R.string.ui_ajustes_9751f8), tint = VaultColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    // Library and playlists contain native RecyclerViews. Mount them
                    // when selected so their holders are laid out with the latest
                    // Room snapshot, as happens after returning from Settings.
                    Box(Modifier.fillMaxSize()) {
                        PersistentSection(
                            active = state.section == AppSection.HOME,
                            label = stringResource(com.musicvault.app.R.string.ui_inicio_4f70d8)
                        ) {
                            HomeScreen(
                                state = state,
                                albumDownloads = albumDownloads,
                                isPlaying = isPlaying,
                                isOnline = isOnline,
                                offlinePlayableIds = offlinePlayableIds,
                                vm = vm,
                                onInitialVisualReady = {
                                    if (onboardingFinishing && recommendationWarmupReady && !homeVisualReady) {
                                        homeVisualReady = true
                                    }
                                }
                            )
                        }
                        PersistentSection(
                            active = state.section == AppSection.SEARCH,
                            label = "Buscar"
                        ) {
                            SearchScreen(searchState, isPlaying, vm, prewarm = onboardingFinishing)
                        }
                        if (state.section == AppSection.LIBRARY) {
                            key(state.downloaded.isEmpty()) {
                                LibraryScreen(
                                    libraryState, isPlaying, vm,
                                    permissionRequested = libraryPermissionRequested,
                                    onPermissionRequested = { libraryPermissionRequested = true }
                                )
                            }
                        }
                        if (state.section == AppSection.PLAYLISTS) {
                            key(state.playlists.isEmpty()) {
                                PlaylistScreen(state, vm)
                            }
                        }

                    }
                }
            }

        }
        // Detail navigation is a sibling of Scaffold so it can cover the main
        // navigation. Keep its own mini-player at the bottom, though: otherwise
        // playback started from a detail screen is hidden until that screen is
        // closed.
        if (state.selectedLocalAlbum != null || state.selectedRemoteAlbum != null || state.selectedPlaylist != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f)
                    .background(VaultColors.Background)
            ) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                isDownloading = playlist.id in albumDownloads,
                                isPlaying = isPlaying,
                                isOnline = isOnline,
                                offlinePlayableIds = offlinePlayableIds,
                                selectedTrackId = state.selectedTrack?.id,
                                playlists = state.playlists,
                                albums = state.albums,
                                vm = vm
                            )
                        }
                    }
                    state.selectedTrack?.let { track ->
                        MiniPlayer(
                            track = track,
                            isPlaying = isPlaying,
                            onToggle = vm::togglePlayback,
                            onOpen = vm::openFullPlayer,
                            favorite = track.id in favoriteIds,
                            onFavorite = { vm.toggleFavorite(track) },
                            hybrid = state.appDesign == APP_DESIGN_HYBRID,
                            vm = vm
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            modifier = Modifier.fillMaxSize().zIndex(30f),
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
                    FullPlayerScreen(track, isPlaying, state.shuffle, favoriteIds, state.playerStyle, vm)
                }
            }
        }
        if (showSpotifyLogin) {
            SpotifyLoginDialog(
                busy = state.spotifyImporting,
                onSession = vm::connectSpotify,
                onDismiss = { showSpotifyLogin = false }
            )
        }
        state.spotifyPlaylistReview?.let { playlist ->
            SpotifyPlaylistReviewDialog(
                playlist = playlist,
                onImport = vm::confirmSpotifyPlaylistImport,
                onDismiss = vm::cancelSpotifyPlaylistImport
            )
        }
        AppNoticeHost(
            state = state,
            onImportSpotify = vm::importSpotifyOffer,
            onDismissSpotify = vm::clearSpotifyOffer,
            onDismissError = vm::clearError,
            onDismissMessage = vm::clearProviderMessage
        )
        // Keep first-run loading fully opaque. Fading the whole overlay made the
        // already-mounted Home screen visible while its rows were still settling.
        // We remove the surface atomically only after the visual-ready gate passes.
        if (onboardingFinishing) {
            Box(Modifier.fillMaxSize().zIndex(2_000f)) {
                RecommendationLoadingScreen(
                    genres = onboardingGenres,
                    artists = onboardingArtists
                )
            }
        }
    }
    }
}

/** A single notice layer shared by every app surface, including details and player. */
@Composable
internal fun AppNoticeHost(
    state: MusicUiState,
    onImportSpotify: () -> Unit,
    onDismissSpotify: () -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val offer = state.spotifyOffer.takeIf {
        state.errorMessage == null && state.providerMessage == null && !state.spotifyImporting
    }
    val message = state.errorMessage ?: state.providerMessage
        ?: if (state.spotifyImporting) "Importando desde Spotify…" else null
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = offer != null || message != null,
            enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)) +
                slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(140)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) +
                slideOutVertically(tween(160)) { -it / 2 }
        ) {
            if (offer != null) {
                SpotifyClipboardNotice(
                    link = offer,
                    hybrid = state.appDesign == APP_DESIGN_HYBRID,
                    onImport = onImportSpotify,
                    onDismiss = onDismissSpotify
                )
            } else if (message != null) {
                val dismiss = if (state.errorMessage != null) onDismissError else onDismissMessage
                AssistChip(
                    onClick = dismiss,
                    label = { Text(message, maxLines = if (state.errorMessage != null) 8 else 3) },
                    trailingIcon = { if (!state.spotifyImporting) Icon(Icons.Default.Close, null) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = VaultColors.Surface2,
                        labelColor = VaultColors.TextPrimary,
                        trailingIconContentColor = VaultColors.TextSecondary
                    ),
                    border = BorderStroke(1.dp, VaultColors.Divider),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun SpotifyLoginDialog(
    busy: Boolean,
    onSession: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cookieManager = remember { CookieManager.getInstance() }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var rootBrowser by remember { mutableStateOf<WebView?>(null) }
    val loginBrowsers = remember { mutableListOf<WebView>() }
    var captured by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }

    fun inspectCookie() {
        if (captured) return
        val value = sequenceOf(SPOTIFY_WEB_URL, SPOTIFY_ACCOUNTS_URL)
            .mapNotNull(cookieManager::getCookie)
            .flatMap { it.split(';').asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?: return
        captured = true
        onSession(value)
    }

    LaunchedEffect(browser, captured) {
        while (browser != null && !captured) {
            delay(600L)
            inspectCookie()
        }
    }
    BackHandler(enabled = !busy && browser != null) {
        val current = browser ?: return@BackHandler
        when {
            current.canGoBack() -> current.goBack()
            current !== rootBrowser -> {
                (current.parent as? ViewGroup)?.removeView(current)
                loginBrowsers.remove(current)
                current.destroy()
                browser = rootBrowser
            }
            else -> onDismiss()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            loginBrowsers.toList().forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.removeAllViews()
                view.destroy()
            }
            loginBrowsers.clear()
            browser = null
            rootBrowser = null
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = VaultColors.Surface1
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_conectar_spotify_ed2de8), color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.Close, stringResource(R.string.ui_cerrar_4b0816), tint = VaultColors.TextSecondary)
                    }
                }
                if (busy || pageLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = VaultColors.Accent)
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        FrameLayout(context).apply {
                            val container = this
                            val client = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    browser = view
                                    cookieManager.flush()
                                    inspectCookie()
                                }
                            }
                            lateinit var createBrowser: () -> WebView
                            val chrome = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    if (view === browser) pageLoading = newProgress < 100
                                }

                                override fun onCreateWindow(
                                    view: WebView,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message
                                ): Boolean {
                                    if (!isUserGesture) return false
                                    val popup = createBrowser()
                                    container.addView(
                                        popup,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    browser = popup
                                    pageLoading = true
                                    (resultMsg.obj as WebView.WebViewTransport).apply {
                                        webView = popup
                                    }
                                    resultMsg.sendToTarget()
                                    return true
                                }

                                override fun onCloseWindow(window: WebView) {
                                    if (window !== rootBrowser) {
                                        (window.parent as? ViewGroup)?.removeView(window)
                                        loginBrowsers.remove(window)
                                        window.destroy()
                                        browser = rootBrowser
                                    }
                                }
                            }
                            createBrowser = {
                                WebView(context).apply {
                                    setBackgroundColor(android.graphics.Color.rgb(18, 18, 18))
                                    settings.javaScriptEnabled = true
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.setSupportMultipleWindows(true)
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)
                                    webViewClient = client
                                    webChromeClient = chrome
                                    loginBrowsers += this
                                }
                            }
                            val main = createBrowser()
                            rootBrowser = main
                            browser = main
                            addView(
                                main,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            cookieManager.removeAllCookies {
                                main.loadUrl(SPOTIFY_LOGIN_URL)
                            }
                        }
                    }
                )
            }
        }
    }
}

internal const val SPOTIFY_WEB_URL = "https://open.spotify.com/"
internal const val SPOTIFY_ACCOUNTS_URL = "https://accounts.spotify.com/"
internal const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

@Composable
internal fun VaultModalDialog(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onDismiss: () -> Unit,
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Keep the modal inside the real dialog viewport. Some of the richer
        // dialogs (notably create-playlist) contain a search field plus a
        // fixed-height native RecyclerView. Without a bounded viewport that
        // content can push the action row below the screen, making the
        // "Crear playlist" button look as if it disappeared.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight),
                shape = RoundedCornerShape(28.dp),
                color = VaultColors.Surface1,
                contentColor = VaultColors.TextPrimary,
                tonalElevation = 0.dp,
                shadowElevation = 24.dp,
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 18.dp, end = 10.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(VaultColors.AccentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = VaultColors.Accent, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                color = VaultColors.TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    subtitle,
                                    color = VaultColors.TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ui_cerrar_4b0816), tint = VaultColors.TextSecondary)
                        }
                    }
                    HorizontalDivider(color = VaultColors.Divider.copy(alpha = 0.8f))

                    // Header and actions remain fixed. Only the center section is
                    // allowed to scroll when the device/IME leaves less height.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = !scrollContent)
                            .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        content = content
                    )

                    HorizontalDivider(color = VaultColors.Divider.copy(alpha = 0.8f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VaultColors.SurfaceBase)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
        }
    }
}

@Composable
internal fun VaultSelectionBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = VaultColors.AccentSoft,
        border = BorderStroke(1.dp, VaultColors.Accent.copy(alpha = 0.28f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = VaultColors.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = VaultFonts.Mono
        )
    }
}

@Composable
internal fun SpotifyPlaylistReviewDialog(
    playlist: SpotifyPlaylist,
    onImport: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(playlist.id) {
        mutableStateOf<Set<String>>(playlist.tracks.mapTo(linkedSetOf()) { it.id })
    }
    val allSelected = selected.size == playlist.tracks.size && playlist.tracks.isNotEmpty()
    val spotifyPickerAdapter = remember(playlist.id) {
        SpotifyImportTrackPickerAdapter(selected) { selected = it }
    }

    VaultModalDialog(
        title = playlist.title,
        subtitle = "Importar playlist de Spotify",
        icon = VaultIcons.QueueMusic,
        onDismiss = onDismiss,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CachedCover(
                    url = playlist.coverUrl,
                    contentDescription = "Portada de ${playlist.title}",
                    modifier = Modifier
                        .size(74.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(VaultColors.Surface2),
                    targetSizePx = 180,
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ui_elige_que_canciones_quieres_traer_a_music_vaul_76dd85),
                        color = VaultColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    VaultSelectionBadge("${selected.size} / ${playlist.tracks.size} seleccionadas")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.ui_canciones_90e305), color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
                TextButton(
                    onClick = {
                        if (allSelected) spotifyPickerAdapter.clearSelection()
                        else spotifyPickerAdapter.selectAll(playlist.tracks)
                    }
                ) {
                    Text(if (allSelected) "Quitar todas" else "Seleccionar todas", color = VaultColors.Accent)
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = VaultColors.SurfaceBase,
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                if (playlist.tracks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ui_spotify_no_devolvio_canciones_para_esta_playli_942c17), color = VaultColors.TextSecondary)
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                        factory = { context ->
                            RecyclerView(context).apply {
                                layoutManager = LinearLayoutManager(context).apply {
                                    isItemPrefetchEnabled = true
                                    initialPrefetchItemCount = 10
                                }
                                adapter = spotifyPickerAdapter
                                itemAnimator = null
                                setHasFixedSize(true)
                                setItemViewCacheSize(14)
                                recycledViewPool.setMaxRecycledViews(0, 18)
                                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                                setPadding(0, 6, 0, 6)
                                clipToPadding = false
                            }
                        },
                        update = { spotifyPickerAdapter.update(playlist.tracks) }
                    )
                }
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                Text(stringResource(R.string.ui_cancelar_c111e0), color = VaultColors.TextSecondary)
            }
            Button(
                onClick = { onImport(selected) },
                enabled = selected.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultColors.Accent,
                    contentColor = VaultColors.OnAccent,
                    disabledContainerColor = VaultColors.Surface3,
                    disabledContentColor = VaultColors.TextTertiary
                )
            ) {
                Icon(VaultIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Importar ${selected.size}", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun PersistentSection(
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
internal fun RecommendationTrackCard(
    track: Track,
    progressFlow: Flow<Int?>,
    onPlay: () -> Unit,
    actions: @Composable () -> Unit,
    isPlaying: Boolean,
    enabled: Boolean,
    hybrid: Boolean
) {
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloading = progress != null && !track.isDownloaded
    val cardSize = if (hybrid) 128.dp else 150.dp
    val pressInteraction = remember { MutableInteractionSource() }
    val pressed by pressInteraction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(durationMillis = if (pressed) 70 else 120),
        label = "recommendationPress"
    )
    Column(
        Modifier
            .width(cardSize)
            .scale(pressScale)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clickable(
                interactionSource = pressInteraction,
                indication = null,
                enabled = enabled,
                onClick = onPlay
            )
    ) {
        Box(Modifier.size(cardSize)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(track.coverUri)
                    .size(if (hybrid) 256 else 300)
                    .memoryCacheKey("home-track:${track.coverUri}")
                    .diskCacheKey(track.coverUri)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                imageLoader = CoverImageCache.loader(LocalContext.current)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (isPlaying) VaultColors.Accent else VaultColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (hybrid) track.artistName.uppercase() else track.artistName,
                    color = VaultColors.TextSecondary,
                    fontSize = if (hybrid) 10.sp else 12.sp,
                    fontFamily = if (hybrid) VaultFonts.Mono else VaultFonts.Body,
                    letterSpacing = if (hybrid) 0.4.sp else 0.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (downloading) {
                ThinDownloadProgress(
                    progress = ((progress ?: 0) / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.width(28.dp)
                )
            }
            // Same interaction as list rows: tap the card to play; overflow is
            // always present and owns Download/Delete/queue actions.
            actions()
        }
    }
}

@Composable
internal fun ThinDownloadProgress(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    color: Color = VaultColors.Accent
) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.height(2.dp),
            color = color,
            trackColor = VaultColors.Surface2
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.height(2.dp),
            color = color,
            trackColor = VaultColors.Surface2
        )
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
internal fun CreateCollectionDialog(
    title: String,
    placeholder: String,
    tracks: List<Track>,
    onCreate: (String, List<Track>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var songQuery by remember { mutableStateOf("") }
    var selectedCount by remember { mutableIntStateOf(0) }

    // Build the lowercase search keys once per library snapshot instead of doing
    // title/artist normalization again for every visible row while the user types.
    val searchIndex = remember(tracks) {
        tracks.map { track ->
            track to (track.title + "\u0000" + track.artistName).lowercase()
        }
    }
    val visibleTracks = remember(songQuery, searchIndex) {
        val query = songQuery.trim().lowercase()
        if (query.isBlank()) tracks
        else {
            val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            searchIndex.asSequence()
                .filter { (_, key) -> terms.all(key::contains) }
                .map { it.first }
                .toList()
        }
    }

    val pickerAdapter = remember {
        PlaylistTrackPickerAdapter { count -> selectedCount = count }
    }
    val allSelected = tracks.isNotEmpty() && selectedCount == tracks.size

    VaultModalDialog(
        title = title,
        subtitle = "Ponle un nombre y elige las canciones que quieras incluir",
        icon = VaultIcons.QueueMusic,
        onDismiss = onDismiss,
        scrollContent = false,
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.ui_nombre_e68491)) },
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(VaultIcons.QueueMusic, contentDescription = null, tint = VaultColors.Accent) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VaultColors.Accent,
                    unfocusedBorderColor = VaultColors.Divider,
                    focusedTextColor = VaultColors.TextPrimary,
                    unfocusedTextColor = VaultColors.TextPrimary,
                    focusedLabelColor = VaultColors.Accent,
                    unfocusedLabelColor = VaultColors.TextSecondary,
                    cursorColor = VaultColors.Accent
                )
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_canciones_90e305), color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
                    Text(
                        if (tracks.isEmpty()) "Puedes crearla vacía y agregar canciones después"
                        else "${tracks.size} disponibles en tu biblioteca",
                        color = VaultColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                if (tracks.isNotEmpty()) {
                    VaultSelectionBadge("$selectedCount elegidas")
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            if (allSelected) pickerAdapter.clearSelection()
                            else pickerAdapter.selectAll(tracks)
                        }
                    ) {
                        Text(if (allSelected) "Ninguna" else "Todas", color = VaultColors.Accent)
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = songQuery,
                    onValueChange = { songQuery = it.take(80) },
                    placeholder = { Text(stringResource(R.string.ui_buscar_por_cancion_o_artista_aaa665)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = VaultColors.TextSecondary) },
                    trailingIcon = {
                        if (songQuery.isNotBlank()) {
                            IconButton(onClick = { songQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ui_limpiar_busqueda_493ecd), tint = VaultColors.TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VaultColors.Accent,
                        unfocusedBorderColor = VaultColors.Divider,
                        focusedTextColor = VaultColors.TextPrimary,
                        unfocusedTextColor = VaultColors.TextPrimary,
                        cursorColor = VaultColors.Accent
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = VaultColors.SurfaceBase,
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                when {
                    tracks.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(VaultIcons.LibraryMusic, contentDescription = null, tint = VaultColors.TextTertiary, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.ui_tu_biblioteca_todavia_esta_vacia_a3c989), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ui_crea_la_playlist_ahora_y_agrega_canciones_cuan_7b25d5), color = VaultColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    visibleTracks.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = VaultColors.TextTertiary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.ui_no_encontramos_esa_cancion_b8bdfe), color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ui_prueba_con_el_titulo_o_el_nombre_del_artista_b1aaf7), color = VaultColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    else -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                RecyclerView(context).apply {
                                    layoutManager = LinearLayoutManager(context).also { lm ->
                                        lm.isItemPrefetchEnabled = true
                                        lm.initialPrefetchItemCount = 12
                                    }
                                    setHasFixedSize(true)
                                    setItemViewCacheSize(14)
                                    recycledViewPool.setMaxRecycledViews(0, 18)
                                    itemAnimator = null
                                    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                                    adapter = pickerAdapter
                                }
                            },
                            update = { pickerAdapter.update(visibleTracks) }
                        )
                    }
                }
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                Text(stringResource(R.string.ui_cancelar_c111e0), color = VaultColors.TextSecondary)
            }
            Button(
                onClick = {
                    val cleanName = name.trim()
                    val ids = pickerAdapter.selectedIds()
                    // Preserve library order as playlist order. The first chosen
                    // song therefore also becomes the stable playlist cover.
                    onCreate(cleanName, tracks.filter { it.id in ids })
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultColors.Accent,
                    contentColor = VaultColors.OnAccent
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_crear_playlist_6fadc4), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun DeletePlaylistDialog(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    VaultModalDialog(
        title = "Eliminar playlist",
        subtitle = playlist.title,
        icon = VaultIcons.DeleteOutline,
        onDismiss = onDismiss,
        content = {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = VaultColors.SurfaceBase,
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(VaultIcons.DeleteOutline, contentDescription = null, tint = VaultColors.Error, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text("¿Eliminar \"${playlist.title}\"?", color = VaultColors.TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(R.string.ui_la_playlist_desaparecera_pero_las_canciones_de_6dbca0),
                            color = VaultColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VaultColors.Divider)
            ) { Text(stringResource(R.string.ui_cancelar_c111e0), color = VaultColors.TextSecondary) }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Error, contentColor = Color.White)
            ) {
                Icon(VaultIcons.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_eliminar_54e140), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun BottomBar(section: AppSection, hybrid: Boolean, onSelect: (AppSection) -> Unit) {
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
            BottomBarItem(AppSection.HOME, stringResource(com.musicvault.app.R.string.ui_inicio_a9dec9), section, hybrid, onSelect) {
                Icon(if (section == AppSection.HOME) Icons.Default.Home else Icons.Outlined.Home, null)
            }
            BottomBarItem(AppSection.SEARCH, stringResource(com.musicvault.app.R.string.ui_buscar_9c0e8f), section, hybrid, onSelect) {
                Icon(if (section == AppSection.SEARCH) Icons.Default.Search else Icons.Outlined.Search, null)
            }
            BottomBarItem(AppSection.LIBRARY, stringResource(com.musicvault.app.R.string.ui_biblioteca_0b4762), section, hybrid, onSelect) {
                Icon(if (section == AppSection.LIBRARY) VaultIcons.LibraryMusic else VaultIcons.LibraryMusicOutline, null)
            }
            BottomBarItem(AppSection.PLAYLISTS, "Playlists", section, hybrid, onSelect) {
                Icon(if (section == AppSection.PLAYLISTS) VaultIcons.QueueMusic else VaultIcons.QueueMusicOutline, null)
            }
        }
    }
}

@Composable
internal fun RowScope.BottomBarItem(
    item: AppSection,
    label: String,
    section: AppSection,
    hybrid: Boolean,
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
        if (hybrid) {
            Box(
                Modifier
                    .width(52.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) VaultColors.AccentSoft else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides if (active) VaultColors.Accent else VaultColors.TextTertiary) {
                    icon()
                }
            }
        } else {
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
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontFamily = VaultFonts.Mono,
            fontWeight = if (active && hybrid) FontWeight.Bold else if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) VaultColors.TextPrimary else VaultColors.TextTertiary,
            letterSpacing = 0.sp
        )
    }
}

@Composable
internal fun SectionTitle(text: String, hybrid: Boolean = false) {
    Text(text, color = VaultColors.TextPrimary, fontSize = if (hybrid) 19.sp else 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
}

@Composable
internal fun AlbumCard(
    album: Album,
    onClick: () -> Unit = {},
    onDownloadAll: (() -> Unit)? = null,
    isDownloading: Boolean = false,
    hybrid: Boolean = false
) {
    val (top, bottom) = remember(album.id) { gradientFor(album.id) }
    val cardSize = if (hybrid) 140.dp else 150.dp
    Column(Modifier.width(cardSize)) {
        Box(
            Modifier
                .size(cardSize)
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp), ambientColor = top.copy(alpha = 0.4f))
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(top, bottom)))
                .pressScale(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                CachedCover(
                    url = album.coverUri,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    targetSizePx = 300,
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
                            ThinDownloadProgress(modifier = Modifier.width(28.dp), color = VaultColors.TextPrimary)
                        } else {
                            Icon(VaultIcons.Download, contentDescription = stringResource(R.string.ui_descargar_album_fac210), tint = VaultColors.TextPrimary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(album.title, color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (hybrid) album.artistName.uppercase() else album.artistName,
            color = VaultColors.TextSecondary,
            fontSize = if (hybrid) 10.sp else 12.sp,
            fontFamily = if (hybrid) VaultFonts.Mono else VaultFonts.Body,
            letterSpacing = if (hybrid) 0.4.sp else 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    val hybrid = LocalHybridDesign.current
    val (top, bottom) = remember(playlist.id) { gradientFor(playlist.id) }
    Column(
        Modifier
            .width(if (hybrid) 164.dp else 170.dp)
            .clip(RoundedCornerShape(if (hybrid) 16.dp else 12.dp))
            .background(if (hybrid) VaultColors.Surface2 else VaultColors.Surface1)
            .then(if (hybrid) Modifier.border(1.dp, VaultColors.Divider, RoundedCornerShape(16.dp)) else Modifier)
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
        ) {
            if (playlist.coverUri != null) {
                CachedCover(
                    url = playlist.coverUri,
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    targetSizePx = 300
                )
            } else {
                Icon(VaultIcons.Album, null, tint = VaultColors.TextPrimary.copy(alpha = 0.9f), modifier = Modifier.size(40.dp))
            }
        }
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
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
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
    val hybrid = LocalHybridDesign.current
    val displayedProgress = progress
    val progressFraction = ((displayedProgress ?: 0) / 100f).coerceIn(0f, 1f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (hybrid) 14.dp else 10.dp))
            .then(
                if (hybrid) Modifier
                    .background(VaultColors.Surface1)
                    .border(1.dp, VaultColors.Divider.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
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
        if (displayedProgress != null) {
            Box(
                Modifier.width(38.dp).height(48.dp).clickable(onClick = onCancelDownload),
                contentAlignment = Alignment.Center
            ) {
                ThinDownloadProgress(
                    progress = ((displayedProgress ?: 0) / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.width(28.dp)
                )
            }
        }
        // Playback is owned by the whole row. The trailing affordance is always
        // the overflow menu, regardless of whether the track is local or online.
        actions()
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
    val currentProgress by vm.downloadProgressFor(track.id).collectAsStateWithLifecycle(initialValue = null)
    var showAlbums by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.size(48.dp),
        factory = { context ->
            android.widget.ImageButton(context).apply {
                setImageResource(R.drawable.ic_vault_more)
                val density = resources.displayMetrics.density
                val inset = (12 * density).roundToInt()
                setPadding(inset, inset, inset, inset)
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(50, 224, 164, 88)),
                    shape,
                    null
                )
                contentDescription = context.getString(R.string.ui_mas_opciones_977c75)
            }
        },
        update = { button ->
            button.setOnClickListener {
                android.widget.PopupMenu(button.context, button, android.view.Gravity.END).apply {
                    menu.add(0, 1, 0, "Agregar a cola de reproducción")
                    menu.add(0, 2, 1, if (currentFavorite) "Quitar de Me gusta" else "Me gusta")
                    menu.add(0, 3, 2, "Agregar a álbum")
                    when {
                        track.isDownloaded && !track.audioUri.isNullOrBlank() ->
                            menu.add(0, 4, 3, "Eliminar canción descargada")
                        currentProgress != null ->
                            menu.add(0, 6, 3, "Cancelar descarga")
                        else ->
                            menu.add(0, 5, 3, "Descargar")
                    }
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> vm.addToQueue(track)
                            2 -> vm.toggleFavorite(track)
                            3 -> showAlbums = true
                            4 -> confirmDelete = true
                            5 -> vm.requestDownload(track)
                            6 -> vm.cancelDownload(track.id)
                        }
                        true
                    }
                    show()
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ui_eliminar_cancion_d65308)) },
            text = { Text("¿Quieres eliminar \"${track.title}\" de las descargas de MusicVault?") },
            confirmButton = { TextButton(onClick = { vm.deleteTrack(track); confirmDelete = false }) { Text(stringResource(R.string.ui_eliminar_54e140), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } }
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
            title = { Text(stringResource(R.string.ui_crear_nuevo_album_5d97f7)) },
            text = { OutlinedTextField(newAlbumName, { newAlbumName = it }, label = { Text(stringResource(R.string.ui_nombre_e68491)) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newAlbumName.isNotBlank(), onClick = {
                    vm.createPlaylist(newAlbumName, listOf(track))
                    showCreateAlbum = false
                    newAlbumName = ""
                }) { Text(stringResource(R.string.ui_crear_y_agregar_d4727e)) }
            },
            dismissButton = { TextButton(onClick = { showCreateAlbum = false }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } }
        )
    }
}

@Composable
internal fun RemoteAlbumDetailActionsMenu(
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
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_agregar_a_cola_de_reproduccion_f8c617)) }, onClick = { onDismiss(); vm.addRemoteTrackToQueue(track) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_agregar_a_album_7c4ad3)) }, onClick = { onDismiss(); showAlbums = true })
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
            title = { Text(stringResource(R.string.ui_crear_nuevo_album_5d97f7)) },
            text = { OutlinedTextField(newAlbumName, { newAlbumName = it }, label = { Text(stringResource(R.string.ui_nombre_e68491)) }, singleLine = true) },
            confirmButton = { TextButton(enabled = newAlbumName.isNotBlank(), onClick = { vm.createAlbumFromRemote(newAlbumName, track); showCreateAlbum = false; newAlbumName = "" }) { Text(stringResource(R.string.ui_crear_48a068)) } },
            dismissButton = { TextButton(onClick = { showCreateAlbum = false }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } }
        )
    }
}

@Composable
internal fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        if (choices.isEmpty()) Text(stringResource(R.string.ui_no_tienes_albumes_creados_6edecb)) else Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
        ) {
            choices.forEach { (id, label) ->
                Text(label, Modifier.fillMaxWidth().clickable { onSelect(id) }.padding(14.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancelar_c111e0)) } })
}

@Composable
internal fun AlbumChoiceDialog(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ui_agregar_a_album_7c4ad3)) }, text = {
        Column {
            TextButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_crear_nuevo_album_5d97f7)) }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                albums.forEach { album ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(album.title, Modifier.weight(1f).clickable { onSelect(album.id) }.padding(10.dp))
                        if (album.id != "playlist:favorites") {
                            IconButton(onClick = { deleteId = album.id }) { Icon(VaultIcons.DeleteOutline, stringResource(R.string.ui_eliminar_album_8f78a0)) }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancelar_c111e0)) } })
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.ui_eliminar_album_8f78a0)) }, text = { Text(stringResource(R.string.ui_eliminar_este_album_de_musicvault_las_cancione_33f05c)) }, confirmButton = { TextButton(onClick = { onDelete(id); deleteId = null }) { Text(stringResource(R.string.ui_eliminar_54e140)) } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.ui_cancelar_c111e0)) } })
    }
}

@Composable
internal fun StatCard(title: String, value: String) {
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
internal val LocalHybridDesign = staticCompositionLocalOf { false }
