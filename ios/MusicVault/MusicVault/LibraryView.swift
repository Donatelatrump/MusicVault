import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    @Environment(\.openImportPicker) private var openImportPicker

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if !store.favorites.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "FAVORITOS")
                        VStack(spacing: 2) {
                            ForEach(store.favorites) { track in
                                TrackRow(track: track) {
                                    Task { await store.play(track, from: store.favorites) }
                                }
                            }
                        }
                    }
                }
                if !store.localTracks.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "MIS IMPORTACIONES (\(store.localTracks.count))")
                        VStack(spacing: 2) {
                            ForEach(store.localTracks) { track in
                                TrackRow(track: track) {
                                    Task { await store.play(track, from: store.localTracks) }
                                }
                            }
                        }
                    }
                }
                if store.favorites.isEmpty && store.localTracks.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "tray")
                            .font(.system(size: 38))
                            .foregroundColor(VaultColors.textTertiary)
                        Text("Biblioteca vacía")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(VaultColors.textPrimary)
                        Text("Importa audio desde Archivos o marca favoritos en la búsqueda.")
                            .font(.system(size: 13))
                            .foregroundColor(VaultColors.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 80)
                }
            }
            .padding(.top, 8)
        }
    }

    private var header: some View {
        HStack {
            Text("Biblioteca")
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
            Spacer()
            Button {
                openImportPicker.call()
            } label: {
                Label("Importar", systemImage: "plus")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(VaultColors.onAccent)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 18).fill(VaultColors.accent))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
    }
}
