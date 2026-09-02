import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    @Binding var showSettings: Bool
    @State private var greeting = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if !store.recommendations.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "PARA TI")
                        carousel(store.recommendations.map(\.asTrack))
                    }
                }
                if !store.favorites.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "TUS FAVORITOS")
                        carousel(store.favorites)
                    }
                }
                if !store.localTracks.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "IMPORTADAS RECIENTES")
                        VStack(spacing: 2) {
                            ForEach(Array(store.localTracks.prefix(8))) { track in
                                TrackRow(track: track) {
                                    Task { await store.play(track, from: store.localTracks) }
                                }
                                .padding(.horizontal, 20)
                            }
                        }
                    }
                }
                if store.recommendations.isEmpty && store.localTracks.isEmpty {
                    emptyVault
                }
            }
            .padding(.top, 8)
        }
        .onAppear { greeting = Self.greetingText() }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text(greeting)
                .font(.vaultDisplay)
                .foregroundColor(VaultColors.textPrimary)
            Spacer()
            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .foregroundColor(VaultColors.textSecondary)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(VaultColors.surface1))
                    .overlay(Circle().strokeBorder(VaultColors.divider))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
    }

    private var emptyVault: some View {
        VStack(spacing: 10) {
            Image(systemName: "archivebox")
                .font(.system(size: 40))
                .foregroundColor(VaultColors.textTertiary)
            Text("Tu bóveda está vacía")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(VaultColors.textPrimary)
            Text("Busca música para escuchar previews o importa tus archivos desde la pestaña Biblioteca.")
                .font(.system(size: 13))
                .foregroundColor(VaultColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .padding(.top, 80)
    }

    private func carousel(_ tracks: [Track]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(tracks) { track in
                    VStack(alignment: .leading, spacing: 6) {
                        CoverView(url: track.coverURL, cacheKey: track.id, cornerRadius: 12)
                            .frame(width: 150, height: 150)
                            .pressable {
                                Task { await store.play(track, from: tracks) }
                            }
                        Text(track.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(engine.currentTrack?.id == track.id ? VaultColors.accent : VaultColors.textPrimary)
                            .lineLimit(1)
                        Text(track.artistName)
                            .font(.system(size: 11))
                            .foregroundColor(VaultColors.textSecondary)
                            .lineLimit(1)
                    }
                    .frame(width: 150)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private static func greetingText() -> String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12: return "Buenos días"
        case 12..<19: return "Buenas tardes"
        default: return "Buenas noches"
        }
    }
}
