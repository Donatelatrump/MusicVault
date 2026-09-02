import Foundation
import SQLite3

/// Thin SQLite3 data access layer — the iOS mirror of the Room database on
/// Android (tracks / playlists / favorites / history / search caches).
/// Actor-confined: every call is serialized and safe off the main thread.
actor Database {
    static let shared = Database()

    private var handle: OpaquePointer?

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("musicvault.sqlite3").path
        sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
        migrate()
    }

    deinit {
        if let handle { sqlite3_close_v2(handle) }
    }

    // MARK: - Core helpers

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK
    }

    private func query<T>(_ sql: String, bind: [(Int, SQLiteValue)] = [], row: (OpaquePointer) -> T?) -> [T] {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        for (index, value) in bind {
            switch value {
            case .text(let s): sqlite3_bind_text(stmt, Int32(index), s, -1, Self.transient)
            case .int(let i): sqlite3_bind_int64(stmt, Int32(index), i)
            case .real(let d): sqlite3_bind_double(stmt, Int32(index), d)
            case .null: sqlite3_bind_null(stmt, Int32(index))
            }
        }
        var result: [T] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let value = row(stmt!) { result.append(value) }
        }
        return result
    }

    private func run(_ sql: String, bind: [(Int, SQLiteValue)]) -> Bool {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        defer { sqlite3_finalize(stmt) }
        for (index, value) in bind {
            switch value {
            case .text(let s): sqlite3_bind_text(stmt, Int32(index), s, -1, Self.transient)
            case .int(let i): sqlite3_bind_int64(stmt, Int32(index), i)
            case .real(let d): sqlite3_bind_double(stmt, Int32(index), d)
            case .null: sqlite3_bind_null(stmt, Int32(index))
            }
        }
        return sqlite3_step(stmt) == SQLITE_DONE
    }

    enum SQLiteValue {
        case text(String)
        case int(Int64)
        case real(Double)
        case null
    }

    // MARK: - Schema

    private func migrate() {
        exec("""
        CREATE TABLE IF NOT EXISTS tracks (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            artistName TEXT NOT NULL,
            albumId TEXT NOT NULL,
            albumTitle TEXT NOT NULL,
            durationMs REAL NOT NULL,
            audioUri TEXT,
            isLocal INTEGER NOT NULL DEFAULT 0,
            coverUri TEXT,
            addedAt REAL NOT NULL
        )
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_tracks_local ON tracks(isLocal)")
        exec("""
        CREATE TABLE IF NOT EXISTS playlists (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            createdAt REAL NOT NULL
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS playlist_tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlistId TEXT NOT NULL,
            trackId TEXT NOT NULL,
            position INTEGER NOT NULL
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS favorites (
            trackId TEXT PRIMARY KEY,
            createdAt REAL NOT NULL
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS play_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trackId TEXT NOT NULL,
            playedAt REAL NOT NULL,
            completionRatio REAL NOT NULL DEFAULT 0
        )
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_history_track ON play_history(trackId)")
        exec("""
        CREATE TABLE IF NOT EXISTS search_history (
            query TEXT PRIMARY KEY,
            searchedAt REAL NOT NULL
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS catalog_search_cache (
            cacheKey TEXT PRIMARY KEY,
            query TEXT NOT NULL,
            tracksJson TEXT NOT NULL,
            savedAt REAL NOT NULL
        )
        """)
        // Favorites collection, same trick as Android ("playlist:favorites").
        _ = run(
            "INSERT OR IGNORE INTO playlists (id, title, description, createdAt) VALUES (?,?,?,?)",
            bind: [1, .text("playlist:favorites"), 2, .text("Favoritos"), 3, .text("Canciones que te gustan"), 4, .real(Date().timeIntervalSince1970)]
        )
    }

    // MARK: - Tracks

    private func trackRow(_ stmt: OpaquePointer) -> Track {
        let text: (Int32) -> String? = { col in
            let c = sqlite3_column_text(stmt, col)
            return c.map { String(cString: $0) }
        }
        return Track(
            id: text(0) ?? "",
            title: text(1) ?? "",
            artistName: text(2) ?? "",
            albumId: text(3) ?? "unknown",
            albumTitle: text(4) ?? "Álbum desconocido",
            durationMs: sqlite3_column_double(stmt, 5),
            audioUri: text(6),
            isLocal: sqlite3_column_int(stmt, 7) != 0,
            coverUri: text(8),
            addedAt: sqlite3_column_double(stmt, 9)
        )
    }

    func tracks() -> [Track] {
        query("SELECT id, title, artistName, albumId, albumTitle, durationMs, audioUri, isLocal, coverUri, addedAt FROM tracks ORDER BY title") { trackRow($0) }
    }

    func localTracks() -> [Track] {
        query("SELECT id, title, artistName, albumId, albumTitle, durationMs, audioUri, isLocal, coverUri, addedAt FROM tracks WHERE isLocal = 1 ORDER BY addedAt DESC") { trackRow($0) }
    }

    func track(id: String) -> Track? {
        query("SELECT id, title, artistName, albumId, albumTitle, durationMs, audioUri, isLocal, coverUri, addedAt FROM tracks WHERE id = ? LIMIT 1", bind: [1, .text(id)]) { trackRow($0) }.first
    }

    /// Insert-only for catalog rows: never clobber an imported/local row.
    func insertTrackIfAbsent(_ track: Track) {
        _ = run(
            "INSERT OR IGNORE INTO tracks (id, title, artistName, albumId, albumTitle, durationMs, audioUri, isLocal, coverUri, addedAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
            bind: [
                1, .text(track.id), 2, .text(track.title), 3, .text(track.artistName),
                4, .text(track.albumId), 5, .text(track.albumTitle), 6, .real(track.durationMs),
                7, track.audioUri.map { .text($0) } ?? .null, 8, .int(track.isLocal ? 1 : 0),
                9, track.coverUri.map { .text($0) } ?? .null, 10, .real(track.addedAt)
            ]
        )
    }

    func upsertLocalTrack(_ track: Track) {
        _ = run(
            "INSERT INTO tracks (id, title, artistName, albumId, albumTitle, durationMs, audioUri, isLocal, coverUri, addedAt) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET title=excluded.title, artistName=excluded.artistName, albumId=excluded.albumId, " +
            "albumTitle=excluded.albumTitle, durationMs=excluded.durationMs, audioUri=excluded.audioUri, isLocal=1, coverUri=excluded.coverUri",
            bind: [
                1, .text(track.id), 2, .text(track.title), 3, .text(track.artistName),
                4, .text(track.albumId), 5, .text(track.albumTitle), 6, .real(track.durationMs),
                7, track.audioUri.map { .text($0) } ?? .null, 8, .int(1),
                9, track.coverUri.map { .text($0) } ?? .null, 10, .real(track.addedAt)
            ]
        )
    }

    func deleteTrack(id: String) {
        _ = run("DELETE FROM tracks WHERE id = ?", bind: [1, .text(id)])
        _ = run("DELETE FROM favorites WHERE trackId = ?", bind: [1, .text(id)])
        _ = run("DELETE FROM playlist_tracks WHERE trackId = ?", bind: [1, .text(id)])
    }

    // MARK: - Playlists

    private func playlistRow(_ stmt: OpaquePointer) -> Playlist {
        let title = sqlite3_column_text(stmt, 1).map { String(cString: $0) } ?? ""
        let desc = sqlite3_column_text(stmt, 2).map { String(cString: $0) } ?? ""
        return Playlist(
            id: sqlite3_column_text(stmt, 0).map { String(cString: $0) } ?? "",
            title: title,
            playlistDescription: desc,
            createdAt: sqlite3_column_double(stmt, 3)
        )
    }

    func playlists() -> [Playlist] {
        query("SELECT id, title, description, createdAt FROM playlists ORDER BY createdAt ASC") { playlistRow($0) }
    }

    func createPlaylist(id: String, title: String, description: String) {
        _ = run(
            "INSERT OR IGNORE INTO playlists (id, title, description, createdAt) VALUES (?,?,?,?)",
            bind: [1, .text(id), 2, .text(title), 3, .text(description), 4, .real(Date().timeIntervalSince1970)]
        )
    }

    func deletePlaylist(id: String) {
        _ = run("DELETE FROM playlists WHERE id = ?", bind: [1, .text(id)])
        _ = run("DELETE FROM playlist_tracks WHERE playlistId = ?", bind: [1, .text(id)])
    }

    func playlistTracks(_ playlistId: String) -> [Track] {
        query(
            """
            SELECT t.id, t.title, t.artistName, t.albumId, t.albumTitle, t.durationMs, t.audioUri, t.isLocal, t.coverUri, t.addedAt
            FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId = t.id
            WHERE pt.playlistId = ? ORDER BY pt.position
            """,
            bind: [1, .text(playlistId)]
        ) { trackRow($0) }
    }

    func addTrackToPlaylist(_ playlistId: String, trackId: String) {
        let position = query(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = ?",
            bind: [1, .text(playlistId)]
        ) { sqlite3_column_int64($0, 0) }.first ?? 0
        _ = run(
            "INSERT INTO playlist_tracks (playlistId, trackId, position) VALUES (?,?,?)",
            bind: [1, .text(playlistId), 2, .text(trackId), 3, .int(position)]
        )
    }

    func removeTrackFromPlaylist(_ playlistId: String, trackId: String) {
        _ = run(
            "DELETE FROM playlist_tracks WHERE playlistId = ? AND trackId = ?",
            bind: [1, .text(playlistId), 2, .text(trackId)]
        )
    }

    // MARK: - Favorites

    func favoriteIds() -> Set<String> {
        Set(query("SELECT trackId FROM favorites") { sqlite3_column_text($0, 0).map { String(cString: $0) } ?? "" })
    }

    func addFavorite(_ trackId: String) {
        _ = run("INSERT OR REPLACE INTO favorites (trackId, createdAt) VALUES (?,?)", bind: [1, .text(trackId), 2, .real(Date().timeIntervalSince1970)])
    }

    func removeFavorite(_ trackId: String) {
        _ = run("DELETE FROM favorites WHERE trackId = ?", bind: [1, .text(trackId)])
    }

    // MARK: - History

    func recordPlay(trackId: String) {
        _ = run(
            "INSERT INTO play_history (trackId, playedAt, completionRatio) VALUES (?,?,0)",
            bind: [1, .text(trackId), 2, .real(Date().timeIntervalSince1970)]
        )
    }

    func latestHistoryId(trackId: String) -> Int64? {
        query("SELECT id FROM play_history WHERE trackId = ? ORDER BY playedAt DESC LIMIT 1", bind: [1, .text(trackId)]) {
            sqlite3_column_int64($0, 0)
        }.first
    }

    func updateCompletion(historyId: Int64, ratio: Double) {
        _ = run("UPDATE play_history SET completionRatio = ? WHERE id = ?", bind: [1, .real(ratio), 2, .int(historyId)])
    }

    func recentPlays(limit: Int = 20) -> [Track] {
        query(
            """
            SELECT t.id, t.title, t.artistName, t.albumId, t.albumTitle, t.durationMs, t.audioUri, t.isLocal, t.coverUri, t.addedAt, MAX(h.playedAt) as latest
            FROM play_history h INNER JOIN tracks t ON t.id = h.trackId
            GROUP BY h.trackId ORDER BY latest DESC LIMIT ?
            """,
            bind: [1, .int(Int64(limit))]
        ) { trackRow($0) }
    }

    // MARK: - Search history

    func recentSearches(limit: Int = 12) -> [String] {
        query("SELECT query FROM search_history ORDER BY searchedAt DESC LIMIT ?", bind: [1, .int(Int64(limit))]) {
            sqlite3_column_text($0, 0).map { String(cString: $0) } ?? ""
        }
    }

    func recordSearch(_ query: String) {
        _ = run("INSERT OR REPLACE INTO search_history (query, searchedAt) VALUES (?,?)", bind: [1, .text(query), 2, .real(Date().timeIntervalSince1970)])
    }

    func clearSearchHistory() {
        exec("DELETE FROM search_history")
    }

    // MARK: - Catalog search cache (30 min TTL, same as Android)

    func cachedSearch(key: String, ttl: Double) -> [CatalogTrack]? {
        let rows = query("SELECT tracksJson, savedAt FROM catalog_search_cache WHERE cacheKey = ? LIMIT 1", bind: [1, .text(key)]) {
            (json: sqlite3_column_text($0, 0).map { String(cString: $0) } ?? "",
             savedAt: sqlite3_column_double($0, 1))
        }
        guard let row = rows.first, Date().timeIntervalSince1970 - row.savedAt <= ttl else { return nil }
        guard let data = row.json.data(using: .utf8),
              let tracks = try? JSONDecoder().decode([CatalogTrack].self, from: data) else { return nil }
        return tracks
    }

    func saveSearchCache(key: String, queryText: String, tracks: [CatalogTrack]) {
        guard let json = try? String(data: JSONEncoder().encode(tracks), encoding: .utf8) else { return }
        _ = run(
            "INSERT OR REPLACE INTO catalog_search_cache (cacheKey, query, tracksJson, savedAt) VALUES (?,?,?,?)",
            bind: [1, .text(key), 2, .text(queryText), 3, .text(json), 4, .real(Date().timeIntervalSince1970)]
        )
        _ = run("DELETE FROM catalog_search_cache WHERE savedAt < ?", bind: [1, .real(Date().timeIntervalSince1970 - 24 * 3600)])
    }

    /// Home recommendations: flatten the newest cached searches WITHOUT hitting
    /// the network — the iOS mirror of Android's cachedTracks() flow.
    func cachedRecommendations(max: Int) -> [CatalogTrack] {
        let rows = query(
            "SELECT tracksJson FROM catalog_search_cache ORDER BY savedAt DESC LIMIT 8"
        ) { sqlite3_column_text($0, 0).map { String(cString: $0) } ?? "" }
        var seen = Set<String>()
        var result: [CatalogTrack] = []
        for json in rows {
            guard let data = json.data(using: .utf8),
                  let tracks = try? JSONDecoder().decode([CatalogTrack].self, from: data) else { continue }
            for track in tracks where !seen.contains(track.id) {
                seen.insert(track.id)
                result.append(track)
                if result.count >= max { return result }
            }
        }
        return result
    }
}
