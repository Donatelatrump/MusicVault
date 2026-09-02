import Foundation
import SwiftUI

/// Single source of truth for library state — the iOS mirror of
/// MusicVaultViewModel, minus the player internals (those live in
/// PlayerEngine; this store wires history, smart successor and queue ops).
@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var tracks: [Track] = []
    @Published private(set) var localTracks: [Track] = []
    @Published private(set) var playlists: [Playlist] = []
    @Published private(set) var favoriteIds: Set<String> = []
    @Published private(set) var recentSearches: [String] = []
    @Published private(set) var recommendations: [CatalogTrack] = []

    // Catalog search results for the Search tab.
    @Published var searchResults: [CatalogTrack] = []
    @Published var albumResults: [CatalogAlbum] = []
    @Published var isSearching = false
    @Published var searchError: String?

    // Playlist / custom album detail overlays (Android-style).
    @Published var selectedPlaylist: Playlist?
    @Published private(set) var selectedPlaylistTracks: [Track] = []
    @Published var selectedCatalogAlbum: CatalogAlbum?
    @Published private(set) var catalogAlbumTracks: [CatalogTrack] = []
    @Published private(set) var loadingCatalogAlbum = false

    // Provider/error banners (mirror of providerMessage/errorMessage).
    @Published var bannerMessage: String?

    // Equalizer (AVAudioEngine-based, local files only)
    @Published var eqEnabled = false
    @Published var eqBands: [EqualizerController.Band] = EqualizerController.defaultBands()

    private let eqController = EqualizerController.shared

    var userPlaylists: [Playlist] {
        playlists.filter { $0.id != "playlist:favorites" && !$0.id.hasPrefix("album:custom:") }
    }

    /// Custom albums reuse the playlists table with the same id scheme as
    /// Android ("album:custom:…").
    var customAlbums: [Playlist] {
        playlists.filter { $0.id.hasPrefix("album:custom:") }
    }

    /// The permanent Favorites collection (id "playlist:favorites").
    var favoritesPlaylist: Playlist? {
        playlists.first { $0.id == "playlist:favorites" }
    }

    var favorites: [Track] { tracks.filter { favoriteIds.contains($0.id) } }

    private var searchTask: Task<Void, Never>?
    private var lastHistoryUpdateAt: Double = 0
    private var currentHistoryId: Int64?
    private var currentHistoryTrackId: String?
    private var appendingSuccessor = false

    init() {
        Task {
            await seedDemoPlaylistsIfNeeded()
            await reloadAll()
        }
    }

    func reloadAll() async {
        tracks = await Database.shared.tracks()
        localTracks = tracks.filter(\.isLocal)
        playlists = await Database.shared.playlists()
        favoriteIds = await Database.shared.favoriteIds()
        recentSearches = await Database.shared.recentSearches()
        recommendations = await CatalogService.shared.recommendations()
    }

    /// Parity with Android's DemoCatalog: the three starter playlists appear
    /// on first launch only (flag-guarded so deleting them stays permanent).
    private func seedDemoPlaylistsIfNeeded() async {
        guard !UserDefaults.standard.bool(forKey: "demo_playlists_seeded") else { return }
        UserDefaults.standard.set(true, forKey: "demo_playlists_seeded")
        await Database.shared.createPlaylist(id: "playlist:demo-night", title: "Night Drive", description: "Synths, lights and late-night roads.")
        await Database.shared.createPlaylist(id: "playlist:demo-focus", title: "Focus Mode", description: "A calm selection for studying and coding.")
        await Database.shared.createPlaylist(id: "playlist:demo-mix", title: "Daily Mix", description: "A local mix generated from your listening history.")
    }

    // MARK: - Playback wiring

    /// Catalog tracks must exist as rows before favorites/playlist references.
    private func materialize(_ track: Track) async {
        await Database.shared.insertTrackIfAbsent(track)
        if !tracks.contains(where: { $0.id == track.id }) {
            tracks.append(track)
            tracks.sort { $0.title < $1.title }
        }
    }

    func play(_ track: Track, from queue: [Track]? = nil) async {
        let playable = (queue ?? [track]).filter { $0.audioURL != nil }
        // Materialize the whole queue so lock-screen transitions keep the
        // history join valid (mirror of Android's catalog placeholder rows).
        for item in playable where !item.isLocal {
            await materialize(item)
        }
        let index = playable.firstIndex(where: { $0.id == track.id }) ?? 0

        // If first track is local and EQ enabled, play through equalizer engine
        if track.isLocal, eqEnabled, let url = track.audioURL {
            do {
                try eqController.playLocalFile(url: url) {
                    Task { @MainActor in
                        // When EQ playback ends, continue with queue via regular engine
                        if let next = playable.dropFirst().first {
                            await self.play(next, from: playable)
                        }
                    }
                }
            } catch {
                bannerMessage = "Error al reproducir con ecualizador: \(error.localizedDescription)"
                // Fallback to regular engine
                engine.playQueue(playable, startIndex: index)
            }
        } else {
            engine.playQueue(playable, startIndex: index)
        }
        await recordPlay(track)
    }

    /// Called by the engine on every transition (including lock-screen skips).
    func recordPlay(_ track: Track) async {
        await Database.shared.recordPlay(trackId: track.id)
        currentHistoryId = await Database.shared.latestHistoryId(trackId: track.id)
        currentHistoryTrackId = track.id
        lastHistoryUpdateAt = Date().timeIntervalSince1970
    }

    /// Completion ratio writer — port of Android's updateLatestCompletion
    /// collector (throttled to 10 s), fed by PlayerEngine.onProgress.
    func updateCompletion(track: Track, position: Double, duration: Double) async {
        guard duration > 0 else { return }
        let now = Date().timeIntervalSince1970
        guard now - lastHistoryUpdateAt >= 10 else { return }
        lastHistoryUpdateAt = now
        if currentHistoryTrackId != track.id || currentHistoryId == nil {
            currentHistoryId = await Database.shared.latestHistoryId(trackId: track.id)
            currentHistoryTrackId = track.id
        }
        guard let historyId = currentHistoryId else { return }
        let ratio = min(max(position / duration, 0), 1)
        await Database.shared.updateCompletion(historyId: historyId, ratio: ratio)
    }

    /// Continuous playback — port of Android's appendSmartSuccessor: when the
    /// queue dies, continue with a related track from the cached catalog.
    func appendSmartSuccessor(after track: Track) async {
        guard !appendingSuccessor else { return }
        appendingSuccessor = true
        defer { appendingSuccessor = false }
        let pool = recommendations.filter { $0.previewUri != nil && $0.id != track.id }
        let candidate = pool.first { candidate in
            candidate.artistName.lowercased() == track.artistName.lowercased() ||
            !engine.queue.contains(where: { $0.id == candidate.asTrack.id })
        } ?? pool.first
        guard let candidate else { return }
        let next = candidate.asTrack
        await materialize(next)
        engine.appendToQueue(next)
        bannerMessage = "Sigue: \(next.title) — \(next.artistName)"
    }

    // MARK: - Queue passthroughs (QueueBottomSheet parity)

    func removeFromQueue(_ index: Int) { engine.removeQueueItem(index) }
    func moveQueueItem(from: Int, to: Int) { engine.moveQueueItem(from: from, to: to) }
    func jumpTo(_ index: Int) { engine.jumpTo(index) }
    func toggleShuffle() { engine.toggleShuffle() }
    func cycleRepeatMode() { engine.cycleRepeatMode() }

    private var engine: PlayerEngine { PlayerEngineHolder.shared.engine }

    // MARK: - Favorites

    func toggleFavorite(_ track: Track) async {
        if favoriteIds.contains(track.id) {
            await Database.shared.removeFavorite(track.id)
            favoriteIds.remove(track.id)
        } else {
            await materialize(track)
            await Database.shared.addFavorite(track.id)
            favoriteIds.insert(track.id)
        }
    }

    // MARK: - Playlists & custom albums

    func createPlaylist(title: String) async {
        let id = "playlist:\(UUID().uuidString)"
        await Database.shared.createPlaylist(id: id, title: title, description: "")
        playlists = await Database.shared.playlists()
    }

    func createAlbum(title: String, tracks: [Track]) async {
        let id = "album:custom:\(UUID().uuidString)"
        await Database.shared.createPlaylist(id: id, title: title, description: "Álbum personalizado")
        for track in tracks {
            await materialize(track)
            await Database.shared.addTrackToPlaylist(id, trackId: track.id)
        }
        playlists = await Database.shared.playlists()
    }

    func deleteCollection(_ playlist: Playlist) async {
        guard playlist.id != "playlist:favorites" else { return }
        await Database.shared.deletePlaylist(id: playlist.id)
        playlists = await Database.shared.playlists()
        if selectedPlaylist?.id == playlist.id { closePlaylist() }
    }

    func openPlaylist(_ playlist: Playlist) async {
        selectedPlaylist = playlist
        selectedPlaylistTracks = await Database.shared.playlistTracks(playlist.id)
    }

    func closePlaylist() {
        selectedPlaylist = nil
        selectedPlaylistTracks = []
    }

    func addTrackToPlaylist(_ playlistId: String, track: Track) async {
        await materialize(track)
        await Database.shared.addTrackToPlaylist(playlistId, trackId: track.id)
        if selectedPlaylist?.id == playlistId {
            selectedPlaylistTracks = await Database.shared.playlistTracks(playlistId)
        }
    }

    func removeTrackFromPlaylist(_ playlistId: String, track: Track) async {
        await Database.shared.removeTrackFromPlaylist(playlistId, trackId: track.id)
        if selectedPlaylist?.id == playlistId {
            selectedPlaylistTracks = await Database.shared.playlistTracks(playlistId)
        }
    }

    // MARK: - Remote catalog albums (RemoteAlbumDetail parity)

    func openCatalogAlbum(_ album: CatalogAlbum) async {
        selectedCatalogAlbum = album
        loadingCatalogAlbum = true
        catalogAlbumTracks = await CatalogService.shared.tracksForAlbum(album)
        loadingCatalogAlbum = false
    }

    func closeCatalogAlbum() {
        selectedCatalogAlbum = nil
        catalogAlbumTracks = []
    }

    // MARK: - Search

    func search(_ query: String) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            searchResults = []
            albumResults = []
            isSearching = false
            return
        }
        isSearching = true
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000) // same 350 ms debounce
            guard !Task.isCancelled else { return }
            async let tracksTask = CatalogService.shared.search(trimmed)
            async let albumsTask = CatalogService.shared.searchAlbums(trimmed)
            let (trackHits, albumHits) = await (tracksTask, albumsTask)
            guard !Task.isCancelled else { return }
            self?.searchResults = trackHits
            self?.albumResults = albumHits
            self?.searchError = trackHits.isEmpty && albumHits.isEmpty
                ? "Sin resultados (revisa tu conexión)." : nil
            self?.isSearching = false
            guard !trackHits.isEmpty else { return }
            await Database.shared.recordSearch(trimmed)
            self?.recentSearches = await Database.shared.recentSearches()
        }
    }

    func clearSearchHistory() async {
        await Database.shared.clearSearchHistory()
        recentSearches = []
    }

    // MARK: - Import / delete

    func importFiles(_ urls: [URL]) async {
        let imported = await LocalImporter.importFiles(urls)
        for track in imported {
            await Database.shared.upsertLocalTrack(track)
        }
        tracks = await Database.shared.tracks()
        localTracks = tracks.filter(\.isLocal)
        if !imported.isEmpty {
            bannerMessage = "Importadas \(imported.count) pista\(imported.count == 1 ? "" : "s") a la bóveda"
        }
    }

    func deleteTrack(_ track: Track) async {
        if track.isLocal { LocalImporter.deleteLocalFile(of: track) }
        await Database.shared.deleteTrack(id: track.id)
        tracks = await Database.shared.tracks()
        localTracks = tracks.filter(\.isLocal)
        favoriteIds = await Database.shared.favoriteIds()
    }
}

/// Indirection so stores/views can reach the engine without a retain cycle.
@MainActor
final class PlayerEngineHolder: ObservableObject {
    static let shared = PlayerEngineHolder()
    let engine = PlayerEngine()
}
