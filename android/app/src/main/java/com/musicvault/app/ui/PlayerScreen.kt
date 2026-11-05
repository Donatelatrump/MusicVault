package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.horizontalScroll

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
internal fun MiniPlayer(
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
                vertical = 4.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedCover(
                url = track.coverUri,
                contentDescription = track.title,
                targetSizePx = 72,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
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
            IconButton(onClick = onToggle, enabled = !isPreparing) {
                Box(
                    modifier = Modifier
                        .size(if (hybrid) 44.dp else 40.dp)
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
internal fun rememberLyricPositionState(
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
        val extrapolated = anchorPos + ((nowElapsed - anchorElapsed) / 1_000_000.0 * progress.speed).toLong()
        val jumped = kotlin.math.abs(progress.positionMs - extrapolated) > 400L
        anchorSample = progress.sampledAtElapsed
        if (jumped || !isPlaying) {
            // Real discontinuity (seek, track change, pause): snap to the sample.
            anchorPos = progress.positionMs
            anchorElapsed = progress.sampledAtElapsed
            state.longValue = progress.positionMs.coerceAtLeast(0L)
        } else {
            // Clock noise: keep the extrapolation continuous but gently pull it
            // toward the sampled truth (⅓ per sample) — no visible snap, no
            // permanent drift/lag.
            anchorPos = extrapolated + (progress.positionMs - extrapolated) / 3
            anchorElapsed = nowElapsed
        }
    }
    LaunchedEffect(isPlaying, progress.speed) {
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
                    val next = (anchorPos + ((nowElapsed - anchorElapsed) / 1_000_000.0 * progress.speed).toLong())
                        .coerceAtLeast(0L)
                    // Monotonic while playing: lyrics never step back, so the
                    // highlight cannot flicker at a stanza boundary.
                    if (next >= state.longValue) state.longValue = next
                }
            }
        } else {
            // Pause: freeze at the exact player position.
            anchorPos = state.longValue
        }
    }
    return state
}

@Composable
internal fun FullPlayerScreen(track: Track, isPlaying: Boolean, shuffle: Boolean, favoriteIds: Set<String>, playerStyle: String, vm: MusicVaultViewModel) {
    val progress by vm.playbackProgress.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
    var lyricsImportTrack by remember { mutableStateOf<com.musicvault.app.data.Track?>(null) }
    val lyricsImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = lyricsImportTrack
        if (uri != null && requested != null) vm.importLyrics(requested, uri)
        lyricsImportTrack = null
    }
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    val duration = progress.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val position = progress.positionMs.coerceIn(0L, duration)
    var sliderPosition by remember(track.id) { mutableFloatStateOf(position.toFloat()) }
    var sliderDragging by remember(track.id) { mutableStateOf(false) }
    var showRelated by remember(track.id) { mutableStateOf(false) }
    if (showRelated) RelatedArtistsDialog(track, vm) { showRelated = false }
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showArtworkOnly by remember { mutableStateOf(true) }
    val sleepRemainingMs by vm.sleepRemainingMs.collectAsStateWithLifecycle()
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
            IconButton(onClick = vm::closeFullPlayer) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.ui_cerrar_4b0816), tint = VaultColors.TextPrimary, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.weight(1f))
            var showOptions by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showOptions = true }) { Text("···", color = VaultColors.TextSecondary) }
                androidx.compose.material3.DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(if (showArtworkOnly) "Mostrar visualizador" else "Mostrar portada") }, onClick = { showOptions = false; showArtworkOnly = !showArtworkOnly })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Actualizar letra") }, onClick = { showOptions = false; vm.loadLyrics(track, force = true) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Importar letra LRC / TXT") }, onClick = { showOptions = false; lyricsImportTrack = track; lyricsImporter.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Adelantar letra 0,5 s") }, onClick = { showOptions = false; vm.adjustLyricsOffset(track, -500L) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Retrasar letra 0,5 s") }, onClick = { showOptions = false; vm.adjustLyricsOffset(track, 500L) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Temporizador") }, onClick = { showOptions = false; showSleepTimer = true })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Radio de esta canción") }, onClick = { showOptions = false; vm.startRadio(track) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Artistas relacionados") }, onClick = { showOptions = false; showRelated = true })
                }
            }
            // Queue integrated in the header (mirrors the close button) instead
            // of a floating button at the bottom edge.
            IconButton(onClick = { showQueue = true }) {
                Icon(VaultIcons.QueueMusic, stringResource(R.string.ui_cola_de_reproduccion_ddfb28), tint = VaultColors.TextSecondary, modifier = Modifier.size(26.dp))
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
        // Once the sheet is open, its body belongs to the lyrics list.  Keeping
        // the sheet drag on the whole Column made every downward lyric scroll
        // compete with (and usually become) a request to minimize the sheet.
        // The header remains the deliberate affordance for closing it.
        val sheetDragModifier = Modifier.draggable(
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
                                .padding(top = 24.dp, bottom = 8.dp)
                                .padding(horizontal = if (showArtworkOnly) 12.dp else 0.dp)
                                .aspectRatio(1f)
                            if (showArtworkOnly) {
                            CachedCover(
                                url = track.coverUri,
                                contentDescription = track.title,
                                targetSizePx = 1200,
                                contentScale = ContentScale.Crop,
                                modifier = stageModifier.clip(RoundedCornerShape(16.dp))
                            )
                            } else if (playerStyle == "circular") {
                                CircularVisualizer(coverUri = track.coverUri, title = track.title, isPlaying = isPlaying, seed = abs(track.id.hashCode()), modifier = stageModifier)
                            } else {
                                VinylDisc(coverUri = track.coverUri, title = track.title, isPlaying = isPlaying, modifier = stageModifier)
                            }
                        }
                    }
                }
                // Fixed block: metadata, seek and transport. It rides up as the
                // sheet below grows.
                Column(Modifier.fillMaxWidth().onSizeChanged { fixedPx = it.height.toFloat() }) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.title, color = VaultColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontFamily = VaultFonts.Display, letterSpacing = (-0.5).sp
                )
                Text(track.artistName, color = VaultColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        // Transport controls: a fixed square center keeps Play circular on any
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
                        stringResource(R.string.ui_aleatorio_a7567a),
                        tint = if (shuffle) VaultColors.Accent else VaultColors.TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::previousTrack, modifier = Modifier.size(48.dp)) {
                    Icon(VaultIcons.SkipPrevious, stringResource(R.string.ui_anterior_d97f10), tint = VaultColors.TextPrimary, modifier = Modifier.size(32.dp))
                }
            }
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                // Reserve both dimensions before distributing the remaining width.
                FilledIconButton(
                    onClick = vm::togglePlayback,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer { scaleX = playScale; scaleY = playScale },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = VaultColors.Accent,
                        contentColor = VaultColors.OnAccent
                    )
                ) {
                    Icon(
                        if (isPlaying) VaultIcons.Pause else Icons.Default.PlayArrow,
                        null,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::nextTrack, modifier = Modifier.size(48.dp)) {
                    Icon(VaultIcons.SkipNext, stringResource(R.string.ui_siguiente_c4d83a), tint = VaultColors.TextPrimary, modifier = Modifier.size(32.dp))
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
                // While collapsed, swiping the lyric preview still opens it.
                // While open, leave the body unclaimed so RecyclerView/
                // verticalScroll can handle lyric scrolling normally.
                .then(if (panelOpen) Modifier else sheetDragModifier)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // The grabber is intentionally the only place that can
                    // minimize an expanded sheet.
                    .then(if (panelOpen) sheetDragModifier else Modifier)
                    .pointerInput(Unit) {
                        detectTapGestures { revealLyrics(if (reveal > 0.5f) 0f else 1f) }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_letra_6e7c6f), color = VaultColors.TextTertiary, fontSize = 11.sp, fontFamily = VaultFonts.Mono)
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
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
                if (panelOpen) {
                    LyricsPanel(
                        lyrics = lyrics,
                        positionState = lyricPosition,
                        onRetry = { vm.loadLyrics(track, force = true) },
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
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        )
                        else -> Text(
                            if (lyrics.loading || lyrics.trackId != track.id) "Buscando letra…" else "Letra no disponible",
                            color = VaultColors.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        )
                    }
                }
            }
        }
        }
        }
    }

    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            title = { Text(stringResource(R.string.ui_temporizador_de_apagado_e3aefc)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.ui_la_musica_se_pausara_al_terminar_el_tiempo_c19ec2))
                    if (sleepRemainingMs > 0L) {
                        Text("Quedan ${(sleepRemainingMs + 59_999L) / 60_000L} minutos", color = VaultColors.Accent)
                    }
                    listOf(5, 10, 15, 20, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = { vm.setSleepTimer(minutes); showSleepTimer = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (minutes == 60) "1 hora" else "$minutes minutos") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimer = false }) { Text(stringResource(R.string.ui_cerrar_4b0816)) } },
            dismissButton = {
                if (sleepRemainingMs > 0L) {
                    TextButton(onClick = { vm.setSleepTimer(0); showSleepTimer = false }) { Text(stringResource(R.string.ui_cancelar_temporizador_2fdecc)) }
                }
            }
        )
    }
    if (showQueue) {
        QueueBottomSheet(queue, queueIndex, vm) { showQueue = false }
    }
}

@Composable
internal fun SkeletonRow(modifier: Modifier = Modifier) {
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
internal fun VinylDisc(coverUri: String?, title: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
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
                stringResource(R.string.ui_vault_hv_01_side_a_7dca31),
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
internal fun DeckKey(icon: ImageVector, tint: Color) {
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
internal fun CircularVisualizer(
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
internal fun LyricsPanel(
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
            Text(stringResource(R.string.ui_buscando_letra_dd59ef), color = VaultColors.TextTertiary, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
        }
        lyrics.notFound -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(lyrics.message ?: stringResource(R.string.ui_letra_no_disponible_fb58a1), color = VaultColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.ui_reintentar_adec7b), color = VaultColors.Accent, fontSize = 12.sp, fontFamily = VaultFonts.Mono)
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
internal fun LyricStanza(
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
internal fun SyncedLyricsList(lines: List<LyricLine>, positionState: State<Long>, modifier: Modifier = Modifier) {
    val currentLine by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionState.value }.takeIf { it >= 0 } }
    }
    var following by remember(lines) { mutableStateOf(true) }
    val adapter = remember { SyncedLyricsAdapter() }
    val lastAutoScrolledIndex = remember { intArrayOf(Int.MIN_VALUE) }

    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { context ->
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context).apply {
                        isItemPrefetchEnabled = true
                        initialPrefetchItemCount = 10
                    }
                    this.adapter = adapter
                    addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) following = false
                        }
                    })
                    itemAnimator = null
                    setItemViewCacheSize(16)
                    recycledViewPool.setMaxRecycledViews(0, 20)
                    setPadding(0, 0, 0, 0)
                    clipToPadding = false
                    clipChildren = true
                    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                }
            },
            update = { rv ->
                val active = currentLine
                val anchor = active ?: -1
                val mustCenter = following && lines.isNotEmpty() && anchor != lastAutoScrolledIndex[0]
                if (mustCenter) {
                    lastAutoScrolledIndex[0] = anchor
                }
                adapter.update(lines, active, fullLyrics = true, onCommitted = if (mustCenter) ({ centerLyricPosition(rv, active ?: 0) }) else null)
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
        if (!following) {
            TextButton(
                onClick = { lastAutoScrolledIndex[0] = Int.MIN_VALUE; following = true },
                modifier = Modifier.align(Alignment.BottomCenter).background(VaultColors.Surface1, RoundedCornerShape(20.dp))
            ) { Text("Volver a la línea actual", color = VaultColors.Accent) }
        }
    }
}

internal fun centerLyricPosition(recyclerView: RecyclerView, position: Int) {
    if (position < 0) return
    recyclerView.post firstPass@{
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@firstPass
        val estimatedHalfRow = (32f * recyclerView.resources.displayMetrics.density).roundToInt()
        val centeredOffset = (recyclerView.height / 2 - estimatedHalfRow).coerceAtLeast(0)
        layoutManager.scrollToPositionWithOffset(position, centeredOffset)
        recyclerView.post secondPass@{
            val activeView = layoutManager.findViewByPosition(position) ?: return@secondPass
            val activeCenter = (activeView.top + activeView.bottom) / 2
            recyclerView.scrollBy(0, activeCenter - recyclerView.height / 2)
        }
    }
}


@Composable
internal fun SearchSegmentedControl(
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
internal fun SearchSegment(
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


/** Horizontally-scrolling chips that select one of the curated EQ presets. */
@Composable
internal fun PresetChips(
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
internal fun QueueBottomSheet(queue: List<Track>, currentIndex: Int, vm: MusicVaultViewModel, onDismiss: () -> Unit) {
    val adapter = remember {
        PlaybackQueueAdapter(
            onMove = vm::moveQueueItem,
            onRemove = vm::removeFromQueue
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VaultColors.Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.ui_a_continuacion_0d36b0), color = VaultColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            if (queue.isEmpty()) {
                Text(stringResource(R.string.ui_la_cola_esta_vacia_8ed5c4), color = VaultColors.TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
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

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

