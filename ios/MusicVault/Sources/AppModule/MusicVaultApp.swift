import SwiftUI

@main
struct MusicVaultApp: App {
    @StateObject private var store = LibraryStore()
    @StateObject private var engineHolder = PlayerEngineHolder.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(engineHolder.engine)
                .environment(\.colorScheme, .dark)
        }
    }
}

enum AppTab: String, CaseIterable {
    case home, search, library, albums

    var title: String {
        switch self {
        case .home: return "Inicio"
        case .search: return "Buscar"
        case .library: return "Biblioteca"
        case .albums: return "Álbumes"
        }
    }

    var icon: String {
        switch self {
        case .home: return "house.fill"
        case .search: return "magnifyingglass"
        case .library: return "music.note.list"
        case .albums: return "square.stack.fill"
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine

    @State private var tab: AppTab = .home
    @State private var showFullPlayer = false
    @State private var showSettings = false
    @State private var showImportPicker = false

    var body: some View {
        ZStack {
            VaultColors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if engine.currentTrack != nil {
                    MiniPlayerView(showFullPlayer: $showFullPlayer)
                }
                VaultTabBar(tab: $tab)
            }

            // Playlist detail overlays everything (Android-style detail screen).
            if let playlist = store.selectedPlaylist {
                PlaylistDetailView(playlist: playlist)
                    .environmentObject(store)
                    .environmentObject(engine)
                    .transition(.opacity)
            }

            // Catalog album detail overlay (remote album from search).
            if let album = store.selectedCatalogAlbum {
                CatalogAlbumDetailView(album: album)
                    .environmentObject(store)
                    .environmentObject(engine)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.18), value: store.selectedPlaylist != nil)
        .animation(.easeOut(duration: 0.18), value: store.selectedCatalogAlbum != nil)
        // Must precede the sheets: sheets capture the environment from the
        // point of attachment.
        .environment(\.openImportPicker, OpenImportPickerAction { showImportPicker = true })
        .fullScreenCover(isPresented: $showFullPlayer) {
            FullPlayerView()
                .environmentObject(store)
                .environmentObject(engine)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .environmentObject(store)
        }
        .sheet(isPresented: $showImportPicker) {
            ImportPickerView()
                .environmentObject(store)
        }
        .overlay(alignment: .top) {
            if let msg = store.bannerMessage {
                BannerToast(message: msg)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .onAppear {
            // Wire engine lifecycle hooks to LibraryStore for history,
            // completion updates and smart successor — mirrors the Android
            // ViewModel's player.onPlaybackUiChanged / progress observers.
            engine.onTrackStarted = { track in
                Task { await store.recordPlay(track) }
            }
            engine.onProgress = { track, position, duration in
                Task { await store.updateCompletion(track: track, position: position, duration: duration) }
            }
            engine.onQueueEnded = {
                Task { await store.appendSmartSuccessor(after: engine.currentTrack ?? Track.dummy) }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .home: HomeView(showSettings: $showSettings)
        case .search: SearchView()
        case .library: LibraryView()
        case .albums: AlbumsView()
        }
    }
}

/// Environment hook so any screen can open the import picker hosted at root
/// (the fileImporter must live in a single place for security-scoped URLs).
struct OpenImportPickerAction {
    let call: () -> Void
}

private struct OpenImportPickerKey: EnvironmentKey {
    static let defaultValue = OpenImportPickerAction {}
}

extension EnvironmentValues {
    var openImportPicker: OpenImportPickerAction {
        get { self[OpenImportPickerKey.self] }
        set { self[OpenImportPickerKey.self] = newValue }
    }
}

struct VaultTabBar: View {
    @Binding var tab: AppTab

    var body: some View {
        HStack {
            ForEach(AppTab.allCases, id: \.self) { item in
                tabButton(item)
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(VaultColors.surfaceBase)
        .overlay(alignment: .top) { Rectangle().fill(VaultColors.divider).frame(height: 1) }
    }

    private func tabButton(_ item: AppTab) -> some View {
        let active = tab == item
        return Button {
            tab = item
        } label: {
            VStack(spacing: 3) {
                Image(systemName: item.icon)
                    .font(.system(size: 17, weight: .semibold))
                Text(item.title)
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundColor(active ? VaultColors.accent : VaultColors.textTertiary)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}
