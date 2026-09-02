import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject var engine: PlayerEngine
    @Binding var showFullPlayer: Bool

    var body: some View {
        let track = engine.currentTrack
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                CoverView(url: track?.coverURL, cacheKey: track?.id ?? "", cornerRadius: 6)
                    .frame(width: 42, height: 42)
                VStack(alignment: .leading, spacing: 2) {
                    Text(track?.title ?? "")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(VaultColors.textPrimary)
                        .lineLimit(1)
                    Text(track?.artistName ?? "")
                        .font(.system(size: 11))
                        .foregroundColor(VaultColors.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    engine.togglePlayback()
                } label: {
                    Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(VaultColors.textPrimary)
                        .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
            .contentShape(Rectangle())
            .onTapGesture { showFullPlayer = true }

            // Thin progress line (0.5 s ticker).
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(VaultColors.divider).frame(height: 2)
                    Rectangle()
                        .fill(VaultColors.accent)
                        .frame(width: geo.size.width * progressFraction, height: 2)
                }
            }
            .frame(height: 2)
        }
        .background(VaultColors.surfaceBase)
        .overlay(alignment: .top) { Rectangle().fill(VaultColors.divider).frame(height: 1) }
    }

    private var progressFraction: Double {
        guard engine.duration > 0 else { return 0 }
        return min(1, max(0, engine.position / engine.duration))
    }
}
