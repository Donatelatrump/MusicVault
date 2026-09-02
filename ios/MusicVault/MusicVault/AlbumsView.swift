import SwiftUI

/// Playlists + custom albums manager — the iOS mirror of the Android
/// "Álbumes" tab (playlists + custom albums + the persistent Favoritos).
struct AlbumsView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine

    @State private var showCreatePlaylist = false
    @State private var newPlaylistTitle = ""
    @State private var showCreateAlbum = false
    @State private var newAlbumTitle = ""
    @State private var showAlbumTrackPicker = false
    @State private var selectedTrackIdsForAlbum: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header

                // Favorites card (always first)
                if let favorites = store.favoritesPlaylist {
                    playlistCard(favorites)
                        .padding(.horizontal, 20)
                }

                // User playlists
                if !store.userPlaylists.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "TUS LISTAS")
                        ForEach(store.userPlaylists) { playlist in
                            playlistCard(playlist)
                        }
                    }
                    .padding(.horizontal, 20)
                }

                // Custom albums
                if !store.customAlbums.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "MIS ÁLBUMES")
                        ForEach(store.customAlbums) { album in
                            playlistCard(album) // same rendering; detail opens same
                        }
                    }
                    .padding(.horizontal, 20)
                }

                if store.userPlaylists.isEmpty && store.customAlbums.isEmpty {
                    emptyState
                }
            }
            .padding(.bottom, 20)
        }
        .alert("Nueva lista", isPresented: $showCreatePlaylist) {
            TextField("Nombre", text: $newPlaylistTitle)
            Button("Crear") {
                let title = newPlaylistTitle.trimmingCharacters(in: .whitespaces)
                if !title.isEmpty { Task { await store.createPlaylist(title: title) } }
                newPlaylistTitle = ""
            }
            Button("Cancelar", role: .cancel) { newPlaylistTitle = "" }
        }
        .alert("Nuevo álbum personalizado", isPresented: $showCreateAlbum) {
            TextField("Nombre del álbum", text: $newAlbumTitle)
            Button("Siguiente") {
                let title = newAlbumTitle.trimmingCharacters(in: .whitespaces)
                guard !title.isEmpty else { newAlbumTitle = ""; return }
                // Open track picker for this new album
                newAlbumTitle = title
                selectedTrackIdsForAlbum.removeAll()
                showAlbumTrackPicker = true
            }
            Button("Cancelar", role: .cancel) { newAlbumTitle = "" }
        }
        .sheet(isPresented: $showAlbumTrackPicker) {
            AlbumTrackPickerView(albumTitle: newAlbumTitle, selectedIds: $selectedTrackIdsForAlbum)
                .environmentObject(store)
        }
    }

    private var header: some View {
        HStack {
            Text("Álbumes")
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
            Spacer()
            HStack(spacing: 8) {
                Button { showCreateAlbum = true } label: {
                    Image(systemName: "plus.rectangle.on.folder.fill")
                        .font(.system(size: 24))
                        .foregroundColor(VaultColors.accent)
                }
                .buttonStyle(.plain)
                Button { showCreatePlaylist = true } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 26))
                        .foregroundColor(VaultColors.accent)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray")
                .font(.system(size: 38))
                .foregroundColor(VaultColors.textTertiary)
            Text("Sin listas ni álbumes")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(VaultColors.textPrimary)
            Text("Crea tu primera lista o álbum personalizado con el botón +.")
                .font(.system(size: 13))
                .foregroundColor(VaultColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private func playlistCard(_ playlist: Playlist) -> some View {
        let isFavorites = playlist.id == "playlist:favorites"
        let isCustomAlbum = playlist.id.hasPrefix("album:custom:")

        return HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(VaultColors.surface2)
                Image(systemName: isFavorites ? "heart.fill" : (isCustomAlbum ? "square.stack.fill" : "music.note.list"))
                    .foregroundColor(isFavorites ? VaultColors.favorite : (isCustomAlbum ? VaultColors.accentDeep : VaultColors.accent))
            }
            .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 3) {
                Text(playlist.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(VaultColors.textPrimary)
                Text(isFavorites ? "Tu colección permanente" :
                        isCustomAlbum ? "Álbum personalizado" :
                        playlist.playlistDescription.isEmpty ? "Lista personalizada" : playlist.playlistDescription)
                    .font(.system(size: 12))
                    .foregroundColor(VaultColors.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 12))
                .foregroundColor(VaultColors.textTertiary)
        }
        .padding(12)
        .vaultCard()
        .contentShape(Rectangle())
        .onTapGesture { Task { await store.openPlaylist(playlist) } }
        .contextMenu {
            if !playlist.id.hasPrefix("playlist:favorites") {
                Button(role: .destructive) {
                    Task { await store.deleteCollection(playlist) }
                } label: {
                    Label("Eliminar", systemImage: "trash")
                }
            }
        }
    }
}

struct PlaylistDetailView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    let playlist: Playlist

    private var isFavorites: Bool { playlist.id == "playlist:favorites" }
    private var isCustomAlbum: Bool { playlist.id.hasPrefix("album:custom:") }

    var body: some View {
        ZStack {
            VaultColors.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 14) {
                        Button { store.closePlaylist() } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundColor(VaultColors.textPrimary)
                        }
                        .buttonStyle(.plain)
                        ZStack {
                            RoundedRectangle(cornerRadius: 12).fill(VaultColors.surface2)
                            Image(systemName: isCustomAlbum ? "square.stack.fill" : (isFavorites ? "heart.fill" : "music.note.list"))
                                .font(.system(size: 28))
                                .foregroundColor(isFavorites ? VaultColors.favorite : (isCustomAlbum ? VaultColors.accentDeep : VaultColors.accent))
                        }
                        .frame(width: 76, height: 76)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(playlist.title)
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundColor(VaultColors.textPrimary)
                            Text("\(store.selectedPlaylistTracks.count) canciones")
                                .font(.vaultMono)
                                .foregroundColor(VaultColors.textSecondary)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 20)

                    if store.selectedPlaylistTracks.count > 1 {
                        Button {
                            let tracks = store.selectedPlaylistTracks
                            Task { await store.play(tracks[0], from: tracks) }
                        } label: {
                            Label("Reproducir todo", systemImage: "play.fill")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(VaultColors.onAccent)
                                .padding(.horizontal, 18)
                                .padding(.vertical, 10)
                                .background(RoundedRectangle(cornerRadius: 22).fill(VaultColors.accent))
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 20)
                    }

                    if store.selectedPlaylistTracks.isEmpty {
                        Text("Este \(isCustomAlbum ? "álbum" : "lista") está vacío. Añade canciones desde el menú ⋯ de cualquier fila.")
                            .font(.system(size: 13))
                            .foregroundColor(VaultColors.textSecondary)
                            .padding(24)
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else {
                        VStack(spacing: 2) {
                            ForEach(store.selectedPlaylistTracks) { track in
                                HStack(spacing: 0) {
                                    TrackRow(track: track) {
                                        Task { await store.play(track, from: store.selectedPlaylistTracks) }
                                    }
                                    if !(isFavorites || isCustomAlbum) {
                                        Button {
                                            Task { await store.removeTrackFromPlaylist(playlist.id, track: track) }
                                        } label: {
                                            Image(systemName: "minus.circle")
                                                .foregroundColor(VaultColors.textTertiary)
                                                .padding(.horizontal, 8)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 20)
                            }
                        }
                    }
                }
                .padding(.top, 10)
                .padding(.bottom, 30)
            }
        }
    }
}

/// Picker for selecting tracks when creating a custom album.
struct AlbumTrackPickerView: View {
    @EnvironmentObject var store: LibraryStore
    @Environment(\.dismiss) private var dismiss

    let albumTitle: String
    @Binding var selectedIds: Set<String>
    @State private var titleText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Nombre del álbum", text: $titleText)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(VaultColors.textPrimary)
                    .padding(16)

                List {
                    ForEach(store.localTracks) { track in
                        HStack(spacing: 12) {
                            CoverView(url: track.coverURL, cacheKey: track.id, cornerRadius: 6)
                                .frame(width: 40, height: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.title).font(.system(size: 14, weight: .semibold)).foregroundColor(VaultColors.textPrimary).lineLimit(1)
                                Text(track.artistName).font(.system(size: 12)).foregroundColor(VaultColors.textSecondary).lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: selectedIds.contains(track.id) ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(selectedIds.contains(track.id) ? VaultColors.accent : VaultColors.textTertiary)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if selectedIds.contains(track.id) { selectedIds.remove(track.id) }
                            else { selectedIds.insert(track.id) }
                        }
                    }
                }
            }
            .navigationTitle("Elegir pistas")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crear") {
                        let title = albumTitle.trimmingCharacters(in: .whitespaces)
                        if !title.isEmpty && !selectedIds.isEmpty {
                            let trackIds = Array(selectedIds)
                            let tracks = store.localTracks.filter { selectedIds.contains($0.id) }
                            Task { await store.createAlbum(title: title, tracks: tracks) }
                        }
                        dismiss()
                    }
                    .disabled(selectedIds.isEmpty)
                }
            }
        }
        .presentationDetents([.large])
        .background(VaultColors.background)
    }
}