import Foundation

/// Lyrics via LRCLib — a faithful port of the Android LyricsProvider:
/// exact /api/get lookup first, free-text /api/search fallback, and the same
/// quality gates that reject degenerate records (single-line troll entries).
struct LyricsService {
    static let shared = LyricsService()

    private let minSyncedLines = 5
    private let minPlainChars = 80

    private struct LrcRecord: Decodable {
        var plainLyrics: String?
        var syncedLyrics: String?
        var duration: Double?
    }

    func fetch(artist: String, title: String, album: String? = nil, durationMs: Double = 0) async -> Lyrics? {
        let durationSeconds = Int(durationMs / 1000)
        if durationSeconds > 0 {
            var query = "https://lrclib.net/api/get?artist_name=\(encoded(artist))&track_name=\(encoded(title))"
            if let album, !album.isEmpty { query += "&album_name=\(encoded(album))" }
            query += "&duration=\(durationSeconds)"
            if let body = try? await getJSON(query, expectingArray: false) as? LrcRecord,
               let lyrics = toLyrics(body), isUsable(lyrics) {
                return lyrics
            }
        }

        // Fallback: free-text search; best usable record wins (synced beats
        // plain, then closest duration).
        let searchQuery = "https://lrclib.net/api/search?q=\(encoded("\(title) \(artist)"))"
        guard let records = try? await getJSON(searchQuery, expectingArray: true) as? [LrcRecord] else { return nil }
        var best: (Lyrics, Double)?
        for record in records {
            guard let lyrics = toLyrics(record), isUsable(lyrics) else { continue }
            let remoteMs = (record.duration ?? 0) * 1000
            let syncedPenalty = lyrics.synced.count >= minSyncedLines ? 0.0 : 60_000
            let durationPenalty = durationSeconds > 0 && remoteMs > 0 ? abs(remoteMs - durationMs) : 0
            let score = syncedPenalty + durationPenalty
            if best == nil || score < best!.1 { best = (lyrics, score) }
        }
        return best?.0
    }

    private func isUsable(_ lyrics: Lyrics) -> Bool {
        lyrics.synced.count >= minSyncedLines || (lyrics.plain?.count ?? 0) >= minPlainChars
    }

    private func toLyrics(_ record: LrcRecord) -> Lyrics? {
        let plain = record.plainLyrics?.isEmpty == false ? record.plainLyrics : nil
        let synced = record.syncedLyrics?.isEmpty == false ? Self.parseLrc(record.syncedLyrics!) : []
        if plain == nil && synced.isEmpty { return nil }
        return Lyrics(plain: plain, synced: synced)
    }

    /// Parses LRC text: "01:23.45 line" — supports multiple timestamps per line.
    static func parseLrc(_ raw: String) -> [LyricLine] {
        let pattern = #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        var lines: [LyricLine] = []
        for line in raw.split(separator: "\n") {
            let ns = String(line) as NSString
            let matches = regex.matches(in: ns, range: NSRange(location: 0, length: ns.length))
            if matches.isEmpty { continue }
            guard let last = matches.last else { continue }
            let text = ns.substring(from: last.range.upperBound).trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }
            for match in matches {
                let minutes = Double(ns.substring(with: match.range(at: 1))) ?? 0
                let seconds = Double(ns.substring(with: match.range(at: 2))) ?? 0
                let fractionRaw = match.range(at: 3).location != NSNotFound ? ns.substring(with: match.range(at: 3)) : ""
                var fractionMs = 0.0
                switch fractionRaw.count {
                case 1: fractionMs = (Double(fractionRaw) ?? 0) * 100
                case 2: fractionMs = (Double(fractionRaw) ?? 0) * 10
                case 3: fractionMs = Double(fractionRaw.prefix(3)) ?? 0
                default: fractionMs = 0
                }
                lines.append(LyricLine(timeMs: minutes * 60_000 + seconds * 1000 + fractionMs, text: text))
            }
        }
        return lines.sorted { $0.timeMs < $1.timeMs }
    }

    private func encoded(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func getJSON(_ urlString: String, expectingArray: Bool) async throws -> Any {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 12)
        request.setValue("MusicVault/1.0 (iOS lyrics client)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let decoder = JSONDecoder()
        return expectingArray ? try decoder.decode([LrcRecord].self, from: data) : try decoder.decode(LrcRecord.self, from: data)
    }
}
