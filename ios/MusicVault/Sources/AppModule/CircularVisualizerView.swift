import SwiftUI

/// Circular visualizer — a faithful SwiftUI port of the Compose
/// CircularVisualizer: the artwork is a large centered disc and the music is
/// drawn as a ring of radial bars. Bass sits at the top and pulses wide and
/// slow (plus a soft beat ring hugging the artwork), mids run down the sides,
/// treble flutters fast and short near the bottom. Values are procedural and
/// driven by the playback state — the audio pipeline is never touched.
struct CircularVisualizerView: View {
    let cover: URL?
    let title: String
    let isPlaying: Bool
    let seed: Int

    @State private var lastPlayFlip = Date()
    @State private var playingOnFlip = false

    private let halfBars = 72

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)

            ZStack {
                TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { timeline in
                    Canvas { context, size in
                        let now = timeline.date
                        let energy = easedEnergy(at: now)
                        drawRing(context: &context, size: size, energy: energy, t: now.timeIntervalSinceReferenceDate)
                    }
                }

                // The artwork: a large centered disc the ring never covers.
                CoverView(url: cover, cacheKey: title, cornerRadius: side * 0.24)
                    .frame(width: side * 0.48, height: side * 0.48)
                    .clipShape(Circle())
                    .overlay(Circle().strokeBorder(VaultColors.surface3, lineWidth: 1.5))
                    .shadow(color: .black.opacity(0.5), radius: 5, x: 0, y: 4)
            }
            .frame(width: side, height: side)
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
        }
        .aspectRatio(1, contentMode: .fit)
        .padding(10)
        .onAppear {
            playingOnFlip = isPlaying
            lastPlayFlip = Date()
        }
        .onChange(of: isPlaying) { playing in
            playingOnFlip = playing
            lastPlayFlip = Date()
        }
    }

    /// Smooth 0.9 s ease between the play/pause energy plateaus (1 ↔ 0.16),
    /// evaluated per timeline tick — no animation state writes needed.
    private func easedEnergy(at date: Date) -> Double {
        let elapsed = date.timeIntervalSince(lastPlayFlip)
        let ramp = min(1, max(0, elapsed / 0.9))
        let eased = ramp * ramp * (3 - 2 * ramp) // smoothstep
        let target: Double = playingOnFlip ? 1.0 : 0.16
        let from: Double = playingOnFlip ? 0.16 : 1.0
        return from + (target - from) * eased
    }

    private func drawRing(context: inout GraphicsContext, size: CGSize, energy: Double, t: Double) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let artRadius = min(size.width, size.height) * 0.24
        let r0 = artRadius + 9
        let maxLen = min(size.width, size.height) / 2 - r0 - 5
        let twoPi = 2 * Double.pi

        let s = Double((seed % 9973)) / 9973
        // Slow dynamics envelope: swells and dips like song sections.
        let swell = 0.72 + 0.28 * sin(t * 0.19 + s * 17) * sin(t * 0.113 + s * 5)
        // Beat pulse: a soft kick that mostly lifts the bass bars.
        let beatHz = 1 / (0.58 + s * 0.3)
        let kickPhase = (t * beatHz + s * 3).truncatingRemainder(dividingBy: 1)
        let kick = energy > 0.5 ? pow(max(0, cos(kickPhase * twoPi)), 20) : 0

        // Hairline ring where the bars start.
        let baseRing = Path(ellipseIn: CGRect(x: center.x - r0 + 1, y: center.y - r0 + 1, width: (r0 - 1) * 2, height: (r0 - 1) * 2))
        context.stroke(baseRing, with: .color(VaultColors.divider), lineWidth: 1)

        // Beat ring that breathes with the kick around the artwork.
        let beatRadius = r0 - 5 + kick * 5
        let beatRing = Path(ellipseIn: CGRect(x: center.x - beatRadius, y: center.y - beatRadius, width: beatRadius * 2, height: beatRadius * 2))
        context.stroke(
            beatRing,
            with: .color(VaultColors.accent.opacity(0.08 + 0.20 * kick * energy)),
            lineWidth: 1.5
        )

        for i in 0..<halfBars {
            let p = Double(i) / Double(halfBars - 1) // 0 = bass (top) → 1 = treble (bottom)
            // Deterministic per-bar phases from the seed.
            let n1 = sin(Double(i) * 12.9898 + Double(seed)) * 43758.5453
            let phase = (n1 - n1.rounded(.down)) * twoPi
            let n2 = sin(Double(i) * 78.233 + Double(seed) * 1.7) * 12543.21
            let phase2 = (n2 - n2.rounded(.down)) * twoPi

            // Temporal speed: bass sways slowly, treble flutters fast.
            let speed = 0.9 + p * p * 6.5
            let osc = 0.5 + 0.5 * sin(t * speed * twoPi * 0.5 + phase)
            let shimmer = 0.5 + 0.5 * sin(t * (1.7 + p * 5) * twoPi * 0.5 + phase2)
            // Spatial traveling wave so the ring moves as a whole.
            let wave = 0.82 + 0.18 * sin(p * 9 - t * 2.1)
            // Amplitude: bass wide, treble short.
            let amp = (1.02 - 0.58 * p) * (0.55 + 0.45 * osc) * (0.62 + 0.38 * shimmer) * wave
            let bassBoost = (1 - p) * (1 - p) * kick * 0.45
            let v = min(max(energy * swell * (amp + bassBoost), 0.05), 1)
            let len = v * maxLen

            let color: Color
            switch p {
            case ..<0.34: color = VaultColors.accentDeep
            case ..<0.67: color = VaultColors.accent
            default: color = VaultColors.ivory
            }
            let strokeColor = color.opacity(0.35 + 0.65 * v)

            // Mirror the bar across the vertical axis: right + left.
            for degrees in [-90 + p * 180, -90 - p * 180] {
                let a = degrees * Double.pi / 180
                let dir = CGPoint(x: cos(a), y: sin(a))
                var bar = Path()
                bar.move(to: CGPoint(x: center.x + dir.x * r0, y: center.y + dir.y * r0))
                bar.addLine(to: CGPoint(x: center.x + dir.x * (r0 + len), y: center.y + dir.y * (r0 + len)))
                context.stroke(bar, with: .color(strokeColor), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
            }
        }
    }
}
