import Foundation

// MARK: - Domain models (mirror of the Room entities on Android)

/// A playable track. Local imports carry a file URL; catalog tracks carry a
/// streaming preview URL (iOS has no yt-dlp, so downloads are replaced by
/// imports from the Files app + provider previews).
struct Track: Identifiable, Hashable, Codable {
    let id: String
    var title: String
    var artistName: String
    var albumId: String
    var albumTitle: String
    var durationMs: Double
    var audioUri: String?       // file path or https URL
    var isLocal: Bool
    var coverUri: String?
    var addedAt: Double

    var audioURL: URL? {
        guard let audioUri, !audioUri.isEmpty else { return nil }
        if audioUri.hasPrefix("/") { return URL(fileURLWithPath: audioUri) }
        return URL(string: audioUri)
    }

    var coverURL: URL? {
        guard let coverUri, !coverUri.isEmpty else { return nil }
        return URL(string: coverUri)
    }

    var isPreview: Bool { !isLocal && (audioUri?.hasPrefix("http") ?? false) }

    static let dummy = Track(
        id: "dummy", title: "", artistName: "", albumId: "", albumTitle: "",
        durationMs: 0, audioUri: nil, isLocal: false, coverUri: nil, addedAt: 0
    )
}

struct Playlist: Identifiable, Hashable, Codable {
    let id: String
    var title: String
    var playlistDescription: String
    var createdAt: Double
}

struct PlayHistoryEntry: Identifiable, Hashable {
    let id: Int64
    var trackId: String
    var playedAt: Double
    var completionRatio: Double
}

/// Metadata result from the public catalog providers (iTunes/Deezer).
struct CatalogTrack: Identifiable, Hashable {
    let id: String              // already namespaced: "deezer:<id>" / "itunes:<id>"
    var title: String
    var artistName: String
    var albumId: String?
    var albumTitle: String?
    var durationMs: Double
    var coverUri: String?
    var previewUri: String?
    var provider: String

    /// Materializes a playable Track (streams the provider preview).
    var asTrack: Track {
        Track(
            id: id,
            title: title,
            artistName: artistName,
            albumId: albumId ?? "unknown",
            albumTitle: albumTitle ?? "Álbum desconocido",
            durationMs: durationMs,
            audioUri: previewUri,
            isLocal: false,
            coverUri: coverUri,
            addedAt: Date().timeIntervalSince1970
        )
    }
}

struct CatalogAlbum: Identifiable, Hashable {
    let id: String
    var title: String
    var artistName: String
    var year: Int?
    var coverUri: String?
    var provider: String
}

// MARK: - Lyrics

struct LyricLine: Hashable {
    let timeMs: Double
    let text: String
}

struct Lyrics: Hashable {
    var plain: String?
    var synced: [LyricLine]

    var isEmpty: Bool { (plain?.isEmpty ?? true) && synced.isEmpty }
}

// MARK: - Formatting helpers

enum Fmt {
    static func time(_ ms: Double) -> String {
        let total = Int(max(0, ms) / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    static func catalogNumber(_ key: String) -> String {
        String(format: "VAULT-%04d", abs(stableHash(key)) % 10000)
    }

    /// Deterministic djb2 hash — Swift's built-in String.hash is per-process
    /// random, so visuals (spine accents, visualizer seeds) use this instead.
    static func stableHash(_ key: String) -> Int {
        var hash: Int = 5381
        for byte in key.utf8 { hash = (hash &* 33) ^ Int(byte) }
        return hash
    }
}
