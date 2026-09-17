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
import com.musicvault.app.permissions.StoragePermissions
import com.musicvault.app.player.AudioEffects
import com.musicvault.app.player.EqualizerPreset
import com.musicvault.app.player.PlayerController
import com.musicvault.app.providers.ProviderResult
import com.musicvault.app.providers.Providers
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
import kotlinx.coroutines.flow.*
import com.musicvault.app.player.AudioSessionBridge

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

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
    private val equalizer = AudioEffects()
    private val preferences: SharedPreferences = appContext.getSharedPreferences("musicvault.settings", Context.MODE_PRIVATE)
    private val spotifySession = SpotifySessionStore(appContext)
    private val spotifyClient = SpotifySongMirrorClient()

    private val _section = MutableStateFlow(AppSection.HOME)
    private val _search = MutableStateFlow("")
    private val _selectedTrack = MutableStateFlow<Track?>(null)
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
        const val SEARCH_MERGE_TIMEOUT_MS = 1_200L
    }
    private var playlistJob: Job? = null
    private var activePlaylistId: String? = null
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
    private var historyJob: Job? = null
    private var lastHistoryUpdateAt = 0L
    // Cached resolution of the currently playing track for the history writer.
    private var historyTrackId: String? = null
    private var historyTrack: Track? = null
    private val whitespaceRegex = Regex("\\s+")

    private val localTracks = repository.tracks()
    private val downloaded = repository.downloaded()
    private val allCatalogAlbums = repository.albums()
    private val cachedCatalogTracks = catalog.cachedTracks()
    private val albums = allCatalogAlbums.map { list -> list.filter { it.id.startsWith("album:custom:") } }
    private val playlists = repository.playlists()

    val isPlaying: StateFlow<Boolean> = player.isPlaying
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

    fun loadLyrics(track: Track) {
        if (_lyrics.value.trackId == track.id && (_lyrics.value.loading || _lyrics.value.notFound ||
                _lyrics.value.plain != null || _lyrics.value.synced.isNotEmpty())
        ) return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            val localReference = database.downloadRecordDao().get(track.id)?.path ?: track.audioUri
            val local = LyricsLocalStore.read(appContext, localReference)
            val state = when {
                local != null && !local.isEmpty -> LyricsUiState(
                    trackId = track.id,
                    plain = local.plain,
                    synced = local.synced
                )
                lyricsCache[track.id] != null -> lyricsCache[track.id]!!
                else -> {
                    _lyrics.value = LyricsUiState(trackId = track.id, loading = true)
                    val result = runCatching {
                        LyricsProvider.fetch(
                            artist = track.artistName,
                            title = track.title,
                            album = track.albumTitle.takeIf { it.isNotBlank() && !it.equals("Álbum desconocido", ignoreCase = true) },
                            durationMs = track.durationMs
                        )
                    }.getOrNull()
                    when {
                        result == null || result.isEmpty -> LyricsUiState(trackId = track.id, notFound = true)
                        else -> LyricsUiState(trackId = track.id, plain = result.plain, synced = result.synced)
                    }
                }
            }
            if (lyricsCache.size >= 40) lyricsCache.clear()
            lyricsCache[track.id] = state
            _lyrics.value = state
        }
    }

    // Related catalog tracks fetched for the artists the user has downloaded.
    // Refreshed once per downloaded-artist profile signature, not per screen visit.
    private val relatedCatalog = MutableStateFlow<List<CatalogTrack>>(emptyList())
    private var fetchedArtistProfile: String? = null

    private fun catalogToTrack(ct: CatalogTrack): Track = Track(
        id = "${ct.provider}:recording:${ct.id}", title = ct.title,
        artistName = ct.artistName,
        albumId = ct.albumId?.let { "${ct.provider}:release:$it" } ?: "unknown",
        albumTitle = ct.albumTitle ?: "Álbum desconocido", durationMs = ct.durationMs,
        audioUri = null, isDownloaded = false, coverUri = ct.coverUri,
        source = ct.source, sourceId = ct.sourceId
    )

    // Recommendations are isolated from fast-changing player/download UI state.
    // This prevents Home from rescoring/sorting the catalog during playback or
    // download progress updates. The expensive work runs off the main thread.
    private val recommendations: StateFlow<Pair<List<Track>, List<Album>>> = combine(
        localTracks, allCatalogAlbums, favoriteIds, recentSearches, cachedCatalogTracks, relatedCatalog
    ) { v ->
        val tracks = (v[0] as? List<*>)?.filterIsInstance<Track>() ?: emptyList()
        val catalogAlbums = (v[1] as? List<*>)?.filterIsInstance<Album>() ?: emptyList()
        val favorites = (v[2] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val searches = (v[3] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val cachedTracks = (v[4] as? List<*>)?.filterIsInstance<CatalogTrack>() ?: emptyList()
        val related = (v[5] as? List<*>)?.filterIsInstance<CatalogTrack>() ?: emptyList()

        // User profile: artists and albums already in the vault.
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
        val downloadedAlbumTitles = downloaded.mapTo(HashSet()) { it.albumTitle.trim().lowercase() }

        val tokens = searches.asSequence()
            .flatMap { it.lowercase().split(whitespaceRegex).asSequence() }
            .filter { it.length >= 2 }
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
        val tracksList = tracksResult
            .distinctBy { it.id }
            .filter { it.id !in downloadedIds }
            .map { it to scoreTrack(it) }
            .sortedByDescending { it.second }
            .take(24)
            .map { it.first }
            .toList()

        val albumsResult = catalogAlbums.asSequence()
            .filter { !it.id.startsWith("album:custom:") }
            .distinctBy { it.id }
            .map { it to scoreAlbum(it) }
            .sortedWith(compareByDescending<Pair<Album, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .take(12).toList()
            .map { it.first }
        tracksList to albumsResult
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Track>() to emptyList())

    // Keep rapidly changing download/player state out of recommendation calculation.
    val state: StateFlow<MusicUiState> = combine(
        _section, _search, localTracks, downloaded, albums, playlists,
        _remoteTracks, _remoteAlbums, _selectedTrack, _selectedPlaylist,
        _selectedPlaylistTracks, _selectedRemoteAlbum, _remoteAlbumTracks, _loadingRemoteAlbum,
        _selectedLocalAlbum, _selectedLocalAlbumTracks, _fullPlayer, _shuffle, _crossfadeSeconds,
        _searching, _scanning, _providerMessage, _errorMessage, favoriteIds, recentSearches, recommendations,
        _playerStyle, _appDesign, _spotifyConnected, _spotifyOffer, _spotifyImporting, _spotifyPlaylistReview
    ) { v ->
        val rec = v[25] as? Pair<*, *> ?: (emptyList<Track>() to emptyList<Album>())
        MusicUiState(
            section = v[0] as? AppSection ?: AppSection.HOME, search = v[1] as? String ?: "",
            tracks = v[2].stateList(),
            downloaded = v[3].stateList(), albums = v[4].stateList(),
            playlists = v[5].stateList(),
            remoteTracks = v[6].stateList(), remoteAlbums = v[7].stateList(),
            selectedTrack = v[8] as? Track, selectedPlaylist = v[9] as? Playlist,
            selectedPlaylistTracks = v[10].stateList(), selectedRemoteAlbum = v[11] as? CatalogAlbum,
            remoteAlbumTracks = v[12].stateList(), isLoadingRemoteAlbum = v[13] as? Boolean ?: false,
            selectedLocalAlbum = v[14] as? Album, selectedLocalAlbumTracks = v[15].stateList(),
            fullPlayer = v[16] as? Boolean ?: false, shuffle = v[17] as? Boolean ?: false, crossfadeSeconds = v[18] as? Int ?: 5,
            isSearching = v[19] as? Boolean ?: false, isScanning = v[20] as? Boolean ?: false,
            providerMessage = v[21] as? String?, errorMessage = v[22] as? String?,
            favoriteIds = v[23].stateSet(), recentSearches = v[24].stateList(),
            recommendedTracks = rec.first.stateList(), recommendedAlbums = rec.second.stateList(),
            playerStyle = v[26] as? String ?: "vinyl",
            appDesign = v[27] as? String ?: APP_DESIGN_HYBRID,
            spotifyConnected = v[28] as? Boolean ?: false,
            spotifyOffer = v[29] as? SpotifyLink,
            spotifyImporting = v[30] as? Boolean ?: false,
            spotifyPlaylistReview = v[31] as? SpotifyPlaylist
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
            if (!explicitQueueMode) {
                val generation = queueGeneration
                viewModelScope.launch {
                    val current = finishedTrackId
                        ?.let { id -> state.value.tracks.firstOrNull { it.id == id } }
                        ?: _selectedTrack.value
                    if (current != null && generation == queueGeneration) appendSmartSuccessor(current)
                }
            }
        }
        // Keep the home-screen widget in sync with playback state/track changes.
        player.onPlaybackUiChanged = {
            com.musicvault.app.widget.MusicWidgetProvider.push(appContext)
        }
        // Home discovery: fetch more catalog tracks for the top downloaded
        // artists. Runs once per profile signature (artist list), with a small
        // delay so it never competes with the first paint; failures are silent.
        viewModelScope.launch(Dispatchers.IO) {
            localTracks
                .map { list ->
                    list.filter { it.isDownloaded }
                        .asSequence()
                        .map { it.artistName.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .groupingBy { it }.eachCount()
                        .entries.asSequence()
                        .sortedByDescending { it.value }
                        .take(4)
                        .map { it.key }
                        .joinToString("|")
                }
                .distinctUntilChanged()
                .collect { signature ->
                    if (signature.isBlank() || signature == fetchedArtistProfile) return@collect
                    fetchedArtistProfile = signature
                    delay(1500)
                    runCatching {
                        val results = signature.split('|').flatMap { artist ->
                            runCatching { catalog.searchTracks(artist, 12) }.getOrDefault(emptyList())
                        }
                        relatedCatalog.value = results.distinctBy { "${it.provider}:${it.id}" }
                    }
                }
        }
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
            playlistJob?.cancel()
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

        // The single search field accepts both catalog text and Spotify links.
        // A valid URL is an import action; every other value remains a normal
        // song, artist or album query.
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
            // Publish the fast-path result the moment it lands so the UI does
            // not wait for the slower full merge before showing anything.
            try {
                val fast = catalog.searchFast(query, 40, 20)
                publishRemoteResults(generation, query, fast.tracks, fast.albums)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == searchGeneration) _searching.value = false
                return@launch
            }

            // While the user is already looking at results, fetch the merged
            // view across every provider and replace the fast-path list when it
            // arrives. Generation guard prevents stale merges from clobbering
            // a newer query's first paint.
            try {
                val merged = withTimeoutOrNull(SEARCH_MERGE_TIMEOUT_MS) {
                    catalog.searchMerged(query, 40, 20)
                }
                if (merged != null && generation == searchGeneration) {
                    publishRemoteResults(generation, query, merged.tracks, merged.albums)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The fast-path result is already shown; ignore merge errors.
            } finally {
                if (generation == searchGeneration) {
                    _searching.value = false
                }
            }
        }
    }

    private fun publishRemoteResults(
        generation: Long,
        query: String,
        tracks: List<CatalogTrack>,
        albums: List<CatalogAlbum>
    ) {
        if (generation != searchGeneration) return
        CoverImageCache.prefetch(
            appContext,
            tracks.map { it.coverUri } + albums.map { it.coverUri }
        )
        if (generation != searchGeneration) return
        _remoteTracks.value = tracks
        _remoteAlbums.value = albums
        if (tracks.isEmpty() && albums.isEmpty()) {
            _errorMessage.value = "No se encontró metadata para \"${query}\"."
        }
        viewModelScope.launch(Dispatchers.IO) { repository.recordSearch(query) }
    }

    fun selectTrack(track: Track) {
        activePlaylistId = null
        explicitQueueMode = false
        if (track.audioUri != null && track.isDownloaded) {
            _selectedTrack.value = track
            player.play(track)
            viewModelScope.launch {
                repository.recordPlay(track, 0f)
                // No smart-next here: the player fires onNextItemNeeded when the
                // item has no successor, which handles every entry point
                // uniformly and keeps the queue (and crossfade) alive.
            }
        } else {
            // A remote/non-downloaded track is a download action, not a playback action.
            // Do not select it in the player and do not autoplay when the download finishes.
            requestDownloadInternal(track.id, track.title, track.artistName) { result ->
                if (result is ProviderResult.Success) {
                    val updated = track.copy(audioUri = result.audioUri, isDownloaded = true)
                    database.trackDao().upsertAll(listOf(TrackEntity(updated.id, updated.title, updated.artistName, updated.albumId, updated.albumTitle, updated.durationMs, updated.audioUri, true, updated.coverUri)))
                }
            }
        }
    }

    fun playRemote(track: CatalogTrack) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded && it.audioUri != null }
        if (local != null) {
            selectTrack(local)
            return
        }
        // A remote track that is not downloaded must never enter the player automatically.
        // The first click downloads it; a later click on the now-downloaded track plays it.
        requestRemoteDownload(track, autoPlay = false)
    }

    fun playRecommendation(track: Track) {
        if (track.isDownloaded && track.audioUri != null) {
            selectTrack(track)
            return
        }
        val parts = track.id.split(":", limit = 3)
        if (parts.size == 3 && parts[1] == "recording") {
            playRemote(CatalogTrack(
                id = parts[2],
                title = track.title,
                artistId = "",
                artistName = track.artistName,
                albumId = track.albumId.substringAfter(":release:", "").takeIf { it.isNotBlank() },
                albumTitle = track.albumTitle,
                durationMs = track.durationMs,
                coverUri = track.coverUri,
                provider = parts[0]
            ))
        }
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

    fun togglePlayback() = player.toggle()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun refreshPlaybackState() = player.refreshPlaybackState()
    fun openFullPlayer() { if (_selectedTrack.value != null) _fullPlayer.value = true }
    fun closeFullPlayer() { _fullPlayer.value = false }

    fun toggleShuffle() {
        val enabled = !_shuffle.value
        _shuffle.value = enabled
        player.setShuffle(enabled)
        if (!explicitQueueMode && activePlaylistId == null && enabled) {
            _selectedTrack.value?.let { viewModelScope.launch { enqueueSmartNext(it) } }
        }
    }

    fun cycleRepeatMode() = player.cycleRepeatMode()

    fun playPlaylist(playlist: Playlist, shuffle: Boolean = _shuffle.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getPlaylistTracks(playlist.id)
                .filter { it.isDownloaded && it.audioUri != null }
            if (tracks.isEmpty()) {
                _errorMessage.value = "Esta playlist no tiene canciones descargadas."
                return@launch
            }
            activePlaylistId = playlist.id
            _selectedPlaylist.value = playlist
            _selectedPlaylistTracks.value = tracks
            _shuffle.value = shuffle
            withContext(Dispatchers.Main.immediate) {
                explicitQueueMode = true
                // When shuffle is requested, start from a genuinely random item
                // instead of always beginning with item 0. Media3 then keeps the
                // rest of the queue shuffled within this playlist.
                val startIndex = if (shuffle && tracks.size > 1) tracks.indices.random() else 0
                player.playQueue(tracks, startIndex, shuffle)
                _selectedTrack.value = tracks[startIndex]
                repository.recordPlay(tracks[startIndex], 0f)
            }
        }
    }

    fun playPlaylistTrack(playlist: Playlist, track: Track, shuffle: Boolean = _shuffle.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getPlaylistTracks(playlist.id)
                .filter { it.isDownloaded && it.audioUri != null }
            val index = tracks.indexOfFirst { it.id == track.id }
            if (index < 0) {
                _errorMessage.value = "La canción ya no está disponible en esta playlist."
                return@launch
            }
            activePlaylistId = playlist.id
            _selectedPlaylist.value = playlist
            _selectedPlaylistTracks.value = tracks
            _shuffle.value = shuffle
            withContext(Dispatchers.Main.immediate) {
                explicitQueueMode = true
                player.playQueue(tracks, index, shuffle)
                _selectedTrack.value = tracks[index]
                repository.recordPlay(tracks[index], 0f)
            }
        }
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
        if (!track.isDownloaded || track.audioUri == null) {
            return
        }
        activateExplicitQueueMode()
        pendingQueueEntries += PendingQueueEntry(ready = true, track = track)
        flushPendingQueue()
    }

    fun addRemoteTrackToQueue(track: CatalogTrack) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded && it.audioUri != null }
        if (local != null) {
            addToQueue(local)
            return
        }
        // Queue intent is explicit as soon as the user presses "add to queue";
        // otherwise smart-next can keep appending auto-picked successors while
        // the download is still in flight.
        activateExplicitQueueMode()
        val generation = queueGeneration
        val entry = PendingQueueEntry()
        pendingQueueEntries += entry
        requestRemoteDownload(track, autoPlay = false) { ready ->
            withContext(Dispatchers.Main.immediate) {
                if (generation == queueGeneration) {
                    entry.ready = true
                    entry.track = ready
                    flushPendingQueue()
                }
            }
        }
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
                    requestRemoteDownload(nextRemote, autoPlay = true)
                    return@launch
                }
            }

            val hasAlbumContext = _selectedLocalAlbum.value != null || _selectedRemoteAlbum.value != null || current.albumId.startsWith("album:custom:")
            if (hasAlbumContext && isOnline()) {
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
                requestRemoteDownload(nextRemote, autoPlay = false) { ready ->
                    ready?.let { appendSmartCandidate(current, it, generation) }
                }
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
        if (!isOnline()) {
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
                requestRemoteDownload(candidate, autoPlay = false) { ready ->
                    ready?.let { appendSmartCandidate(current, it, generation) }
                }
            } else {
                localCandidates.randomOrNull()?.let { appendSmartCandidate(current, it, generation) }
            }
        } catch (_: Exception) {
            localCandidates.randomOrNull()?.let { appendSmartCandidate(current, it, generation) }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            repository.createPlaylist(id, title.trim(), "Creada por ti")
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
        _selectedRemoteAlbum.value = album
        _remoteAlbumTracks.value = emptyList()
        _loadingRemoteAlbum.value = true
        playlistJob?.cancel()
        searchJob?.cancel()
        viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { catalog.tracksForAlbum(album, 50) }
                _remoteAlbumTracks.value = tracks
                if (tracks.isEmpty()) {
                    _errorMessage.value = "No se pudieron obtener las canciones de este álbum."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo abrir el álbum: ${e.message ?: "error de red"}"
            } finally {
                _loadingRemoteAlbum.value = false
            }
        }
    }

    fun closeRemoteAlbum() {
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
        _selectedPlaylist.value = playlist
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            repository.playlistTracks(playlist.id).collect { _selectedPlaylistTracks.value = it }
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
        requestDownloadInternal(track.id, track.title, track.artistName, track.url) { result ->
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
                    .filterNot { it.isDownloaded && it.audioUri != null }
                if (pending.isEmpty()) {
                    showProvider("La playlist ya está descargada.")
                    return@launch
                }
                val completed = pending.map { track ->
                    async { awaitTrackDownload(track) }
                }.awaitAll().count { it }
                if (completed > 0) {
                    showProvider("$completed canciones descargadas de ${playlist.title}.")
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

    private suspend fun awaitTrackDownload(track: Track): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            requestDownloadInternal(track.id, track.title, track.artistName, track.url) { result ->
                if (result is ProviderResult.Success) {
                    repository.setDownloaded(track, result.audioUri)
                    persistDownloadedLyrics(
                        track = track,
                        localReference = result.localPath ?: result.audioUri
                    )
                }
                if (continuation.isActive) continuation.resume(result is ProviderResult.Success)
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

    fun addTrackToPlaylist(track: Track, playlistId: String) {
        if (track.isDownloaded && track.audioUri != null) {
            viewModelScope.launch(Dispatchers.IO) { repository.addTrackToPlaylist(playlistId, track) }
            return
        }
        requestDownloadInternal(track.id, track.title, track.artistName, track.url) { result ->
            if (result is ProviderResult.Success) {
                val ready = track.copy(audioUri = result.audioUri, isDownloaded = true)
                repository.setDownloaded(track, result.audioUri)
                repository.addTrackToPlaylist(playlistId, ready)
            }
        }
    }

    fun addRemoteTrackToPlaylist(track: CatalogTrack, playlistId: String) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded }
        if (local != null) addTrackToPlaylist(local, playlistId)
        else viewModelScope.launch(Dispatchers.IO) {
            awaitRemoteDownload(track)?.let { repository.addTrackToPlaylist(playlistId, it) }
        }
    }

    fun addTrackToAlbum(track: Track, albumId: String) {
        if (track.isDownloaded && track.audioUri != null) {
            viewModelScope.launch(Dispatchers.IO) { repository.addTrackToAlbum(albumId, track) }
            return
        }
        requestDownloadInternal(track.id, track.title, track.artistName, track.url) { result ->
            if (result is ProviderResult.Success) {
                val ready = track.copy(audioUri = result.audioUri, isDownloaded = true)
                repository.setDownloaded(track, result.audioUri)
                repository.addTrackToAlbum(albumId, ready)
            }
        }
    }

    fun addRemoteTrackToAlbum(track: CatalogTrack, albumId: String) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded }
        if (local != null) addTrackToAlbum(local, albumId)
        else viewModelScope.launch(Dispatchers.IO) {
            awaitRemoteDownload(track)?.let { addTrackToAlbum(it, albumId) }
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
        viewModelScope.launch(Dispatchers.IO) {
            waiters.forEach { callback -> runCatching { callback(result) } }
            when (result) {
                is ProviderResult.Unavailable -> showError(result.reason)
                is ProviderResult.Error -> showError(result.message)
                is ProviderResult.Success -> Unit
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
        synchronized(downloadLock) {
            // Do NOT cancel the foreground service here. Its purpose is to keep
            // downloads alive when the Activity/ViewModel is recreated or the
            // user leaves the UI.
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            downloadWaiters.clear()
        }
        searchJob?.cancel(); playlistJob?.cancel(); albumJob?.cancel(); historyJob?.cancel()
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

        val result = runCatching {
            LyricsProvider.fetch(
                artist = track.artistName,
                title = track.title,
                album = track.albumTitle.takeIf { it.isNotBlank() && !it.equals("Álbum desconocido", ignoreCase = true) },
                durationMs = track.durationMs
            )
        }.getOrNull() ?: return

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
