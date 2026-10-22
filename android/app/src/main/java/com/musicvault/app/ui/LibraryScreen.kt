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

@Immutable
internal data class LibraryScreenState(
    val downloaded: List<Track>,
    val playlists: List<Playlist>,
    val albums: List<Album>,
    val selectedTrackId: String?,
    val active: Boolean
)

@Composable
internal fun LibraryScreen(
    state: LibraryScreenState,
    isPlaying: Boolean,
    vm: MusicVaultViewModel,
    permissionRequested: Boolean,
    onPermissionRequested: () -> Unit
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
    val hybrid = LocalHybridDesign.current
    val adapter = remember(hybrid) { LibraryFeedAdapter(vm, hybrid) }
    val customAlbums = remember(state.albums) { state.albums.filter { a -> a.id.startsWith("album:custom:") } }

    LaunchedEffect(state.active, permissionRequested) {
        if (state.active && !permissionRequested) {
            onPermissionRequested()
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
            adapter.update(
                tracks = state.downloaded,
                playlists = state.playlists,
                customAlbums = customAlbums,
                selectedTrackId = state.selectedTrackId,
                isPlaying = isPlaying
            )
        }
    )
}

@Composable
internal fun PlaylistScreen(
    state: MusicUiState,
    vm: MusicVaultViewModel
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
                setPadding(libraryPixels(context, 20), libraryPixels(context, 20), libraryPixels(context, 20), libraryPixels(context, 20))
                clipToPadding = false
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                this.adapter = adapter
            }
        },
        update = { adapter.update(state.playlists) }
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

internal fun libraryPixels(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).roundToInt()

