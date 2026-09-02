import Foundation

/// Catalog metadata from the same public providers as Android: Deezer first
/// (playable 30 s previews + artwork), iTunes as fallback (also previews).
/// Results are cached in SQLite for 30 minutes — same policy as the Android
/// CatalogRepository. MusicBrainz provides authoritative MBIDs for enrichment.
struct CatalogService {
    static let shared = CatalogService()
    private let cacheTTL: Double = 30 * 60

    private struct ItunesResponse: Decodable {
        struct Result: Decodable {
            var trackId: Int?
            var trackName: String?
            var artistName: String?
            var collectionId: Int?
            var collectionName: String?
            var trackTimeMillis: Int?
            var artworkUrl100: String?
            var previewUrl: String?
        }
        var results: [Result]?
    }

    private struct DeezerResponse: Decodable {
        struct DataItem: Decodable {
            struct Artist: Decodable { var name: String? }
            struct Album: Decodable {
                var id: Int?
                var title: String?
                var coverMedium: String?
                var coverBig: String?
                enum CodingKeys: String, CodingKey {
                    case id, title, artist, album
                    case coverMedium = "cover_medium"
                    case coverBig = "cover_big"
                }
            }
            var id: Int?
            var title: String?
            var duration: Int?
            var preview: String?
            var artist: Artist?
            var album: Album?
        }
        var data: [DataItem]?
    }

    func search(_ rawQuery: String, limit: Int = 20) async -> [CatalogTrack] {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return [] }
        let key = "\(query)|\(limit)"

        if let cached = await Database.shared.cachedSearch(key: key, ttl: cacheTTL) {
            return cached
        }

        async let deezerTracks = searchDeezer(query, limit: limit)
        async let itunesTracks = searchItunes(query, limit: limit)
        let (fromDeezer, fromItunes) = await (deezerTracks, itunesTracks)

        // Deezer first: it carries previews + artwork; iTunes fills the rest,
        // deduped by normalized title+artist.
        var merged: [CatalogTrack] = fromDeezer
        var seen = Set(fromDeezer.map { normalize($0.title, $0.artistName) })
        for track in fromItunes where !seen.contains(normalize(track.title, track.artistName)) {
            merged.append(track)
        }
        merged = Array(merged.prefix(limit))

        // Enrich with MusicBrainz metadata (authoritative MBIDs, release groups)
        let enriched = await withTaskGroup(of: (Int, CatalogTrack).self) { group in
            for (idx, track) in merged.enumerated() {
                group.addTask {
                    (idx, await MusicBrainzProvider.shared.enrichWithMusicBrainz(track))
                }
            }
            var result = merged
            for await (idx, enrichedTrack) in group {
                result[idx] = enrichedTrack
            }
            return result
        }

        await Database.shared.saveSearchCache(key: key, queryText: query, tracks: enriched)
        return enriched
    }

    private func normalize(_ title: String, _ artist: String) -> String {
        title.lowercased().trimmingCharacters(in: .whitespaces) + "|" + artist.lowercased().trimmingCharacters(in: .whitespaces)
    }

    private func searchDeezer(_ query: String, limit: Int) async -> [CatalogTrack] {
        guard let url = URL(string: "https://api.deezer.com/search?q=\(encoded(query))&limit=\(min(limit, 50))") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(DeezerResponse.self, from: data)
            return (decoded.data ?? []).compactMap { item in
                guard let id = item.id, let title = item.title, !title.isEmpty else { return nil }
                return CatalogTrack(
                    id: "deezer:\(id)",
                    title: title,
                    artistName: item.artist?.name ?? "Artista desconocido",
                    albumId: item.album?.id.map { "deezer:\($0)" },
                    albumTitle: item.album?.title,
                    durationMs: Double((item.duration ?? 0) * 1000),
                    coverUri: item.album?.coverMedium ?? item.album?.coverBig,
                    previewUri: item.preview,
                    provider: "deezer"
                )
            }
        } catch {
            return []
        }
    }

    private func searchItunes(_ query: String, limit: Int) async -> [CatalogTrack] {
        let country = Locale.current.regionCode ?? "US"
        guard let url = URL(string: "https://itunes.apple.com/search?term=\(encoded(query))&media=music&entity=song&limit=\(min(limit, 50))&country=\(country)") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(ItunesResponse.self, from: data)
            return (decoded.results ?? []).compactMap { item in
                guard let id = item.trackId, let title = item.trackName, !title.isEmpty else { return nil }
                return CatalogTrack(
                    id: "itunes:\(id)",
                    title: title,
                    artistName: item.artistName ?? "Artista desconocido",
                    albumId: item.collectionId.map { "itunes:\($0)" },
                    albumTitle: item.collectionName,
                    durationMs: Double(item.trackTimeMillis ?? 0),
                    coverUri: item.artworkUrl100?.replacingOccurrences(of: "100x100", with: "512x512"),
                    previewUri: item.previewUrl,
                    provider: "itunes"
                )
            }
        } catch {
            return []
        }
    }

    private func encoded(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    /// Home recommendations: flatten the newest cached searches WITHOUT any
    /// network request — the iOS mirror of Android's cachedTracks() flow.
    func recommendations(max: Int = 24) async -> [CatalogTrack] {
        await Database.shared.cachedRecommendations(max: max)
    }

    // MARK: - Albums (iOS port of searchAlbums / tracksForAlbum)

    func searchAlbums(_ rawQuery: String, limit: Int = 10) async -> [CatalogAlbum] {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return [] }
        async let deezerAlbums = deezerAlbums(query, limit: limit)
        async let itunesAlbums = itunesAlbums(query, limit: limit)
        let (dz, it) = await (deezerAlbums, itunesAlbums)
        var merged = dz
        var seen = Set(dz.map { normalize($0.title, $0.artistName) })
        for album in it where !seen.contains(normalize(album.title, album.artistName)) {
            merged.append(album)
        }
        return Array(merged.prefix(limit))
    }

    private func deezerAlbums(_ query: String, limit: Int) async -> [CatalogAlbum] {
        guard let url = URL(string: "https://api.deezer.com/search/album?q=\(encoded(query))&limit=\(min(limit, 50))") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(DeezerAlbumResponse.self, from: data)
            return (decoded.data ?? []).compactMap { item in
                guard let id = item.id, let title = item.title, !title.isEmpty else { return nil }
                return CatalogAlbum(
                    id: "deezer:\(id)",
                    title: title,
                    artistName: item.artist?.name ?? "Artista desconocido",
                    year: nil,
                    coverUri: item.coverMedium ?? item.coverBig,
                    provider: "deezer"
                )
            }
        } catch {
            return []
        }
    }

    private func itunesAlbums(_ query: String, limit: Int) async -> [CatalogAlbum] {
        let country = Locale.current.regionCode ?? "US"
        guard let url = URL(string: "https://itunes.apple.com/search?term=\(encoded(query))&media=music&entity=album&limit=\(min(limit, 50))&country=\(country)") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(ItunesAlbumResponse.self, from: data)
            return (decoded.results ?? []).compactMap { item in
                guard let id = item.collectionId, let title = item.collectionName, !title.isEmpty else { return nil }
                let year = item.releaseDate.flatMap { Int($0.prefix(4)) }
                return CatalogAlbum(
                    id: "itunes:\(id)",
                    title: title,
                    artistName: item.artistName ?? "Artista desconocido",
                    year: year,
                    coverUri: item.artworkUrl100?.replacingOccurrences(of: "100x100", with: "512x512"),
                    provider: "itunes"
                )
            }
        } catch {
            return []
        }
    }

    /// Track listing for a remote album — mirrors the Android albumTracks().
    func tracksForAlbum(_ album: CatalogAlbum, limit: Int = 30) async -> [CatalogTrack] {
        switch album.provider {
        case "deezer":
            return await deezerAlbumTracks(albumId: String(album.id.dropFirst("deezer:".count)), fallbackCover: album.coverUri)
        case "itunes":
            return await itunesAlbumTracks(albumId: String(album.id.dropFirst("itunes:".count)), limit: limit)
        default:
            return []
        }
    }

    private func deezerAlbumTracks(albumId: String, fallbackCover: String?) async -> [CatalogTrack] {
        guard let url = URL(string: "https://api.deezer.com/album/\(albumId)") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(DeezerAlbumDetailResponse.self, from: data)
            let albumTitle = decoded.title
            return (decoded.tracks?.data ?? []).compactMap { item in
                guard let id = item.id, let title = item.title else { return nil }
                return CatalogTrack(
                    id: "deezer:\(id)",
                    title: title,
                    artistName: item.artist?.name ?? decoded.artist?.name ?? "Artista desconocido",
                    albumId: "deezer:\(albumId)",
                    albumTitle: albumTitle,
                    durationMs: Double((item.duration ?? 0) * 1000),
                    coverUri: fallbackCover,
                    previewUri: item.preview,
                    provider: "deezer"
                )
            }
        } catch {
            return []
        }
    }

    private func itunesAlbumTracks(albumId: String, limit: Int) async -> [CatalogTrack] {
        let country = Locale.current.regionCode ?? "US"
        guard let url = URL(string: "https://itunes.apple.com/lookup?id=\(albumId)&entity=song&limit=\(min(limit, 200))&country=\(country)") else { return [] }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
            let decoded = try JSONDecoder().decode(ItunesResponse.self, from: data)
            return (decoded.results ?? []).compactMap { item in
                guard let id = item.trackId, let title = item.trackName else { return nil }
                return CatalogTrack(
                    id: "itunes:\(id)",
                    title: title,
                    artistName: item.artistName ?? "Artista desconocido",
                    albumId: "itunes:\(albumId)",
                    albumTitle: item.collectionName,
                    durationMs: Double(item.trackTimeMillis ?? 0),
                    coverUri: item.artworkUrl100?.replacingOccurrences(of: "100x100", with: "512x512"),
                    previewUri: item.previewUrl,
                    provider: "itunes"
                )
            }
        } catch {
            return []
        }
    }
}

// MARK: - Album response models

private struct DeezerAlbumResponse: Decodable {
    struct DataItem: Decodable {
        struct Artist: Decodable { var name: String? }
        var id: Int?
        var title: String?
        var artist: Artist?
        var coverMedium: String?
        var coverBig: String?
        enum CodingKeys: String, CodingKey {
            case id, title, artist
            case coverMedium = "cover_medium"
            case coverBig = "cover_big"
        }
    }
    var data: [DataItem]?
}

private struct DeezerAlbumDetailResponse: Decodable {
    struct Artist: Decodable { var name: String? }
    struct TrackItem: Decodable {
        struct Artist: Decodable { var name: String? }
        var id: Int?
        var title: String?
        var duration: Int?
        var preview: String?
        var artist: Artist?
    }
    struct Tracks: Decodable { var data: [TrackItem]? }
    var title: String?
    var artist: Artist?
    var tracks: Tracks?
}
