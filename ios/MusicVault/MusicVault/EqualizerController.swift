import Foundation
import AVFoundation

/// Equalizer controller using AVAudioEngine with AVAudioUnitEQ.
/// Supports local file playback with 10-band parametric EQ.
/// Does NOT affect remote streaming previews (AVPlayer-based).
/// Configure bands in Settings; applied only to local file playback via this engine.
@MainActor
final class EqualizerController: ObservableObject {
    static let shared = EqualizerController()

    // 10 bands matching Android's NativeEqualizer frequencies (Hz)
    static let bandFrequencies: [Float] = [
        32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    ]

    @Published var bands: [Band] = Self.defaultBands()
    @Published var isEnabled = false {
        didSet { updateEQ() }
    }

    private let engine = AVAudioEngine()
    private let eqNode = AVAudioUnitEQ(numberOfBands: 10)
    private var playerNode: AVAudioPlayerNode?
    private var audioFile: AVAudioFile?
    private var playbackCompletion: (() -> Void)?

    static func defaultBands() -> [Band] {
        (0..<10).map { index in
            Band(
                index: index,
                frequency: bandFrequencies[index],
                level: 0.0, // dB
                minLevel: -12.0,
                maxLevel: 12.0
            )
        }
    }

    struct Band: Identifiable, Equatable {
        let index: Int
        let frequency: Float
        var level: Double // dB, -12 to +12
        let minLevel: Double
        let maxLevel: Double
        var id: Int { index }
    }

    private init() {
        // EQ node is always in the chain; gain applied per band when enabled
        engine.attach(eqNode)
    }

    func setBand(_ index: Int, level: Double) {
        guard bands.indices.contains(index) else { return }
        let clamped = min(max(level, -12.0), 12.0)
        bands[index].level = clamped
        if isEnabled { updateEQ() }
    }

    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
    }

    private func updateEQ() {
        for (index, band) in bands.enumerated() {
            eqNode.bands[index].frequency = band.frequency
            eqNode.bands[index].gain = isEnabled ? Float(band.level) : 0
            eqNode.bands[index].bypass = !isEnabled
        }
    }

    /// Play a local file through the equalizer engine.
    /// Call from LibraryStore.play when track.isLocal and equalizer enabled.
    func playLocalFile(url: URL, completion: @escaping () -> Void) throws {
        // Stop any current playback
        stop()

        // Create player node and connect: playerNode -> eqNode -> mainMixer
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: eqNode, format: nil)
        engine.connect(eqNode, to: engine.mainMixerNode, format: nil)

        let file = try AVAudioFile(forReading: url)
        audioFile = file
        playerNode = node

        let format = file.processingFormat
        let frameCount = AVAudioFrameCount(file.length)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
            throw NSError(domain: "EqualizerController", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create buffer"])
        }
        try file.read(into: buffer)

        // Install completion handler
        playbackCompletion = { [weak self] in
            Task { @MainActor [weak self] in
                self?.stop()
            }
        }
        node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.playbackCompletion?()
        }

        // Start engine if not running
        if !engine.isRunning {
            try engine.start()
        }
        node.play()
    }

    func stop() {
        playerNode?.stop()
        playerNode = nil
        audioFile = nil
        if engine.isRunning {
            engine.stop()
        }
        engine.detach(eqNode) // Will be reattached on next play
        engine.attach(eqNode)
    }

    func pause() {
        playerNode?.pause()
    }

    func resume() {
        playerNode?.play()
    }

    var isPlaying: Bool {
        playerNode?.isPlaying ?? false
    }
}