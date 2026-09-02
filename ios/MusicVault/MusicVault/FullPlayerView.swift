import SwiftUI

struct FullPlayerView: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    @Environment(\.dismiss) private var dismiss

    @AppStorage("player_style") private var playerStyle = "vinyl"

    @State private var sliderDragging = false
    @State private var sliderValue: Double = 0
    @State private var showLyrics = false
    @State private var showQueue = false

    var body: some View {
        let track = engine.currentTrack
        ZStack {
            VaultColors.background.ignoresSafeArea()
            if let track {
                VStack(spacing: 0) {
                    header(track)

                    // Stage: the two interchangeable skins, chosen in Settings.
                    Group {
                        if showLyrics {
                            LyricsPanelView(track: track)
                        } else if playerStyle == "circular" {
                            CircularVisualizerView(
                                cover: track.coverURL,
                                title: track.title,
                                isPlaying: engine.isPlaying,
                                seed: Fmt.stableHash(track.id)
                            )
                            .padding(.vertical, 10)
                        } else {
                            VinylDiscView(
                                cover: track.coverURL,
                                title: track.title,
                                isPlaying: engine.isPlaying
                            )
                            .padding(.vertical, 10)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(maxHeight: .infinity)

                    Spacer(minLength: 12)

                    metadata(track)
                    seekBar(track)
                    transport
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 14)
            }
        }
        .sheet(isPresented: $showQueue) { QueueSheet() }
    }

    private func header(_ track: Track) -> some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(VaultColors.textPrimary)
            }
            .buttonStyle(.plain)
            Spacer()
            Text(track.isPreview ? "PREVIEW · 30S" : "BÓVEDA LOCAL")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(1.5)
                .foregroundColor(VaultColors.textTertiary)
            Spacer()
            Button {
                withAnimation(.easeInOut(duration: 0.25)) { showLyrics.toggle() }
            } label: {
                Image(systemName: "text.quote")
                    .font(.system(size: 17))
                    .foregroundColor(showLyrics ? VaultColors.accent : VaultColors.textSecondary)
            }
            .buttonStyle(.plain)
            Button {
                showQueue = true
            } label: {
                Image(systemName: "list.number")
                    .font(.system(size: 17))
                    .foregroundColor(showQueue ? VaultColors.accent : VaultColors.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 10)
    }

    private func metadata(_ track: Track) -> some View {
        let favorite = store.favoriteIds.contains(track.id)
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(Fmt.catalogNumber(track.id))
                    .font(.vaultMono)
                    .tracking(1)
                    .foregroundColor(VaultColors.accent)
                Spacer()
                Button {
                    Task { await store.toggleFavorite(track) }
                } label: {
                    Image(systemName: favorite ? "heart.fill" : "heart")
                        .font(.system(size: 22))
                        .foregroundColor(favorite ? VaultColors.favorite : VaultColors.textPrimary)
                        .scaleEffect(favorite ? 1.1 : 1)
                        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: favorite)
                }
                .buttonStyle(.plain)
            }
            Text(track.title)
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundColor(VaultColors.textPrimary)
                .lineLimit(2)
            Text(track.artistName)
                .font(.system(size: 15))
                .foregroundColor(VaultColors.textSecondary)
                .lineLimit(1)
        }
        .padding(.top, 6)
    }

    private var effectivePosition: Double {
        sliderDragging ? sliderValue : engine.position
    }

    private var duration: Double {
        max(engine.duration, engine.currentTrack.map { $0.durationMs / 1000 } ?? 0, 1)
    }

    private func seekBar(_ track: Track) -> some View {
        VStack(spacing: 2) {
            Slider(
                value: Binding(
                    get: { sliderDragging ? sliderValue : min(engine.position, duration) },
                    set: { sliderValue = $0; sliderDragging = true }
                ),
                in: 0...duration,
                onEditingChanged: { editing in
                    if !editing {
                        sliderDragging = false
                        engine.seek(to: sliderValue)
                    }
                }
            )
            .tint(VaultColors.accent)
            HStack {
                Text(Fmt.time(effectivePosition * 1000))
                    .font(.vaultMono)
                    .foregroundColor(VaultColors.textSecondary)
                Spacer()
                Text(Fmt.time(duration * 1000))
                    .font(.vaultMono)
                    .foregroundColor(VaultColors.textSecondary)
            }
        }
        .padding(.top, 8)
    }

    private var transport: some View {
        HStack {
            Spacer()
            // Shuffle
            Button {
                store.toggleShuffle()
            } label: {
                Image(systemName: "shuffle")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(engine.shuffleEnabled ? VaultColors.accent : VaultColors.textTertiary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            Spacer()
            Button {
                engine.previousTrack()
            } label: {
                Image(systemName: "backward.fill")
                    .font(.system(size: 26))
                    .foregroundColor(VaultColors.textPrimary)
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            Spacer()
            Button {
                engine.togglePlayback()
            } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(VaultColors.onAccent)
                    .frame(width: 68, height: 68)
                    .background(Circle().fill(VaultColors.accent))
                    .scaleEffect(engine.isPlaying ? 1.0 : 0.94)
                    .animation(.spring(response: 0.3, dampingFraction: 0.6), value: engine.isPlaying)
            }
            .buttonStyle(.plain)
            Spacer()
            Button {
                engine.nextTrack()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.system(size: 26))
                    .foregroundColor(VaultColors.textPrimary)
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            Spacer()
            // Repeat
            Button {
                engine.cycleRepeatMode()
            } label: {
                Image(systemName: repeatIcon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(engine.repeatMode != .off ? VaultColors.accent : VaultColors.textTertiary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            Spacer()
        }
        .padding(.top, 4)
    }

    private var repeatIcon: String {
        switch engine.repeatMode {
        case .one: return "repeat.1"
        case .all: return "repeat"
        case .off: return "repeat"
        }
    }
}

/// Queue sheet with reorder, remove, and jump — mirrors Android's QueueBottomSheet.
struct QueueSheet: View {
    @EnvironmentObject var store: LibraryStore
    @EnvironmentObject var engine: PlayerEngine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if engine.queue.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "music.note.list")
                            .font(.system(size: 40))
                            .foregroundColor(VaultColors.textTertiary)
                        Text("La cola está vacía.")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(VaultColors.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(Array(engine.queue.enumerated()), id: \.element.id) { index, track in
                            HStack(spacing: 12) {
                                Text("\(index + 1)")
                                    .font(.vaultMono)
                                    .foregroundColor(VaultColors.textTertiary)
                                    .frame(width: 24, alignment: .trailing)
                                CoverView(url: track.coverURL, cacheKey: track.id, cornerRadius: 6)
                                    .frame(width: 40, height: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(track.title)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(engine.queueIndex == index ? VaultColors.accent : VaultColors.textPrimary)
                                        .lineLimit(1)
                                    Text(track.artistName)
                                        .font(.system(size: 11))
                                        .foregroundColor(VaultColors.textSecondary)
                                        .lineLimit(1)
                                }
                                Spacer()
                                if index != engine.queueIndex {
                                    Button {
                                        store.jumpTo(index)
                                    } label: {
                                        Image(systemName: "play.fill")
                                            .foregroundColor(VaultColors.accent)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                store.jumpTo(index)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if index != engine.queueIndex {
                                    Button(role: .destructive) {
                                        store.removeFromQueue(index)
                                    } label: {
                                        Label("Eliminar", systemImage: "trash")
                                    }
                                }
                            }
                        }
                        .onMove { from, to in
                            store.moveQueueItem(from: from.first!, to: to)
                        }
                    }
                }
            }
            .navigationTitle("Cola de reproducción")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    EditButton()
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cerrar") { dismiss() }
                }
            }
            .environment(\.defaultMinListRowHeight, 60)
        }
        .presentationDetents([.large])
        .background(VaultColors.background)
    }
}

/// Lyrics panel with synced karaoke scrolling and plain lyrics support.
struct LyricsPanelView: View {
    @EnvironmentObject var engine: PlayerEngine
    let track: Track

    @State private var lyrics: Lyrics?
    @State private var isLoading = true
    @State private var activeIndex: Int = -1

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .fill(VaultColors.surface1)
                .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(VaultColors.divider))

            if isLoading {
                VStack(spacing: 12) {
                    ProgressView()
                        .tint(VaultColors.accent)
                    Text("Cargando letras…")
                        .font(.vaultMono)
                        .foregroundColor(VaultColors.textTertiary)
                }
            } else if let lyrics, !lyrics.isEmpty {
                if !lyrics.synced.isEmpty {
                    ScrollViewReader { proxy in
                        ScrollView(showsIndicators: false) {
                            VStack(alignment: .leading, spacing: 18) {
                                ForEach(Array(lyrics.synced.enumerated()), id: \.offset) { index, line in
                                    let isActive = index == activeIndex
                                    Text(line.text)
                                        .font(.system(size: isActive ? 20 : 16, weight: isActive ? .bold : .medium, design: .rounded))
                                        .foregroundColor(isActive ? VaultColors.accent : VaultColors.textTertiary)
                                        .scaleEffect(isActive ? 1.02 : 1.0, anchor: .leading)
                                        .animation(.spring(response: 0.35, dampingFraction: 0.7), value: isActive)
                                        .id(index)
                                        .contentShape(Rectangle())
                                        .onTapGesture {
                                            engine.seek(to: line.timeMs / 1000)
                                        }
                                }
                            }
                            .padding(.horizontal, 24)
                            .padding(.vertical, 32)
                        }
                        .onChange(of: engine.position) { pos in
                            updateActiveLine(positionMs: pos * 1000, lines: lyrics.synced, proxy: proxy)
                        }
                    }
                } else if let plain = lyrics.plain {
                    ScrollView(showsIndicators: false) {
                        Text(plain)
                            .font(.system(size: 15, weight: .regular, design: .rounded))
                            .foregroundColor(VaultColors.textSecondary)
                            .lineSpacing(6)
                            .padding(24)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "text.quote")
                        .font(.system(size: 36))
                        .foregroundColor(VaultColors.textTertiary)
                    Text("Sin letras disponibles")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(VaultColors.textSecondary)
                }
            }
        }
        .task(id: track.id) {
            await loadLyrics()
        }
    }

    private func loadLyrics() async {
        isLoading = true
        lyrics = await LyricsService.shared.fetch(
            artist: track.artistName,
            title: track.title,
            album: track.albumTitle,
            durationMs: track.durationMs
        )
        isLoading = false
    }

    private func updateActiveLine(positionMs: Double, lines: [LyricLine], proxy: ScrollViewProxy) {
        var foundIndex = -1
        for (i, line) in lines.enumerated() {
            if positionMs >= line.timeMs {
                foundIndex = i
            } else {
                break
            }
        }
        if foundIndex != activeIndex {
            activeIndex = foundIndex
            if foundIndex >= 0 {
                withAnimation(.easeInOut(duration: 0.3)) {
                    proxy.scrollTo(foundIndex, anchor: .center)
                }
            }
        }
    }
}