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

enum class AppSection(val label: String) { HOME("Inicio"), SEARCH("Buscar"), LIBRARY("Biblioteca"), PLAYLISTS("Playlists") }

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
    private val favoriteIds = repository.favorites()
    private val recentSearches = repository.recentSearches(12)
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadWaiters = mutableMapOf<String, MutableList<suspend (ProviderResult) -> Unit>>()
    private var searchJob: Job? = null
    // Monotonic generation prevents a slow/cancel-resistant provider request from
    // publishing stale results after the user has typed a newer query.
    private var searchGeneration: Long = 0L
    private var playlistJob: Job? = null
    private var albumJob: Job? = null
    private var historyJob: Job? = null
    private var lastHistoryUpdateAt = 0L

    private val localTracks = repository.tracks()
    private val downloaded = repository.downloaded()
    private val allCatalogAlbums = repository.albums()
    private val albums = allCatalogAlbums.map { list -> list.filter { it.id.startsWith("album:custom:") } }
    private val playlists = repository.playlists()

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<PlayerController.PlaybackProgress> = player.progress
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    fun downloadProgressFor(id: String): Flow<Int?> =
        downloadProgress.map { it[id] }.distinctUntilChanged()
    val shuffle: StateFlow<Boolean> = player.shuffle
    val crossfadeSeconds: StateFlow<Int> = player.crossfadeSeconds
    val queue: StateFlow<List<Track>> = player.queue
    val queueIndex: StateFlow<Int> = player.queueIndex
    val audioSessionId: StateFlow<Int> = player.audioSessionId
    private val _equalizerBands = MutableStateFlow<List<NativeEqualizer.Band>>(emptyList())
    val equalizerBands: StateFlow<List<NativeEqualizer.Band>> = _equalizerBands.asStateFlow()

    // Keep rapidly changing download/player state out of the main screen state object.
    val state: StateFlow<MusicUiState> = combine(
        _section, _search, localTracks, downloaded, albums, playlists,
        _remoteTracks, _remoteAlbums, _selectedTrack, _selectedPlaylist,
        _selectedPlaylistTracks, _selectedRemoteAlbum, _remoteAlbumTracks, _loadingRemoteAlbum,
        _selectedLocalAlbum, _selectedLocalAlbumTracks, _fullPlayer, _shuffle, _crossfadeSeconds,
        _searching, _scanning, _providerMessage, _errorMessage, favoriteIds, recentSearches, allCatalogAlbums
    ) { v ->
        val tracks = v[2] as List<Track>
        val albums = v[4] as List<Album>
        val catalogAlbums = v[25] as List<Album>
        val favorites = v[23] as Set<String>
        val searches = v[24] as List<String>
        val tokens = searches.asSequence()
            .flatMap { it.lowercase().split(Regex("\\s+")).asSequence() }
            .filter { it.length >= 2 }
            .take(24)
            .toList()
        fun scoreTrack(t: Track): Int {
            val hay = "${t.title} ${t.artistName} ${t.albumTitle}".lowercase()
            val match = tokens.count { hay.contains(it) } * 8
            return match + if (t.id in favorites) 40 else 0
        }
        fun scoreAlbum(a: Album): Int {
            val hay = "${a.title} ${a.artistName}".lowercase()
            return tokens.count { hay.contains(it) } * 8
        }
        val recommendedTracks = tracks.asSequence()
            .distinctBy { it.id }
            .sortedByDescending { scoreTrack(it) }
            .take(12)
            .toList()
        val recommendedAlbums = catalogAlbums.asSequence()
            .distinctBy { it.id }
            .sortedByDescending { scoreAlbum(it) }
            .take(10)
            .toList()
        MusicUiState(
            section = v[0] as AppSection,
            search = v[1] as String,
            tracks = v[2] as List<Track>,
            downloaded = v[3] as List<Track>,
            albums = v[4] as List<Album>,
            playlists = v[5] as List<Playlist>,
            remoteTracks = v[6] as List<CatalogTrack>,
            remoteAlbums = v[7] as List<CatalogAlbum>,
            selectedTrack = v[8] as Track?,
            selectedPlaylist = v[9] as Playlist?,
            selectedPlaylistTracks = v[10] as List<Track>,
            selectedRemoteAlbum = v[11] as CatalogAlbum?,
            remoteAlbumTracks = v[12] as List<CatalogTrack>,
            isLoadingRemoteAlbum = v[13] as Boolean,
            selectedLocalAlbum = v[14] as Album?,
            selectedLocalAlbumTracks = v[15] as List<Track>,
            fullPlayer = v[16] as Boolean,
            shuffle = v[17] as Boolean,
            crossfadeSeconds = v[18] as Int,
            isSearching = v[19] as Boolean,
            isScanning = v[20] as Boolean,
            providerMessage = v[21] as String?,
            errorMessage = v[22] as String?,
            favoriteIds = favorites,
            recentSearches = searches,
            recommendedTracks = recommendedTracks,
            recommendedAlbums = recommendedAlbums
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    init {
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
        if (enabled) _selectedTrack.value?.let { viewModelScope.launch { enqueueSmartNext(it) } }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = value
        preferences.edit().putInt("crossfade_seconds", value).apply()
        player.setCrossfadeSeconds(value)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) { repository.toggleFavorite(track.id) }
    }

    fun isFavorite(trackId: String): Boolean = state.value.favoriteIds.contains(trackId)

    fun removeFromQueue(index: Int) = player.removeQueueItem(index)

    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)

    fun setEqualizerEnabled(enabled: Boolean) { equalizer.setEnabled(enabled) }

    fun setEqualizerBand(index: Int, level: Short) {
        equalizer.setBandLevel(index, level)
        _equalizerBands.value = equalizer.bands()
    }

    fun nextTrack() {
        val current = _selectedTrack.value ?: return
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

    fun createAlbum(title: String, tracks: List<Track>) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createAlbum("album:custom:${java.util.UUID.randomUUID()}", title.trim(), tracks)
        }
    }


    fun createAlbumFromRemote(title: String, track: CatalogTrack) {
        if (title.isBlank()) return
        val albumId = "album:custom:${java.util.UUID.randomUUID()}"
        viewModelScope.launch(Dispatchers.IO) {
            database.albumDao().upsertAll(listOf(AlbumEntity(albumId, title.trim(), track.artistId, track.artistName, 0, track.coverUri)))
            addRemoteTrackToAlbum(track, albumId)
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

    fun openPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            repository.playlistTracks(playlist.id).collect { _selectedPlaylistTracks.value = it }
        }
    }

    fun clearSelection() {
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
