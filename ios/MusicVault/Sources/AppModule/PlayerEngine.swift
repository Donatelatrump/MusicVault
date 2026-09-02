import Foundation
import AVFoundation
import MediaPlayer
import UIKit

/// The single playback engine — iOS mirror of PlayerController/Media3.
/// One AVPlayer drives both skins (vinyl & circular visualizer) and the whole
/// app: queue management with shuffle/repeat, crossfade (dual-player volume
/// ramp), a 0.5 s progress ticker, Now Playing info and remote-command
/// handling (lock screen / headphones).
@MainActor
final class PlayerEngine: NSObject, ObservableObject {
    @Published private(set) var queue: [Track] = []
    @Published private(set) var queueIndex: Int = -1
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var shuffleEnabled = false
    @Published private(set) var repeatMode: RepeatMode = .off

    enum RepeatMode {
        case off, one, all

        var next: RepeatMode {
            switch self {
            case .off: return .one
            case .one: return .all
            case .all: return .off
            }
        }
    }

    var currentTrack: Track? { queue.indices.contains(queueIndex) ? queue[queueIndex] : nil }
    var hasNextTrack: Bool { queueIndex + 1 < queue.count }

    // Hooks consumed by LibraryStore (history, smart successor).
    var onTrackStarted: ((Track) -> Void)?
    var onProgress: ((Track, Double, Double) -> Void)?   // (track, position, duration) every tick
    var onQueueEnded: (() -> Void)?

    // Primary player; a second player fades in during crossfades.
    private var player = AVPlayer()
    private var fadePlayer: AVPlayer?
    private var fading = false
    private var fadeTimer: Timer?
    private var rateObserver: NSKeyValueObservation?
    private var progressTimer: Timer?
    private var artworkCache: [String: Data] = [:]
    // Original order while shuffle is active.
    private var originalQueue: [Track] = []

    override init() {
        super.init()
        configureAudioSession()
        installRemoteCommands()
        observeInterruptions()
        observeEndOfTrack()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
        attachRateObserver()
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Playback still works in-app; background/lock-screen audio just
            // won't activate. Nothing else to do about it here.
        }
    }

    private func attachRateObserver() {
        rateObserver?.invalidate()
        rateObserver = player.observe(\.rate, options: [.new]) { [weak self] _, change in
            Task { @MainActor [weak self] in
                guard let self, !(self.fading) else { return }
                self.isPlaying = (change.newValue ?? 0) > 0
            }
        }
    }

    private func installRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.resume() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayback() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.nextTrack() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previousTrack() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self, let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self.seek(to: e.positionTime)
            return .success
        }
    }

    private func observeInterruptions() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification, object: nil
        )
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let typeRaw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return }
        if type == .began { pause() }
    }

    func observeEndOfTrack() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(itemDidEnd(_:)),
            name: .AVPlayerItemDidPlayToEndTime, object: nil
        )
    }

    @objc private func itemDidEnd(_ note: Notification) {
        // Ignore end-events from the crossfade's incoming player; only the
        // primary player's item drives the queue forward.
        guard let ended = note.object as? AVPlayerItem, ended == player.currentItem else { return }
        Task { @MainActor [weak self] in self?.handleTrackEnded() }
    }

    // MARK: - Queue / playback API (mirror of PlayerController)

    func play(_ track: Track) {
        queue = [track]
        queueIndex = 0
        loadCurrent(autoPlay: true)
    }

    func playQueue(_ tracks: [Track], startIndex: Int) {
        let playable = tracks.filter { $0.audioURL != nil }
        guard !playable.isEmpty else { return }
        queue = playable
        queueIndex = min(max(0, startIndex), playable.count - 1)
        if shuffleEnabled { reshuffleAroundCurrent() }
        loadCurrent(autoPlay: true)
    }

    func appendToQueue(_ track: Track) {
        guard track.audioURL != nil else { return }
        queue.append(track)
    }

    func jumpTo(_ index: Int) {
        guard queue.indices.contains(index) else { return }
        queueIndex = index
        loadCurrent(autoPlay: true)
    }

    func removeQueueItem(_ index: Int) {
        guard queue.indices.contains(index), index != queueIndex else { return }
        queue.remove(at: index)
        if index < queueIndex { queueIndex -= 1 }
    }

    func moveQueueItem(from: Int, to: Int) {
        guard queue.indices.contains(from), queue.indices.contains(to),
              from != to, from != queueIndex, to != queueIndex else { return }
        let item = queue.remove(at: from)
        queue.insert(item, at: to)
    }

    func toggleShuffle() {
        shuffleEnabled.toggle()
        if shuffleEnabled {
            originalQueue = queue
            reshuffleAroundCurrent()
        } else {
            queue = originalQueue.isEmpty ? queue : originalQueue
            originalQueue = []
            if let current = currentTrack, let idx = queue.firstIndex(where: { $0.id == current.id }) {
                queueIndex = idx
            }
        }
    }

    /// Keeps the current track first so playback continues seamlessly.
    private func reshuffleAroundCurrent() {
        guard queueIndex >= 0, queue.count > 1 else { return }
        let current = queue[queueIndex]
        var others = queue
        others.remove(at: queueIndex)
        others.shuffle()
        queue = [current] + others
        queueIndex = 0
    }

    func cycleRepeatMode() {
        repeatMode = repeatMode.next
    }

    func togglePlayback() {
        if isPlaying { pause() } else { resume() }
    }

    func pause() {
        player.pause()
        if !fading { isPlaying = false }
        updateNowPlaying()
    }

    func resume() {
        guard currentTrack != nil else { return }
        player.play()
        if !fading { isPlaying = true }
        updateNowPlaying()
    }

    func nextTrack() {
        // Manual skip bypasses the crossfade window.
        cancelCrossfade()
        guard hasNextTrack else {
            if repeatMode == .all && !queue.isEmpty {
                queueIndex = 0
                loadCurrent(autoPlay: true)
            } else {
                pause()
            }
            return
        }
        queueIndex += 1
        loadCurrent(autoPlay: true)
    }

    func previousTrack() {
        cancelCrossfade()
        if position > 3 { seek(to: 0) }
        else if queueIndex > 0 {
            queueIndex -= 1
            loadCurrent(autoPlay: true)
        } else {
            seek(to: 0)
        }
    }

    func seek(to seconds: Double) {
        let clamped = max(0, seconds)
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        position = clamped
        updateNowPlaying()
    }

    private func loadCurrent(autoPlay: Bool) {
        guard let track = currentTrack, let url = track.audioURL else { return }
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        position = 0
        duration = track.durationMs / 1000
        isPlaying = autoPlay
        if autoPlay { player.play() }
        updateNowPlaying()
        onTrackStarted?(track)
    }

    // MARK: - Ticker (0.5 s, player-agnostic — survives crossfade swaps)

    private func tick() {
        let seconds = player.currentTime().seconds
        if seconds.isFinite { position = max(0, seconds) }
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        if let track = currentTrack {
            onProgress?(track, position, duration)
        }
        maybeStartCrossfade()
    }

    // MARK: - Track end / repeat / smart successor

    /// Natural end-of-track: repeat modes and smart successor keep playback
    /// alive like Android's continuous playback.
    func handleTrackEnded() {
        if fading { finishCrossfade() }
        switch repeatMode {
        case .one:
            seek(to: 0)
            player.play()
            isPlaying = true
        case .all:
            guard !queue.isEmpty else { return }
            queueIndex = (queueIndex + 1) % queue.count
            loadCurrent(autoPlay: true)
        case .off:
            if hasNextTrack {
                queueIndex += 1
                loadCurrent(autoPlay: true)
            } else {
                isPlaying = false
                updateNowPlaying()
                onQueueEnded?() // LibraryStore may append a smart successor.
            }
        }
    }

    // MARK: - Crossfade (dual AVPlayer volume ramp)

    private var crossfadeSeconds: Double {
        // Same persisted setting as the Android app; UserDefaults reads are
        // in-memory cached, so polling per tick is cheap.
        UserDefaults.standard.double(forKey: "crossfade_seconds")
    }

    private func maybeStartCrossfade() {
        guard !fading,
              crossfadeSeconds > 0,
              isPlaying,
              repeatMode != .one,
              hasNextTrack,
              duration > 0,
              duration - position <= crossfadeSeconds,
              let next = queue[queueIndex + 1].audioURL else { return }

        let incoming = AVPlayer()
        incoming.volume = 0
        incoming.replaceCurrentItem(with: AVPlayerItem(url: next))
        incoming.play()
        fadePlayer = incoming
        fading = true

        let window = crossfadeSeconds
        let startedAt = Date()
        fadeTimer?.invalidate()
        fadeTimer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.rampCrossfade(window: window, startedAt: startedAt) }
        }
    }

    private func rampCrossfade(window: Double, startedAt: Date) {
        guard fading, let incoming = fadePlayer else { return }
        let elapsed = Date().timeIntervalSince(startedAt)
        let progress = min(1, elapsed / window)
        player.volume = Float(1 - progress)
        incoming.volume = Float(progress)
        if progress >= 1 { finishCrossfade() }
    }

    private func finishCrossfade() {
        fadeTimer?.invalidate()
        fadeTimer = nil
        if let incoming = fadePlayer {
            player = incoming
            attachRateObserver()
        }
        fadePlayer = nil
        fading = false
        player.volume = 1
        if queueIndex + 1 < queue.count { queueIndex += 1 }
        position = player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        isPlaying = true
        updateNowPlaying()
        if let track = currentTrack { onTrackStarted?(track) }
    }

    private func cancelCrossfade() {
        guard fading else { return }
        fadeTimer?.invalidate()
        fadeTimer = nil
        fadePlayer?.pause()
        fadePlayer = nil
        fading = false
        player.volume = 1
    }

    // MARK: - Now Playing / lock screen

    private func updateNowPlaying() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artistName,
            MPMediaItemPropertyAlbumTitle: track.albumTitle,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if let coverUri = track.coverUri, let url = URL(string: coverUri) {
            if let data = artworkCache[track.id] ?? fetchData(url) {
                artworkCache[track.id] = data
                if let image = UIImage(data: data) {
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Blocking fetch on purpose: now-playing updates are rare (transitions).
    private func fetchData(_ url: URL) -> Data? {
        if url.isFileURL { return try? Data(contentsOf: url) }
        return try? URLSession.shared.dataSync(from: url)
    }
}

private extension URLSession {
    /// Small synchronous helper for best-effort artwork fetches.
    func dataSync(from url: URL) -> Data? {
        var result: Data?
        let semaphore = DispatchSemaphore(value: 0)
        dataTask(with: url) { data, _, _ in
            result = data
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 5)
        return result
    }
}
