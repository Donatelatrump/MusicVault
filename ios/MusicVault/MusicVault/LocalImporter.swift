import Foundation
import AVFoundation

/// Imports audio files from the Files app (fileImporter) into the app's
/// Documents/Music folder — the iOS replacement for yt-dlp downloads.
enum LocalImporter {
    static var musicDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Music", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Copies the picked security-scoped files into the vault and reads their
    /// duration metadata. Returns the materialized local tracks.
    static func importFiles(_ picked: [URL]) async -> [Track] {
        var imported: [Track] = []
        for source in picked {
            let accessing = source.startAccessingSecurityScopedResource()
            defer { if accessing { source.stopAccessingSecurityScopedResource() } }

            let fileName = source.lastPathComponent
            let destination = musicDirectory.appendingPathComponent(fileName)
            do {
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.copyItem(at: source, to: destination)
            } catch {
                continue
            }

            let asset = AVURLAsset(url: destination)
            let durationSeconds: Double
            if let cm = try? await asset.load(.duration), cm.seconds.isFinite {
                durationSeconds = cm.seconds
            } else {
                durationSeconds = 0
            }

            let name = destination.deletingPathExtension().lastPathComponent
            // "Artist - Title" naming still fills both fields sensibly.
            let parts = name.components(separatedBy: " - ")
            let title = parts.count > 1 ? parts.dropFirst().joined(separator: " - ") : name
            let artist = parts.count > 1 ? parts[0] : "Desconocido"

            imported.append(Track(
                id: "local:\(fileName)",
                title: title,
                artistName: artist,
                albumId: "local:imports",
                albumTitle: "Mis importaciones",
                durationMs: durationSeconds * 1000,
                audioUri: destination.path,
                isLocal: true,
                coverUri: nil,
                addedAt: Date().timeIntervalSince1970
            ))
        }
        return imported
    }

    static func deleteLocalFile(of track: Track) {
        guard track.isLocal, let path = track.audioUri, path.hasPrefix("/") else { return }
        try? FileManager.default.removeItem(atPath: path)
    }
}
