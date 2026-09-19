package com.musicvault.app.ui

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
private fun SpotifyClipboardNotice(
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
                    letterSpacing = if (hybrid) 0.6.sp else 0.sp,
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
                    Text("Importar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                TextButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(contentColor = VaultColors.Accent),
                    contentPadding = PaddingValues(horizontal = 9.dp)
                ) {
                    Text("Importar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(if (hybrid) 44.dp else 34.dp)) {
                Icon(Icons.Default.Close, "Ignorar enlace", tint = VaultColors.TextSecondary, modifier = Modifier.size(17.dp))
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
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val albumDownloads by vm.albumDownloads.collectAsStateWithLifecycle()
    val favoriteIds = state.favoriteIds
    val downloadDirLabel by vm.downloadDirLabel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // SAF directory picker for the user-selected download destination.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::chooseDownloadTree) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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

    CompositionLocalProvider(LocalHybridDesign provides (state.appDesign == APP_DESIGN_HYBRID)) {
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
                            hybrid = state.appDesign == APP_DESIGN_HYBRID,
                            vm = vm
                        )
                    }
                    BottomBar(
                        section = state.section,
                        hybrid = state.appDesign == APP_DESIGN_HYBRID,
                        onSelect = vm::selectSection
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showSettings && !state.fullPlayer) {
                SettingsScreen(
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
                                state.section == AppSection.LIBRARY -> "Biblioteca"
                                state.section == AppSection.PLAYLISTS -> "Listas"
                                else -> "MusicVault"
                            },
                            color = VaultColors.TextPrimary,
                            fontSize = if (state.appDesign == APP_DESIGN_HYBRID) 22.sp else 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = VaultFonts.Display,
                            letterSpacing = (-0.75).sp,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(if (state.appDesign == APP_DESIGN_HYBRID) 44.dp else 34.dp)
                                .clip(CircleShape)
                                .background(VaultColors.Surface1)
                                .border(1.dp, VaultColors.Divider, CircleShape)
                                .pressScale { showSettings = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, "Ajustes", tint = VaultColors.TextSecondary, modifier = Modifier.size(18.dp))
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
                            HomeScreen(
                                state = state,
                                albumDownloads = albumDownloads,
                                isPlaying = isPlaying,
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
                        PersistentSection(
                            active = state.section == AppSection.LIBRARY,
                            label = "Biblioteca"
                        ) {
                            LibraryScreen(libraryState, isPlaying, vm, prewarm = onboardingFinishing)
                        }
                        PersistentSection(
                            active = state.section == AppSection.PLAYLISTS,
                            label = "Álbumes"
                        ) {
                            PlaylistScreen(state, vm, prewarm = onboardingFinishing)
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
                        isDownloading = playlist.id in albumDownloads,
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
private fun AppNoticeHost(
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
private fun SpotifyLoginDialog(
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
                        Text("Conectar Spotify", color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.Close, "Cerrar", tint = VaultColors.TextSecondary)
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

private const val SPOTIFY_WEB_URL = "https://open.spotify.com/"
private const val SPOTIFY_ACCOUNTS_URL = "https://accounts.spotify.com/"
private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

@Composable
private fun VaultModalDialog(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onDismiss: () -> Unit,
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
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = VaultColors.TextSecondary)
                        }
                    }
                    HorizontalDivider(color = VaultColors.Divider.copy(alpha = 0.8f))

                    // Header and actions remain fixed. Only the center section is
                    // allowed to scroll when the device/IME leaves less height.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
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
private fun VaultSelectionBadge(text: String) {
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
private fun SpotifyPlaylistReviewDialog(
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
                        "Elige qué canciones quieres traer a Music Vault",
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
                Text("CANCIONES", color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
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
                        Text("Spotify no devolvió canciones para esta playlist.", color = VaultColors.TextSecondary)
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
                Text("Cancelar", color = VaultColors.TextSecondary)
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
private fun HomeScreen(
    state: MusicUiState,
    albumDownloads: Set<String>,
    isPlaying: Boolean,
    vm: MusicVaultViewModel,
    onInitialVisualReady: () -> Unit = {}
) {
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
                setFitsSystemWindows(true)
                this.adapter = adapter
            }
        },
        update = { rv ->
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
                    isPlaying = isPlaying,
                    hybridDesign = state.appDesign == APP_DESIGN_HYBRID,
                    onContentCommitted = if (state.recommendedTracks.isNotEmpty() && state.recommendedAlbums.isNotEmpty()) {
                        {
                            // The adapter list is committed. Wait for an actual
                            // pre-draw so the loading overlay is not removed while
                            // RecyclerView/Compose are still laying out the cards.
                            rv.post {
                                if (!rv.isAttachedToWindow) {
                                    onInitialVisualReady()
                                } else {
                                    val observer = rv.viewTreeObserver
                                    if (!observer.isAlive) {
                                        onInitialVisualReady()
                                    } else {
                                        val listener = object : ViewTreeObserver.OnPreDrawListener {
                                            override fun onPreDraw(): Boolean {
                                                if (rv.viewTreeObserver.isAlive) {
                                                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                                                }
                                                rv.post { onInitialVisualReady() }
                                                return true
                                            }
                                        }
                                        observer.addOnPreDrawListener(listener)
                                        rv.invalidate()
                                    }
                                }
                            }
                        }
                    } else null
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
    isPlaying: Boolean,
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
            .clickable(interactionSource = pressInteraction, indication = null, onClick = onPlay)
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
private fun ThinDownloadProgress(
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


@Immutable
private data class SearchScreenState(
    val downloaded: List<Track>,
    val remoteTracks: List<CatalogTrack>,
    val remoteAlbums: List<CatalogAlbum>,
    val isSearching: Boolean,
    val selectedTrackId: String?,
    val playlists: List<Playlist>,
    val active: Boolean
)

private enum class SearchTab { TRACKS, ALBUMS, PLAYLISTS }

@Composable
private fun SearchScreen(
    state: SearchScreenState,
    isPlaying: Boolean,
    vm: MusicVaultViewModel,
    prewarm: Boolean = false
) {
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

    LaunchedEffect(submittedQuery, searchTab) {
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

    LaunchedEffect(selectedRemotePlaylist?.id) {
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
                    Icon(Icons.AutoMirrored.Filled.Send, "Buscar", tint = VaultColors.Accent)
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
            visible = submittedQuery.isNotBlank(),
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
private fun YoutubePlaylistPreviewDialog(
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
                        Icon(Icons.Default.Close, "Cerrar", tint = VaultColors.TextSecondary)
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
                    Text("Agregar a MusicVault")
                }
                if (loading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = VaultColors.Accent, trackColor = VaultColors.Surface2)
                    Spacer(Modifier.height(8.dp))
                    Text("Cargando canciones de YouTube…", color = VaultColors.TextTertiary, fontSize = 11.sp)
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
private fun CachedCover(
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
            Icon(Icons.Default.MoreVert, "Más opciones", tint = VaultColors.TextPrimary)
        }
    }
}

@Immutable
private data class LibraryScreenState(
    val downloaded: List<Track>,
    val playlists: List<Playlist>,
    val albums: List<Album>,
    val selectedTrackId: String?,
    val active: Boolean
)

@Composable
private fun LibraryScreen(
    state: LibraryScreenState,
    isPlaying: Boolean,
    vm: MusicVaultViewModel,
    prewarm: Boolean = false
) {
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
    val hybrid = LocalHybridDesign.current
    val adapter = remember(hybrid) { LibraryFeedAdapter(vm, hybrid) }
    val customAlbums = remember(state.albums) { state.albums.filter { a -> a.id.startsWith("album:custom:") } }

    LaunchedEffect(state.active) {
        if (state.active && !permissionRequested) {
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
            if (state.active || prewarm) {
                adapter.update(
                    tracks = state.downloaded,
                    playlists = state.playlists,
                    customAlbums = customAlbums,
                    selectedTrackId = state.selectedTrackId,
                    isPlaying = isPlaying
                )
            }
        }
    )
}

@Composable
private fun PlaylistScreen(
    state: MusicUiState,
    vm: MusicVaultViewModel,
    prewarm: Boolean = false
) {
    var showAlbumDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    val context = LocalContext.current
    val hybrid = LocalHybridDesign.current
    val adapter = remember(hybrid) { AlbumFeedAdapter(vm, hybrid) }

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
            if (state.section == AppSection.PLAYLISTS || prewarm) {
                adapter.update(state.playlists)
            }
        }
    )

    if (showAlbumDialog) {
        CreateCollectionDialog("Nueva playlist", "Nombre de la playlist", state.downloaded, vm::createPlaylist) {
            showAlbumDialog = false
        }
    }

    pendingDelete?.let { playlist ->
        DeletePlaylistDialog(
            playlist = playlist,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                vm.deletePlaylist(playlist.id)
                pendingDelete = null
            }
        )
    }

    // The native header/card holders only emit callbacks. Compose owns dialogs
    // so creation/deletion use the same polished Vault modal language.
    adapter.onAddAlbum = { showAlbumDialog = true }
    adapter.onDeleteAlbum = { pendingDelete = it }
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
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Nombre") },
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
                    Text("CANCIONES", color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
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
                    placeholder = { Text("Buscar por canción o artista") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = VaultColors.TextSecondary) },
                    trailingIcon = {
                        if (songQuery.isNotBlank()) {
                            IconButton(onClick = { songQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda", tint = VaultColors.TextSecondary)
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
                            Text("Tu biblioteca todavía está vacía", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Crea la playlist ahora y agrega canciones cuando quieras.", color = VaultColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    visibleTracks.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = VaultColors.TextTertiary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No encontramos esa canción", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Prueba con el título o el nombre del artista.", color = VaultColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    else -> {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(304.dp),
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
                Text("Cancelar", color = VaultColors.TextSecondary)
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
                Text("Crear playlist", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun DeletePlaylistDialog(
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
                            "La playlist desaparecerá, pero las canciones descargadas seguirán en tu biblioteca.",
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
            ) { Text("Cancelar", color = VaultColors.TextSecondary) }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Error, contentColor = Color.White)
            ) {
                Icon(VaultIcons.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Eliminar", fontWeight = FontWeight.Bold)
            }
        }
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
    val hybrid = LocalHybridDesign.current
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
        IconButton(onClick = vm::closeRemoteAlbum) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
        }

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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (albumDownloading) ThinDownloadProgress(modifier = Modifier.width(24.dp))
                else Icon(VaultIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (albumDownloading) "Descargando álbum" else "Descargar álbum", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                        "No se encontraron canciones para este álbum.",
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
                        isPlaying = isPlaying
                    )
                    if (recyclerView.adapter !== adapter) recyclerView.adapter = adapter
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
                        newAlbumName = ""
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbum = false; actionTrack = null }) { Text("Cancelar") }
            }
        )
    }

    deleteTrack?.let { remote ->
        AlertDialog(
            onDismissRequest = { deleteTrack = null },
            title = { Text("Eliminar canción") },
            text = { Text("¿Quieres eliminar \"${remote.title}\" de las descargas de MusicVault?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDownloadedTrack("${remote.provider}:recording:${remote.id}")
                    deleteTrack = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTrack = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    isDownloading: Boolean,
    isPlaying: Boolean,
    selectedTrackId: String?,
    playlists: List<Playlist>,
    albums: List<Album>,
    vm: MusicVaultViewModel
) {
    val context = LocalContext.current
    val hybrid = LocalHybridDesign.current
    // A playlist detail must start from its header every time it is opened.
    // Key the adapter/reset state to the playlist so RecyclerView/Compose cannot
    // carry a scroll anchor from a previously opened detail screen.
    val adapter = remember(playlist.id, vm, hybrid) { PlaylistDetailAdapter(vm, hybrid) }
    val resetScrollOnOpen = remember(playlist.id) { mutableStateOf(true) }
    val customAlbums = remember(albums) { albums.filter { it.id.startsWith("album:custom:") } }
    val pendingDownloads = remember(tracks) { tracks.count { !it.isDownloaded || it.audioUri == null } }
    // Header identity only changes when what it renders changes; playback
    // ticks rebind rows via payload instead of rebuilding the header.
    val headerContent = remember(playlist, tracks.size, pendingDownloads, isDownloading) {
        @Composable {
            PlaylistDetailHeader(
                playlist = playlist,
                trackCount = tracks.size,
                isDownloading = isDownloading,
                pendingDownloads = pendingDownloads,
                onBack = vm::clearSelection,
                onPlay = { vm.playPlaylist(playlist, shuffle = false) },
                onShuffle = { vm.playPlaylist(playlist, shuffle = true) },
                onDownload = { vm.downloadPlaylist(playlist) }
            )
        }
    }
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
                recycledViewPool.setMaxRecycledViews(PlaylistDetailAdapter.TYPE_TRACK, 12)
                itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply { supportsChangeAnimations = false }
                setPadding(
                    dp(context, if (hybrid) 16 else 20), 0,
                    dp(context, if (hybrid) 16 else 20), dp(context, if (hybrid) 16 else 20)
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
                onRowClick = { vm.playPlaylistTrack(playlist, it) },
                onPlayNew = { vm.playPlaylistTrack(playlist, it) },
                onRemoveFromPlaylist = { vm.removeTrackFromPlaylist(playlist.id, it.id) },
                header = headerContent
            )
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

@Composable
private fun PlaylistDetailHeader(
    playlist: Playlist,
    trackCount: Int,
    isDownloading: Boolean,
    pendingDownloads: Int,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit
) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier.fillMaxWidth().padding(
            start = if (hybrid) 16.dp else 20.dp,
            top = if (hybrid) 16.dp else 20.dp,
            end = if (hybrid) 16.dp else 20.dp
        )
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary)
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlay,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = VaultColors.Accent, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Reproducir", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onShuffle,
                shape = RoundedCornerShape(50)
            ) {
                Icon(VaultIcons.Shuffle, null, tint = VaultColors.TextPrimary)
                Spacer(Modifier.width(6.dp))
                Text("Aleatorio", color = VaultColors.TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
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
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    favorite: Boolean = false,
    onFavorite: () -> Unit = {},
    hybrid: Boolean,
    vm: MusicVaultViewModel
) {
    // Observe rapidly changing playback progress only inside the mini-player.
    // Keeping it out of MusicVaultApp prevents the entire navigation/search tree
    // from recomposing on every playback tick.
    val playbackProgress by vm.playbackProgress.collectAsStateWithLifecycle()
    val preparingTrackId by vm.preparingTrackId.collectAsStateWithLifecycle()
    val isPreparing = preparingTrackId == track.id
    val durationMs = if (isPreparing) track.durationMs else playbackProgress.durationMs.coerceAtLeast(track.durationMs)
    val positionMs = if (isPreparing) 0L else playbackProgress.positionMs
    val fraction = if (!isPreparing && durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
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
            .background(if (hybrid) VaultColors.SurfaceBase else VaultColors.Surface1)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = VaultColors.Accent,
            trackColor = Color.Transparent
        )
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (hybrid) 12.dp else 14.dp,
                vertical = if (hybrid) 6.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedCover(
                url = track.coverUri,
                contentDescription = track.title,
                targetSizePx = 72,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (hybrid) 40.dp else 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, VaultColors.Divider, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = VaultColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = if (hybrid) 13.sp else 14.sp, fontFamily = VaultFonts.Display)
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
            IconButton(onClick = onToggle, enabled = !isPreparing) {
                Box(
                    modifier = Modifier
                        .size(if (hybrid) 44.dp else 34.dp)
                        .clip(CircleShape)
                        .background(VaultColors.Accent),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (hybrid) 20.dp else 17.dp),
                            strokeWidth = 2.dp,
                            color = VaultColors.OnAccent,
                            trackColor = Color.Transparent
                        )
                    } else {
                        Icon(
                            if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow,
                            null,
                            tint = VaultColors.OnAccent,
                            modifier = Modifier.padding(if (hybrid) 10.dp else 4.dp)
                        )
                    }
                }
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
private fun FullPlayerScreen(track: Track, isPlaying: Boolean, shuffle: Boolean, favoriteIds: Set<String>, playerStyle: String, vm: MusicVaultViewModel) {
    val progress by vm.playbackProgress.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    val duration = progress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val position = progress.positionMs.coerceIn(0L, duration)
    var sliderPosition by remember(track.id) { mutableFloatStateOf(position.toFloat()) }
    var sliderDragging by remember(track.id) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(position, track.id) { if (!sliderDragging) sliderPosition = position.toFloat() }
    val lyricPosition = rememberLyricPositionState(progress, isPlaying)
    val repeatMode by vm.repeatMode.collectAsStateWithLifecycle()
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
        // Two stanzas + the dimmed previous line need more room than a bare
        // 1-line peek.
        val peekHeight = 138.dp
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
                    // Collapsed / semi-open peek: the same Spotify treatment in
                    // miniature — active stanza large and sharp, previous and
                    // next dimmed and blurred around it. AnimatedContent keyed
                    // by the active line index rolls the trio up (or down on
                    // seek-back) with one shared spring, so line changes glide
                    // instead of swapping text in place.
                    val synced = lyrics.synced
                    val plain = lyrics.plain
                    when {
                        synced.isNotEmpty() -> {
                            val peekIndex by remember(synced) {
                                derivedStateOf { synced.indexOfLast { it.timeMs <= lyricPosition.value } }
                            }
                            AnimatedContent(
                                targetState = peekIndex,
                                transitionSpec = {
                                    val forward = targetState >= initialState
                                    if (forward) {
                                        (
                                            slideInVertically(
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            ) { it / 3 } + fadeIn(tween(160))
                                            ) togetherWith (
                                            slideOutVertically(
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            ) { -it / 3 } + fadeOut(tween(110))
                                            )
                                    } else {
                                        (
                                            slideInVertically(
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            ) { -it / 3 } + fadeIn(tween(160))
                                            ) togetherWith (
                                            slideOutVertically(
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            ) { it / 3 } + fadeOut(tween(110))
                                            )
                                    }
                                },
                                label = "lyricPeek"
                            ) { index ->
                                Column(
                                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (index < 0) {
                                        synced.firstOrNull()?.let {
                                            LyricStanza(
                                                it.text, active = false, distance = 1,
                                                compact = true, maxLines = 2, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        synced.getOrNull(index - 1)?.let {
                                            LyricStanza(
                                                it.text, active = false, distance = -1,
                                                compact = true, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        synced.getOrNull(index)?.let {
                                            LyricStanza(
                                                it.text, active = true, distance = 0,
                                                compact = true, maxLines = 2, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        synced.getOrNull(index + 1)?.let {
                                            LyricStanza(
                                                it.text, active = false, distance = 1,
                                                compact = true, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
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

/**
 * One synced stanza with the Spotify treatment: the active line grows, turns
 * bold-accented and stays sharp, while every other line fades, scales down and
 * blurs progressively with its signed distance to the active one (negatives
 * are already sung, positives upcoming; before the first line everything is
 * upcoming). All properties share one spring curve so a role change reads as
 * a single gliding movement. Alpha and blur are read inside graphicsLayer
 * (draw phase): lines whose distance merely shifts animate without ever
 * recomposing. On pre-API 31 devices the blur is skipped gracefully.
 */
@Composable
private fun LyricStanza(
    text: String,
    active: Boolean,
    distance: Int,
    compact: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val sung = distance < 0
    val focus = animateFloatAsState(
        if (active) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lyricFocus"
    )
    val alphaTarget = when {
        active -> 1f
        sung -> (0.46f - 0.08f * (abs(distance) - 1)).coerceAtLeast(0.22f)
        else -> (0.9f - 0.12f * (abs(distance) - 1)).coerceAtLeast(0.45f)
    }
    val lyricAlpha = animateFloatAsState(
        alphaTarget,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lyricAlpha"
    )
    val blurUnit = if (compact) 0.6f else 1f
    val blurTarget = if (active) 0f
        else abs(distance).coerceAtMost(3) * (if (sung) 1.2f else 1f) * blurUnit
    val lyricBlur = animateFloatAsState(
        blurTarget,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lyricBlur"
    )
    val sizeFrom = if (compact) 12.sp else 15.sp
    val sizeTo = if (compact) 18.sp else 22.sp
    val leadFrom = if (compact) 16.sp else 22.sp
    val leadTo = if (compact) 24.sp else 30.sp
    val padFrom = if (compact) 2.dp else 6.dp
    val padTo = if (compact) 5.dp else 10.dp
    val blurCache = remember { HashMap<Int, androidx.compose.ui.graphics.RenderEffect>() }
    val densityPx = with(LocalDensity.current) { 1.dp.toPx() }
    Text(
        text,
        color = when {
            active -> VaultColors.Accent
            sung -> VaultColors.TextPrimary
            else -> VaultColors.TextSecondary
        },
        fontSize = androidx.compose.ui.unit.lerp(sizeFrom, sizeTo, focus.value),
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        lineHeight = androidx.compose.ui.unit.lerp(leadFrom, leadTo, focus.value),
        maxLines = maxLines,
        overflow = overflow,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = androidx.compose.ui.unit.lerp(padFrom, padTo, focus.value))
            .graphicsLayer {
                val s = 0.94f + 0.06f * focus.value
                scaleX = s
                scaleY = s
                alpha = lyricAlpha.value
                if (lyricBlur.value > 0.05f && Build.VERSION.SDK_INT >= 31) {
                    val step = (lyricBlur.value * 4f).roundToInt().coerceAtLeast(1)
                    renderEffect = blurCache.getOrPut(step) {
                        val radius = step * 0.25f * densityPx
                        android.graphics.RenderEffect
                            .createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    }
                } else {
                    renderEffect = null
                }
            }
    )
}

@Composable
private fun SyncedLyricsList(lines: List<LyricLine>, positionState: State<Long>, modifier: Modifier = Modifier) {
    val currentLine by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionState.value }.takeIf { it >= 0 } }
    }
    val adapter = remember { SyncedLyricsAdapter() }
    val lastUserTouchMs = remember { longArrayOf(0L) }
    val lastAutoScrolledIndex = remember { intArrayOf(-1) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context).apply {
                        isItemPrefetchEnabled = true
                        initialPrefetchItemCount = 10
                    }
                    this.adapter = adapter
                    itemAnimator = null
                    setItemViewCacheSize(16)
                    recycledViewPool.setMaxRecycledViews(0, 20)
                    setPadding(0, 48, 0, 72)
                    clipToPadding = false
                    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                            event.actionMasked == android.view.MotionEvent.ACTION_MOVE
                        ) {
                            lastUserTouchMs[0] = android.os.SystemClock.uptimeMillis()
                        }
                        false
                    }
                }
            },
            update = { rv ->
                adapter.update(lines, currentLine)
                val active = currentLine
                if (active != null && active != lastAutoScrolledIndex[0] &&
                    android.os.SystemClock.uptimeMillis() - lastUserTouchMs[0] >= 4_000L
                ) {
                    lastAutoScrolledIndex[0] = active
                    rv.post {
                        (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                            (active - 3).coerceAtLeast(0),
                            (rv.height * 0.18f).roundToInt()
                        )
                    }
                }
            }
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(VaultColors.Surface1, Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, VaultColors.Surface1)))
        )
    }
}

@Composable
private fun SettingsScreen(
    crossfadeSeconds: Int,
    playerStyle: String,
    appDesign: String,
    downloadDirLabel: String,
    spotifyConnected: Boolean,
    spotifyBusy: Boolean,
    onCrossfadeChange: (Int) -> Unit,
    onPlayerStyleChange: (String) -> Unit,
    onAppDesignChange: (String) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onUseInternalFolder: () -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onClose: () -> Unit
) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (hybrid) 16.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (hybrid) 12.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = VaultColors.TextPrimary) }
            Text("Ajustes", color = VaultColors.TextPrimary, fontSize = if (hybrid) 24.sp else 26.sp, fontWeight = FontWeight.Bold, fontFamily = VaultFonts.Display, letterSpacing = (-0.75).sp)
        }
        SettingsSection("APARIENCIA") {
            Text("Diseño de la aplicación", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Puedes volver al diseño anterior en cualquier momento. Tus canciones y ajustes no cambian.",
                color = VaultColors.TextTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOption(
                    title = "Híbrido",
                    description = "Más claro, compacto y fácil de recorrer.",
                    icon = Icons.Default.Home,
                    selected = appDesign == APP_DESIGN_HYBRID,
                    onClick = { onAppDesignChange(APP_DESIGN_HYBRID) },
                    modifier = Modifier.weight(1f)
                )
                StyleOption(
                    title = "Clásico",
                    description = "Restaura la apariencia anterior de MusicVault.",
                    icon = VaultIcons.LibraryMusic,
                    selected = appDesign == APP_DESIGN_CLASSIC,
                    onClick = { onAppDesignChange(APP_DESIGN_CLASSIC) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = VaultColors.Divider)
            Spacer(Modifier.height(18.dp))
            Text("Estilo del reproductor", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
        SettingsSection("REPRODUCCIÓN") {
            Text("Crossfade", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            // Keep the drag value local so a 60 Hz gesture never recomposes the whole
            // app (crossfadeSeconds feeds the root state combine) nor hits prefs.
            var dragValue by remember { mutableStateOf<Float?>(null) }
            val shownSeconds = dragValue?.toInt() ?: crossfadeSeconds
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
        SettingsSection("ALMACENAMIENTO") {
            Text("Ruta de descargas", color = VaultColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VaultIcons.FolderOpen, null, tint = VaultColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(downloadDirLabel, color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
            }
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
        SettingsSection("SPOTIFY") {
            Text(
                if (spotifyConnected) "Cuenta conectada en este dispositivo" else "Spotify no está conectado",
                color = VaultColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            if (spotifyConnected) {
                OutlinedButton(
                    onClick = onDisconnectSpotify,
                    enabled = !spotifyBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.TextPrimary)
                ) { Text("Desconectar Spotify", fontSize = 13.sp) }
            } else {
                Button(
                    onClick = onConnectSpotify,
                    enabled = !spotifyBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    )
                ) { Text("Conectar Spotify", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
    }
}
@Composable
private fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val hybrid = LocalHybridDesign.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (hybrid) 18.dp else 14.dp))
            .background(if (hybrid) VaultColors.Surface2 else VaultColors.Surface1)
            .border(1.dp, VaultColors.Divider, RoundedCornerShape(if (hybrid) 18.dp else 14.dp))
            .padding(if (hybrid) 18.dp else 16.dp)
    ) {
        Text(title, color = VaultColors.Accent, fontSize = 10.sp, fontFamily = VaultFonts.Mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SearchSegmentedControl(
    selected: SearchTab,
    trackCount: Int,
    albumCount: Int,
    playlistCount: Int,
    onSelected: (SearchTab) -> Unit
) {
    val hybrid = LocalHybridDesign.current
    val outerShape = RoundedCornerShape(if (hybrid) 14.dp else 10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(VaultColors.Surface2)
            .border(1.dp, VaultColors.Divider, outerShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SearchSegment(
            label = "Canciones",
            count = trackCount,
            selected = selected == SearchTab.TRACKS,
            modifier = Modifier.weight(1.08f),
            onClick = { onSelected(SearchTab.TRACKS) }
        )
        SearchSegment(
            label = "Álbumes",
            count = albumCount,
            selected = selected == SearchTab.ALBUMS,
            modifier = Modifier.weight(0.84f),
            onClick = { onSelected(SearchTab.ALBUMS) }
        )
        SearchSegment(
            label = "Playlists",
            count = playlistCount,
            selected = selected == SearchTab.PLAYLISTS,
            modifier = Modifier.weight(1.08f),
            onClick = { onSelected(SearchTab.PLAYLISTS) }
        )
    }
}

@Composable
private fun SearchSegment(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val background by animateColorAsState(
        targetValue = if (selected) VaultColors.Accent else Color.Transparent,
        animationSpec = tween(180),
        label = "searchSegmentBg"
    )
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (selected) VaultColors.OnAccent else VaultColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            val compactCount = when {
                count < 1000 -> count.toString()
                count < 10_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1000f).replace(".0k", "k")
                else -> "${count / 1000}k+"
            }
            Text(
                text = compactCount,
                color = if (selected) VaultColors.OnAccent.copy(alpha = 0.78f) else VaultColors.TextTertiary,
                fontSize = 10.sp,
                fontFamily = VaultFonts.Mono,
                maxLines = 1,
                softWrap = false
            )
        }
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
    val hybrid = LocalHybridDesign.current
    Column(
        modifier
            .clip(RoundedCornerShape(if (hybrid) 16.dp else 12.dp))
            .background(if (selected) VaultColors.AccentSoft else VaultColors.Surface2)
            .border(1.dp, if (selected) VaultColors.Accent else VaultColors.Divider, RoundedCornerShape(if (hybrid) 16.dp else 12.dp))
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
    val adapter = remember {
        PlaybackQueueAdapter(
            onMove = vm::moveQueueItem,
            onRemove = vm::removeFromQueue
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VaultColors.Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("A continuación", color = VaultColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            if (queue.isEmpty()) {
                Text("La cola está vacía.", color = VaultColors.TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                val queueHeight = (queue.size * 64).coerceIn(64, 520).dp
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(queueHeight),
                    factory = { context ->
                        RecyclerView(context).apply {
                            layoutManager = LinearLayoutManager(context).apply {
                                isItemPrefetchEnabled = true
                                initialPrefetchItemCount = 8
                            }
                            this.adapter = adapter
                            itemAnimator = null
                            setItemViewCacheSize(12)
                            recycledViewPool.setMaxRecycledViews(0, 16)
                            overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                        }
                    },
                    update = { adapter.update(queue, currentIndex) }
                )
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
private fun BottomBar(section: AppSection, hybrid: Boolean, onSelect: (AppSection) -> Unit) {
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
            BottomBarItem(AppSection.HOME, "INICIO", section, hybrid, onSelect) {
                Icon(if (section == AppSection.HOME) Icons.Default.Home else Icons.Outlined.Home, null)
            }
            BottomBarItem(AppSection.SEARCH, "BUSCAR", section, hybrid, onSelect) {
                Icon(if (section == AppSection.SEARCH) Icons.Default.Search else Icons.Outlined.Search, null)
            }
            BottomBarItem(AppSection.LIBRARY, "BIBLIOTECA", section, hybrid, onSelect) {
                Icon(if (section == AppSection.LIBRARY) VaultIcons.LibraryMusic else VaultIcons.LibraryMusicOutline, null)
            }
            BottomBarItem(AppSection.PLAYLISTS, "ÁLBUMES", section, hybrid, onSelect) {
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
            letterSpacing = 0.8.sp
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
                            Icon(VaultIcons.Download, contentDescription = "Descargar álbum", tint = VaultColors.TextPrimary)
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
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
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
                setImageResource(android.R.drawable.ic_menu_more)
                setColorFilter(android.graphics.Color.rgb(241, 234, 217))
                background = null
                contentDescription = "Más opciones"
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
        if (choices.isEmpty()) Text("No tienes álbumes creados.") else Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
        ) {
            choices.forEach { (id, label) ->
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
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                albums.forEach { album ->
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
internal val LocalHybridDesign = staticCompositionLocalOf { false }
