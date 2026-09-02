import SwiftUI

/// Turntable deck — a faithful SwiftUI port of the Compose VinylDisc:
/// the record IS the album artwork spinning on a slipmat, the tonearm parks
/// on its rest clip when paused, the pitch-style RPM fader retimes the
/// platter (16–78 RPM, detented at 33⅓/45/78), and the deck hardware
/// (power + LED, speed selector, transport keys) dresses the plinth.
/// The RPM control is purely cosmetic: it never touches the audio pipeline.
struct VinylDiscView: View {
    let cover: URL?
    let title: String
    let isPlaying: Bool

    @AppStorage("vinyl_rpm") private var rpm: Double = 33.33

    // Angle is derived (base + elapsed * speed) and rebased on pause/rpm
    // changes — no per-frame state writes, the same draw-phase trick as the
    // Compose version.
    @State private var baseAngle: Double = 0
    @State private var baseDate = Date()
    @State private var armParked: Double = 1

    private let classicSpeeds: [(Double, String)] = [(33.33, "33"), (45, "45"), (78, "78")]

    private func angle(at date: Date) -> Double {
        let dt = date.timeIntervalSince(baseDate)
        return (baseAngle + dt * rpm / 60 * 360).truncatingRemainder(dividingBy: 360)
    }

    private func rebase() {
        baseAngle = angle(at: Date())
        baseDate = Date()
    }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let recordDiameter = side * 0.64
            let center = CGPoint(x: geo.size.width * 0.36, y: geo.size.height * 0.5)

            ZStack {
                // Plinth.
                RoundedRectangle(cornerRadius: 20)
                    .fill(VaultColors.surface1)
                    .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(VaultColors.divider))

                // Slipmat ring.
                Canvas { context, size in
                    let ringCenter = CGPoint(x: size.width * 0.36, y: size.height * 0.5)
                    let ringRadius = (min(size.width, size.height) * 0.64) / 2 + 4
                    context.fill(
                        Path(ellipseIn: CGRect(x: ringCenter.x - ringRadius - 3, y: ringCenter.y - ringRadius - 3, width: (ringRadius + 3) * 2, height: (ringRadius + 3) * 2)),
                        with: .color(Color(red: 0x26/255, green: 0x22/255, blue: 0x1E/255))
                    )
                    context.stroke(
                        Path(ellipseIn: CGRect(x: ringCenter.x - ringRadius, y: ringCenter.y - ringRadius, width: ringRadius * 2, height: ringRadius * 2)),
                        with: .color(Color(red: 0x14/255, green: 0x12/255, blue: 0x10/255)),
                        lineWidth: 1
                    )
                }

                // Top-left hardware: power key + LED + model plate.
                HStack(spacing: 5) {
                    deckKey("power")
                    Circle()
                        .fill(isPlaying ? VaultColors.ledGreen : VaultColors.divider)
                        .frame(width: 5, height: 5)
                        .animation(.easeInOut(duration: 0.32), value: isPlaying)
                    Text("VAULT HV-01  ·  SIDE A")
                        .font(.system(size: 9, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(VaultColors.textTertiary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.leading, 12)
                .padding(.top, 12)

                // Right-edge hardware panel: speed selector + transport keys.
                VStack(spacing: 4) {
                    ForEach(classicSpeeds, id: \.1) { speed, label in
                        speedPill(label: label, speed: speed)
                    }
                    Spacer(minLength: 4)
                    deckKey("stop.fill")
                    deckKey("backward.fill")
                    deckKey(isPlaying ? "pause.fill" : "play.fill", tint: isPlaying ? VaultColors.accent : VaultColors.textTertiary)
                    deckKey("forward.fill")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                .padding(.trailing, 8)

                // Bottom strip: pitch-style RPM fader + engraved value.
                HStack(spacing: 10) {
                    RpmFader(rpm: $rpm)
                        .frame(height: 34)
                    Text("\(Int(rpm.rounded())) RPM")
                        .font(.system(size: 11, design: .monospaced))
                        .tracking(1)
                        .foregroundColor(VaultColors.accent)
                        .fixedSize()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .padding(.leading, 16)
                .padding(.trailing, 12)
                .padding(.bottom, 12)

                // The record: album artwork spinning on the slipmat.
                TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !isPlaying)) { timeline in
                    CoverView(url: cover, cacheKey: title, cornerRadius: recordDiameter / 2)
                        .frame(width: recordDiameter, height: recordDiameter)
                        .clipShape(Circle())
                        .overlay(Circle().strokeBorder(VaultColors.surface3, lineWidth: 2))
                        .shadow(color: .black.opacity(0.5), radius: 6, x: 0, y: 4)
                        .rotationEffect(.degrees(angle(at: timeline.date)))
                }
                .position(center)

                // Spindle.
                Circle()
                    .fill(VaultColors.background)
                    .overlay(Circle().strokeBorder(VaultColors.divider))
                    .frame(width: 10, height: 10)
                    .position(center)

                // Tonearm (law-of-cosines parking, ported from the Canvas version).
                TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { timeline in
                    Canvas { context, size in
                        drawTonearm(context: &context, size: size, parked: armParked)
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .padding(10)
        .onAppear {
            armParked = isPlaying ? 0 : 1
        }
        .onChange(of: isPlaying) { playing in
            rebase()
            withAnimation(.easeInOut(duration: 0.7)) {
                armParked = playing ? 0 : 1
            }
        }
        .onChange(of: rpm) { _ in
            rebase()
        }
    }

    private func speedPill(label: String, speed: Double) -> some View {
        let selected = abs(rpm - speed) < 1.5
        return Button {
            rpm = speed
        } label: {
            Text(label)
                .font(.system(size: 10, design: .monospaced))
                .foregroundColor(selected ? VaultColors.accent : VaultColors.textTertiary)
                .frame(width: 34)
                .padding(.vertical, 3)
                .background(
                    RoundedRectangle(cornerRadius: 7)
                        .fill(selected ? VaultColors.accentSoft : VaultColors.surface2)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 7)
                        .strokeBorder(selected ? VaultColors.accent : VaultColors.divider)
                )
        }
        .buttonStyle(.plain)
    }

    /// Decorative deck key — pure dressing, performs no action (same as Android).
    private func deckKey(_ icon: String, tint: Color = VaultColors.textTertiary) -> some View {
        Image(systemName: icon)
            .font(.system(size: 11))
            .foregroundColor(tint)
            .frame(width: 24, height: 24)
            .background(Circle().fill(VaultColors.surface2))
            .overlay(Circle().strokeBorder(VaultColors.divider))
    }

    private func drawTonearm(context: inout GraphicsContext, size: CGSize, parked: Double) {
        let recordCenter = CGPoint(x: size.width * 0.36, y: size.height * 0.5)
        let recordRadius = (min(size.width, size.height) * 0.64) / 2
        let pivot = CGPoint(x: size.width * 0.88, y: size.height * 0.14)

        let toCenter = CGPoint(x: recordCenter.x - pivot.x, y: recordCenter.y - pivot.y)
        let pivotToCenter = sqrt(toCenter.x * toCenter.x + toCenter.y * toCenter.y)
        let unit = CGPoint(x: toCenter.x / pivotToCenter, y: toCenter.y / pivotToCenter)
        let armLength = pivotToCenter - recordRadius * 0.80
        let clearance = recordRadius * 1.25

        let cosSwing = (pivotToCenter * pivotToCenter + armLength * armLength - clearance * clearance) /
            (2 * pivotToCenter * armLength)
        let maxSwing = 40.0 * Double.pi / 180
        let swing = min(max(acos(min(max(cosSwing, -1), 1)), 0), maxSwing)

        // Negative swing rotates the arm away from the record onto the rest clip.
        let armAngle = -swing * parked
        let cosA = cos(armAngle), sinA = sin(armAngle)
        let dir = CGPoint(
            x: unit.x * cosA - unit.y * sinA,
            y: unit.x * sinA + unit.y * cosA
        )
        let tip = CGPoint(x: pivot.x + dir.x * armLength, y: pivot.y + dir.y * armLength)
        let armVector = CGPoint(x: tip.x - pivot.x, y: tip.y - pivot.y)

        // Rest clip under the parked tip.
        let restDir = CGPoint(
            x: unit.x * cosSwing + unit.y * sin(swing),
            y: -unit.x * sin(swing) + unit.y * cosSwing
        )
        let restTip = CGPoint(x: pivot.x + restDir.x * armLength, y: pivot.y + restDir.y * armLength)
        var clip = Path()
        clip.move(to: restTip)
        clip.addLine(to: CGPoint(x: restTip.x, y: restTip.y + 14))
        context.stroke(clip, with: .color(Color(red: 0x26/255, green: 0x22/255, blue: 0x1E/255)), style: StrokeStyle(lineWidth: 6, lineCap: .round))

        // Counterweight stub behind the pivot.
        var counterweight = Path()
        counterweight.move(to: pivot)
        counterweight.addLine(to: CGPoint(x: pivot.x - armVector.x * 0.16, y: pivot.y - armVector.y * 0.16))
        context.stroke(counterweight, with: .color(Color(red: 0x8F/255, green: 0x89/255, blue: 0x7D/255)), style: StrokeStyle(lineWidth: 5, lineCap: .round))

        // Arm.
        var arm = Path()
        arm.move(to: pivot)
        arm.addLine(to: tip)
        context.stroke(arm, with: .color(Color(red: 0xB8/255, green: 0xB2/255, blue: 0xA6/255)), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))

        // Pivot + cartridge tip.
        context.fill(Path(ellipseIn: CGRect(x: pivot.x - 7, y: pivot.y - 7, width: 14, height: 14)), with: .color(Color(red: 0x8F/255, green: 0x89/255, blue: 0x7D/255)))
        context.fill(Path(ellipseIn: CGRect(x: pivot.x - 3, y: pivot.y - 3, width: 6, height: 6)), with: .color(Color(red: 0x3A/255, green: 0x36/255, blue: 0x2F/255)))
        context.fill(Path(ellipseIn: CGRect(x: tip.x - 5, y: tip.y - 5, width: 10, height: 10)), with: .color(VaultColors.accent))
    }
}

/// Pitch-style RPM fader: groove track with detents at the classic speeds.
/// Drag or tap retimes the platter fluidly.
private struct RpmFader: View {
    @Binding var rpm: Double
    private let range: ClosedRange<Double> = 16...78

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            func xFor(_ value: Double) -> Double { (value - range.lowerBound) / (range.upperBound - range.lowerBound) * width }
            let thumbX = xFor(rpm)

            ZStack(alignment: .leading) {
                Canvas { context, size in
                    let midY = size.height / 2
                    // Groove track.
                    var track = Path()
                    track.move(to: CGPoint(x: 0, y: midY))
                    track.addLine(to: CGPoint(x: size.width, y: midY))
                    context.stroke(track, with: .color(Color(red: 0x14/255, green: 0x12/255, blue: 0x10/255)), style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    context.stroke(track, with: .color(VaultColors.divider), lineWidth: 1)

                    // Detents at 33⅓ / 45 / 78.
                    for detent in [33.33, 45.0, 78.0] {
                        let x = (detent - range.lowerBound) / (range.upperBound - range.lowerBound) * size.width
                        var tick = Path()
                        tick.move(to: CGPoint(x: x, y: midY - 6))
                        tick.addLine(to: CGPoint(x: x, y: midY + 6))
                        context.stroke(tick, with: .color(VaultColors.surface3), lineWidth: 1.5)
                    }
                }
                // Filled run + brass knob.
                Canvas { context, size in
                    let midY = size.height / 2
                    let x = (rpm - range.lowerBound) / (range.upperBound - range.lowerBound) * size.width
                    var filled = Path()
                    filled.move(to: CGPoint(x: 0, y: midY))
                    filled.addLine(to: CGPoint(x: x, y: midY))
                    context.stroke(filled, with: .color(VaultColors.accentDeep), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    context.fill(Path(ellipseIn: CGRect(x: x - 6, y: midY - 6, width: 12, height: 12)), with: .color(VaultColors.accent))
                    context.fill(Path(ellipseIn: CGRect(x: x - 2, y: midY - 2, width: 4, height: 4)), with: .color(Color(red: 0x14/255, green: 0x12/255, blue: 0x10/255)))
                }
                .allowsHitTesting(false)
                .frame(width: width, height: geo.size.height)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let fraction = min(max(0, value.location.x / max(1, width)), 1)
                        rpm = range.lowerBound + fraction * (range.upperBound - range.lowerBound)
                    }
            )
        }
    }
}
