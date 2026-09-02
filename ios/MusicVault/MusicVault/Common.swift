import SwiftUI

// MARK: - Cover art

/// Album cover with the deterministic spine-gradient placeholder.
struct CoverView: View {
    let url: URL?
    let cacheKey: String
    var cornerRadius: CGFloat = 8

    var body: some View {
        let (accent, soft) = gradientFor(cacheKey)
        ZStack {
            Rectangle().fill(LinearGradient(colors: [soft, accent.opacity(0.4)], startPoint: .topLeading, endPoint: .bottomTrailing))
            if let url {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Image(systemName: "music.note")
                            .foregroundColor(VaultColors.textTertiary)
                    }
                }
            } else {
                Image(systemName: "music.note")
                    .foregroundColor(VaultColors.textTertiary)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

// MARK: - Rows

struct TrackRow: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    let track: Track
    var onPlay: () -> Void
    @State private var showActions = false

    var body: some View {
        HStack(spacing: 12) {
            CoverView(url: track.coverURL, cacheKey: track.id)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(track.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(engine.currentTrack?.id == track.id ? VaultColors.accent : VaultColors.textPrimary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(track.artistName)
                        .font(.system(size: 12))
                        .foregroundColor(VaultColors.textSecondary)
                        .lineLimit(1)
                    if track.isPreview {
                        Text("PREVIEW 30s")
                            .font(.system(size: 8, weight: .semibold, design: .monospaced))
                            .foregroundColor(VaultColors.textTertiary)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(RoundedRectangle(cornerRadius: 3).fill(VaultColors.surface2))
                    }
                }
            }
            Spacer()
            Button {
                showActions = true
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundColor(VaultColors.textTertiary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onPlay)
        .sheet(isPresented: $showActions) {
            TrackActionsSheet(track: track)
                .environmentObject(store)
                .environmentObject(engine)
        }
    }
}

/// The three-dots menu — port of TrackActionsMenu.
struct TrackActionsSheet: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    @Environment(\.dismiss) private var dismiss
    let track: Track
    @State private var showPlaylistPicker = false

    private var isFavorite: Bool { store.favoriteIds.contains(track.id) }

    var body: some View {
        VStack(spacing: 4) {
            Capsule().fill(VaultColors.surface3).frame(width: 36, height: 4).padding(.top, 8)
            HStack(spacing: 12) {
                CoverView(url: track.coverURL, cacheKey: track.id).frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).font(.system(size: 14, weight: .semibold)).foregroundColor(VaultColors.textPrimary).lineLimit(1)
                    Text(track.artistName).font(.system(size: 12)).foregroundColor(VaultColors.textSecondary).lineLimit(1)
                }
                Spacer()
            }
            .padding(12)

            actionRow("play.fill", isPlayingTrack ? "Reproduciendo" : "Reproducir") {
                Task { await store.play(track) }
                dismiss()
            }
            .disabled(isPlayingTrack)
            actionRow("text.line.first.and.arrowtriangle.forward", "Añadir a la cola") {
                engine.appendToQueue(track)
                dismiss()
            }
            actionRow(isFavorite ? "heart.fill" : "heart", isFavorite ? "Quitar de favoritos" : "Añadir a favoritos") {
                Task { await store.toggleFavorite(track) }
                dismiss()
            }
            actionRow("plus.rectangle.on.folder", "Añadir a lista") {
                showPlaylistPicker = true
            }
            if track.isLocal {
                actionRow("trash", "Eliminar de la bóveda", tint: VaultColors.error) {
                    Task {
                        await store.deleteTrack(track)
                        dismiss()
                    }
                }
            }
            Spacer(minLength: 20)
        }
        .presentationDetents([.medium])
        .background(VaultColors.surface1)
        .sheet(isPresented: $showPlaylistPicker) {
            PlaylistPickerSheet(track: track)
                .environmentObject(store)
        }
    }

    private var isPlayingTrack: Bool { engine.currentTrack?.id == track.id }

    private func actionRow(_ icon: String, _ label: String, tint: Color = VaultColors.textPrimary, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon).frame(width: 22).foregroundColor(tint == VaultColors.textPrimary ? VaultColors.accent : tint)
                Text(label).font(.system(size: 14)).foregroundColor(tint)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Chooser for "add to playlist/album" — lists playlists and custom albums separately.
struct PlaylistPickerSheet: View {
    @EnvironmentObject var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    let track: Track

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Añadir a")
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
                .padding(16)
            ScrollView {
                // Playlists section (includes Favorites)
                VStack(alignment: .leading, spacing: 2) {
                    Text("LISTAS")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .tracking(2)
                        .foregroundColor(VaultColors.accent)
                        .padding(.horizontal, 16)
                    let allPlaylists = (store.favoritesPlaylist.map { [$0] } ?? []) + store.userPlaylists
                    ForEach(allPlaylists) { playlist in
                        Button {
                            Task {
                                await store.addTrackToPlaylist(playlist.id, track: track)
                                dismiss()
                            }
                        } label: {
                            HStack {
                                Image(systemName: playlist.id == "playlist:favorites" ? "heart.fill" : "music.note.list")
                                    .foregroundColor(VaultColors.accent)
                                Text(playlist.title).font(.system(size: 14)).foregroundColor(VaultColors.textPrimary)
                                Spacer()
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 11)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Custom albums section
                if !store.customAlbums.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ÁLBUMES")
                            .font(.system(size: 10, weight: .semibold, design: .monospaced))
                            .tracking(2)
                            .foregroundColor(VaultColors.accent)
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                        ForEach(store.customAlbums) { album in
                            Button {
                                Task {
                                    await store.addTrackToPlaylist(album.id, track: track)
                                    dismiss()
                                }
                            } label: {
                                HStack {
                                    Image(systemName: "square.stack.fill")
                                        .foregroundColor(VaultColors.accentDeep)
                                    Text(album.title).font(.system(size: 14)).foregroundColor(VaultColors.textPrimary)
                                    Spacer()
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 11)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Spacer(minLength: 12)
        }
        .presentationDetents([.medium])
        .background(VaultColors.surface1)
    }
}

// MARK: - Import picker (root-hosted, mirrors SAF folder picking)

struct ImportPickerView: View {
    @EnvironmentObject var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var showPicker = false
    @State private var importing = false

    var body: some View {
        VStack(spacing: 16) {
            Text("Importar audio")
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
                .padding(.top, 24)
            Text("Elige archivos de audio desde la app Archivos. Se copiarán a la bóveda local.")
                .font(.system(size: 13))
                .foregroundColor(VaultColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            if importing {
                ProgressView().tint(VaultColors.accent)
                Text("Importando…").font(.vaultMono).foregroundColor(VaultColors.textTertiary)
            } else {
                Button {
                    showPicker = true
                } label: {
                    Label("Elegir archivos", systemImage: "folder.badge.plus")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(VaultColors.onAccent)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 24).fill(VaultColors.accent))
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(VaultColors.surface1)
        .onAppear { showPicker = true }
        .fileImporter(
            isPresented: $showPicker,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: true,
            onCompletion: { result in
                switch result {
                case .success(let urls) where !urls.isEmpty:
                    importing = true
                    Task {
                        await store.importFiles(urls)
                        importing = false
                        dismiss()
                    }
                default:
                    dismiss()
                }
            }
        )
    }
}

// MARK: - Section header

struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .tracking(2)
            .foregroundColor(VaultColors.accent)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Banner toast (providerMessage / errorMessage mirror)

struct BannerToast: View {
    let message: String

    var body: some View {
        HStack(spacing: 10) {
            Text(message)
                .font(.system(size: 13))
                .foregroundColor(VaultColors.textPrimary)
                .lineLimit(2)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(VaultColors.surface1))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(VaultColors.divider))
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) {
                // The parent view will clear the message when it re-evaluates.
            }
        }
    }
}

// MARK: - Remote catalog album detail (mirrors RemoteAlbumDetail)

struct CatalogAlbumDetailView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    let album: CatalogAlbum

    var body: some View {
        ZStack {
            VaultColors.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 14) {
                        Button {
                            store.closeCatalogAlbum()
                        } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundColor(VaultColors.textPrimary)
                        }
                        .buttonStyle(.plain)
                        CoverView(url: album.coverURL, cacheKey: album.id)
                            .frame(width: 76, height: 76)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(album.title)
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundColor(VaultColors.textPrimary)
                            Text(album.artistName)
                                .font(.vaultMono)
                                .foregroundColor(VaultColors.textSecondary)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 20)

                    if !store.loadingCatalogAlbum && store.catalogAlbumTracks.isEmpty {
                        Text("Este álbum no tiene pistas disponibles.")
                            .font(.system(size: 13))
                            .foregroundColor(VaultColors.textSecondary)
                            .padding(24)
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else {
                        VStack(spacing: 2) {
                            ForEach(store.catalogAlbumTracks) { track in
                                TrackRow(track: track.asTrack) {
                                    Task { await store.play(track.asTrack, from: store.catalogAlbumTracks.map(\.asTrack)) }
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
