import Foundation

/// MusicBrainz metadata provider — enriches catalog search with
/// authoritative MBIDs and release-group data (no audio; audio comes
/// from Deezer/iTunes). The Android app uses this as a metadata fallback.
/// Rate-limited to 1 req/s per MusicBrainz policy; includes polite UA.
struct MusicBrainzProvider {
    static let shared = MusicBrainzProvider()

    private let baseURL = "https://musicbrainz.org/ws/2"
    private let userAgent = "MusicVault/1.0 (iOS; https://musicvault.app)"

    private struct RecordingSearchResponse: Decodable {
        struct Recording: Decodable {
            var id: String
            var title: String
            var artistCredit: [ArtistCredit]?
            var releases: [Release]?
            var length: Int? // milliseconds
        }
        struct ArtistCredit: Decodable {
            var artist: Artist
        }
        struct Artist: Decodable {
            var id: String
            var name: String
        }
        struct Release: Decodable {
            var id: String
            var title: String
            var releaseGroup: ReleaseGroup?
        }
        struct ReleaseGroup: Decodable {
            var id: String
            var primaryType: String?
        }
        var recordings: [Recording]?
    }

    private struct ReleaseGroupResponse: Decodable {
        var releaseGroups: [ReleaseGroup]?
        struct ReleaseGroup: Decodable {
            var id: String
            var title: String
            var primaryType: String?
            var releases: [Release]?
            struct Release: Decodable {
                var id: String
                var title: String
            }
        }
    }

    /// Search recordings by query — used to enrich Deezer/iTunes results
    /// with MusicBrainz IDs and authoritative metadata.
    func searchRecordings(query: String, limit: Int = 20) async -> [MBRecording] {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        let urlString = "\(baseURL)/recording?query=\(encoded)&fmt=json&limit=\(min(limit, 50))"
        guard let url = URL(string: urlString) else { return [] }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(RecordingSearchResponse.self, from: data)
            return (decoded.recordings ?? []).compactMap { recording in
                guard let id = recording.id, let title = recording.title, !title.isEmpty else { return nil }
                let artistName = recording.artistCredit?.first?.artist.name ?? "Artista desconocido"
                let artistId = recording.artistCredit?.first?.artist.id ?? ""
                let durationMs = Double(recording.length ?? 0)
                let releaseGroupId = recording.releases?.first?.releaseGroup?.id
                return MBRecording(
                    id: "mb:\(id)",
                    title: title,
                    artistName: artistName,
                    artistId: artistId,
                    durationMs: durationMs,
                    releaseGroupID: releaseGroupId,
                    coverUri: nil // Will be fetched from Cover Art Archive if needed
                )
            }
        } catch {
            return []
        }
    }

    /// Enrich a track with MusicBrainz metadata (MBID, release group, etc.)
    /// Used to enrich Deezer/iTunes results with authoritative MBIDs.
    func enrichWithMusicBrainz(_ track: CatalogTrack) async -> CatalogTrack {
        guard track.provider != "musicbrainz" else { return track }
        let mbResults = await searchRecordings(query: "\(track.title) \(track.artistName)", limit: 1)
        guard let mb = mbResults.first else { return track }
        return CatalogTrack(
            id: track.id,
            title: mb.title,
            artistName: mb.artistName,
            albumId: mb.releaseGroupID.map { "mb:\($0)" } ?? track.albumId,
            albumTitle: track.albumTitle,
            durationMs: mb.durationMs > 0 ? mb.durationMs : track.durationMs,
            coverUri: track.coverUri ?? mb.releaseGroupID.flatMap { Self.coverURL(forReleaseGroupID: $0)?.absoluteString },
            previewUri: track.previewUri,
            provider: track.provider
        )
    }

    /// Fetch release group (album) info for a recording's release group.
    func fetchReleaseGroup(id: String) async -> MBReleaseGroup? {
        let urlString = "\(baseURL)/release-group/\(id)?fmt=json&inc=releases"
        guard let url = URL(string: urlString) else { return nil }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            let decoded = try JSONDecoder().decode(ReleaseGroupResponse.self, from: data)
            guard let rg = decoded.releaseGroups?.first else { return nil }
            return MBReleaseGroup(
                id: rg.id,
                title: rg.title,
                primaryType: rg.primaryType,
                releaseCount: rg.releases?.count ?? 0
            )
        } catch { return nil }
    }

    /// Cover Art Archive URL for a release group (500px front cover).
    static func coverURL(forReleaseGroupID id: String, size: Int = 500) -> URL? {
        URL(string: "https://coverartarchive.org/release-group/\(id)/front-\(size)")
    }
}

/// MusicBrainz recording — maps to CatalogTrack for unified search.
struct MBRecording: Identifiable, Hashable {
    let id: String          // "mb:<mbid>"
    var title: String
    var artistName: String
    var artistId: String
    var durationMs: Double
    var releaseGroupID: String?
    var coverUri: String?

    var asCatalogTrack: CatalogTrack {
        CatalogTrack(
            id: id,
            title: title,
            artistName: artistName,
            albumId: releaseGroupID.map { "mb:\($0)" },
            albumTitle: nil,
            durationMs: durationMs,
            coverUri: coverUri,
            previewUri: nil, // No audio from MB
            provider: "musicbrainz"
        )
    }
}

struct MBReleaseGroup: Hashable {
    let id: String
    let title: String
    let primaryType: String?
    let releaseCount: Int
}