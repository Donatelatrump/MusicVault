package com.musicvault.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogRepository
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.catalog.MetadataCatalogProvider
import com.musicvault.app.data.*
import com.musicvault.app.library.LocalAudioScanner
import com.musicvault.app.player.NativeEqualizer
import com.musicvault.app.player.PlayerController
import com.musicvault.app.providers.ProviderResult
import com.musicvault.app.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

enum class AppSection(val label: String) { HOME("Inicio"), SEARCH("Buscar"), LIBRARY("Biblioteca"), PLAYLISTS("Álbumes") }

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
    val remoteAlbumTracks: List<CatalogTrack> = emptyList(),
    val isLoadingRemoteAlbum: Boolean = false,
    val isSearching: Boolean = false,
    val isScanning: Boolean = false,
    val providerMessage: String? = null,
    val errorMessage: String? = null,
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
    private val equalizer = NativeEqualizer()
    private val preferences: SharedPreferences = appContext.getSharedPreferences("musicvault.settings", Context.MODE_PRIVATE)

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
    private val _remoteAlbumTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    private val _loadingRemoteAlbum = MutableStateFlow(false)
    private val _remoteTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    private val _remoteAlbums = MutableStateFlow<List<CatalogAlbum>>(emptyList())
    private val _searching = MutableStateFlow(false)
    private val _scanning = MutableStateFlow(false)
    private val _providerMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _albumDownloads = MutableStateFlow<Set<String>>(emptySet())
    private val favoriteIds = repository.favorites()
    private val recentSearches = repository.recentSearches(12)
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadWaiters = mutableMapOf<String, MutableList<suspend (ProviderResult) -> Unit>>()
    private var searchJob: Job? = null
    // Monotonic generation prevents a slow/cancel-resistant provider request from
    // publishing stale results after the user has typed a newer query.
    private var searchGeneration: Long = 0L
    private var playlistJob: Job? = null
    private var activePlaylistId: String? = null
    // True only when the user explicitly created/selected a playback queue.
    // A normal single-track play also creates a Media3 item, but that must not
    // force Next to stay on a one-item queue.
    private var explicitQueueMode = false
    private var albumJob: Job? = null
    private var historyJob: Job? = null
    private var lastHistoryUpdateAt = 0L

    private val localTracks = repository.tracks()
    private val downloaded = repository.downloaded()
    private val allCatalogAlbums = repository.albums()
    private val cachedCatalogTracks = catalog.cachedTracks()
    private val albums = allCatalogAlbums.map { list -> list.filter { it.id.startsWith("album:custom:") } }
    private val playlists = repository.playlists()

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<PlayerController.PlaybackProgress> = player.progress
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()
    val albumDownloads: StateFlow<Set<String>> = _albumDownloads.asStateFlow()

    fun downloadProgressFor(id: String): Flow<Int?> =
        downloadProgress.map { it[id] }.distinctUntilChanged()
    val shuffle: StateFlow<Boolean> = player.shuffle
    val crossfadeSeconds: StateFlow<Int> = player.crossfadeSeconds
    val queue: StateFlow<List<Track>> = player.queue
    val queueIndex: StateFlow<Int> = player.queueIndex
    val audioSessionId: StateFlow<Int> = player.audioSessionId
    private val _equalizerBands = MutableStateFlow<List<NativeEqualizer.Band>>(emptyList())
    val equalizerBands: StateFlow<List<NativeEqualizer.Band>> = _equalizerBands.asStateFlow()

    // Recommendations are isolated from fast-changing player/download UI state.
    // This prevents Home from rescoring/sorting the catalog during playback or
    // download progress updates. The expensive work runs off the main thread.
    private val recommendations: StateFlow<Pair<List<Track>, List<Album>>> = combine(
        localTracks, allCatalogAlbums, favoriteIds, recentSearches, cachedCatalogTracks
    ) { tracks, catalogAlbums, favorites, searches, cachedTracks ->
        val tokens = searches.asSequence()
            .flatMap { it.lowercase().split(Regex("\\s+")).asSequence() }
            .filter { it.length >= 2 }
            .take(24)
            .toList()
        fun scoreTrack(t: Track): Int {
            val hay = "${t.title} ${t.artistName} ${t.albumTitle}".lowercase()
            return tokens.count { hay.contains(it) } * 8 + if (t.id in favorites) 40 else 0
        }
        fun scoreAlbum(a: Album): Int {
            val hay = "${a.title} ${a.artistName}".lowercase()
            return tokens.count { hay.contains(it) } * 8
        }
        val cachedRecommendations = cachedTracks.asSequence()
            .map { ct ->
                Track(
                    id = "${ct.provider}:recording:${ct.id}", title = ct.title,
                    artistName = ct.artistName,
                    albumId = ct.albumId?.let { "${ct.provider}:release:$it" } ?: "unknown",
                    albumTitle = ct.albumTitle ?: "Álbum desconocido", durationMs = ct.durationMs,
                    audioUri = null, isDownloaded = false, coverUri = ct.coverUri
                )
            }
        // Home intentionally mixes downloaded favorites/history with new cached discoveries.
        // The UI itself decides which action to show: Play for remote-only tracks,
        // spinner while downloading, and only the overflow menu once persisted.
        val tracksResult = (tracks.asSequence() + cachedRecommendations)
            .distinctBy { it.id }.sortedByDescending(::scoreTrack).take(16).toList()
        val albumsResult = catalogAlbums.asSequence()
            .filter { !it.id.startsWith("album:custom:") }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Album> { scoreAlbum(it) }.thenBy { it.title.lowercase() })
            .take(12).toList()
        tracksResult to albumsResult
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Track>() to emptyList())

    // Keep rapidly changing download/player state out of recommendation calculation.
    val state: StateFlow<MusicUiState> = combine(
        _section, _search, localTracks, downloaded, albums, playlists,
        _remoteTracks, _remoteAlbums, _selectedTrack, _selectedPlaylist,
        _selectedPlaylistTracks, _selectedRemoteAlbum, _remoteAlbumTracks, _loadingRemoteAlbum,
        _selectedLocalAlbum, _selectedLocalAlbumTracks, _fullPlayer, _shuffle, _crossfadeSeconds,
        _searching, _scanning, _providerMessage, _errorMessage, favoriteIds, recentSearches, recommendations
    ) { v ->
        val rec = v[25] as Pair<*, *>
        MusicUiState(
            section = v[0] as AppSection, search = v[1] as String, tracks = v[2] as List<Track>,
            downloaded = v[3] as List<Track>, albums = v[4] as List<Album>, playlists = v[5] as List<Playlist>,
            remoteTracks = v[6] as List<CatalogTrack>, remoteAlbums = v[7] as List<CatalogAlbum>,
            selectedTrack = v[8] as Track?, selectedPlaylist = v[9] as Playlist?,
            selectedPlaylistTracks = v[10] as List<Track>, selectedRemoteAlbum = v[11] as CatalogAlbum?,
            remoteAlbumTracks = v[12] as List<CatalogTrack>, isLoadingRemoteAlbum = v[13] as Boolean,
            selectedLocalAlbum = v[14] as Album?, selectedLocalAlbumTracks = v[15] as List<Track>,
            fullPlayer = v[16] as Boolean, shuffle = v[17] as Boolean, crossfadeSeconds = v[18] as Int,
            isSearching = v[19] as Boolean, isScanning = v[20] as Boolean,
            providerMessage = v[21] as String?, errorMessage = v[22] as String?,
            favoriteIds = v[23] as Set<String>, recentSearches = v[24] as List<String>,
            recommendedTracks = rec.first as List<Track>, recommendedAlbums = rec.second as List<Album>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Favorites is a permanent user collection backed by the existing playlist tables.
            // Creating it with a stable id makes it available immediately and preserves it across launches.
            repository.createPlaylist(favoritesAlbumId, "Favoritos", "Canciones que te gustan")
        }
        player.setCrossfadeSeconds(_crossfadeSeconds.value)
        historyJob = viewModelScope.launch {
            launch {
                player.audioSessionId.collect { sessionId ->
                    if (sessionId > 0 && equalizer.attach(sessionId)) {
                        _equalizerBands.value = equalizer.bands()
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
                    if (now - lastHistoryUpdateAt < 2_000L) return@collect
                    val ratio = (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
                    progress.trackId?.let { id ->
                        localTracks.first().firstOrNull { it.id == id }?.let { current ->
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

    fun search(value: String) {
        _search.value = value
        _section.value = AppSection.SEARCH
        searchGeneration += 1L
        val generation = searchGeneration

        // Every keystroke cancels and restarts the full debounce window.
        // This is intentionally 1.5s: typing must never be interrupted by an
        // intermediate network request.
        searchJob?.cancel()
        closeRemoteAlbum()
        _remoteTracks.value = emptyList()
        _remoteAlbums.value = emptyList()
        _searching.value = false

        if (value.isBlank()) return

        searchJob = viewModelScope.launch {
            delay(1_500L)

            // A newer keystroke may have arrived while this coroutine was waiting.
            if (generation != searchGeneration) return@launch

            _searching.value = true
            _errorMessage.value = null
            try {
                val results = catalog.search(value, 20, 10)

                // Some providers use blocking I/O and may not react immediately to
                // coroutine cancellation. Never publish results belonging to an
                // older query.
                if (generation != searchGeneration) return@launch

                // Warm the image cache before exposing the list so the first scroll
                // is not competing with a burst of thumbnail decodes.
                CoverImageCache.prefetch(
                    appContext,
                    results.tracks.map { it.coverUri } +
                        results.albums.map { it.coverUri }
                )

                if (generation != searchGeneration) return@launch

                _remoteTracks.value = results.tracks
                _remoteAlbums.value = results.albums
                repository.recordSearch(value)
                if (results.tracks.isEmpty() && results.albums.isEmpty()) {
                    _errorMessage.value = "No se encontró metadata para \"${value.trim()}\"."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration) {
                    _errorMessage.value = "No se pudo consultar metadata: ${e.message ?: "error de red"}"
                }
            } finally {
                if (generation == searchGeneration) {
                    _searching.value = false
                }
            }
        }
    }

    fun selectTrack(track: Track) {
        activePlaylistId = null
        explicitQueueMode = false
        _selectedTrack.value = track
        if (track.audioUri != null && track.isDownloaded) {
            player.play(track)
            viewModelScope.launch {
                repository.recordPlay(track, 0f)
                enqueueSmartNext(track)
            }
        } else {
            _providerMessage.value = "Preparando \"${track.title}\"..."
            requestDownloadInternal(track.id, track.title, track.artistName) { result ->
                if (result is ProviderResult.Success) {
                    val updated = track.copy(audioUri = result.audioUri, isDownloaded = true)
                    database.trackDao().upsertAll(listOf(TrackEntity(updated.id, updated.title, updated.artistName, updated.albumId, updated.albumTitle, updated.durationMs, updated.audioUri, true, updated.coverUri)))
                    player.play(updated)
                    repository.recordPlay(updated, 0f)
                    enqueueSmartNext(updated)
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
        _selectedTrack.value = Track(localId, track.title, track.artistName, track.albumId?.let { "${track.provider}:release:$it" } ?: "unknown", track.albumTitle ?: "Álbum desconocido", track.durationMs, null, false, track.coverUri)
        _providerMessage.value = "Descargando \"${track.title}\" para reproducir..."
        requestRemoteDownload(track, autoPlay = true)
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
                if (albumTracks.isEmpty()) {
                    _errorMessage.value = "No hay canciones disponibles para este álbum."
                    return@launch
                }
                _providerMessage.value = "Descargando álbum \"${album.title}\" (${albumTracks.size} canciones)..."
                albumTracks.forEach { track -> requestRemoteDownload(track, autoPlay = false) }
                while (albumTracks.any { _downloadProgress.value.containsKey("${it.provider}:recording:${it.id}") }) {
                    delay(120)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo preparar el álbum: ${e.message ?: "error"}"
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
                if (albumTracks.isEmpty()) {
                    _errorMessage.value = "No hay canciones disponibles para este álbum."
                    return@launch
                }
                _providerMessage.value = "Descargando álbum \"${album.title}\" (${albumTracks.size} canciones)..."
                albumTracks.forEach { track -> requestRemoteDownload(track, autoPlay = false) }
                while (albumTracks.any { _downloadProgress.value.containsKey("${it.provider}:recording:${it.id}") }) {
                    delay(120)
                }
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
    fun openFullPlayer() { if (_selectedTrack.value != null) _fullPlayer.value = true }
    fun closeFullPlayer() { _fullPlayer.value = false }

    fun toggleShuffle() {
        val enabled = !_shuffle.value
        _shuffle.value = enabled
        player.setShuffle(enabled)
        if (activePlaylistId == null && enabled) {
            _selectedTrack.value?.let { viewModelScope.launch { enqueueSmartNext(it) } }
        }
    }

    fun playPlaylist(playlist: Playlist, shuffle: Boolean = _shuffle.value) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getPlaylistTracks(playlist.id)
                .filter { it.isDownloaded && it.audioUri != null }
            if (tracks.isEmpty()) {
                _errorMessage.value = "Esta playlist no tiene canciones descargadas."
                return@launch
            }
            activePlaylistId = playlist.id
            explicitQueueMode = true
            _selectedPlaylist.value = playlist
            _selectedPlaylistTracks.value = tracks
            _shuffle.value = shuffle
            withContext(Dispatchers.Main.immediate) {
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
            explicitQueueMode = true
            _selectedPlaylist.value = playlist
            _selectedPlaylistTracks.value = tracks
            _shuffle.value = shuffle
            withContext(Dispatchers.Main.immediate) {
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
            _providerMessage.value = "Descarga la canción antes de agregarla a la cola."
            return
        }
        explicitQueueMode = true
        player.append(track)
    }

    fun addRemoteTrackToQueue(track: CatalogTrack) {
        val localId = "${track.provider}:recording:${track.id}"
        val local = state.value.tracks.firstOrNull { it.id == localId && it.isDownloaded && it.audioUri != null }
        if (local != null) {
            explicitQueueMode = true
            player.append(local)
            return
        }
        _providerMessage.value = "Descargando \"${track.title}\" para añadirla a la cola..."
        requestRemoteDownload(track, autoPlay = false) { ready ->
            explicitQueueMode = true
            player.append(ready)
        }
    }

    fun removeFromQueue(index: Int) = player.removeQueueItem(index)

    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)

    fun setEqualizerEnabled(enabled: Boolean) { equalizer.setEnabled(enabled) }

    fun setEqualizerBand(index: Int, level: Short) {
        equalizer.setBandLevel(index, level)
        _equalizerBands.value = equalizer.bands()
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
            player.append(next)
            return
        }

        val remoteAlbum = _selectedRemoteAlbum.value
        if (remoteAlbum != null && _remoteAlbumTracks.value.isNotEmpty()) {
            val index = _remoteAlbumTracks.value.indexOfFirst { "${it.provider}:recording:${it.id}" == current.id }
            val remaining = if (index >= 0) _remoteAlbumTracks.value.drop(index + 1) else _remoteAlbumTracks.value
            val nextRemote = if (_shuffle.value && remaining.isNotEmpty()) remaining.randomOrNull() else remaining.firstOrNull()
            if (nextRemote != null) {
                requestRemoteDownload(nextRemote, autoPlay = false) { ready -> player.append(ready) }
                return
            }
        }

        val downloaded = state.value.downloaded.filter { it.id != current.id && it.audioUri != null }
        if (downloaded.isNotEmpty()) {
            val next = if (_shuffle.value) downloaded.random() else downloaded.first()
            player.append(next)
        } else if (_shuffle.value) {
            findAndDownloadRelated(current)
        }
    }

    private suspend fun findAndDownloadRelated(current: Track) {
        val localCandidates = state.value.downloaded.filter { it.id != current.id && it.audioUri != null }
        if (!isOnline()) {
            localCandidates.randomOrNull()?.let { player.append(it) }
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
                requestRemoteDownload(candidate, autoPlay = false) { ready -> player.append(ready) }
            } else {
                localCandidates.randomOrNull()?.let { player.append(it) }
            }
        } catch (_: Exception) {
            localCandidates.randomOrNull()?.let { player.append(it) }
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
                if (uri != null && uri.scheme == "content") {
                    val fileName = uri.lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    if (!fileName.isNullOrBlank()) {
                        val root = java.io.File(appContext.getExternalFilesDir("Music") ?: appContext.filesDir, "downloads")
                        root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }?.delete()
                    }
                }
            }
            repository.deleteTrack(track.id)
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
                // Replace only the entries created by the device scanner.
                // Downloaded/provider tracks use different IDs and therefore
                // survive rescans. This also removes old short audio entries
                // that no longer meet the 90-second music threshold.
                database.trackDao().deleteLocalScannedTracks()
                database.trackDao().upsertAll(scanned)
            } catch (e: Exception) {
                _errorMessage.value = "No se pudo escanear el almacenamiento."
            } finally {
                _scanning.value = false
            }
        }
    }

    fun requestDownload(track: Track) {
        requestDownloadInternal(track.id, track.title, track.artistName) { result ->
            if (result is ProviderResult.Success) repository.setDownloaded(track, result.audioUri)
        }
    }

    fun requestRemoteDownload(track: CatalogTrack) = requestRemoteDownload(track, autoPlay = false)

    private fun requestRemoteDownload(
        track: CatalogTrack,
        autoPlay: Boolean,
        onReady: (suspend (Track) -> Unit)? = null
    ) {
        val id = "${track.provider}:recording:${track.id}"
        val existing = state.value.tracks.firstOrNull { it.id == id && it.isDownloaded }
        if (existing != null) {
            viewModelScope.launch { onReady?.invoke(existing); if (autoPlay) selectTrack(existing) }
            return
        }
        requestDownloadInternal(id, track.title, track.artistName) { result ->
            if (result is ProviderResult.Success) {
                val entity = TrackEntity(id, track.title, track.artistName,
                    track.albumId?.let { "${track.provider}:release:$it" } ?: "unknown",
                    track.albumTitle ?: "Álbum desconocido", track.durationMs, result.audioUri, true, track.coverUri)
                database.trackDao().upsertAll(listOf(entity))
                track.albumId?.let { albumId -> database.albumDao().upsertAll(listOf(AlbumEntity(
                    "${track.provider}:release:$albumId", track.albumTitle ?: "Álbum desconocido", track.artistId, track.artistName, 0, track.coverUri))) }
                val local = Track(id, track.title, track.artistName, entity.albumId, entity.albumTitle, track.durationMs, result.audioUri, true, track.coverUri)
                onReady?.invoke(local)
                if (autoPlay) {
                    _selectedTrack.value = local
                    player.play(local)
                    repository.recordPlay(local, 0f)
                    enqueueSmartNext(local)
                }
            }
        }
    }

    fun addTrackToPlaylist(track: Track, playlistId: String) {
        if (track.isDownloaded && track.audioUri != null) {
            viewModelScope.launch(Dispatchers.IO) { repository.addTrackToPlaylist(playlistId, track) }
            return
        }
        _providerMessage.value = "Descargando \"${track.title}\" para añadirla a la playlist..."
        requestDownloadInternal(track.id, track.title, track.artistName) { result ->
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
        else requestRemoteDownload(track, autoPlay = false).also {
            viewModelScope.launch {
                while (_downloadProgress.value.containsKey(localId)) delay(200)
                database.trackDao().get(localId)?.let { entity -> repository.addTrackToPlaylist(playlistId, Track(entity.id, entity.title, entity.artistName, entity.albumId, entity.albumTitle, entity.durationMs, entity.audioUri, entity.isDownloaded, entity.coverUri)) }
            }
        }
    }

    fun addTrackToAlbum(track: Track, albumId: String) {
        if (track.isDownloaded && track.audioUri != null) {
            viewModelScope.launch(Dispatchers.IO) { repository.addTrackToAlbum(albumId, track) }
            return
        }
        _providerMessage.value = "Descargando \"${track.title}\" para añadirla al álbum..."
        requestDownloadInternal(track.id, track.title, track.artistName) { result ->
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
        else requestRemoteDownload(track, autoPlay = false).also {
            viewModelScope.launch {
                while (_downloadProgress.value.containsKey(localId)) delay(200)
                database.trackDao().get(localId)?.let { entity -> addTrackToAlbum(Track(entity.id, entity.title, entity.artistName, entity.albumId, entity.albumTitle, entity.durationMs, entity.audioUri, entity.isDownloaded, entity.coverUri), albumId) }
            }
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

    private fun requestDownloadInternal(id: String, name: String, author: String, onSuccess: suspend (ProviderResult) -> Unit) {
        downloadWaiters.getOrPut(id) { mutableListOf() }.add(onSuccess)
        if (_downloadProgress.value.containsKey(id)) return

        _downloadProgress.value = _downloadProgress.value + (id to 0)
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                var lastProgress = 0
                var lastProgressAt = 0L
                val result = Providers.download(name, author, onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    // Download progress is not animation state. Throttle UI updates
                    // so a fast provider cannot invalidate the whole visible list
                    // dozens of times per second. 100% is always delivered.
                    if (progress == 100 || progress == 0 || progress - lastProgress >= 2 || now - lastProgressAt >= 120L) {
                        lastProgress = progress
                        lastProgressAt = now
                        _downloadProgress.value = _downloadProgress.value + (id to progress)
                    }
                }, requestId = id)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val waiters = downloadWaiters.remove(id).orEmpty().toList()
                waiters.forEach { callback ->
                    try {
                        callback(result)
                    } catch (_: Exception) {
                        // One consumer must not prevent the other download consumers from completing.
                    }
                }
                when (result) {
                    is ProviderResult.Success -> _providerMessage.value = "Disponible: ${result.fileName}"
                    is ProviderResult.Unavailable -> _providerMessage.value = result.reason
                    is ProviderResult.Error -> _providerMessage.value = result.message
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                downloadWaiters.remove(id)
                throw e
            } catch (e: Exception) {
                downloadWaiters.remove(id)
                _providerMessage.value = "Error durante la descarga: ${e.message ?: "error desconocido"}"
            } finally {
                _downloadProgress.value = _downloadProgress.value - id
                downloadJobs.remove(id)
            }
        }
        downloadJobs[id] = job
    }

    fun cancelDownload(id: String) {
        downloadJobs.remove(id)?.cancel()
        Providers.cancel(id)
        _downloadProgress.value = _downloadProgress.value - id
    }

    fun clearProviderMessage() { _providerMessage.value = null }
    fun clearError() { _errorMessage.value = null }

    override fun onCleared() {
        downloadJobs.keys.toList().forEach { Providers.cancel(it) }
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        downloadWaiters.clear()
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
}
