import SwiftUI

struct SearchView: View {
    @EnvironmentObject var store: LibraryStore
    @State private var query = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            Text("Buscar")
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.top, 10)

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(VaultColors.textTertiary)
                TextField("Canciones, artistas…", text: $query)
                    .foregroundColor(VaultColors.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { store.search(query) }
                    .onChange(of: query) { newValue in
                        store.search(newValue)
                    }
                if !query.isEmpty {
                    Button {
                        query = ""
                        store.search("")
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(VaultColors.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(VaultColors.surface1))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(VaultColors.divider))
            .padding(.horizontal, 20)
            .padding(.vertical, 10)

            if store.isSearching {
                Spacer()
                ProgressView().tint(VaultColors.accent)
                Spacer()
            } else if query.trimmingCharacters(in: .whitespaces).isEmpty {
                recentSearches
            } else {
                resultsList
            }
        }
    }

    private var recentSearches: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if !store.recentSearches.isEmpty {
                    HStack {
                        SectionHeader(title: "BÚSQUEDAS RECIENTES")
                        Spacer()
                        Button {
                            Task { await store.clearSearchHistory() }
                        } label: {
                            Text("Limpiar")
                                .font(.vaultMono)
                                .foregroundColor(VaultColors.textTertiary)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 20)
                }
                ForEach(store.recentSearches, id: \.self) { entry in
                    Button {
                        query = entry
                        store.search(entry)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundColor(VaultColors.textTertiary)
                            Text(entry)
                                .foregroundColor(VaultColors.textPrimary)
                                .font(.system(size: 14))
                            Spacer()
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 6)
        }
    }

    private var resultsList: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                // Albums section (mirrors Android's RemoteAlbum results)
                if !store.albumResults.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "ÁLBUMES")
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 14) {
                                ForEach(store.albumResults) { album in
                                    Button {
                                        Task { await store.openCatalogAlbum(album) }
                                    } label: {
                                        VStack(alignment: .leading, spacing: 6) {
                                            CoverView(url: album.coverURL, cacheKey: album.id, cornerRadius: 8)
                                                .frame(width: 150, height: 150)
                                            Text(album.title)
                                                .font(.system(size: 13, weight: .semibold))
                                                .foregroundColor(VaultColors.textPrimary)
                                                .lineLimit(1)
                                            Text(album.artistName)
                                                .font(.system(size: 11))
                                                .foregroundColor(VaultColors.textSecondary)
                                                .lineLimit(1)
                                        }
                                        .frame(width: 150)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                    }

                // Tracks section
                if store.searchResults.isEmpty {
                    Text(store.searchError ?? "Sin resultados para esa búsqueda.")
                        .font(.system(size: 13))
                        .foregroundColor(VaultColors.textSecondary)
                        .padding(.top, 60)
                }
                ForEach(store.searchResults) { catalogTrack in
                    TrackRow(track: catalogTrack.asTrack) {
                        Task { await store.play(catalogTrack.asTrack, from: store.searchResults.map(\.asTrack)) }
                    }
                    .padding(.horizontal, 20)
                }
            }
            .padding(.top, 4)
        }
    }
}
