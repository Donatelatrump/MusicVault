// MusicVaultWidget.swift
// Home screen widget showing now playing with play/pause/next controls.

import WidgetKit
import SwiftUI
import MediaPlayer

struct MusicVaultWidget: Widget {
    let kind: String = "MusicVaultWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            MusicVaultWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("MusicVault")
        .description("Muestra la canción actual y controles de reproducción.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> SimpleEntry {
        SimpleEntry(date: Date(), title: "MusicVault", artist: "Tu bóveda", isPlaying: false, artwork: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (SimpleEntry) -> Void) {
        let entry = SimpleEntry(date: Date(), title: "MusicVault", artist: "Tu bóveda", isPlaying: false, artwork: nil)
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SimpleEntry>) -> Void) {
        // Update every 30 seconds when playing, 5 minutes when paused
        let controller = MPRemoteCommandCenter.shared()
        let nowPlaying = MPNowPlayingInfoCenter.default().nowPlayingInfo
        let title = nowPlaying?[MPMediaItemPropertyTitle] as? String ?? "MusicVault"
        let artist = nowPlaying?[MPMediaItemPropertyArtist] as? String ?? "Tu bóveda"
        let isPlaying = (nowPlaying?[MPNowPlayingInfoPropertyPlaybackRate] as? Double ?? 0) > 0
        let artworkData = (nowPlaying?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork)?.image(at: CGSize(width: 200, height: 200))?.pngData()

        let entry = SimpleEntry(date: Date(), title: title, artist: artist, isPlaying: isPlaying, artwork: artworkData)
        let nextUpdate = Date().addingTimeInterval(isPlaying ? 30 : 300)
        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
        completion(timeline)
    }
}

struct SimpleEntry: TimelineEntry {
    let date: Date
    let title: String
    let artist: String
    let isPlaying: Bool
    let artwork: Data?
}

struct MusicVaultWidgetEntryView: View {
    var entry: SimpleEntry

    var body: some View {
        ZStack {
            VaultColors.background.ignoresSafeArea()

            VStack(spacing: 8) {
                if let data = entry.artwork, let uiImage = UIImage(data: data) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(1, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .frame(maxHeight: 120)
                } else {
                    Image(systemName: "music.note")
                        .font(.system(size: 40))
                        .foregroundColor(VaultColors.accent)
                        .frame(height: 120)
                }

                VStack(spacing: 2) {
                    Text(entry.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(VaultColors.textPrimary)
                        .lineLimit(1)
                    Text(entry.artist)
                        .font(.system(size: 11))
                        .foregroundColor(VaultColors.textSecondary)
                        .lineLimit(1)
                }

                HStack(spacing: 20) {
                    Link(destination: URL(string: "musicvault://previous")!) {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 20))
                            .foregroundColor(VaultColors.textPrimary)
                    }
                    Link(destination: URL(string: "musicvault://playpause")!) {
                        Image(systemName: entry.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(VaultColors.accent)
                    }
                    Link(destination: URL(string: "musicvault://next")!) {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 20))
                            .foregroundColor(VaultColors.textPrimary)
                    }
                }
                .padding(.top, 4)
            }
            .padding()
        }
    }
}

// Colors mirror VaultColors for widget (can't use extension in widget target easily)
extension Color {
    static let widgetBackground = Color(red: 0x12/255, green: 0x11/255, blue: 0x10/255)
    static let widgetTextPrimary = Color(red: 0xF1/255, green: 0xEA/255, blue: 0xD9/255)
    static let widgetTextSecondary = Color(red: 0xB3/255, green: 0xA9/255, blue: 0x92/255)
    static let widgetAccent = Color(red: 0xE0/255, green: 0xA4/255, blue: 0x58/255)
}

struct VaultColors {
    static let background = Color.widgetBackground
    static let textPrimary = Color.widgetTextPrimary
    static let textSecondary = Color.widgetTextSecondary
    static let accent = Color.widgetAccent
}

#Preview(as: .systemSmall) {
    MusicVaultWidget()
} timeline: {
    SimpleEntry(date: Date(), title: "Canción de prueba", artist: "Artista", isPlaying: true, artwork: nil)
}