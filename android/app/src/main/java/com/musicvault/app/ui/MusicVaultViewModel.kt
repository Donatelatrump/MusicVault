package com.musicvault.app.ui

import androidx.compose.runtime.Immutable

import android.content.Context
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogPlaylist
import com.musicvault.app.catalog.CatalogRepository
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.catalog.MetadataCatalogProvider
import com.musicvault.app.catalog.YouTubeMetadataProvider
import com.musicvault.app.data.*
import com.musicvault.app.library.LocalAudioScanner
import com.musicvault.app.lyrics.LyricLine
import com.musicvault.app.lyrics.LyricsLocalStore
import com.musicvault.app.lyrics.LyricsProvider
import com.musicvault.app.lyrics.VocalActivityDetector
import com.musicvault.app.network.ConnectivityMonitor
import com.musicvault.app.permissions.StoragePermissions
import com.musicvault.app.player.AudioEffects
import com.musicvault.app.player.EqualizerPreset
import com.musicvault.app.player.PlayerController
import com.musicvault.app.player.StreamingCache
import com.musicvault.app.providers.ProviderResult
import com.musicvault.app.providers.Providers
import com.musicvault.app.providers.NewPipeStreamResolver
import com.musicvault.app.download.DownloadForegroundService
import com.musicvault.app.spotify.SpotifyLink
import com.musicvault.app.spotify.SpotifyLinkKind
import com.musicvault.app.spotify.SpotifyLinks
import com.musicvault.app.spotify.SpotifyPlaylist
import com.musicvault.app.spotify.SpotifySessionStore
import com.musicvault.app.spotify.SpotifySongMirrorClient
import com.musicvault.app.spotify.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.*
import com.musicvault.app.player.AudioSessionBridge

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import java.text.Normalizer

enum class AppSection(val label: String) { HOME("Inicio"), SEARCH("Buscar"), LIBRARY("Biblioteca"), PLAYLISTS("Álbumes") }

const val APP_DESIGN_HYBRID = "hybrid"
const val APP_DESIGN_CLASSIC = "classic"

// All values entering the root combine are owned, strongly typed StateFlows.
// Reusing their immutable collections avoids copying every playlist/library list
// whenever an unrelated state field changes.
@Suppress("UNCHECKED_CAST")
private fun <T> Any?.stateList(): List<T> = this as? List<T> ?: emptyList()

@Suppress("UNCHECKED_CAST")
private fun <T> Any?.stateSet(): Set<T> = this as? Set<T> ?: emptySet()

@Immutable
data class LyricsUiState(
    val trackId: String? = null,
    val loading: Boolean = false,
    val plain: String? = null,
    val synced: List<LyricLine> = emptyList(),
    val notFound: Boolean = false
)

@Immutable
data class MusicUiState(
    val section: AppSection = AppSection.HOME,
    val search: String = "",
    val tracks: List<Track> = emptyList(),
    val downloaded: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val remoteTracks: List<CatalogTrack> = emptyList(),
    val remoteAlbums: List<CatalogAlbum> = emptyList(),
    val selectedTrack: Track? = null,
    val selectedPlaylist: Playlist? = null,
    val selectedPlaylistTracks: List<Track> = emptyList(),
    val selectedRemoteAlbum: CatalogAlbum? = null,
    val selectedLocalAlbum: Album? = null,
    val selectedLocalAlbumTracks: List<Track> = emptyList(),
    val fullPlayer: Boolean = false,
    val shuffle: Boolean = false,
    val crossfadeSeconds: Int = 5,
    // Visual style of the full player: "vinyl" (turntable) or "circular"
    // (circular visualizer). Persisted in prefs so it survives relaunches;
    // it only picks which interface renders, playback logic is shared.
    val playerStyle: String = "vinyl",
    // App-wide visual skin. Both modes share navigation, playback and data;
    // only the presentation changes, so switching is instant and reversible.
    val appDesign: String = APP_DESIGN_HYBRID,
    val remoteAlbumTracks: List<CatalogTrack> = emptyList(),
    val isLoadingRemoteAlbum: Boolean = false,
    val isSearching: Boolean = false,
    val isScanning: Boolean = false,
    val providerMessage: String? = null,
    val errorMessage: String? = null,
    val spotifyConnected: Boolean = false,
    val spotifyOffer: SpotifyLink? = null,
    val spotifyImporting: Boolean = false,
    val spotifyPlaylistReview: SpotifyPlaylist? = null,
    val favoriteIds: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(),
    val recommendedTracks: List<Track> = emptyList(),
    val recommendedAlbums: List<Album> = emptyList()
)

class MusicVaultViewModel(
    private val appContext: Context,
    private val database: MusicDatabase
) : ViewModel() {
    private val repository = MusicRepository(database)
    private val favoritesAlbumId = "playlist:favorites"
    private val catalog = CatalogRepository(MetadataCatalogProvider(), database)
    private val player = PlayerController(appContext)
    private val connectivityMonitor = ConnectivityMonitor(appContext)
    private val equalizer = AudioEffects()
    private val preferences: SharedPreferences = appContext.getSharedPreferences("musicvault.settings", Context.MODE_PRIVATE)
    private val homeRecommendationSnapshot = HomeRecommendationCache.load(preferences)
    private val spotifySession = SpotifySessionStore(appContext)
    private val spotifyClient = SpotifySongMirrorClient()

    private val _section = MutableStateFlow(AppSection.HOME)
    private val _search = MutableStateFlow("")
    private val _selectedTrack = MutableStateFlow<Track?>(null)
    // Track shown immediately in the mini-player while its remote URL is being
    // resolved/buffered. The spinner is cleared only when that exact track
    // actually starts producing audio.
    private val _preparingTrackId = MutableStateFlow<String?>(null)
    val preparingTrackId: StateFlow<String?> = _preparingTrackId.asStateFlow()
    private var streamSelectionGeneration = 0L
    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    private val _selectedPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    private val _selectedRemoteAlbum = MutableStateFlow<CatalogAlbum?>(null)
    private val _selectedLocalAlbum = MutableStateFlow<Album?>(null)
    private val _selectedLocalAlbumTracks = MutableStateFlow<List<Track>>(emptyList())
    private val _fullPlayer = MutableStateFlow(false)
    private val _shuffle = MutableStateFlow(false)
    private val _crossfadeSeconds = MutableStateFlow(preferences.getInt("crossfade_seconds", 5).coerceIn(0, 12))
    private val _playerStyle = MutableStateFlow(
        preferences.getString("player_style", null)?.takeIf { it == "circular" } ?: "vinyl"
    )
    private val _appDesign = MutableStateFlow(
        preferences.getString("app_design", null)?.takeIf { it == APP_DESIGN_CLASSIC }
            ?: APP_DESIGN_HYBRID
    )
    private val _remoteAlbumTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    private val _loadingRemoteAlbum = MutableStateFlow(false)
    private val _remoteTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    private val _remoteAlbums = MutableStateFlow<List<CatalogAlbum>>(emptyList())

    // Estado primera vez / actualización
    private val _esPrimeraVez = MutableStateFlow(true)
    val esPrimeraVez: StateFlow<Boolean> = _esPrimeraVez.asStateFlow()
    private val _estadoInicialCargado = MutableStateFlow(false)
    val estadoInicialCargado: StateFlow<Boolean> = _estadoInicialCargado.asStateFlow()

    // Preferencias del usuario
    private val _preferencias = MutableStateFlow(PrefModel())
    val preferencias: StateFlow<PrefModel> = _preferencias.asStateFlow()

    // First-run handoff gate. The onboarding animation stays on top while the
    // selected artists/genres are fetched and the Home feed is populated.
    // Existing users start in the ready state and are never gated by this.
    private val _recommendationWarmupReady = MutableStateFlow(true)
    val recommendationWarmupReady: StateFlow<Boolean> = _recommendationWarmupReady.asStateFlow()
    private var recommendationWarmupJob: Job? = null

    // Modelo simple de preferencias
    data class PrefModel(
        val generosFavoritos: Set<String> = emptySet(),
        val artistasFavoritos: Set<String> = emptySet(),
        val primeraVezCompleto: Boolean = true
    )
    private val _searching = MutableStateFlow(false)
    private val _scanning = MutableStateFlow(false)
    private val _providerMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _spotifyConnected = MutableStateFlow(spotifySession.isConfigured())
    private val _spotifyOffer = MutableStateFlow<SpotifyLink?>(null)
    private val _spotifyImporting = MutableStateFlow(false)
    private val _spotifyPlaylistReview = MutableStateFlow<SpotifyPlaylist?>(null)
    private var lastSpotifyClipboardUrl: String? = null
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _albumDownloads = MutableStateFlow<Set<String>>(emptySet())
    private val favoriteIds = repository.favorites()
    private val recentSearches = repository.recentSearches(12)
    private val downloadLock = Any()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadWaiters = mutableMapOf<String, MutableList<suspend (ProviderResult?) -> Unit>>()
    // Bulk playlist downloads deliberately suppress per-attempt provider errors.
    // A large import can transiently fail on an individual YouTube request and
    // immediately recover on retry; surfacing every intermediate failure makes
    // the UI look broken even when the batch ultimately succeeds.
    private val quietDownloadIds = mutableSetOf<String>()
    private var errorAutoClearJob: Job? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DownloadForegroundService.ACTION_PROGRESS -> {
                    val id = intent.getStringExtra(DownloadForegroundService.EXTRA_ID) ?: return
                    val progress = intent.getIntExtra(DownloadForegroundService.EXTRA_PROGRESS, 0)
                    if (_downloadProgress.value.containsKey(id)) {
                        _downloadProgress.value = _downloadProgress.value + (id to progress)
                    }
                }
                DownloadForegroundService.ACTION_RESULT -> {
                    handleDownloadServiceResult(intent)
                }
            }
        }
    }
    private var providerAutoClearJob: Job? = null
    // 5s visibility window for transient toast-style messages: long enough
    // that the user reads it, short enough to not block subsequent actions.
    private val messageDisplayMs = 5_000L
    // Tracks how many times the user denied the storage permission dialog.
    // After two consecutive denials Android stops showing the prompt by
    // default; we use this to suppress further launch attempts until the
    // user changes the choice in Settings.
    private var storagePermissionDeniedCount = 0
    private var storagePermissionPrompted = false
    private var searchJob: Job? = null
    private var searchStreamWarmJob: Job? = null
    private var homeStreamWarmJob: Job? = null
    private var searchStreamWarmSignature: List<String> = emptyList()
    // Speculative extraction is deliberately single-flight. NewPipe is much
    // lighter than yt-dlp, but two simultaneous YouTube extractors still compete
    // for CPU/network on slower phones and links.
    private val searchStreamWarmSlots = Semaphore(1)
    @Volatile
    private var activeSpeculativeWarmTrackId: String? = null
    // Monotonic generation prevents a slow/cancel-resistant provider request from
    // publishing stale results after the user has typed a newer query.
    private var searchGeneration: Long = 0L
    private companion object {
        /**
         * Cap on how long the UI keeps waiting for the full multi-provider
         * merged view to replace the fast-path result. The fast path is shown
         * immediately; this only gates how long a stale list is allowed to be
         * refreshed. Short enough that the user never feels a stall.
         */
        const val SEARCH_FAST_TIMEOUT_MS = 4_500L
        // Track search may legitimately fall through from Deezer to yt-dlp.
        // Keep album/UI search fast, but do not abort the YouTube fallback while
        // it is still starting.
        const val SEARCH_TRACK_FAST_TIMEOUT_MS = 15_000L
        const val SEARCH_REFINE_TIMEOUT_MS = 2_500L
        const val SEARCH_TRACK_REFINE_TIMEOUT_MS = 16_000L
        // Give the user/UI a short settling window before speculative network
        // work starts. This avoids resolving rows that flash past during a fast
        // search refinement or fling, while still hiding NewPipe latency during
        // the few seconds users normally spend reading titles/covers.
        const val STREAM_SCREEN_IDLE_WARMUP_MS = 650L
        const val STREAM_SCROLL_IDLE_WARMUP_MS = 420L
        const val STREAM_WARM_WINDOW_SIZE = 4
        const val STREAM_WARM_FULL_RESOLVE_COUNT = 3
        const val BULK_DOWNLOAD_CONCURRENCY = 2
        const val BULK_DOWNLOAD_MAX_ATTEMPTS = 3
        const val BULK_DOWNLOAD_RETRY_BASE_DELAY_MS = 1_200L
    }

    fun detectarEstadoInicial() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = database.userPreferencesDao().obtenerPreferencias("current_user")
                val totalTracks = database.trackDao().count()

                val generosFav = decodePreferenceSet(prefs?.generosFavoritos)
                val artistasFav = decodePreferenceSet(prefs?.artistasFavoritos)

                // `primeraVezCompleto == false` is the persisted "onboarding done"
                // value used by the existing schema. Existing users with library
                // content are also allowed through so an update never forces the
                // new onboarding on them.
                val onboardingCompletado = prefs?.primeraVezCompleto == false
                val usuarioExistente = totalTracks > 0

                _preferencias.value = PrefModel(
                    generosFavoritos = generosFav,
                    artistasFavoritos = artistasFav,
                    primeraVezCompleto = !onboardingCompletado
                )
                _esPrimeraVez.value = !onboardingCompletado && !usuarioExistente
            } catch (_: Exception) {
                // A first-run UI is safer than leaving the app permanently on
                // the startup gate if Room cannot be read for any reason.
                _preferencias.value = PrefModel()
                _esPrimeraVez.value = true
            } finally {
                _estadoInicialCargado.value = true
            }
        }
    }

    fun guardarPreferencias(generos: Set<String>, artistas: Set<String>) {
        val generosLimpios = generos.map(String::trim).filter(String::isNotBlank).toSet()
        val artistasLimpios = artistas.map(String::trim).filter(String::isNotBlank).toSet()

        // Dismiss immediately; persistence can finish off the main thread.
        _preferencias.value = PrefModel(
            generosFavoritos = generosLimpios,
            artistasFavoritos = artistasLimpios,
            primeraVezCompleto = false
        )
        _esPrimeraVez.value = false
        _estadoInicialCargado.value = true

        val prefs = UserPreferences(
            userId = "current_user",
            generosFavoritos = encodePreferenceSet(generosLimpios),
            artistasFavoritos = encodePreferenceSet(artistasLimpios),
            primeraVezCompleto = false,
            fechaUltimaActualizacion = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            database.userPreferencesDao().insertarPreferencias(prefs)
        }
    }

    fun saltarOnboarding() {
        guardarPreferencias(emptySet(), emptySet())
    }
    private var playlistJob: Job? = null
    private var activePlaylistId: String? = null
    // Playlist streaming is deliberately windowed. Only the current item plus
    // two successors are resolved/prebuffered, preventing a 300-song playlist
    // from becoming 300 yt-dlp requests while still keeping Next/crossfade hot.
    private var activePlaylistOrder: List<Track> = emptyList()
    private var activePlaylistAppendIndex: Int = -1
    private val playlistLookAheadMutex = Mutex()
    private var playlistLookAheadJob: Job? = null
    private var lastStreamRetryId: String? = null
    private var lastStreamRetryAt: Long = 0L
    // True only when the user explicitly created/selected a playback queue.
    // A normal single-track play also creates a Media3 item, but that must not
    // force Next to stay on a one-item queue.
    private data class PendingQueueEntry(var ready: Boolean = false, var track: Track? = null)
    private val pendingQueueEntries = mutableListOf<PendingQueueEntry>()
    private var queueGeneration = 0L
    private var explicitQueueMode = false
        set(value) {
            field = value
            queueGeneration++
            pendingQueueEntries.clear()
        }
    private var albumJob: Job? = null
    private var remoteAlbumJob: Job? = null
    // Small in-memory LRU for album track lists. Home recommendations often point
    // to the same releases the user opens more than once; reusing the resolved
    // list makes revisits instant and avoids decoding/rebinding a second network
    // response while the detail RecyclerView is already on screen.
    private val remoteAlbumTrackCache = object : LinkedHashMap<String, List<CatalogTrack>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CatalogTrack>>?): Boolean = size > 12
    }
    private var historyJob: Job? = null
    private var lastHistoryUpdateAt = 0L
    // Cached resolution of the currently playing track for the history writer.
    private var historyTrackId: String? = null
    private var historyTrack: Track? = null
    private val whitespaceRegex = Regex("\\s+")

    private val localTracks = repository.tracks()
    private val downloaded = repository.downloaded()
    private val _offlinePlayableIds = MutableStateFlow<Set<String>>(emptySet())
    private val allCatalogAlbums = repository.albums()
    private val cachedCatalogTracks = catalog.cachedTracks()
    private val albums = allCatalogAlbums.map { list -> list.filter { it.id.startsWith("album:custom:") } }
    private val playlists = repository.playlists()

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val isOnline: StateFlow<Boolean> = connectivityMonitor.isOnline
    val offlinePlayableIds: StateFlow<Set<String>> = _offlinePlayableIds.asStateFlow()
    val playbackProgress: StateFlow<PlayerController.PlaybackProgress> = player.progress
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()
    val playerStyle: StateFlow<String> = _playerStyle.asStateFlow()
    val albumDownloads: StateFlow<Set<String>> = _albumDownloads.asStateFlow()

    private val _downloadDirLabel = MutableStateFlow(
        preferences.getString(com.musicvault.app.providers.Providers.DOWNLOAD_TREE_PREF, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(android.net.Uri::parse)
            ?.let(::treeLabel)
            ?: "Carpeta interna de la app"
    )
    val downloadDirLabel: StateFlow<String> = _downloadDirLabel.asStateFlow()

    fun chooseDownloadTree(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            preferences.edit().putString(com.musicvault.app.providers.Providers.DOWNLOAD_TREE_PREF, uri.toString()).apply()
            _downloadDirLabel.value = treeLabel(uri)
        }
    }

    fun useInternalDownloads() {
        preferences.edit().remove(com.musicvault.app.providers.Providers.DOWNLOAD_TREE_PREF).apply()
        _downloadDirLabel.value = "Carpeta interna de la app"
    }

    private fun treeLabel(uri: android.net.Uri): String {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val parts = docId?.split(':') ?: emptyList()
        return if (parts.size == 2) {
            val root = if (parts[0] == "primary") "Almacenamiento interno" else parts[0]
            "$root/${parts[1]}"
        } else "Carpeta seleccionada"
    }

    fun downloadProgressFor(id: String): Flow<Int?> =
        downloadProgress.map { it[id] }.distinctUntilChanged()
    val shuffle: StateFlow<Boolean> = player.shuffle
    val repeatMode: StateFlow<Int> = player.repeatMode
    val crossfadeSeconds: StateFlow<Int> = player.crossfadeSeconds
    val queue: StateFlow<List<Track>> = player.queue
    val queueIndex: StateFlow<Int> = player.queueIndex
    val audioSessionId: StateFlow<Int> = player.audioSessionId
    private val _equalizerBands = MutableStateFlow<List<AudioEffects.Band>>(emptyList())
    val equalizerBands: StateFlow<List<AudioEffects.Band>> = _equalizerBands.asStateFlow()
    // Persisted opt-in: the EQ is OFF unless the user enables it, so attaching
    // the effect (app start, activity recreation) can never enable an audio
    // effect on a live session unprompted.
    private val _eqEnabled = MutableStateFlow(preferences.getBoolean("eq_enabled", false))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqPreset = MutableStateFlow(EqualizerPreset.fromName(preferences.getString("eq_preset", null)))
    val eqPreset: StateFlow<EqualizerPreset> = _eqPreset.asStateFlow()

    private val _loudnessGainMb = MutableStateFlow(preferences.getInt("loudness_gain_mb", 0))
    val loudnessGainMb: StateFlow<Int> = _loudnessGainMb.asStateFlow()

    // Surface to the UI which auxiliary effects the device actually supports.
    // Equalizer is the only one required to render the band sliders; the rest
    // can be hidden if the platform refused to construct the AudioEffect.
    private val _bassBoostAvailable = MutableStateFlow(false)
    val bassBoostAvailable: StateFlow<Boolean> = _bassBoostAvailable.asStateFlow()
    private val _virtualizerAvailable = MutableStateFlow(false)
    val virtualizerAvailable: StateFlow<Boolean> = _virtualizerAvailable.asStateFlow()

    // Lyrics for the full player. One request in flight, results cached per
    // track so re-opening the player (or replaying a track) never refetches.
    private val _lyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = _lyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private val lyricsCache = LinkedHashMap<String, LyricsUiState>()

    private fun lyricsCacheKey(track: Track): String {
        val exactVideoId = NewPipeStreamResolver.cachedYoutubeVideoId(track.id)
            ?: track.sourceId?.takeIf { it.length == 11 }
            ?: Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(track.url.orEmpty())?.groupValues?.get(1)
        return "${track.id}|${exactVideoId.orEmpty()}"
    }

    private suspend fun fastestLyrics(track: Track, localReference: String?): com.musicvault.app.lyrics.Lyrics? = coroutineScope {
        val sourceVideoId = track.sourceId?.takeIf { it.length == 11 }
            ?: Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(track.url.orEmpty())?.groupValues?.get(1)
        // resolveAudio also indexes captions by the logical track id. This is
        // essential for Spotify/Deezer entries whose sourceId is not YouTube's
        // 11-character id even though their audio was matched to YouTube.
        val captionKey = listOf(track.id, sourceVideoId).firstOrNull(NewPipeStreamResolver::hasCachedYoutubeCaptions)
        val exactVideoCaptionsAvailable = captionKey != null
        val youtube = async {
            runCatching { NewPipeStreamResolver.cachedYoutubeLyrics(captionKey) }.getOrNull()?.let {
                LyricsProvider.keepOnlyTrustedSync(it, track.durationMs, exactTimeline = true)
            }
        }
        val lrclib = async {
            runCatching {
                LyricsProvider.fetch(
                    artist = track.artistName,
                    title = track.title,
                    album = track.albumTitle.takeIf { it.isNotBlank() && !it.equals("Álbum desconocido", ignoreCase = true) },
                    durationMs = track.durationMs
                )
            }.getOrNull()?.let {
                LyricsProvider.keepOnlyTrustedSync(
                    VocalActivityDetector.align(appContext, localReference, it),
                    track.durationMs
                )
            }
        }
        // Captions from the exact video own its timeline. Do not let a faster
        // LRCLib response for the studio edit beat them (e.g. Stressed Out's
        // official video starts singing at 16.585s). LRCLib still runs in
        // parallel, so a failed caption fetch adds no second serial wait.
        if (exactVideoCaptionsAvailable) {
            val exact = youtube.await()
            if (exact != null && !exact.isEmpty) {
                lrclib.cancel()
                return@coroutineScope exact
            }
            return@coroutineScope lrclib.await()
        }

        val first = select<Pair<Boolean, com.musicvault.app.lyrics.Lyrics?>> {
            youtube.onAwait { true to it }
            lrclib.onAwait { false to it }
        }
        val firstLyrics = first.second
        if (firstLyrics != null && !firstLyrics.isEmpty) {
            if (first.first) lrclib.cancel() else youtube.cancel()
            firstLyrics
        } else {
            if (first.first) lrclib.await() else youtube.await()
        }
    }

    fun loadLyrics(track: Track) {
        if (_lyrics.value.trackId == track.id && (_lyrics.value.loading || _lyrics.value.notFound ||
                _lyrics.value.plain != null || _lyrics.value.synced.isNotEmpty())
        ) return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            val cacheKey = lyricsCacheKey(track)
            val localReference = database.downloadRecordDao().get(track.id)?.path ?: track.audioUri
            val local = LyricsLocalStore.read(appContext, localReference)?.let {
                // Stored lyrics were validated before persistence and belong to
                // this exact local audio file; preserve their follow timeline.
                LyricsProvider.keepOnlyTrustedSync(it, track.durationMs, exactTimeline = true)
            }
            val state = when {
                local != null && !local.isEmpty -> LyricsUiState(
                    trackId = track.id,
                    plain = local.plain,
                    synced = local.synced
                )
                lyricsCache[cacheKey] != null -> lyricsCache[cacheKey]!!
                else -> {
                    _lyrics.value = LyricsUiState(trackId = track.id, loading = true)
                    val aligned = fastestLyrics(track, localReference)
                    when {
                        aligned == null || aligned.isEmpty -> LyricsUiState(trackId = track.id, notFound = true)
                        else -> LyricsUiState(trackId = track.id, plain = aligned.plain, synced = aligned.synced)
                    }
                }
            }
            if (lyricsCache.size >= 40) lyricsCache.clear()
            lyricsCache[cacheKey] = state
            _lyrics.value = state
        }
    }

    // Related catalog tracks fetched for the artists the user has downloaded.
    // Refreshed once per downloaded-artist profile signature, not per screen visit.
    private val relatedCatalog = MutableStateFlow<List<CatalogTrack>>(emptyList())
    // Albums gathered explicitly during onboarding. Keeping them in-memory makes
    // the first Home frame independent of Room propagation timing.
    private val onboardingCatalogAlbums = MutableStateFlow<List<Album>>(emptyList())
    private var fetchedArtistProfile: String? = null

    private fun normalizedArtist(value: String): String {
        val noAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(whitespaceRegex, " ")
    }

    /**
     * Preference strings used to be comma-separated. That silently broke
     * artists such as "Tyler, The Creator" and "Earth, Wind & Fire" after a
     * process restart. The v2 format uses an ASCII unit separator, while the
     * legacy comma format remains readable for existing installs.
     */
    private fun encodePreferenceSet(values: Set<String>): String =
        "mv2:" + values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\u001F")

    private fun decodePreferenceSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val values = if (raw.startsWith("mv2:")) {
            raw.removePrefix("mv2:").split('\u001F')
        } else {
            // Backward compatibility with MusicVault <= v4.
            raw.split(',')
        }
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    }

    private fun profileSignature(terms: Iterable<String>): String =
        terms.asSequence()
            .map(::normalizedArtist)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .take(12)
            .joinToString("|")

    private fun artistMatches(candidate: String, requested: String): Boolean {
        val a = normalizedArtist(candidate)
        val r = normalizedArtist(requested)
        if (a.isBlank() || r.isBlank()) return false
        return a == r || a.contains(r) || r.contains(a)
    }

    /**
     * Round-robin results by requested artist instead of concatenating every
     * result from artist #1, then artist #2, etc. This is important because the
     * Home feed is capped: concatenation made five selected artists look like
     * recommendations for only the first one or two.
     */
    private fun balanceTrackGroups(
        groups: List<Pair<String, List<CatalogTrack>>>,
        limit: Int,
        requireArtistMatch: Boolean = false
    ): List<CatalogTrack> {
        val prepared = groups.map { (artist, items) ->
            val matching = items.filter { artistMatches(it.artistName, artist) }
            // During onboarding an artist choice is an explicit signal. Never
            // substitute unrelated tracks just because a provider returned an
            // off-topic first page. Genre/discovery callers may still use the
            // small fallback because their term is not necessarily an artist.
            (if (matching.isNotEmpty()) matching else if (requireArtistMatch) emptyList() else items.take(4))
                .distinctBy { "${it.provider}:${it.id}" }
        }
        val out = ArrayList<CatalogTrack>(limit)
        val seen = HashSet<String>()
        var round = 0
        while (out.size < limit && prepared.any { round < it.size }) {
            for (bucket in prepared) {
                if (round >= bucket.size) continue
                val item = bucket[round]
                if (seen.add("${item.provider}:${item.id}")) out += item
                if (out.size >= limit) break
            }
            round++
        }
        return out
    }

    private fun balanceAlbumGroups(
        groups: List<Pair<String, List<CatalogAlbum>>>,
        limit: Int,
        requireArtistMatch: Boolean = false
    ): List<CatalogAlbum> {
        val prepared = groups.map { (artist, items) ->
            val matching = items.filter { artistMatches(it.artistName, artist) }
            (if (matching.isNotEmpty()) matching else if (requireArtistMatch) emptyList() else items.take(3))
                .distinctBy { "${it.provider}:${it.id}" }
        }
        val out = ArrayList<CatalogAlbum>(limit)
        val seen = HashSet<String>()
        var round = 0
        while (out.size < limit && prepared.any { round < it.size }) {
            for (bucket in prepared) {
                if (round >= bucket.size) continue
                val item = bucket[round]
                if (seen.add("${item.provider}:${item.id}")) out += item
                if (out.size >= limit) break
            }
            round++
        }
        return out
    }

    private fun catalogAlbumToAlbum(album: CatalogAlbum): Album = Album(
        id = "${album.provider}:release-group:${album.id}",
        title = album.title,
        artistId = album.artistId,
        artistName = album.artistName,
        year = album.year ?: 0,
        coverUri = album.coverUri
    )

    private suspend fun fetchRecommendationSeed(term: String): MetadataCatalogProvider.SearchResult =
        kotlinx.coroutines.coroutineScope {
            // Each selected artist owns its own timeout. The v4 implementation
            // wrapped the entire 5-artist batch in one timeout, so one slow API
            // request discarded the results of every artist that had already
            // completed. This helper makes failures isolated instead.
            val tracksDeferred = async {
                withTimeoutOrNull(4_200L) { catalog.searchTracksFast(term, 14) }.orEmpty()
            }
            val albumsDeferred = async {
                withTimeoutOrNull(4_200L) { catalog.searchAlbumsFast(term, 6) }.orEmpty()
            }
            var tracks = tracksDeferred.await()
            var albums = albumsDeferred.await()

            // A short precision fallback is worth paying only for the category
            // that actually failed. It remains per-artist, so it cannot erase
            // successful buckets from the rest of the onboarding selection.
            if (tracks.isEmpty()) {
                tracks = withTimeoutOrNull(2_200L) { catalog.searchTracksMerged(term, 14) }.orEmpty()
            }
            if (albums.isEmpty()) {
                albums = withTimeoutOrNull(2_200L) { catalog.searchAlbumsMerged(term, 6) }.orEmpty()
            }
            MetadataCatalogProvider.SearchResult(tracks = tracks, albums = albums)
        }

    /**
     * Starts immediately after the artist step, while the fancy onboarding
     * animation is still covering the app. Artist and genre requests run in
     * parallel; selected artists are interleaved so every chosen artist gets a
     * chance to appear before the feed cap is reached.
     */
    fun preloadRecommendations(generos: Set<String>, artistas: Set<String>) {
        recommendationWarmupJob?.cancel()
        val artistTerms = artistas.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()
        val genreTerms = generos.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(4)
            .toList()

        _recommendationWarmupReady.value = false
        if (artistTerms.isEmpty() && genreTerms.isEmpty()) {
            _recommendationWarmupReady.value = true
            return
        }

        // Temporarily reserve the profile so the regular background collector
        // does not duplicate the exact same requests while onboarding is active.
        // If the whole artist batch fails we clear it below so the collector can
        // retry on the normal Home lifecycle instead of leaving generic results.
        val warmupSignature = profileSignature(artistTerms + genreTerms)
        fetchedArtistProfile = warmupSignature

        recommendationWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Artist choices are the strongest signal and therefore form the
                // blocking first paint. IMPORTANT: timeouts are per term. One slow
                // artist must never cancel and erase the other selected artists.
                val primaryTerms = if (artistTerms.isNotEmpty()) artistTerms else genreTerms
                val primaryResults = primaryTerms.map { term ->
                    async {
                        term to runCatching { fetchRecommendationSeed(term) }
                            .getOrElse { MetadataCatalogProvider.SearchResult(emptyList(), emptyList()) }
                    }
                }.awaitAll()

                val primaryTracks = balanceTrackGroups(
                    primaryResults.map { (term, result) -> term to result.tracks },
                    40,
                    requireArtistMatch = artistTerms.isNotEmpty()
                )
                val primaryAlbums = balanceAlbumGroups(
                    primaryResults.map { (term, result) -> term to result.albums },
                    18,
                    requireArtistMatch = artistTerms.isNotEmpty()
                )

                relatedCatalog.value = primaryTracks
                    .distinctBy { "${it.provider}:${it.id}" }
                    .take(40)
                onboardingCatalogAlbums.value = primaryAlbums
                    .distinctBy { "${it.provider}:${it.id}" }
                    .take(18)
                    .map(::catalogAlbumToAlbum)

                if ((artistTerms.isNotEmpty() && primaryTracks.isEmpty()) ||
                    (artistTerms.isEmpty() && primaryTracks.isEmpty() && primaryAlbums.isEmpty())
                ) {
                    // Do not permanently mark a failed artist fetch as fetched.
                    // Albums alone are not enough: Home must still retry the
                    // selected artists so its song rail cannot become generic.
                    if (fetchedArtistProfile == warmupSignature) fetchedArtistProfile = null
                }

                // Wait until the recommendation combiner has consumed the whole
                // primary artist batch, then warm the EXACT covers Home is going
                // to render. That avoids declaring the onboarding ready based on
                // seed results that may be reordered/replaced by the final feed.
                val firstPaintRecommendations = withTimeoutOrNull(2_500L) {
                    recommendations.first { (tracks, albums) ->
                        tracks.isNotEmpty() && (albums.isNotEmpty() || primaryAlbums.isEmpty())
                    }
                } ?: recommendations.value

                // Download the exact artwork sizes/cache keys used by Home. The
                // wait is bounded; a broken artwork host can never trap onboarding.
                CoverImageCache.prefetchHomeAndAwait(
                    appContext,
                    trackUrls = firstPaintRecommendations.first.map { it.coverUri },
                    albumUrls = firstPaintRecommendations.second.map { it.coverUri },
                    timeoutMs = 6_500L
                )
                _recommendationWarmupReady.value = true

                // Genres add variety, but must not delay the handoff after the
                // selected artists are already represented on Home.
                if (artistTerms.isNotEmpty() && genreTerms.isNotEmpty()) {
                    val genreResults = genreTerms.map { genre ->
                        async {
                            genre to runCatching {
                                withTimeoutOrNull(3_800L) { catalog.searchFast(genre, 6, 3) }
                                    ?: MetadataCatalogProvider.SearchResult(emptyList(), emptyList())
                            }.getOrElse { MetadataCatalogProvider.SearchResult(emptyList(), emptyList()) }
                        }
                    }.awaitAll()

                    if (genreResults.isNotEmpty()) {
                        val genreTracks = genreResults.flatMap { (_, result) -> result.tracks.take(3) }
                        val genreAlbums = genreResults.flatMap { (_, result) -> result.albums.take(2) }
                        relatedCatalog.value = (primaryTracks + genreTracks)
                            .distinctBy { "${it.provider}:${it.id}" }
                            .take(48)
                        onboardingCatalogAlbums.value = (primaryAlbums + genreAlbums)
                            .distinctBy { "${it.provider}:${it.id}" }
                            .take(20)
                            .map(::catalogAlbumToAlbum)
                        CoverImageCache.prefetch(
                            appContext,
                            genreTracks.map { it.coverUri } + genreAlbums.map { it.coverUri }
                        )
                    }
                }
            } finally {
                _recommendationWarmupReady.value = true
            }
        }
    }

    private fun catalogToTrack(ct: CatalogTrack): Track = Track(
        id = "${ct.provider}:recording:${ct.id}", title = ct.title,
        artistName = ct.artistName,
        albumId = ct.albumId?.let { "${ct.provider}:release:$it" } ?: "unknown",
        albumTitle = ct.albumTitle ?: "Álbum desconocido", durationMs = ct.durationMs,
        audioUri = null, isDownloaded = false, coverUri = ct.coverUri,
        source = ct.source, sourceId = ct.sourceId ?: ct.id, url = ct.url
    )

    // Recommendations are isolated from fast-changing player/download UI state.
    // This prevents Home from rescoring/sorting the catalog during playback or
    // download progress updates. The expensive work runs off the main thread.
    private val recommendations: StateFlow<Pair<List<Track>, List<Album>>> = combine(
        localTracks, allCatalogAlbums, favoriteIds, recentSearches, cachedCatalogTracks,
        relatedCatalog, onboardingCatalogAlbums, _preferencias, _estadoInicialCargado
    ) { v ->
        val tracks = (v[0] as? List<*>)?.filterIsInstance<Track>() ?: emptyList()
        val catalogAlbums = (v[1] as? List<*>)?.filterIsInstance<Album>() ?: emptyList()
        val favorites = (v[2] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val searches = (v[3] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val cachedTracks = (v[4] as? List<*>)?.filterIsInstance<CatalogTrack>() ?: emptyList()
        val related = (v[5] as? List<*>)?.filterIsInstance<CatalogTrack>() ?: emptyList()
        val warmAlbums = (v[6] as? List<*>)?.filterIsInstance<Album>() ?: emptyList()
        val prefs = v[7] as? PrefModel ?: PrefModel()
        val initialPreferencesLoaded = v[8] as? Boolean ?: false
        val profileKey = HomeRecommendationCache.profileKey(prefs.generosFavoritos, prefs.artistasFavoritos)

        // Paint the last persisted Home immediately while Room/catalog flows are
        // still warming up. The profile guard prevents showing another taste profile.
        if (!initialPreferencesLoaded) {
            val cached = homeRecommendationSnapshot
            val persistedProfile = HomeRecommendationCache.persistedProfileKey(preferences)
            if (cached != null && cached.profileKey == persistedProfile) {
                return@combine HomeRecommendationCache.forToday(cached)
            }
            return@combine emptyList<Track>() to emptyList()
        }

        // User profile: artists/albums already in the vault plus explicit
        // first-run preferences. This makes onboarding affect Home immediately
        // instead of just storing choices that are never used.
        val downloaded = tracks.filter { it.isDownloaded }
        val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
        val topArtists = downloaded.asSequence()
            .map { it.artistName.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.asSequence()
            .sortedByDescending { it.value }
            .map { it.key }
            .take(8)
            .toHashSet()
        topArtists += prefs.artistasFavoritos
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val downloadedAlbumTitles = downloaded.mapTo(HashSet()) { it.albumTitle.trim().lowercase() }

        val tokens = (searches.asSequence() + prefs.generosFavoritos.asSequence())
            .flatMap { it.lowercase().split(whitespaceRegex).asSequence() }
            .filter { it.length >= 2 }
            .distinct()
            .take(24)
            .toList()

        fun scoreMatches(haystack: String): Int {
            var hits = 0
            for (token in tokens) {
                if (haystack.contains(token)) hits++
            }
            return hits
        }

        fun scoreTrack(t: Track): Int {
            val artist = t.artistName.trim().lowercase()
            val hay = "${t.title} $artist ${t.albumTitle}".lowercase()
            var score = scoreMatches(hay) * 4
            // Artists/albums from the vault dominate the ranking.
            if (artist in topArtists) score += 100
            if (t.albumTitle.trim().lowercase() in downloadedAlbumTitles) score += 30
            if (t.id in favorites) score += 40
            return score
        }

        fun scoreAlbum(a: Album): Int {
            val hay = "${a.title} ${a.artistName}".lowercase()
            var score = scoreMatches(hay) * 8
            if (a.artistName.trim().lowercase() in topArtists) score += 100
            return score
        }

        // Discovery feed: only catalog tracks the user does NOT have yet,
        // prioritized by their downloaded artists/albums (search tokens act as
        // a weak genre/keyword signal). Sources: live artist fetch + search
        // cache + catalog placeholder rows already persisted in the DB, so the
        // feed also works offline without any network request.
        val tracksResult = (related.asSequence() + cachedTracks.asSequence()).map(::catalogToTrack) +
            tracks.asSequence().filter {
                !it.isDownloaded && it.audioUri == null && it.id.contains(":recording:") && it.title.isNotBlank()
            }
        val scoredTracks = tracksResult
            .distinctBy { it.id }
            .filter { it.id !in downloadedIds }
            .map { it to scoreTrack(it) }
            .sortedByDescending { it.second }
            .toList()

        // First paint must represent the whole taste profile, not just whichever
        // artist happened to be queried first. Pull one item per selected artist
        // per round, then fill remaining slots by score.
        val selectedArtists = prefs.artistasFavoritos
            .map(::normalizedArtist)
            .filter(String::isNotBlank)
            .distinct()
        val balancedTracks = ArrayList<Pair<Track, Int>>(24)
        val usedTrackIds = HashSet<String>()
        if (selectedArtists.isNotEmpty()) {
            val buckets = selectedArtists.map { artist ->
                scoredTracks.filter { (track, _) -> artistMatches(track.artistName, artist) }
            }
            var round = 0
            while (balancedTracks.size < 24 && buckets.any { round < it.size }) {
                for (bucket in buckets) {
                    if (round >= bucket.size) continue
                    val candidate = bucket[round]
                    if (usedTrackIds.add(candidate.first.id)) balancedTracks += candidate
                    if (balancedTracks.size >= 24) break
                }
                round++
            }
        }
        scoredTracks.forEach { candidate ->
            if (balancedTracks.size < 24 && usedTrackIds.add(candidate.first.id)) balancedTracks += candidate
        }
        val tracksList = balancedTracks.take(24).map { it.first }

        val scoredAlbums = (warmAlbums.asSequence() + catalogAlbums.asSequence())
            .filter { !it.id.startsWith("album:custom:") }
            .distinctBy { it.id }
            .map { it to scoreAlbum(it) }
            .sortedWith(compareByDescending<Pair<Album, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .toList()

        val balancedAlbums = ArrayList<Pair<Album, Int>>(12)
        val usedAlbumIds = HashSet<String>()
        if (selectedArtists.isNotEmpty()) {
            val buckets = selectedArtists.map { artist ->
                scoredAlbums.filter { (album, _) -> artistMatches(album.artistName, artist) }
            }
            var round = 0
            while (balancedAlbums.size < 12 && buckets.any { round < it.size }) {
                for (bucket in buckets) {
                    if (round >= bucket.size) continue
                    val candidate = bucket[round]
                    if (usedAlbumIds.add(candidate.first.id)) balancedAlbums += candidate
                    if (balancedAlbums.size >= 12) break
                }
                round++
            }
        }
        scoredAlbums.forEach { candidate ->
            if (balancedAlbums.size < 12 && usedAlbumIds.add(candidate.first.id)) balancedAlbums += candidate
        }
        val albumsList = balancedAlbums.take(12).map { it.first }
        val matchingCache = homeRecommendationSnapshot?.takeIf { it.profileKey == profileKey }
        val visibleTracks = tracksList.ifEmpty { matchingCache?.tracks.orEmpty() }
        val visibleAlbums = albumsList.ifEmpty { matchingCache?.albums.orEmpty() }
        if (tracksList.isNotEmpty() || albumsList.isNotEmpty()) {
            HomeRecommendationCache.save(preferences, profileKey, visibleTracks, visibleAlbums)
        }
        HomeRecommendationCache.forToday(
            HomeRecommendationCache.Snapshot(profileKey, visibleTracks, visibleAlbums)
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Track>() to emptyList())

    // Keep rapidly changing download/player state out of recommendation calculation.
    // _search is intentionally excluded from the combined state to avoid recomposing
    // the entire UI on every keystroke. Search text is kept locally in the composable
    // and only pushed to the ViewModel when submitting.
    val state: StateFlow<MusicUiState> = combine(
        _section, localTracks, downloaded, albums, playlists,
        _remoteTracks, _remoteAlbums, _selectedTrack, _selectedPlaylist,
        _selectedPlaylistTracks, _selectedRemoteAlbum, _remoteAlbumTracks, _loadingRemoteAlbum,
        _selectedLocalAlbum, _selectedLocalAlbumTracks, _fullPlayer, _shuffle, _crossfadeSeconds,
        _searching, _scanning, _providerMessage, _errorMessage, favoriteIds, recentSearches, recommendations,
        _playerStyle, _appDesign, _spotifyConnected, _spotifyOffer, _spotifyImporting, _spotifyPlaylistReview
    ) { v ->
        val rec = v[24] as? Pair<*, *> ?: (emptyList<Track>() to emptyList<Album>())
        MusicUiState(
            section = v[0] as? AppSection ?: AppSection.HOME, search = "",
            tracks = v[1].stateList(),
            downloaded = v[2].stateList(), albums = v[3].stateList(),
            playlists = v[4].stateList(),
            remoteTracks = v[5].stateList(), remoteAlbums = v[6].stateList(),
            selectedTrack = v[7] as? Track, selectedPlaylist = v[8] as? Playlist,
            selectedPlaylistTracks = v[9].stateList(), selectedRemoteAlbum = v[10] as? CatalogAlbum,
            remoteAlbumTracks = v[11].stateList(), isLoadingRemoteAlbum = v[12] as? Boolean ?: false,
            selectedLocalAlbum = v[13] as? Album, selectedLocalAlbumTracks = v[14].stateList(),
            fullPlayer = v[15] as? Boolean ?: false, shuffle = v[16] as? Boolean ?: false, crossfadeSeconds = v[17] as? Int ?: 5,
            isSearching = v[18] as? Boolean ?: false, isScanning = v[19] as? Boolean ?: false,
            providerMessage = v[20] as? String?, errorMessage = v[21] as? String?,
            favoriteIds = v[22].stateSet(), recentSearches = v[23].stateList(),
            recommendedTracks = rec.first.stateList(), recommendedAlbums = rec.second.stateList(),
            playerStyle = v[25] as? String ?: "vinyl",
            appDesign = v[26] as? String ?: APP_DESIGN_HYBRID,
            spotifyConnected = v[27] as? Boolean ?: false,
            spotifyOffer = v[28] as? SpotifyLink,
            spotifyImporting = v[29] as? Boolean ?: false,
            spotifyPlaylistReview = v[30] as? SpotifyPlaylist
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    init {
        val filter = IntentFilter().apply {
            addAction(DownloadForegroundService.ACTION_PROGRESS)
            addAction(DownloadForegroundService.ACTION_RESULT)
        }
        ContextCompat.registerReceiver(
            appContext, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModelScope.launch {
            // Recheck on both library and connectivity changes. Files can be
            // deleted outside MusicVault or a persisted SAF grant can become
            // unusable while the database still says isDownloaded=true.
            combine(downloaded, isOnline) { tracks, _ -> tracks }
                .map { tracks ->
                    withContext(Dispatchers.IO) {
                        tracks.asSequence()
                            .filter { isOfflineAudioReadable(it.audioUri) }
                            .mapTo(HashSet()) { it.id }
                    }
                }
                .collect { _offlinePlayableIds.value = it }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Favorites is a permanent user collection backed by the existing playlist tables.
            // Creating it with a stable id makes it available immediately and preserves it across launches.
            repository.createPlaylist(favoritesAlbumId, "Favoritos", "Canciones que te gustan")
        }
        player.setCrossfadeSeconds(_crossfadeSeconds.value)
        // Continuous playback: whenever the playing item has no successor the
        // player asks for one. A one-item queue can never crossfade, so this is
        // what keeps the crossfade (and playback itself) alive across tracks.
        // Explicit queues (playlists) manage their own items and are skipped.
        player.onNextItemNeeded = { finishedTrackId ->
            if (explicitQueueMode && activePlaylistId != null) {
                // Managed playlist: replenish the small streaming look-ahead
                // window instead of resolving/downloading the whole playlist.
                schedulePlaylistLookAhead(finishedTrackId)
            } else if (!explicitQueueMode) {
                val generation = queueGeneration
                viewModelScope.launch {
                    val current = finishedTrackId
                        ?.let { id -> state.value.tracks.firstOrNull { it.id == id } }
                        ?: _selectedTrack.value
                    if (current != null && generation == queueGeneration) appendSmartSuccessor(current)
                }
            }
        }
        player.onPlaybackError = errorHandler@{ trackId ->
            val id = trackId ?: return@errorHandler
            val now = android.os.SystemClock.elapsedRealtime()
            // One forced re-resolution per 30 s per track prevents retry loops
            // while transparently recovering an expired CDN signature/403.
            if (lastStreamRetryId == id && now - lastStreamRetryAt < 30_000L) return@errorHandler
            val logical = activePlaylistOrder.firstOrNull { it.id == id }
                ?: queue.value.firstOrNull { it.id == id }
                ?: _selectedTrack.value?.takeIf { it.id == id }
            if (logical == null || logical.isDownloaded) return@errorHandler
            lastStreamRetryId = id
            lastStreamRetryAt = now
            Providers.invalidateStream(id)
            viewModelScope.launch(Dispatchers.IO) {
                val refreshed = resolveForStreaming(
                    logical.copy(audioUri = null, isDownloaded = false),
                    forceRefresh = true
                )
                if (refreshed == null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (_preparingTrackId.value == id) _preparingTrackId.value = null
                        showError("No se pudo reanudar ${logical.title} por streaming.")
                    }
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    player.retryCurrent(refreshed)
                    _selectedTrack.value = refreshed
                }
            }
        }
        // Keep the home-screen widget in sync with playback state/track changes.
        player.onPlaybackUiChanged = {
            com.musicvault.app.widget.MusicWidgetProvider.push(appContext)
        }
        // Keep the first Home recommendations hot as well, but only one
        // speculative resolver may run at a time. A user tap cancels this job
        // so the selected track gets CPU/network priority immediately.
        viewModelScope.launch(Dispatchers.IO) {
            combine(recommendations, _section) { pair, section ->
                if (section == AppSection.HOME) {
                    pair.first.filterNot { it.isDownloaded && !it.audioUri.isNullOrBlank() }.take(3)
                } else {
                    emptyList()
                }
            }
                .distinctUntilChangedBy { list -> list.map { it.id } }
                .collectLatest { visible ->
                    homeStreamWarmJob?.cancel()
                    homeStreamWarmJob = null
                    if (visible.isNotEmpty()) {
                        homeStreamWarmJob = viewModelScope.launch(Dispatchers.IO) {
                            // Home cards are usually read for a moment before a
                            // tap. Spend that otherwise idle time resolving all
                            // three likely choices sequentially.
                            delay(STREAM_SCREEN_IDLE_WARMUP_MS)
                            warmStreamingCandidates(
                                visible,
                                fullResolveCount = STREAM_WARM_FULL_RESOLVE_COUNT
                            ) {
                                _section.value == AppSection.HOME && _preparingTrackId.value == null
                            }
                        }
                    }
                }
        }

        // Home discovery: fetch catalog tracks from both the existing library
        // and the tastes chosen during onboarding. The small delay keeps this
        // network work away from the first frame.
        viewModelScope.launch(Dispatchers.IO) {
            combine(localTracks, _preferencias) { list, prefs ->
                val downloadedArtists = list.filter { it.isDownloaded }
                    .asSequence()
                    .map { it.artistName.trim() }
                    .filter { it.isNotBlank() }
                    .groupingBy { it.lowercase() }.eachCount()
                    .entries.asSequence()
                    .sortedByDescending { it.value }
                    .take(4)
                    .map { it.key }
                    .toList()

                val preferenceTerms = (prefs.artistasFavoritos.asSequence() + prefs.generosFavoritos.asSequence())
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                (downloadedArtists + preferenceTerms)
                    .distinctBy { it.lowercase() }
                    .take(12)
            }
                .distinctUntilChanged()
                .collect { terms ->
                    val signature = profileSignature(terms)
                    if (signature.isBlank() || signature == fetchedArtistProfile) return@collect
                    fetchedArtistProfile = signature
                    delay(350)
                    runCatching {
                        val groups = terms.map { term ->
                            async {
                                val fast = runCatching {
                                    withTimeoutOrNull(4_200L) { catalog.searchTracksFast(term, 12) }.orEmpty()
                                }.getOrDefault(emptyList())
                                val tracks = if (fast.isNotEmpty()) fast else runCatching {
                                    withTimeoutOrNull(2_200L) { catalog.searchTracksMerged(term, 12) }.orEmpty()
                                }.getOrDefault(emptyList())
                                term to tracks
                            }
                        }.awaitAll()
                        val refreshed = balanceTrackGroups(groups, 48)
                        if (refreshed.isNotEmpty()) {
                            relatedCatalog.value = refreshed
                        } else if (fetchedArtistProfile == signature) {
                            // Allow another profile emission/relaunch to retry a
                            // transient all-provider failure.
                            fetchedArtistProfile = null
                        }
                    }
                }
        }
        // Run initial state detection (first run vs update)
        detectarEstadoInicial()
        historyJob = viewModelScope.launch {
            launch {
                player.audioSessionId.collect { sessionId ->
                    // The effect is created disabled unless the user opted in;
                    // never force-enable it on a live session (see AudioEffects).
                    if (sessionId > 0 && equalizer.attach(sessionId, _eqEnabled.value)) {
                        _equalizerBands.value = equalizer.bands()
                        _bassBoostAvailable.value = equalizer.isBassBoostAvailable
                        _virtualizerAvailable.value = equalizer.isVirtualizerAvailable
                        // Replay persisted state onto the freshly attached
                        // effects stack so the user's selection survives a
                        // session change (new track, new device attach, etc).
                        equalizer.setLoudnessGain(_loudnessGainMb.value)
                        applyEqualizerPreset(_eqPreset.value)
                    }
                }
            }
            launch {
                player.ended.filter { it > 0L }.collect { nextTrack() }
            }
            launch {
                combine(player.progress, player.isPlaying) { progress, playing -> progress.trackId to playing }
                    .collect { (trackId, playing) ->
                        if (playing && trackId != null && trackId == _preparingTrackId.value) {
                            _preparingTrackId.value = null
                        }
                    }
            }
            launch {
                player.progress
                    .map { it.trackId }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { trackId ->
                        // Keep selectedTrack/UI synchronized immediately on an
                        // automatic streaming transition, and refill the two-item
                        // playlist look-ahead while the new track is playing.
                        activePlaylistOrder.firstOrNull { it.id == trackId }?.let { logical ->
                            val queued = queue.value.firstOrNull { it.id == trackId } ?: logical
                            if (_selectedTrack.value?.id != trackId) _selectedTrack.value = queued
                        }
                        if (explicitQueueMode && activePlaylistId != null) {
                            schedulePlaylistLookAhead(trackId)
                        }
                    }
            }
            player.progress
                .filter { it.trackId != null && it.durationMs > 0L }
                .collect { progress ->
                    val now = System.currentTimeMillis()
                    if (!progress.isPlaying && progress.positionMs <= 0L) return@collect
                    if (now - lastHistoryUpdateAt < 10_000L) return@collect
                    val ratio = (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
                    progress.trackId?.let { id ->
                        // Resolve the track once per track change, not once per
                        // throttled tick: a full table query every 2 s was the
                        // hottest DB path during playback.
                        if (id != historyTrackId) {
                            historyTrack = localTracks.first().firstOrNull { it.id == id }
                            historyTrackId = id
                        }
                        historyTrack?.let { current ->
                            if (_selectedTrack.value?.id != id) _selectedTrack.value = current
                        }
                        repository.updateLatestCompletion(id, ratio)
                        lastHistoryUpdateAt = now
                    }
                }
        }
    }

    fun selectSection(section: AppSection) {
        _section.value = section
        if (section != AppSection.PLAYLISTS) {
            _selectedPlaylist.value = null
            _selectedPlaylistTracks.value = emptyList()
            playlistJob?.cancel()
            playlistJob = null
        }
        if (section != AppSection.LIBRARY && section != AppSection.HOME) {
            closeLocalAlbum()
        }
        if (section != AppSection.SEARCH) {
            closeRemoteAlbum()
        }
    }

    fun inspectSpotifyClipboard(text: String?) {
        val link = SpotifyLinks.findInText(text)
        val canonical = link?.canonicalUrl
        if (canonical == lastSpotifyClipboardUrl) return
        lastSpotifyClipboardUrl = canonical
        if (link == null) {
            _spotifyOffer.value = null
            return
        }
        // Keep the offer even when Spotify is disconnected. The link is still
        // valid and useful; importSpotifyLink() will explain that the account
        // must be connected instead of silently ignoring the clipboard.
        viewModelScope.launch(Dispatchers.IO) {
            val kind = if (link.kind == SpotifyLinkKind.TRACK) "track" else "playlist"
            val alreadyImported = repository.containsSpotifyImport(kind, link.id)
            // Clipboard inspection is asynchronous. Never publish an older result
            // after the user has copied a different link in the meantime.
            if (lastSpotifyClipboardUrl == canonical) {
                _spotifyOffer.value = link.takeUnless { alreadyImported }
            }
        }
    }

    fun clearSpotifyOffer() {
        _spotifyOffer.value = null
    }

    fun connectSpotify(cookie: String) {
        if (_spotifyImporting.value) return
        _spotifyImporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                spotifyClient.validate(cookie)
                spotifySession.save(cookie)
                // The current clipboard may have been inspected while disconnected.
                // Let the foreground observer offer that same link now that it is usable.
                lastSpotifyClipboardUrl = null
                _spotifyConnected.value = true
                showProvider("Spotify quedó conectado para importar canciones y playlists.")
            } catch (failure: Exception) {
                showError(failure.message ?: "Spotify rechazó la sesión.")
            } finally {
                _spotifyImporting.value = false
            }
        }
    }

    fun disconnectSpotify() {
        spotifySession.clear()
        _spotifyConnected.value = false
        _spotifyOffer.value = null
        _spotifyPlaylistReview.value = null
        showProvider("Se eliminó la sesión local de Spotify.")
    }

    fun importSpotifyOffer() {
        val link = _spotifyOffer.value ?: return
        importSpotifyLink(link)
    }

    private fun importSpotifyLink(link: SpotifyLink) {
        if (_spotifyImporting.value) return
        val cookie = spotifySession.read()
        if (cookie == null) {
            _spotifyConnected.value = false
            showError("Conecta tu cuenta de Spotify antes de importar.")
            return
        }
        _spotifyOffer.value = null
        _spotifyImporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (link.kind) {
                    SpotifyLinkKind.TRACK -> importSpotifyTrack(spotifyClient.fetchTrack(link.id, cookie))
                    SpotifyLinkKind.PLAYLIST -> {
                        _spotifyPlaylistReview.value = spotifyClient.fetchPlaylist(link.id, cookie)
                    }
                }
            } catch (failure: Exception) {
                showError(failure.message ?: "No se pudo importar desde Spotify.")
            } finally {
                _spotifyImporting.value = false
            }
        }
    }

    fun cancelSpotifyPlaylistImport() {
        _spotifyPlaylistReview.value = null
    }

    fun confirmSpotifyPlaylistImport(selectedIds: Set<String>) {
        val playlist = _spotifyPlaylistReview.value ?: return
        val selected = playlist.tracks.filter { it.id in selectedIds }
        if (selected.isEmpty()) {
            showError("Selecciona al menos una canción para importarla.")
            return
        }
        _spotifyPlaylistReview.value = null
        _spotifyImporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tracks = selected.map(::spotifyToTrack)
                val playlistId = "playlist:spotify:${playlist.id}"
                repository.replaceSpotifyPlaylist(playlistId, playlist.title, playlist.coverUrl, tracks)
                val model = Playlist(
                    playlistId,
                    playlist.title,
                    "Importada desde Spotify",
                    tracks.map { it.id },
                    playlist.coverUrl
                )
                _section.value = AppSection.PLAYLISTS
                openPlaylist(model)
                showProvider("${tracks.size} canciones importadas desde Spotify.")
            } catch (failure: Exception) {
                showError(failure.message ?: "No se pudo guardar la playlist de Spotify.")
            } finally {
                _spotifyImporting.value = false
            }
        }
    }

    private suspend fun importSpotifyTrack(track: SpotifyTrack) {
        val local = spotifyToTrack(track)
        repository.importSpotifyTrack(local)
        _section.value = AppSection.SEARCH
        _search.value = track.title
        _remoteAlbums.value = emptyList()
        _remoteTracks.value = listOf(spotifyToCatalogTrack(track))
        showProvider("Canción importada: ${track.title}")
    }

    private fun spotifyToTrack(track: SpotifyTrack) = Track(
        id = "spotify:recording:${track.id}",
        title = track.title,
        artistName = track.artist,
        albumId = "spotify:release:${track.album.lowercase().hashCode()}",
        albumTitle = track.album,
        durationMs = track.durationMs,
        coverUri = track.coverUrl,
        source = "Spotify",
        sourceId = track.id,
        url = track.url
    )

    private fun spotifyToCatalogTrack(track: SpotifyTrack) = CatalogTrack(
        id = track.id,
        title = track.title,
        artistId = "",
        artistName = track.artist,
        albumId = null,
        albumTitle = track.album,
        durationMs = track.durationMs,
        coverUri = track.coverUrl,
        provider = "spotify",
        url = track.url,
        source = "Spotify",
        sourceId = track.id
    )

    fun updateSearchInput(value: String) {
        // Text edits only update the field state. Network search is triggered
        // explicitly from submitSearch() (Enter / IME Search action) so the
        // user can type without the results list flickering on every keystroke.
        _search.value = value
    }

    fun submitSearch(value: String? = null) {
        val query = (value ?: _search.value).trim()
        if (query.isBlank()) return
        _search.value = query

        SpotifyLinks.findInText(query)?.let { link ->
            importSpotifyLink(link)
            return
        }

        _section.value = AppSection.SEARCH
        searchGeneration += 1L
        val generation = searchGeneration

        searchJob?.cancel()
        closeRemoteAlbum()
        _remoteTracks.value = emptyList()
        _remoteAlbums.value = emptyList()
        _searching.value = true
        _errorMessage.value = null

        searchJob = viewModelScope.launch {
            try {
                var fastTracks: List<CatalogTrack> = emptyList()
                var fastAlbums: List<CatalogAlbum> = emptyList()

                // Songs and albums no longer wait on one another. Deezer's first
                // useful result for either category is published immediately.
                val tracksFastJob = launch {
                    fastTracks = withTimeoutOrNull(SEARCH_TRACK_FAST_TIMEOUT_MS) {
                        catalog.searchTracksFast(query, 40)
                    }.orEmpty()
                    if (fastTracks.isNotEmpty()) {
                        publishRemoteTracks(generation, fastTracks)
                    }
                }
                val albumsFastJob = launch {
                    fastAlbums = withTimeoutOrNull(SEARCH_FAST_TIMEOUT_MS) {
                        catalog.searchAlbumsFast(query, 20)
                    }.orEmpty()
                    if (fastAlbums.isNotEmpty()) {
                        publishRemoteAlbums(generation, fastAlbums)
                    }
                }
                tracksFastJob.join()
                albumsFastJob.join()

                // Always run a non-blocking merged refinement. Deezer still
                // paints the first page immediately, but NewPipe can add/replace
                // weak Deezer matches ~1-2 s later (critical for ASMR, mixes,
                // talks and niche YouTube-only content).
                val refineTracksJob = launch {
                    val refined = withTimeoutOrNull(SEARCH_TRACK_REFINE_TIMEOUT_MS) {
                        catalog.searchTracksMerged(query, 40)
                    }.orEmpty()
                    if (refined.isNotEmpty()) publishRemoteTracks(generation, refined)
                }

                val refineAlbumsJob = if (fastAlbums.isEmpty()) launch {
                    val refined = withTimeoutOrNull(SEARCH_REFINE_TIMEOUT_MS) {
                        catalog.searchAlbumsMerged(query, 20)
                    }.orEmpty()
                    if (refined.isNotEmpty()) publishRemoteAlbums(generation, refined)
                } else null

                refineTracksJob?.join()
                refineAlbumsJob?.join()

                if (generation == searchGeneration && _remoteTracks.value.isEmpty() && _remoteAlbums.value.isEmpty()) {
                    _errorMessage.value = "No se encontró metadata para \"$query\"."
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (generation == searchGeneration) {
                    _searching.value = false
                    viewModelScope.launch(Dispatchers.IO) { repository.recordSearch(query) }
                }
            }
        }
    }

    suspend fun searchYouTubePlaylists(query: String, limit: Int = 20): List<CatalogPlaylist> =
        withContext(Dispatchers.IO) {
            NewPipeStreamResolver.searchYoutubePlaylists(query, limit).map { item ->
                CatalogPlaylist(
                    id = item.id,
                    title = item.title,
                    uploaderName = item.uploader,
                    trackCount = item.streamCount,
                    coverUri = item.thumbnailUrl,
                    url = item.url
                )
            }
        }

    suspend fun loadYouTubePlaylistTracks(playlist: CatalogPlaylist, maxItems: Int = 500): List<CatalogTrack> =
        withContext(Dispatchers.IO) {
            val content = NewPipeStreamResolver.loadYoutubePlaylist(playlist.url, maxItems) ?: return@withContext emptyList()
            content.tracks.map { item ->
                CatalogTrack(
                    id = item.id,
                    title = item.title,
                    artistId = "youtube:${item.uploader}",
                    artistName = item.uploader,
                    albumId = "youtube:playlist:${playlist.id}",
                    albumTitle = playlist.title,
                    durationMs = item.durationMs,
                    coverUri = item.thumbnailUrl ?: playlist.coverUri,
                    provider = "youtube",
                    description = null,
                    url = item.url,
                    source = "YouTube",
                    sourceId = item.id,
                    contentType = com.musicvault.app.catalog.CatalogContentType.MUSIC
                )
            }
        }

    fun importYouTubePlaylist(playlist: CatalogPlaylist, tracks: List<CatalogTrack>) {
        if (tracks.isEmpty()) {
            showError("La playlist no tiene canciones disponibles para importar.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val stored = tracks.map { remote ->
                    Track(
                        id = "${remote.provider}:recording:${remote.id}",
                        title = remote.title,
                        artistName = remote.artistName,
                        albumId = remote.albumId ?: "youtube:playlist:${playlist.id}",
                        albumTitle = remote.albumTitle ?: playlist.title,
                        durationMs = remote.durationMs,
                        audioUri = null,
                        isDownloaded = false,
                        coverUri = remote.coverUri ?: playlist.coverUri,
                        source = "YouTube",
                        sourceId = remote.sourceId ?: remote.id,
                        url = remote.url
                    )
                }
                repository.replaceImportedPlaylist(
                    id = "playlist:youtube:${playlist.id}",
                    title = playlist.title,
                    description = "Importada desde YouTube · ${playlist.uploaderName}",
                    coverUri = playlist.coverUri ?: stored.firstOrNull()?.coverUri,
                    tracks = stored
                )
                showProvider("${playlist.title} se agregó a MusicVault con ${stored.size} canciones.")
            }.onFailure { failure ->
                showError(failure.message ?: "No se pudo importar la playlist de YouTube.")
            }
        }
    }

    private fun publishRemoteTracks(generation: Long, tracks: List<CatalogTrack>) {
        if (generation != searchGeneration) return
        CoverImageCache.prefetch(appContext, tracks.map { it.coverUri })
        if (generation == searchGeneration) _remoteTracks.value = tracks

        // Do not start extraction the instant a fast/refined result list lands.
        // Search can replace its first Deezer page with a merged NewPipe page a
        // moment later; waiting for the screen to settle avoids throwing away a
        // 2-4 s stream extraction. Once stable, fully resolve the first three
        // likely taps and only match the fourth. Work stays strictly sequential.
        scheduleSearchWarmWindow(
            generation = generation,
            warmCandidates = tracks.take(STREAM_WARM_WINDOW_SIZE).map(::catalogTrackForStreaming),
            delayMs = STREAM_SCREEN_IDLE_WARMUP_MS
        )
    }

    /**
     * RecyclerView calls this when Search stops scrolling. The warm window follows
     * what the user is actually reading instead of continuing to prepare rows that
     * have already disappeared off-screen. A small look-ahead covers the next tap.
     */
    fun prewarmVisibleSearchWindow(firstVisible: Int, lastVisible: Int) {
        if (_section.value != AppSection.SEARCH || firstVisible < 0) return
        val tracks = _remoteTracks.value
        if (tracks.isEmpty()) return
        val start = firstVisible.coerceIn(0, tracks.lastIndex)
        val visibleEnd = lastVisible.coerceAtLeast(start).coerceAtMost(tracks.lastIndex)
        val endExclusive = (visibleEnd + 3).coerceAtMost(tracks.size)
        val candidates = tracks.subList(start, endExclusive)
            .take(STREAM_WARM_WINDOW_SIZE)
            .map(::catalogTrackForStreaming)
        scheduleSearchWarmWindow(
            generation = searchGeneration,
            warmCandidates = candidates,
            delayMs = STREAM_SCROLL_IDLE_WARMUP_MS
        )
    }

    private fun scheduleSearchWarmWindow(
        generation: Long,
        warmCandidates: List<Track>,
        delayMs: Long
    ) {
        if (warmCandidates.isEmpty() || _preparingTrackId.value != null) return
        val warmSignature = warmCandidates.map { it.id }
        if (warmSignature == searchStreamWarmSignature && searchStreamWarmJob?.isActive == true) return
        searchStreamWarmSignature = warmSignature
        searchStreamWarmJob?.cancel()
        searchStreamWarmJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (generation != searchGeneration || _section.value != AppSection.SEARCH) return@launch
            android.util.Log.d(
                "MusicVaultStreamPerf",
                "screen-warm-search start=${warmCandidates.firstOrNull()?.id} count=${warmCandidates.size}"
            )
            warmStreamingCandidates(
                candidates = warmCandidates,
                fullResolveCount = STREAM_WARM_FULL_RESOLVE_COUNT
            ) {
                generation == searchGeneration &&
                    _section.value == AppSection.SEARCH &&
                    _preparingTrackId.value == null
            }
        }
    }

    /**
     * Warm a tiny screen-local window without turning Search/Home into bulk
     * extraction. The first [fullResolveCount] rows receive complete signed stream
     * URLs; the remaining row only caches the stable YouTube match. NewPipe work is
     * single-flight so slow phones/connections never run extractors in parallel.
     */
    private suspend fun warmStreamingCandidates(
        candidates: List<Track>,
        fullResolveCount: Int = STREAM_WARM_FULL_RESOLVE_COUNT,
        stillRelevant: () -> Boolean = { true }
    ) {
        val window = candidates.take(STREAM_WARM_WINDOW_SIZE)
        if (window.isEmpty() || !stillRelevant()) return

        suspend fun warmTrack(track: Track, full: Boolean, index: Int) {
            if (!stillRelevant()) return
            searchStreamWarmSlots.withPermit {
                if (!stillRelevant()) return@withPermit
                activeSpeculativeWarmTrackId = track.id
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    if (full) {
                        val resolved = Providers.resolveStream(
                            name = track.title,
                            author = track.artistName,
                            sourceUrl = sourceUrlFor(track),
                            cacheKey = track.id
                        )
                        if (resolved != null) {
                            android.util.Log.d(
                                "MusicVaultStreamPerf",
                                "screen-warm-ready ${android.os.SystemClock.elapsedRealtime() - started}ms index=$index key=${track.id}"
                            )
                        }
                    } else {
                        val matched = Providers.prewarmYoutubeMatch(
                            name = track.title,
                            author = track.artistName,
                            sourceUrl = sourceUrlFor(track),
                            cacheKey = track.id
                        )
                        if (matched) {
                            android.util.Log.d(
                                "MusicVaultStreamPerf",
                                "screen-warm-match ${android.os.SystemClock.elapsedRealtime() - started}ms index=$index key=${track.id}"
                            )
                        }
                    }
                } finally {
                    if (activeSpeculativeWarmTrackId == track.id) {
                        activeSpeculativeWarmTrackId = null
                    }
                }
            }
        }

        // Phase 1: make the most likely tap genuinely hot as soon as possible.
        warmTrack(window.first(), full = true, index = 0)
        if (!stillRelevant()) return

        // Phase 2: cheaply learn the YouTube IDs for the rest of the visible
        // window. This removes the ~1-1.5 s Deezer->YouTube match cost even if
        // the user taps row 2/3 before their complete URL warmup is reached.
        window.drop(1).forEachIndexed { offset, track ->
            warmTrack(track, full = false, index = offset + 1)
            if (!stillRelevant()) return
        }

        // Phase 3: spend the remaining viewing time completing rows 2 and 3.
        // Their cached matches mean this stage normally pays only NewPipe's
        // direct audio extraction (~2-3 s in the user's measured device).
        window.drop(1)
            .take((fullResolveCount - 1).coerceAtLeast(0))
            .forEachIndexed { offset, track ->
                warmTrack(track, full = true, index = offset + 1)
                if (!stillRelevant()) return
            }
    }

    private fun publishRemoteAlbums(generation: Long, albums: List<CatalogAlbum>) {
        if (generation != searchGeneration) return
        CoverImageCache.prefetch(appContext, albums.map { it.coverUri })
        if (generation == searchGeneration) _remoteAlbums.value = albums
    }

    fun selectTrack(track: Track) {
        if (!canPlayNow(track)) {
            showError("Sin conexión. Descarga la canción para escucharla sin Internet.")
            return
        }
        // User intent wins over unrelated speculative work. If this exact track
        // is already warming, keep that extraction alive so the tap joins the
        // per-track resolver lock/cache instead of throwing away several seconds
        // of NewPipe work and starting again.
        if (activeSpeculativeWarmTrackId != track.id) {
            searchStreamWarmJob?.cancel()
            searchStreamWarmJob = null
            homeStreamWarmJob?.cancel()
            homeStreamWarmJob = null
        }
        StreamingCache.markPlaybackRequested(track.id)
        clearActivePlaylistPlayback()
        explicitQueueMode = false
        val generation = ++streamSelectionGeneration

        if (track.audioUri != null && track.isDownloaded) {
            _preparingTrackId.value = null
            playReadyTrack(track)
            return
        }

        // Remote taps update the UI immediately: the mini-player switches to
        // the requested song at once and shows a loader in its play button while
        // NewPipe/yt-dlp fallback + Media3 prepare playback. Pause the previous audio so the new
        // title can never be displayed while an older song keeps sounding.
        player.pause()
        _selectedTrack.value = track
        _preparingTrackId.value = track.id

        viewModelScope.launch {
            val streamed = resolveForStreaming(track)
            // A slow previous tap must never steal playback after the user has
            // already selected a different song.
            if (generation != streamSelectionGeneration || _selectedTrack.value?.id != track.id) return@launch
            if (streamed == null) {
                _preparingTrackId.value = null
                showError("No se pudo preparar ${track.title} para streaming.")
                return@launch
            }
            playReadyTrack(streamed)
        }
    }

    private suspend fun resolveForStreaming(
        track: Track,
        prebuffer: Boolean = false,
        forceRefresh: Boolean = false
    ): Track? {
        if (track.isDownloaded && !track.audioUri.isNullOrBlank()) return track

        // Fully cached streams reopen immediately and do not invoke yt-dlp or
        // the network. CacheDataSource serves this synthetic URI entirely from
        // the stable Track.id cache key.
        StreamingCache.playbackUriIfComplete(appContext, track.id)?.let { cachedUri ->
            return track.copy(audioUri = cachedUri, isDownloaded = false)
        }

        val resolveStarted = android.os.SystemClock.elapsedRealtime()
        val resolved = withTimeoutOrNull(20_000L) {
            Providers.resolveStream(
                name = track.title,
                author = track.artistName,
                sourceUrl = sourceUrlFor(track),
                cacheKey = track.id,
                forceRefresh = forceRefresh
            )
        } ?: return null
        StreamingCache.rememberFormat(appContext, track.id, resolved.extension, resolved.mimeType)
        if (prebuffer && !StreamingCache.hasPrefetchedPrefix(appContext, track.id)) {
            StreamingCache.prefetchPrefix(appContext, track.id, resolved.url)
        }
        android.util.Log.d(
            "MusicVaultStreamPerf",
            "ready-to-media3 id=${track.id} ${android.os.SystemClock.elapsedRealtime() - resolveStarted}ms prebuffer=$prebuffer"
        )
        return track.copy(audioUri = resolved.url, isDownloaded = false)
    }

    private fun playReadyTrack(track: Track) {
        _selectedTrack.value = track
        player.play(track)
        viewModelScope.launch { repository.recordPlay(track, 0f) }
    }

    private fun sourceUrlFor(track: Track): String? = track.url ?: track.sourceId?.takeIf {
        track.source.equals("youtube", ignoreCase = true) || track.id.startsWith("youtube:", ignoreCase = true)
    }?.let { "https://www.youtube.com/watch?v=$it" }

    private fun catalogTrackForStreaming(track: CatalogTrack): Track = Track(
        id = "${track.provider}:recording:${track.id}",
        title = track.title,
        artistName = track.artistName,
        albumId = track.albumId?.let { "${track.provider}:release:$it" } ?: "unknown",
        albumTitle = track.albumTitle ?: "Álbum desconocido",
        durationMs = track.durationMs,
        coverUri = track.coverUri,
        source = track.source ?: displaySource(track),
        sourceId = track.sourceId ?: track.id,
        url = track.url
    )

    fun playRemote(track: CatalogTrack) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded && it.audioUri != null }
        if (local != null) {
            selectTrack(local)
            return
        }
        selectTrack(catalogTrackForStreaming(track))
    }

    fun playRecommendation(track: Track) {
        // Recommendations follow the same contract as every other song row:
        // local files play locally; online items resolve and stream on demand.
        selectTrack(track)
    }

    fun downloadRecommendedAlbum(album: Album) {
        val parts = album.id.split(":", limit = 3)
        if (parts.size != 3 || parts[1] != "release-group") return
        val catalogAlbum = CatalogAlbum(
            id = parts[2], title = album.title, artistId = album.artistId, artistName = album.artistName,
            year = album.year.takeIf { it > 0 }, coverUri = album.coverUri, provider = parts[0]
        )
        val albumKey = album.id
        // Mark it immediately so the button animates while album metadata is fetched.
        _albumDownloads.value = _albumDownloads.value + albumKey
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val albumTracks = catalog.tracksForAlbum(catalogAlbum, 50)
                if (albumTracks.isEmpty()) return@launch
                albumTracks.map { track ->
                    async { awaitRemoteDownload(track) }
                }.awaitAll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main.immediate) { _albumDownloads.value = _albumDownloads.value - albumKey }
            }
        }
    }

    fun downloadRemoteAlbum(album: CatalogAlbum, tracks: List<CatalogTrack> = emptyList()) {
        val albumKey = "${album.provider}:${album.id}"
        _albumDownloads.value = _albumDownloads.value + albumKey
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val albumTracks = if (tracks.isNotEmpty()) tracks else catalog.tracksForAlbum(album, 50)
                if (albumTracks.isEmpty()) return@launch
                albumTracks.map { track ->
                    async { awaitRemoteDownload(track) }
                }.awaitAll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main.immediate) { _albumDownloads.value = _albumDownloads.value - albumKey }
            }
        }
    }

    fun openRecommendedAlbum(album: Album) {
        val parts = album.id.split(":", limit = 3)
        if (parts.size == 3 && parts[1] == "release-group") {
            openRemoteAlbum(CatalogAlbum(
                id = parts[2],
                title = album.title,
                artistId = album.artistId,
                artistName = album.artistName,
                year = album.year.takeIf { it > 0 },
                coverUri = album.coverUri,
                provider = parts[0]
            ))
        } else {
            openLocalAlbum(album)
        }
    }

    fun togglePlayback() {
        // If a remote preparation failed, the mini-player still represents the
        // song the user asked for while Media3 may still hold the previous item.
        // Pressing Play should retry the visible song, never resume an unrelated
        // stale media item.
        if (_preparingTrackId.value != null) return
        val selected = _selectedTrack.value
        val controllerTrackId = player.progress.value.trackId
        if (selected != null && selected.id != controllerTrackId) {
            selectTrack(selected)
        } else {
            player.toggle()
        }
    }
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun refreshPlaybackState() = player.refreshPlaybackState()
    fun openFullPlayer() { if (_selectedTrack.value != null) _fullPlayer.value = true }
    fun closeFullPlayer() { _fullPlayer.value = false }

    fun toggleShuffle() {
        val enabled = !_shuffle.value
        _shuffle.value = enabled
        if (explicitQueueMode && activePlaylistId != null) {
            // Managed streaming playlists already define their own logical order.
            // Rebuild only the future window instead of asking Media3 to shuffle a
            // queue that intentionally contains just current + two successors.
            val currentId = player.progress.value.trackId ?: _selectedTrack.value?.id
            val selectedTracks = _selectedPlaylistTracks.value
            val current = selectedTracks.firstOrNull { it.id == currentId } ?: return
            val all = if (isOnline.value) {
                selectedTracks
            } else {
                // Keep the item that is already playing, but never rebuild the
                // future queue with remote-only songs while offline.
                selectedTracks.filter { it.id == current.id || isAvailableOffline(it) }
            }
            playlistLookAheadJob?.cancel()
            playlistLookAheadJob = null
            player.trimAfterCurrent()
            activePlaylistOrder = if (enabled) {
                listOf(current) + all.filterNot { it.id == current.id }.shuffled()
            } else {
                all
            }
            activePlaylistAppendIndex = activePlaylistOrder.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            player.setShuffle(false)
            schedulePlaylistLookAhead(current.id)
            return
        }
        player.setShuffle(enabled)
        if (!explicitQueueMode && activePlaylistId == null && enabled) {
            _selectedTrack.value?.let { viewModelScope.launch { enqueueSmartNext(it) } }
        }
    }

    fun cycleRepeatMode() = player.cycleRepeatMode()

    fun playPlaylist(playlist: Playlist, shuffle: Boolean = _shuffle.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val allTracks = repository.getPlaylistTracks(playlist.id)
            if (allTracks.isEmpty()) {
                _errorMessage.value = "Esta playlist está vacía."
                return@launch
            }
            val playableTracks = if (isOnline.value) allTracks else allTracks.filter(::isAvailableOffline)
            if (playableTracks.isEmpty()) {
                showError("Sin conexión. Esta playlist no tiene canciones descargadas.")
                return@launch
            }
            val start = if (shuffle && playableTracks.size > 1) playableTracks.random() else playableTracks.first()
            StreamingCache.markPlaybackRequested(start.id)
            startManagedPlaylistPlayback(playlist, playableTracks, start, shuffle)
        }
    }

    fun playPlaylistTrack(playlist: Playlist, track: Track, shuffle: Boolean = _shuffle.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val allTracks = repository.getPlaylistTracks(playlist.id)
            val current = allTracks.firstOrNull { it.id == track.id }
            if (current == null) {
                _errorMessage.value = "La canción ya no está disponible en esta playlist."
                return@launch
            }
            if (!canPlayNow(current)) {
                showError("Sin conexión. Descarga la canción para escucharla sin Internet.")
                return@launch
            }
            val playableTracks = if (isOnline.value) allTracks else allTracks.filter(::isAvailableOffline)
            StreamingCache.markPlaybackRequested(current.id)
            startManagedPlaylistPlayback(playlist, playableTracks, current, shuffle)
        }
    }

    private fun isAvailableOffline(track: Track): Boolean =
        track.isDownloaded && track.id in _offlinePlayableIds.value

    fun canPlayNow(track: Track): Boolean = isOnline.value || isAvailableOffline(track)

    private fun isOfflineAudioReadable(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "file" -> {
                val path = uri.path?.takeIf { it.isNotBlank() } ?: value
                java.io.File(path).let { it.isFile && it.length() > 0L }
            }
            "http", "https" -> false
            else -> runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length != 0L
                } == true
            }.getOrDefault(false)
        }
    }

    /**
     * Starts a playlist from local audio or streaming using a tiny moving window.
     * Only current + two successors are ever resolved/prebuffered at once.
     */
    private suspend fun startManagedPlaylistPlayback(
        playlist: Playlist,
        allTracks: List<Track>,
        requested: Track,
        shuffle: Boolean
    ) {
        val ready = resolveForStreaming(requested) ?: run {
            _errorMessage.value = "La canción sigue en la playlist, pero no se pudo preparar el streaming."
            return
        }
        val order = if (shuffle) {
            listOf(requested) + allTracks.filterNot { it.id == requested.id }.shuffled()
        } else {
            allTracks
        }
        val logicalIndex = order.indexOfFirst { it.id == requested.id }.coerceAtLeast(0)

        withContext(Dispatchers.Main.immediate) {
            playlistLookAheadJob?.cancel()
            activePlaylistId = playlist.id
            activePlaylistOrder = order
            activePlaylistAppendIndex = logicalIndex
            _selectedPlaylist.value = playlist
            _selectedPlaylistTracks.value = allTracks
            _shuffle.value = shuffle
            explicitQueueMode = true
            // Our logical order is already shuffled when requested. Keep Media3
            // shuffle off so dynamic append order remains deterministic.
            player.setShuffle(false)
            player.play(ready)
            _selectedTrack.value = ready
            repository.recordPlay(ready, 0f)
        }
        schedulePlaylistLookAhead(ready.id)
    }

    private fun schedulePlaylistLookAhead(currentTrackId: String? = null) {
        if (activePlaylistId == null || activePlaylistOrder.isEmpty() || !explicitQueueMode) return
        if (playlistLookAheadJob?.isActive == true) return
        val generation = queueGeneration
        playlistLookAheadJob = viewModelScope.launch(Dispatchers.IO) {
            ensurePlaylistLookAhead(currentTrackId, generation)
        }
    }

    private suspend fun ensurePlaylistLookAhead(currentTrackId: String?, generation: Long) {
        playlistLookAheadMutex.withLock {
            if (!explicitQueueMode || activePlaylistId == null || generation != queueGeneration) return
            val id = currentTrackId ?: player.progress.value.trackId ?: _selectedTrack.value?.id ?: return
            val currentLogical = activePlaylistOrder.indexOfFirst { it.id == id }
            if (currentLogical < 0) return
            if (activePlaylistAppendIndex < currentLogical) activePlaylistAppendIndex = currentLogical
            val target = (currentLogical + 2).coerceAtMost(activePlaylistOrder.lastIndex)

            while (activePlaylistAppendIndex < target && generation == queueGeneration && explicitQueueMode) {
                val nextIndex = activePlaylistAppendIndex + 1
                val candidate = activePlaylistOrder[nextIndex]
                // Resolve URL + cache a small prefix before adding it to Media3.
                // If one unavailable song fails, skip it and continue so it can
                // never dead-end a large imported playlist.
                val ready = resolveForStreaming(candidate, prebuffer = true)
                activePlaylistAppendIndex = nextIndex
                if (ready != null && generation == queueGeneration && explicitQueueMode) {
                    withContext(Dispatchers.Main.immediate) { player.append(ready) }
                }
            }
        }
    }

    private fun clearActivePlaylistPlayback() {
        playlistLookAheadJob?.cancel()
        playlistLookAheadJob = null
        activePlaylistId = null
        activePlaylistOrder = emptyList()
        activePlaylistAppendIndex = -1
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = value
        preferences.edit().putInt("crossfade_seconds", value).apply()
        player.setCrossfadeSeconds(value)
    }

    /** Persists the full-player look: "vinyl" (turntable) or "circular" (visualizer). */
    fun setPlayerStyle(style: String) {
        val value = if (style == "circular") "circular" else "vinyl"
        _playerStyle.value = value
        preferences.edit().putString("player_style", value).apply()
    }

    /** Persists the app shell look while keeping the previous design available. */
    fun setAppDesign(style: String) {
        val value = if (style == APP_DESIGN_CLASSIC) APP_DESIGN_CLASSIC else APP_DESIGN_HYBRID
        _appDesign.value = value
        preferences.edit().putString("app_design", value).apply()
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowFavorite = repository.toggleFavorite(track.id)
            if (nowFavorite) {
                repository.addTrackToPlaylist(favoritesAlbumId, track)
            } else {
                repository.removeTrackFromPlaylist(favoritesAlbumId, track.id)
            }
        }
    }

    fun isFavorite(trackId: String): Boolean = state.value.favoriteIds.contains(trackId)

    fun addToQueue(track: Track) {
        if (!canPlayNow(track)) {
            showError("Sin conexión. Descarga la canción antes de agregarla a la cola.")
            return
        }
        if (track.isDownloaded && !track.audioUri.isNullOrBlank()) {
            activateExplicitQueueMode()
            pendingQueueEntries += PendingQueueEntry(ready = true, track = track)
            flushPendingQueue()
            return
        }

        // Queueing an online song must not silently turn into a permanent
        // download. Resolve the temporary stream and append that instead.
        activateExplicitQueueMode()
        val generation = queueGeneration
        val entry = PendingQueueEntry()
        pendingQueueEntries += entry
        viewModelScope.launch {
            val streamed = resolveForStreaming(track)
            withContext(Dispatchers.Main.immediate) {
                if (generation == queueGeneration) {
                    entry.ready = true
                    entry.track = streamed
                    flushPendingQueue()
                }
            }
        }
    }

    fun addRemoteTrackToQueue(track: CatalogTrack) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded && it.audioUri != null }
        if (local != null) {
            addToQueue(local)
            return
        }
        addToQueue(catalogTrackForStreaming(track))
    }

    private fun flushPendingQueue() {
        while (pendingQueueEntries.firstOrNull()?.ready == true) {
            pendingQueueEntries.removeAt(0).track?.let { player.append(it) }
        }
    }

    private fun activateExplicitQueueMode() {
        if (explicitQueueMode) return
        explicitQueueMode = true
        val currentIndex = queueIndex.value
        if (currentIndex < 0) return
        val tail = queue.value.lastIndex
        for (index in tail downTo currentIndex + 1) {
            player.removeQueueItem(index)
        }
    }

    fun removeFromQueue(index: Int) = player.removeQueueItem(index)

    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)

    fun setEqualizerEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("eq_enabled", enabled).apply()
        _eqEnabled.value = enabled
        equalizer.setEnabled(enabled)
    }

    fun setEqualizerBand(index: Int, level: Short) {
        equalizer.setBandLevel(index, level)
        _equalizerBands.value = equalizer.bands()
        // Any manual touch is a custom curve: stop pretending we are still on
        // a curated preset and persist the transition so it survives a restart.
        if (_eqPreset.value != EqualizerPreset.Custom) {
            _eqPreset.value = EqualizerPreset.Custom
            preferences.edit().putString("eq_preset", EqualizerPreset.Custom.name).apply()
        }
    }

    /**
     * Applies a curated preset: writes its band levels into the live effect,
     * drives BassBoost/Virtualizer strengths and stores the selection. The
     * master gain (LoudnessEnhancer) is left untouched so the volume-boost
     * slider keeps its own independent value.
     */
    fun applyEqualizerPreset(preset: EqualizerPreset) {
        _eqPreset.value = preset
        preferences.edit().putString("eq_preset", preset.name).apply()
        val bands = _equalizerBands.value
        if (bands.isEmpty()) return
        val target = preset.project(bands.first().minLevel, bands.first().maxLevel)
        target.forEachIndexed { i, level ->
            equalizer.setBandLevel(i, level)
        }
        equalizer.setBassBoostStrength(preset.bassBoost)
        equalizer.setVirtualizerStrength(preset.virtualizer)
        _equalizerBands.value = equalizer.bands()
    }

    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 3000)
        _loudnessGainMb.value = clamped
        preferences.edit().putInt("loudness_gain_mb", clamped).apply()
        equalizer.setLoudnessGain(clamped)
    }

    /** Mirror of [applyEqualizerPreset] for direct slider control over BassBoost. */
    fun setBassBoostStrength(strength: Int) {
        equalizer.setBassBoostStrength(strength)
    }

    /** Mirror of [applyEqualizerPreset] for direct slider control over Virtualizer. */
    fun setVirtualizerStrength(strength: Int) {
        equalizer.setVirtualizerStrength(strength)
    }

    fun nextTrack() {
        val current = _selectedTrack.value ?: return

        // The explicit player queue always has priority over album/library
        // navigation. Once the user has built a queue, Next must stay inside
        // that queue and must never fall back to the device library.
        if (explicitQueueMode && queue.value.isNotEmpty()) {
            player.next()
            return
        }
        viewModelScope.launch {
            val localAlbumTracks = when {
                _selectedLocalAlbum.value != null -> _selectedLocalAlbumTracks.value
                current.albumId.startsWith("album:custom:") -> repository.getAlbumTracks(current.albumId)
                else -> emptyList()
            }
            val localRemaining = localAlbumTracks.filter { it.id != current.id && it.isDownloaded && it.audioUri != null }
            if (localRemaining.isNotEmpty()) {
                val currentIndex = localAlbumTracks.indexOfFirst { it.id == current.id }
                val orderedNext = if (currentIndex >= 0) {
                    localAlbumTracks.drop(currentIndex + 1).filter { it.isDownloaded && it.audioUri != null }
                } else emptyList()
                val next = if (_shuffle.value) localRemaining.random() else orderedNext.firstOrNull() ?: localRemaining.first()
                selectTrack(next)
                return@launch
            }

            val remoteAlbum = _selectedRemoteAlbum.value
            if (remoteAlbum != null && _remoteAlbumTracks.value.isNotEmpty()) {
                val index = _remoteAlbumTracks.value.indexOfFirst { "${it.provider}:recording:${it.id}" == current.id }
                val remaining = if (index >= 0) _remoteAlbumTracks.value.drop(index + 1) else _remoteAlbumTracks.value
                val nextRemote = if (_shuffle.value && remaining.isNotEmpty()) remaining.randomOrNull() else remaining.firstOrNull()
                if (nextRemote != null) {
                    selectTrack(catalogTrackForStreaming(nextRemote))
                    return@launch
                }
            }

            val hasAlbumContext = _selectedLocalAlbum.value != null || _selectedRemoteAlbum.value != null || current.albumId.startsWith("album:custom:")
            if (hasAlbumContext && isOnline.value) {
                findAndDownloadRelated(current)
                return@launch
            }

            val downloaded = state.value.downloaded.filter { it.id != current.id && it.audioUri != null }
            val next = when {
                downloaded.isEmpty() -> null
                _shuffle.value -> downloaded.random()
                else -> {
                    val currentIndex = state.value.downloaded.indexOfFirst { it.id == current.id }
                    state.value.downloaded.getOrNull(currentIndex + 1)?.takeIf { it.audioUri != null } ?: downloaded.first()
                }
            }
            if (next != null) {
                selectTrack(next)
            } else {
                findAndDownloadRelated(current)
            }
        }
    }

    fun previousTrack() {
        val current = _selectedTrack.value ?: return
        // Keep Previous inside the explicit queue too.
        if (explicitQueueMode && queue.value.isNotEmpty()) {
            player.previous()
            return
        }
        val list = state.value.downloaded.filter { it.audioUri != null }
        val index = list.indexOfFirst { it.id == current.id }
        if (index > 0) selectTrack(list[index - 1]) else player.previous()
    }

    private suspend fun enqueueSmartNext(current: Track) {
        // Smart-next is only allowed for an actively selected track.
        // A manual download must never start downloading neighboring album tracks.
        if (_selectedTrack.value?.id != current.id) return
        appendSmartSuccessor(current)
    }

    private suspend fun appendSmartSuccessor(current: Track) {
        if (explicitQueueMode) return
        val generation = queueGeneration
        val localAlbumTracks = when {
            _selectedLocalAlbum.value != null -> _selectedLocalAlbumTracks.value
            current.albumId.startsWith("album:custom:") -> repository.getAlbumTracks(current.albumId)
            else -> emptyList()
        }
        val localCandidates = localAlbumTracks.filter { it.id != current.id && it.isDownloaded && it.audioUri != null }
        if (localCandidates.isNotEmpty()) {
            val currentIndex = localAlbumTracks.indexOfFirst { it.id == current.id }
            val orderedNext = if (currentIndex >= 0) {
                localAlbumTracks.drop(currentIndex + 1).filter { it.isDownloaded && it.audioUri != null }
            } else emptyList()
            val next = if (_shuffle.value) localCandidates.random() else orderedNext.firstOrNull() ?: localCandidates.first()
            appendSmartCandidate(current, next, generation)
            return
        }

        val remoteAlbum = _selectedRemoteAlbum.value
        if (remoteAlbum != null && _remoteAlbumTracks.value.isNotEmpty()) {
            val index = _remoteAlbumTracks.value.indexOfFirst { "${it.provider}:recording:${it.id}" == current.id }
            val remaining = if (index >= 0) _remoteAlbumTracks.value.drop(index + 1) else _remoteAlbumTracks.value
            val nextRemote = if (_shuffle.value && remaining.isNotEmpty()) remaining.randomOrNull() else remaining.firstOrNull()
            if (nextRemote != null) {
                val streamed = resolveForStreaming(catalogTrackForStreaming(nextRemote), prebuffer = true)
                streamed?.let { appendSmartCandidate(current, it, generation) }
                return
            }
        }

        val pool = state.value.downloaded.filter { it.audioUri != null && it.id != current.id }
        if (pool.isNotEmpty()) {
            val next = if (_shuffle.value) {
                pool.random()
            } else {
                // Continue through the library in listing order, wrapping around,
                // instead of always repeating the alphabetically first track.
                val ordered = state.value.downloaded.filter { it.audioUri != null }
                val currentIndex = ordered.indexOfFirst { it.id == current.id }
                ordered.getOrNull(currentIndex + 1) ?: pool.first()
            }
            appendSmartCandidate(current, next, generation)
        } else if (_shuffle.value) {
            findAndDownloadRelated(current, generation)
        }
    }

    private suspend fun appendSmartCandidate(current: Track, candidate: Track, generation: Long) {
        withContext(Dispatchers.Main.immediate) {
            if (!explicitQueueMode && generation == queueGeneration) {
                player.append(candidate, afterTrackId = current.id)
            }
        }
    }

    private suspend fun findAndDownloadRelated(current: Track, generation: Long = queueGeneration) {
        val localCandidates = state.value.downloaded.filter { it.id != current.id && it.audioUri != null }
        if (!isOnline.value) {
            localCandidates.randomOrNull()?.let { appendSmartCandidate(current, it, generation) }
            return
        }
        if (!Providers.isInitialized()) return
        try {
            val results = catalog.searchTracks(current.artistName, 12)
            val existingIds = state.value.tracks.mapTo(HashSet()) { it.id }
            val candidate = results.firstOrNull {
                it.title.isNotBlank() && "${it.provider}:recording:${it.id}" !in existingIds
            }
            if (candidate != null) {
                val streamed = resolveForStreaming(catalogTrackForStreaming(candidate), prebuffer = true)
                streamed?.let { appendSmartCandidate(current, it, generation) }
            } else {
                localCandidates.randomOrNull()?.let { appendSmartCandidate(current, it, generation) }
            }
        } catch (_: Exception) {
            localCandidates.randomOrNull()?.let { appendSmartCandidate(current, it, generation) }
        }
    }

    fun openLocalAlbum(album: Album) {
        // Album detail, playlist detail and remote album detail are mutually exclusive
        // overlays. Close any previous detail before opening this one.
        closePlaylist()
        closeRemoteAlbum()
        _selectedLocalAlbum.value = album
        albumJob?.cancel()
        albumJob = viewModelScope.launch {
            database.albumTrackDao().observeTracks(album.id).collect { entities ->
                _selectedLocalAlbumTracks.value = entities.map { Track(it.id, it.title, it.artistName, it.albumId, it.albumTitle, it.durationMs, it.audioUri, it.isDownloaded, it.coverUri) }
            }
        }
    }

    fun closeLocalAlbum() {
        _selectedLocalAlbum.value = null
        _selectedLocalAlbumTracks.value = emptyList()
        albumJob?.cancel()
    }

    fun createPlaylist(title: String, tracks: List<Track>) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = "playlist:custom:${java.util.UUID.randomUUID()}"
            repository.createPlaylist(
                id = id,
                title = title.trim(),
                description = "Creada por ti",
                coverUri = tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri
            )
            if (tracks.isNotEmpty()) repository.addTracksToPlaylist(id, tracks)
        }
    }

    // User-created "albums" are stored using the playlist tables. This keeps the
    // existing database compatible while making Albums the only user collection UI.
    fun createAlbum(title: String, tracks: List<Track>) {
        createPlaylist(title, tracks)
    }

    fun createAlbumFromRemote(title: String, track: CatalogTrack) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = "playlist:custom:${java.util.UUID.randomUUID()}"
            repository.createPlaylist(id, title.trim(), "Álbum creado por ti")
            addRemoteTrackToPlaylist(track, id)
        }
    }

    fun openRemoteAlbum(album: CatalogAlbum) {
        // Only one detail overlay may be visible at a time.
        closeLocalAlbum()
        closePlaylist()
        remoteAlbumJob?.cancel()

        val cacheKey = "${album.provider}:${album.id}"
        _selectedRemoteAlbum.value = album

        // Re-open immediately from memory when possible. This also prevents a
        // second network response from landing while the user is already
        // scrolling the album.
        remoteAlbumTrackCache[cacheKey]?.let { cached ->
            _remoteAlbumTracks.value = cached
            _loadingRemoteAlbum.value = false
            return
        }

        _remoteAlbumTracks.value = emptyList()
        _loadingRemoteAlbum.value = true
        playlistJob?.cancel()
        searchJob?.cancel()
        remoteAlbumJob = viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { catalog.tracksForAlbum(album, 50) }
                // Ignore a late response if the user already opened another album.
                val current = _selectedRemoteAlbum.value
                if (current?.id != album.id || current.provider != album.provider) return@launch
                if (tracks.isNotEmpty()) remoteAlbumTrackCache[cacheKey] = tracks
                _remoteAlbumTracks.value = tracks
                if (tracks.isEmpty()) {
                    _errorMessage.value = "No se pudieron obtener las canciones de este álbum."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = _selectedRemoteAlbum.value
                if (current?.id == album.id && current.provider == album.provider) {
                    _errorMessage.value = "No se pudo abrir el álbum: ${e.message ?: "error de red"}"
                }
            } finally {
                val current = _selectedRemoteAlbum.value
                if (current?.id == album.id && current.provider == album.provider) {
                    _loadingRemoteAlbum.value = false
                }
            }
        }
    }

    fun closeRemoteAlbum() {
        remoteAlbumJob?.cancel()
        remoteAlbumJob = null
        _selectedRemoteAlbum.value = null
        _remoteAlbumTracks.value = emptyList()
        _loadingRemoteAlbum.value = false
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        playlistJob = null
        _selectedPlaylist.value = null
        _selectedPlaylistTracks.value = emptyList()
    }

    fun openPlaylist(playlist: Playlist) {
        // Prevent an album detail overlay from remaining underneath the playlist
        // detail (especially noticeable when opening Favoritos from Álbumes).
        closeLocalAlbum()
        closeRemoteAlbum()
        playlistJob?.cancel()
        playlistJob = null
        // Never render a newly selected title with rows retained from the
        // previously observed playlist while Room prepares its first emission.
        _selectedPlaylistTracks.value = emptyList()
        _selectedPlaylist.value = playlist
        playlistJob = viewModelScope.launch {
            repository.playlistTracks(playlist.id).collect { tracks ->
                if (_selectedPlaylist.value?.id == playlist.id) {
                    _selectedPlaylistTracks.value = tracks
                }
            }
        }
    }

    fun deleteTrack(track: Track) {
        if (!track.isDownloaded) return
        if (_selectedTrack.value?.id == track.id) {
            player.clearQueue()
            _selectedTrack.value = null
            _fullPlayer.value = false
            activePlaylistId = null
            explicitQueueMode = false
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val uri = track.audioUri?.let(android.net.Uri::parse)
                if (uri != null) {
                    if (uri.scheme == "content") {
                        if (uri.authority != "${appContext.packageName}.fileprovider") {
                            // MediaStore or SAF Uri (e.g. local scanned audio or custom download tree)
                            runCatching { appContext.contentResolver.delete(uri, null, null) }
                        } else {
                            val fileName = uri.lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            if (!fileName.isNullOrBlank()) {
                                val root = java.io.File(appContext.getExternalFilesDir("Music") ?: appContext.filesDir, "downloads")
                                root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }?.delete()
                            }
                        }
                    } else if (uri.scheme == "file") {
                        uri.path?.let { java.io.File(it).delete() }
                    }
                }
            }
            // Local scanner tracks (id LIKE 'local:%') keep the row around
            // marked as userRemoved so the next MediaStore scan cannot resurrect
            // them. The physical file may still be on the device when
            // ContentResolver.delete was blocked by storage permissions.
            if (track.id.startsWith("local:")) {
                repository.markTrackRemoved(track.id)
            } else {
                repository.deleteTrack(track.id)
            }
        }
    }

    fun deleteDownloadedTrack(id: String) {
        state.value.tracks.firstOrNull { it.id == id }?.let(::deleteTrack)
    }

    fun clearSelection() {
        activePlaylistId = null
        explicitQueueMode = false
        _selectedTrack.value = null
        _selectedPlaylist.value = null
        _selectedPlaylistTracks.value = emptyList()
        closeLocalAlbum()
        _fullPlayer.value = false
        playlistJob?.cancel()
        closeRemoteAlbum()
    }

    fun scanLocalAudio(context: Context) {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val scanned = LocalAudioScanner(context.contentResolver).scan()
                repository.syncScannedTracks(scanned)
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo escanear el almacenamiento."
            } finally {
                _scanning.value = false
            }
        }
    }

    fun requestDownload(track: Track) {
        requestDownloadInternal(track.id, track.title, track.artistName, sourceUrlFor(track)) { result ->
            if (result is ProviderResult.Success) {
                repository.setDownloaded(track, result.audioUri)
                persistDownloadedLyrics(
                    track = track,
                    localReference = result.localPath ?: result.audioUri
                )
            }
        }
    }

    fun downloadPlaylist(playlist: Playlist) {
        val playlistKey = playlist.id
        if (playlistKey in _albumDownloads.value) return
        _albumDownloads.value = _albumDownloads.value + playlistKey
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = repository.getPlaylistTracks(playlist.id)
                    .filterNot { it.isDownloaded && !it.audioUri.isNullOrBlank() }
                if (pending.isEmpty()) {
                    showProvider("La playlist ya está descargada.")
                    return@launch
                }

                // Do not launch hundreds of foreground-service requests at once.
                // Large Spotify imports are intentionally processed in small
                // batches; each song receives transient retries before being
                // left pending for a later manual retry.
                var completed = 0
                pending.chunked(BULK_DOWNLOAD_CONCURRENCY).forEach { batch ->
                    completed += batch.map { track ->
                        async { awaitTrackDownloadWithRetry(track) }
                    }.awaitAll().count { it }
                }

                val failed = pending.size - completed
                when {
                    failed == 0 -> showProvider("$completed canciones descargadas de ${playlist.title}.")
                    completed > 0 -> showProvider("$completed descargadas; $failed quedaron pendientes. Puedes tocar Descargar otra vez para reintentarlas.")
                    else -> showError("No se pudo completar la descarga. ${pending.size} canciones quedaron pendientes para reintentar.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Exception) {
                showError(failure.message ?: "No se pudo descargar la playlist.")
            } finally {
                _albumDownloads.value = _albumDownloads.value - playlistKey
            }
        }
    }

    private suspend fun awaitTrackDownloadWithRetry(track: Track): Boolean {
        synchronized(downloadLock) { quietDownloadIds += track.id }
        return try {
            repeat(BULK_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
                when (val result = awaitTrackDownloadResult(track)) {
                    is ProviderResult.Success -> if (result.audioUri.isNotBlank()) return true
                    is ProviderResult.Unavailable -> return false
                    is ProviderResult.Error, null -> Unit
                }
                if (attempt < BULK_DOWNLOAD_MAX_ATTEMPTS - 1) {
                    delay(BULK_DOWNLOAD_RETRY_BASE_DELAY_MS * (attempt + 1L))
                }
            }
            false
        } finally {
            synchronized(downloadLock) { quietDownloadIds -= track.id }
        }
    }

    private suspend fun awaitTrackDownloadResult(track: Track): ProviderResult? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            requestDownloadInternal(track.id, track.title, track.artistName, sourceUrlFor(track)) { result ->
                if (result is ProviderResult.Success && result.audioUri.isNotBlank()) {
                    repository.setDownloaded(track, result.audioUri)
                    persistDownloadedLyrics(
                        track = track,
                        localReference = result.localPath ?: result.audioUri
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }

    fun requestRemoteDownload(track: CatalogTrack) = requestRemoteDownload(track, autoPlay = false)

    private suspend fun awaitRemoteDownload(track: CatalogTrack): Track? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            requestRemoteDownload(
                track = track,
                autoPlay = false,
                onFinished = { ready ->
                    if (continuation.isActive) continuation.resume(ready)
                }
            )
        }

    private fun requestRemoteDownload(
        track: CatalogTrack,
        autoPlay: Boolean,
        onReady: (suspend (Track) -> Unit)? = null,
        onFinished: (suspend (Track?) -> Unit)? = null
    ) {
        val id = "${track.provider}:recording:${track.id}"
        track.coverUri?.let { CoverImageCache.prefetch(appContext, listOf(it)) }
        val existing = state.value.tracks.firstOrNull { it.id == id && it.isDownloaded }
        if (existing != null) {
            viewModelScope.launch {
                onReady?.invoke(existing)
                onFinished?.invoke(existing)
                if (autoPlay) selectTrack(existing)
            }
            return
        }
        requestDownloadInternal(id, track.title, track.artistName, track.url) { result ->
            if (result is ProviderResult.Success) {
                val entity = TrackEntity(id, track.title, track.artistName,
                    track.albumId?.let { "${track.provider}:release:$it" } ?: "unknown",
                    track.albumTitle ?: "Álbum desconocido", track.durationMs, result.audioUri, true, track.coverUri,
                    source = track.source ?: displaySource(track),
                    sourceId = track.sourceId ?: track.id
                )
                database.trackDao().upsertAll(listOf(entity))
                track.albumId?.let { albumId -> database.albumDao().upsertAll(listOf(AlbumEntity(
                    "${track.provider}:release:$albumId", track.albumTitle ?: "Álbum desconocido", track.artistId, track.artistName, 0, track.coverUri))) }
                val local = Track(
                    id, track.title, track.artistName, entity.albumId, entity.albumTitle,
                    track.durationMs, result.audioUri, true, track.coverUri,
                    source = track.source ?: displaySource(track),
                    sourceId = track.sourceId ?: track.id
                )
                persistDownloadedLyrics(
                    track = local,
                    localReference = result.localPath ?: result.audioUri
                )
                onReady?.invoke(local)
                onFinished?.invoke(local)
                if (autoPlay) {
                    _selectedTrack.value = local
                    player.play(local)
                    repository.recordPlay(local, 0f)
                    enqueueSmartNext(local)
                }
            } else {
                onFinished?.invoke(null)
            }
        }
    }

    private suspend fun persistTrackReference(track: Track): Track {
        val current = database.trackDao().get(track.id)
        if (current?.isDownloaded == true && !current.audioUri.isNullOrBlank()) {
            return track.copy(audioUri = current.audioUri, isDownloaded = true)
        }
        database.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    albumId = track.albumId,
                    albumTitle = track.albumTitle,
                    durationMs = track.durationMs,
                    audioUri = null,
                    isDownloaded = false,
                    coverUri = track.coverUri,
                    source = track.source,
                    sourceId = track.sourceId
                )
            )
        )
        return track.copy(audioUri = null, isDownloaded = false)
    }

    fun addTrackToPlaylist(track: Track, playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = persistTrackReference(track)
            repository.addTrackToPlaylist(playlistId, stored)
        }
    }

    fun addRemoteTrackToPlaylist(track: CatalogTrack, playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = persistTrackReference(catalogTrackForStreaming(track))
            repository.addTrackToPlaylist(playlistId, stored)
        }
    }

    fun addTrackToAlbum(track: Track, albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = persistTrackReference(track)
            repository.addTrackToAlbum(albumId, stored)
        }
    }

    fun addRemoteTrackToAlbum(track: CatalogTrack, albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = persistTrackReference(catalogTrackForStreaming(track))
            repository.addTrackToAlbum(albumId, stored)
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteAlbum(albumId) }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId == favoritesAlbumId) {
            _providerMessage.value = "El álbum Favoritos no se puede eliminar."
            return
        }
        viewModelScope.launch(Dispatchers.IO) { repository.deletePlaylist(playlistId) }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        if (playlistId == favoritesAlbumId) {
            viewModelScope.launch(Dispatchers.IO) { repository.removeTrackFromPlaylist(playlistId, trackId) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    private fun requestDownloadInternal(
        id: String,
        name: String,
        author: String,
        sourceUrl: String? = null,
        onSuccess: suspend (ProviderResult?) -> Unit
    ) {
        if (!StoragePermissions.canWriteLegacy(appContext)) {
            val reason = StoragePermissions.explainWhyCannotWrite(appContext)
                ?: "Sin permiso de almacenamiento."
            showError(reason)
            viewModelScope.launch { onSuccess(null) }
            return
        }

        val shouldStart = synchronized(downloadLock) {
            downloadWaiters.getOrPut(id) { mutableListOf() }.add(onSuccess)
            if (_downloadProgress.value.containsKey(id)) return@synchronized false
            _downloadProgress.value = _downloadProgress.value + (id to 0)
            true
        }
        if (!shouldStart) return

        // Create the lifecycle handle before starting the service. This closes
        // the tiny race where a very fast failure could broadcast its result
        // before downloadJobs had been populated.
        val job = viewModelScope.launch {
            kotlinx.coroutines.awaitCancellation()
        }
        synchronized(downloadLock) { downloadJobs[id] = job }

        val intent = Intent(DownloadForegroundService.ACTION_DOWNLOAD).apply {
            putExtra(DownloadForegroundService.EXTRA_ID, id)
            putExtra(DownloadForegroundService.EXTRA_NAME, name)
            putExtra(DownloadForegroundService.EXTRA_AUTHOR, author)
            putExtra(DownloadForegroundService.EXTRA_SOURCE_URL, sourceUrl)
        }
        try {
            DownloadForegroundService.start(appContext, intent)
        } catch (_: Exception) {
            synchronized(downloadLock) {
                downloadWaiters.remove(id)
                downloadJobs.remove(id)?.cancel()
            }
            _downloadProgress.value = _downloadProgress.value - id
            viewModelScope.launch { onSuccess(null) }
        }
    }

    private fun handleDownloadServiceResult(intent: Intent) {
        val id = intent.getStringExtra(DownloadForegroundService.EXTRA_ID) ?: return
        val status = intent.getStringExtra(DownloadForegroundService.EXTRA_STATUS) ?: return
        val result: ProviderResult = when (status) {
            DownloadForegroundService.STATUS_SUCCESS -> ProviderResult.Success(
                audioUri = intent.getStringExtra(DownloadForegroundService.EXTRA_AUDIO_URI).orEmpty(),
                fileName = intent.getStringExtra(DownloadForegroundService.EXTRA_FILE_NAME).orEmpty(),
                mimeType = intent.getStringExtra(DownloadForegroundService.EXTRA_MIME),
                localPath = intent.getStringExtra(DownloadForegroundService.EXTRA_LOCAL_PATH)
            )
            DownloadForegroundService.STATUS_UNAVAILABLE -> ProviderResult.Unavailable(
                intent.getStringExtra(DownloadForegroundService.EXTRA_MESSAGE) ?: "El audio no está disponible."
            )
            DownloadForegroundService.STATUS_CANCELLED -> ProviderResult.Error(
                intent.getStringExtra(DownloadForegroundService.EXTRA_MESSAGE) ?: "Descarga cancelada"
            )
            else -> ProviderResult.Error(
                intent.getStringExtra(DownloadForegroundService.EXTRA_MESSAGE)
                    ?: "La descarga terminó con un error desconocido."
            )
        }

        if (result is ProviderResult.Success) {
            _downloadProgress.value = _downloadProgress.value + (id to 100)
        }

        val waiters = synchronized(downloadLock) { downloadWaiters.remove(id).orEmpty().toList() }
        val quietFailure = synchronized(downloadLock) { id in quietDownloadIds }
        viewModelScope.launch(Dispatchers.IO) {
            waiters.forEach { callback -> runCatching { callback(result) } }
            if (!quietFailure) {
                when (result) {
                    is ProviderResult.Unavailable -> showError(result.reason)
                    is ProviderResult.Error -> showError(result.message)
                    is ProviderResult.Success -> Unit
                }
            }
            _downloadProgress.value = _downloadProgress.value - id
            synchronized(downloadLock) { downloadJobs.remove(id) }
        }
    }

    fun cancelDownload(id: String) {
        synchronized(downloadLock) { downloadJobs.remove(id)?.cancel() }
        DownloadForegroundService.cancel(appContext, id)
        _downloadProgress.value = _downloadProgress.value - id
    }

    fun clearProviderMessage() {
        providerAutoClearJob?.cancel()
        providerAutoClearJob = null
        _providerMessage.value = null
    }
    fun clearError() {
        errorAutoClearJob?.cancel()
        errorAutoClearJob = null
        _errorMessage.value = null
    }

    private fun showError(message: String) {
        // Errors stay visible until the user dismisses them. Five seconds was
        // too short for Huawei/EMUI failures and made it look as if nothing
        // had happened.
        errorAutoClearJob?.cancel()
        errorAutoClearJob = null
        _errorMessage.value = message
        _providerMessage.value = null
    }

    private fun showProvider(message: String) {
        providerAutoClearJob?.cancel()
        _providerMessage.value = message
        providerAutoClearJob = viewModelScope.launch {
            delay(messageDisplayMs)
            _providerMessage.value = null
            providerAutoClearJob = null
        }
    }

    /**
     * Llamado por MainActivity cuando el usuario responde al diálogo de
     * permisos de almacenamiento.
     *
     * - Si granted=true: se resetea el contador de denegaciones para que en
     *   futuras invocaciones se vuelva a consultar al sistema.
     * - Si granted=false: incrementa el contador. Tras dos denegaciones el
     *   ViewModel asume que el sistema ya no mostrará el diálogo (la app
     *   queda en estado "denied permanently") y MainActivity deja de lanzar
     *   el diálogo para no spamear al usuario.
     */
    fun onStoragePermissionResult(granted: Boolean, totalRequested: Int, denied: Int) {
        storagePermissionPrompted = true
        if (granted) {
            storagePermissionDeniedCount = 0
        } else if (denied > 0) {
            storagePermissionDeniedCount += denied
        }
    }

    /**
     * Re-sincroniza el estado interno con la realidad del sistema. Llamado
     * desde MainActivity.onResume() para detectar si el usuario habilitó el
     * permiso manualmente desde Ajustes.
     */
    fun refreshStoragePermissionState() {
        if (StoragePermissions.hasAll(appContext)) {
            storagePermissionDeniedCount = 0
            storagePermissionPrompted = true
        }
    }

    /**
     * Devuelve true si el ViewModel considera que aún vale la pena volver a
     * mostrar el diálogo de permisos. MainActivity lo consulta antes de
     * relanzar el launcher.
     */
    fun shouldPromptForStoragePermissions(): Boolean {
        if (StoragePermissions.hasAll(appContext)) return false
        // Android deja de mostrar el diálogo tras dos denegaciones. A partir
        // de ahí, seguir lanzándolo no tiene efecto y solo incomoda.
        return storagePermissionDeniedCount < 2
    }

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(downloadReceiver) }
        connectivityMonitor.close()
        synchronized(downloadLock) {
            // Do NOT cancel the foreground service here. Its purpose is to keep
            // downloads alive when the Activity/ViewModel is recreated or the
            // user leaves the UI.
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            downloadWaiters.clear()
        }
        searchJob?.cancel(); playlistJob?.cancel(); albumJob?.cancel(); remoteAlbumJob?.cancel(); historyJob?.cancel()
        equalizer.release()
        player.release()
        super.onCleared()
    }

    class Factory(private val context: Context, private val database: MusicDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MusicVaultViewModel::class.java)) {
                return MusicVaultViewModel(context.applicationContext, database) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    private suspend fun persistDownloadedLyrics(track: Track, localReference: String?) {
        val reference = localReference?.takeIf { it.isNotBlank() } ?: return
        val existing = LyricsLocalStore.read(appContext, reference)
        if (existing != null) return

        // The YouTube caption and LRCLib requests race; captions use the exact
        // video timeline and the first valid result wins without extending wait.
        val result = fastestLyrics(track, reference) ?: return
        LyricsLocalStore.write(appContext, reference, result)
    }

    private fun displaySource(track: CatalogTrack): String = track.source
        ?: when (track.provider) {
            YouTubeMetadataProvider.PROVIDER_ID -> YouTubeMetadataProvider.SOURCE_NAME
            "deezer" -> "Deezer"
            "musicbrainz" -> "MusicBrainz"
            else -> track.provider
        }

    private fun displaySource(track: Track): String = track.source
        ?: when {
            track.sourceId != null && track.sourceId.length == 11 -> YouTubeMetadataProvider.SOURCE_NAME
            else -> "MusicVault"
        }
}
