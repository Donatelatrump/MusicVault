import SwiftUI
import UIKit

/// Vault identity — same warm-black surfaces, brass accent and mono technical
/// labels as the Android app. If custom fonts are bundled in Resources/Fonts,
/// they are used; otherwise system fonts fall back to the Android-equivalent
/// designs (Space Grotesk → rounded, Inter → default, Plex Mono → monospaced).
enum VaultColors {
    static let background = Color(red: 0x12/255, green: 0x11/255, blue: 0x10/255)
    static let surfaceBase = Color(red: 0x17/255, green: 0x15/255, blue: 0x14/255)
    static let surface1 = Color(red: 0x1E/255, green: 0x1B/255, blue: 0x18/255)
    static let surface2 = Color(red: 0x29/255, green: 0x24/255, blue: 0x20/255)
    static let surface3 = Color(red: 0x36/255, green: 0x30/255, blue: 0x28/255)
    static let textPrimary = Color(red: 0xF1/255, green: 0xEA/255, blue: 0xD9/255)
    static let textSecondary = Color(red: 0xB3/255, green: 0xA9/255, blue: 0x92/255)
    static let textTertiary = Color(red: 0x7E/255, green: 0x76/255, blue: 0x66/255)
    static let divider = Color(red: 0x32/255, green: 0x2C/255, blue: 0x25/255)
    static let accent = Color(red: 0xE0/255, green: 0xA4/255, blue: 0x58/255)      // brass
    static let accentDeep = Color(red: 0xB9/255, green: 0x7F/255, blue: 0x35/255)
    static let accentSoft = Color(red: 0x3A/255, green: 0x2E/255, blue: 0x1D/255)
    static let onAccent = Color(red: 0x19/255, green: 0x12/255, blue: 0x07/255)
    static let error = Color(red: 0xD9/255, green: 0x6A/255, blue: 0x55/255)
    static let favorite = Color(red: 0xC9/255, green: 0x6A/255, blue: 0x5E/255)
    static let repeatAll = Color(red: 0x7D/255, green: 0xC4/255, blue: 0xE8/255)
    static let ledGreen = Color(red: 0x7E/255, green: 0x8F/255, blue: 0x6E/255)
    static let ivory = Color(red: 0xD8/255, green: 0xCF/255, blue: 0xBA/255)
}

extension Font {
    /// Loads a custom font from the bundle if available, otherwise falls back.
    private static func customOrSystem(_ name: String, size: CGFloat, weight: Weight = .regular, design: Design? = nil) -> Font {
        if let _ = UIFont(name: name, size: size) {
            return Font.custom(name, size: size, relativeTo: .body).weight(weight)
        }
        return .system(size: size, weight: weight, design: design)
    }

    /// Technical / archive labels (Plex Mono on Android).
    static var vaultMono: Font {
        customOrSystem("IBMPlexMono-Regular", size: 11, weight: .regular, design: .monospaced)
    }

    /// Display titles (Space Grotesk on Android).
    static var vaultDisplay: Font {
        customOrSystem("SpaceGrotesk-Bold", size: 26, weight: .bold, design: .rounded)
    }
}

extension View {
    /// Card look used by settings sections and list containers.
    func vaultCard() -> some View {
        self
            .background(RoundedRectangle(cornerRadius: 14).fill(VaultColors.surface1))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(VaultColors.divider, lineWidth: 1))
    }

    /// Subtle Spotify-style press reaction without losing the tap gesture.
    func pressable(_ action: @escaping () -> Void) -> some View {
        Button(action: action) { self }
            .buttonStyle(PressScaleStyle())
    }
}

struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// Deterministic tone-on-tone accent for a key (spine covers, placeholders).
func gradientFor(_ key: String) -> (Color, Color) {
    let palette: [Color] = [
        Color(red: 0xE0/255, green: 0xA4/255, blue: 0x58/255),
        Color(red: 0xC9/255, green: 0x7B/255, blue: 0x4F/255),
        Color(red: 0x7E/255, green: 0x93/255, blue: 0xA6/255),
        Color(red: 0x7E/255, green: 0x8F/255, blue: 0x6E/255),
        Color(red: 0xA6/255, green: 0x7E/255, blue: 0x93/255),
        Color(red: 0x5F/255, green: 0x8B/255, blue: 0x85/255)
    ]
    var hash: Int = 5381
    for byte in key.utf8 { hash = (hash &* 33) ^ Int(byte) }
    let accent = palette[abs(hash) % palette.count]
    return (accent, accent.opacity(0.25))
}
