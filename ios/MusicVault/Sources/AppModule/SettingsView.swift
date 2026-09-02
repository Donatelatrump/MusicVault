import SwiftUI

/// Settings — the iOS mirror of SettingsScreen. The player style choice is
/// persisted via @AppStorage (UserDefaults) exactly like the Android
/// SharedPreferences key "player_style".
struct SettingsView: View {
    @EnvironmentObject var store: LibraryStore
    @Environment(\.openImportPicker) private var openImportPicker
    @Environment(\.dismiss) private var dismiss

    @AppStorage("player_style") private var playerStyle = "vinyl"
    @AppStorage("vinyl_rpm") private var vinylRpm: Double = 33.33
    @AppStorage("crossfade_seconds") private var crossfadeSeconds: Double = 0

    @State private var eqBands: [EqualizerController.Band] = EqualizerController.defaultBands()

    var body: some View {
        NavigationStack {
            ScrollView {
VStack(alignment: .leading, spacing: 14) {
                appearanceSection
                playbackSection
                equalizerSection
                vaultSection
                aboutSection
            }
                .padding(16)
            }
            .background(VaultColors.background)
            .navigationTitle("Ajustes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.left")
                            .foregroundColor(VaultColors.textPrimary)
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("APARIENCIA")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundColor(VaultColors.accent)

            Text("Estilo del reproductor")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(VaultColors.textPrimary)
            Text("Elige la interfaz del reproductor. La reproducción no cambia.")
                .font(.system(size: 12))
                .foregroundColor(VaultColors.textTertiary)

            HStack(spacing: 10) {
                styleOption(
                    title: "Tornamesa / Vinilo",
                    description: "Disco giratorio con brazo, control de RPM y hardware del deck.",
                    icon: "vinyl.record.fill",
                    selected: playerStyle == "vinyl"
                ) {
                    playerStyle = "vinyl"
                }
                styleOption(
                    title: "Visualizador circular",
                    description: "Portada central rodeada por anillos de graves, medios y agudos.",
                    icon: "waveform.circle.fill",
                    selected: playerStyle == "circular"
                ) {
                    playerStyle = "circular"
                }
            }

            HStack {
                Text("RPM inicial del vinilo")
                    .font(.system(size: 13))
                    .foregroundColor(VaultColors.textSecondary)
                Spacer()
                Text("\(Int(vinylRpm.rounded())) RPM")
                    .font(.vaultMono)
                    .foregroundColor(VaultColors.accent)
            }
            Slider(value: $vinylRpm, in: 16...78, step: 0.5)
                .tint(VaultColors.accent)
        }
        .padding(16)
        .vaultCard()
    }

    private var playbackSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("REPRODUCCIÓN")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundColor(VaultColors.accent)

            Text("Crossfade")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(VaultColors.textPrimary)
            Text(
                crossfadeSeconds == 0
                ? "Desactivado"
                : "Mezcla los últimos \(Int(crossfadeSeconds.rounded())) s con el inicio de la siguiente canción."
            )
                .font(.system(size: 12))
                .foregroundColor(VaultColors.textTertiary)
            HStack {
                Slider(value: $crossfadeSeconds, in: 0...12, step: 1)
                    .tint(VaultColors.accent)
                Text(crossfadeSeconds == 0 ? "OFF" : "\(Int(crossfadeSeconds.rounded())) s")
                    .font(.vaultMono)
                    .foregroundColor(VaultColors.accent)
                    .frame(width: 48, alignment: .trailing)
            }
        }
        .padding(16)
        .vaultCard()
    }

    private var equalizerSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("ECUALIZADOR")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundColor(VaultColors.accent)

            Toggle("Activar ecualizador (solo archivos locales)", isOn: $store.eqEnabled)
                .tint(VaultColors.accent)
                .onChange(of: store.eqEnabled) { enabled in
                    EqualizerController.shared.setEnabled(enabled)
                }

            if store.eqEnabled {
                VStack(spacing: 8) {
                    ForEach($store.eqBands) { $band in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("\(Int(band.frequency)) Hz")
                                    .font(.vaultMono)
                                    .foregroundColor(VaultColors.textSecondary)
                                    .frame(width: 60, alignment: .leading)
                                Text("\(band.level, specifier: "%+.0f") dB")
                                    .font(.vaultMono)
                                    .foregroundColor(VaultColors.accent)
                                    .frame(width: 48, alignment: .trailing)
                            }
                            Slider(value: $band.level, in: band.minLevel...band.maxLevel, step: 0.5)
                                .tint(VaultColors.accent)
                                .onChange(of: band.level) { newLevel in
                                    EqualizerController.shared.setBand(band.index, level: newLevel)
                                }
                        }
                    }
                }
            }
        }
        .padding(16)
        .vaultCard()
    }

    private func styleOption(title: String, description: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundColor(selected ? VaultColors.accent : VaultColors.textSecondary)
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(VaultColors.textPrimary)
                    .multilineTextAlignment(.leading)
                Text(description)
                    .font(.system(size: 10))
                    .foregroundColor(VaultColors.textTertiary)
                    .multilineTextAlignment(.leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(selected ? VaultColors.accentSoft : VaultColors.surface2)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(selected ? VaultColors.accent : VaultColors.divider)
            )
        }
        .buttonStyle(.plain)
    }

    private var vaultSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BÓVEDA")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundColor(VaultColors.accent)

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Importar audio")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(VaultColors.textPrimary)
                    Text("\(store.localTracks.count) archivos locales · \(store.tracks.count) pistas en total")
                        .font(.system(size: 12))
                        .foregroundColor(VaultColors.textSecondary)
                }
                Spacer()
                Button {
                    openImportPicker.call()
                } label: {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 18))
                        .foregroundColor(VaultColors.accent)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(VaultColors.surface2))
                        .overlay(Circle().strokeBorder(VaultColors.divider))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .vaultCard()
    }

    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ACERCA DE")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundColor(VaultColors.accent)
            Text("MusicVault para iOS · v1.0")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(VaultColors.textPrimary)
            Text("Catálogo vía Deezer/iTunes (previews de 30 s), letras por LRCLib. La búsqueda de catálogo se cachea 30 minutos. A diferencia de Android, las descargas completas se sustituyen por importación de archivos.")
                .font(.system(size: 11))
                .foregroundColor(VaultColors.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .vaultCard()
    }
}
