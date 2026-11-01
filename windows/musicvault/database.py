"""SQLite storage. Each operation owns a connection, including background jobs."""
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
import json
import os
import re
import sqlite3
import time
import uuid

from .models import Track, data_dir


class Database:
    def __init__(self, path=None):
        self.path = Path(path) if path else data_dir() / "musicvault.sqlite3"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS tracks (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL,
                    removed INTEGER NOT NULL DEFAULT 0, added REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS favorites (
                    track_id TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE);
                CREATE TABLE IF NOT EXISTS collections (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT '', source_id TEXT NOT NULL DEFAULT '',
                    url TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS members (
                    collection_id TEXT REFERENCES collections(id) ON DELETE CASCADE,
                    track_id TEXT REFERENCES tracks(id) ON DELETE CASCADE, position INTEGER NOT NULL,
                    PRIMARY KEY (collection_id, track_id));
                CREATE TABLE IF NOT EXISTS history (
                    id INTEGER PRIMARY KEY, track_id TEXT REFERENCES tracks(id) ON DELETE CASCADE,
                    played REAL NOT NULL, completion REAL NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS history_track ON history(track_id);
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, value TEXT NOT NULL, saved REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS searches (query TEXT PRIMARY KEY, searched REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS downloads (
                    track_id TEXT PRIMARY KEY, payload TEXT NOT NULL, status TEXT NOT NULL,
                    progress INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '');
                PRAGMA user_version=1;
            """)
            columns = {row[1] for row in db.execute("PRAGMA table_info(collections)")}
            for name in ("source", "source_id", "url", "cover"):
                if name not in columns:
                    db.execute(f"ALTER TABLE collections ADD COLUMN {name} TEXT NOT NULL DEFAULT ''")
            track_columns = {row[1] for row in db.execute("PRAGMA table_info(tracks)")}
            for name, declaration in (
                ("title", "TEXT NOT NULL DEFAULT ''"),
                ("artist", "TEXT NOT NULL DEFAULT ''"),
                ("album", "TEXT NOT NULL DEFAULT ''"),
                ("path", "TEXT NOT NULL DEFAULT ''"),
            ):
                if name not in track_columns:
                    db.execute(f"ALTER TABLE tracks ADD COLUMN {name} {declaration}")
            # Backfill de campos indexados solo si nunca se ha hecho (marcado por setting).
            if not db.execute("SELECT 1 FROM settings WHERE key='backfill_v1'").fetchone():
                for row in db.execute("SELECT id,payload FROM tracks WHERE title='' AND artist='' AND album='' AND path='' LIMIT 1000"):
                    track = Track.from_dict(json.loads(row["payload"]))
                    db.execute("UPDATE tracks SET title=?,artist=?,album=?,path=? WHERE id=?",
                               (track.title, track.artist, track.album, track.path, track.id))
                db.execute("INSERT OR REPLACE INTO settings VALUES (?,?)", ("backfill_v1", "1"))
            db.executescript("""
                CREATE INDEX IF NOT EXISTS tracks_path ON tracks(path);
                CREATE INDEX IF NOT EXISTS tracks_artist ON tracks(artist COLLATE NOCASE);
                CREATE INDEX IF NOT EXISTS tracks_album ON tracks(album COLLATE NOCASE);
                CREATE VIRTUAL TABLE IF NOT EXISTS track_search USING fts5(
                    id UNINDEXED, title, artist, album,
                    tokenize='unicode61 remove_diacritics 2');
                CREATE TRIGGER IF NOT EXISTS track_search_insert AFTER INSERT ON tracks BEGIN
                    INSERT INTO track_search(id,title,artist,album)
                    VALUES (new.id,new.title,new.artist,new.album);
                END;
                CREATE TRIGGER IF NOT EXISTS track_search_update AFTER UPDATE OF title,artist,album ON tracks BEGIN
                    DELETE FROM track_search WHERE id=old.id;
                    INSERT INTO track_search(id,title,artist,album)
                    VALUES (new.id,new.title,new.artist,new.album);
                END;
                CREATE TRIGGER IF NOT EXISTS track_search_delete AFTER DELETE ON tracks BEGIN
                    DELETE FROM track_search WHERE id=old.id;
                END;
                CREATE TABLE IF NOT EXISTS scanned_files (
                    path TEXT PRIMARY KEY, track_id TEXT NOT NULL,
                    size INTEGER NOT NULL, modified_ns INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS scanned_files_track ON scanned_files(track_id);
            """)
            db.execute("""INSERT INTO track_search(id,title,artist,album)
                SELECT t.id,t.title,t.artist,t.album FROM tracks t
                WHERE NOT EXISTS (SELECT 1 FROM track_search s WHERE s.id=t.id)""")
            db.execute("UPDATE downloads SET status='interrupted' WHERE status IN ('queued','downloading')")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _save_track_row(db, track, restore=False, added=None):
        """Merge and write one track using an already-owned transaction."""
        # A stale catalog row must not overwrite a file path published by a
        # downloader, and a scan must not resurrect a manually removed track.
        prior = db.execute("SELECT payload FROM tracks WHERE id=?", (track.id,)).fetchone()
        if prior:
            old = Track.from_dict(json.loads(prior[0]))
            if old.path and (not track.path or (Path(old.path).is_file() and not Path(track.path).is_file())):
                track = replace(track, path=old.path)
            if not track.cover and old.cover:
                track = replace(track, cover=old.cover)
        db.execute("""INSERT INTO tracks(id,payload,added,title,artist,album,path) VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,title=excluded.title,
            artist=excluded.artist,album=excluded.album,path=excluded.path,
            removed=CASE WHEN ? THEN 0 ELSE tracks.removed END""",
                   (track.id, json.dumps(track.to_dict()), added or time.time(), track.title,
                    track.artist, track.album, track.path, restore))
        return track

    def save_track(self, track, restore=False):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            track = self._save_track_row(db, track, restore)
        return track

    def save_tracks(self, tracks, restore=False):
        """Persist many tracks in one transaction while preserving their order."""
        items = tuple(tracks)
        now = time.time()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            result = tuple(
                self._save_track_row(db, track, restore, now + index * 1e-6)
                for index, track in enumerate(items)
            )
        return result

    def get_track(self, track_id):
        with self.connect() as db:
            row = db.execute("SELECT payload FROM tracks WHERE id=? AND removed=0", (track_id,)).fetchone()
        return Track.from_dict(json.loads(row[0])) if row else None

    def download_record(self, track_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM downloads WHERE track_id=?", (track_id,)).fetchone()
        return dict(row) if row else None

    def resolve_track(self, track):
        stored = self.get_track(track.id)
        candidates = [stored, track]
        if not any(t and t.path and Path(t.path).is_file() for t in candidates):
            record = self.download_record(track.id)
            if record and record["status"] == "complete":
                candidates.append(Track.from_dict(json.loads(record["payload"])))
        for candidate in candidates:
            if candidate and candidate.path and Path(candidate.path).is_file():
                resolved = replace(stored or track, path=candidate.path, duration=candidate.duration or track.duration)
                if not stored or stored.path != resolved.path:
                    self.save_track(resolved)
                return resolved
        return stored or track

    def library_tracks(self, query="", recent=False, favorites=False, availability=None,
                       artist="", album=""):
        """Only completed local audio belongs in the user's offline library."""
        records = {r['track_id']:r for r in self.downloads() if r['status']=='complete'}
        result=[]
        available = availability if availability is not None else {}
        for item in self.tracks(query, recent=recent, favorites=favorites, artist=artist, album=album):
            local = available.setdefault(item.id, bool(item.path and os.path.exists(item.path)))
            if local:
                result.append(item)
            elif item.id in records:
                saved=Track.from_dict(json.loads(records[item.id]['payload']))
                if saved.path and Path(saved.path).is_file():
                    restored = self.save_track(replace(item,path=saved.path,duration=saved.duration))
                    available[item.id] = True
                    result.append(restored)
        return result

    @staticmethod
    def _search_expression(query):
        words = re.findall(r"[^\W_]+", query, flags=re.UNICODE)
        return " AND ".join('"' + word.replace('"', '""') + '"*' for word in words)

    def tracks(self, query="", favorites=False, collection=None, recent=False,
               include_removed=False, artist="", album=""):
        search = self._search_expression(query)
        if query.strip() and not search:
            return []
        with self.connect() as db:
            if collection:
                sql = """SELECT t.payload FROM members m JOIN tracks t ON t.id=m.track_id
                    WHERE m.collection_id=? AND t.removed=0"""
                values = [collection]
                if search:
                    sql += " AND t.id IN (SELECT id FROM track_search WHERE track_search MATCH ?)"
                    values.append(search)
                rows = db.execute(sql + " ORDER BY m.position", values).fetchall()
            elif recent:
                sql = """SELECT t.payload FROM tracks t JOIN history h ON h.track_id=t.id
                    WHERE t.removed=0"""
                values = []
                if search:
                    sql += " AND t.id IN (SELECT id FROM track_search WHERE track_search MATCH ?)"
                    values.append(search)
                rows = db.execute(sql + " GROUP BY t.id ORDER BY MAX(h.played) DESC LIMIT 50", values).fetchall()
            else:
                sql = "SELECT payload FROM tracks WHERE 1" if include_removed else "SELECT payload FROM tracks WHERE removed=0"
                values = []
                if favorites:
                    sql += " AND id IN (SELECT track_id FROM favorites)"
                if search:
                    sql += " AND id IN (SELECT id FROM track_search WHERE track_search MATCH ?)"
                    values.append(search)
                if artist:
                    sql += " AND artist=? COLLATE NOCASE"
                    values.append(artist)
                if album:
                    sql += " AND album=? COLLATE NOCASE"
                    values.append(album)
                rows = db.execute(sql + " ORDER BY added DESC", values).fetchall()
        return [Track.from_dict(json.loads(r[0])) for r in rows]

    def library_facets(self, favorites=False):
        clause = "removed=0"
        if favorites:
            clause += " AND id IN (SELECT track_id FROM favorites)"
        with self.connect() as db:
            artists = [r[0] for r in db.execute(
                f"SELECT DISTINCT artist FROM tracks WHERE {clause} AND path<>'' ORDER BY artist COLLATE NOCASE")]
            albums = [r[0] for r in db.execute(
                f"SELECT DISTINCT album FROM tracks WHERE {clause} AND path<>'' ORDER BY album COLLATE NOCASE")]
        return artists, albums

    def scanned_files(self):
        """Return prior scan signatures and tracks in one read transaction."""
        with self.connect() as db:
            rows = db.execute("""SELECT t.payload,t.removed,s.path,s.size,s.modified_ns
                FROM scanned_files s JOIN tracks t ON t.id=s.track_id""").fetchall()
            known = {r["path"]: (Track.from_dict(json.loads(r["payload"])), r["size"],
                                  r["modified_ns"], bool(r["removed"])) for r in rows}
            # Tracks created before scan signatures were introduced are read once.
            legacy = db.execute("""SELECT payload,path,removed FROM tracks
                WHERE path<>'' AND path NOT IN (SELECT path FROM scanned_files)""").fetchall()
        for row in legacy:
            known[row["path"]] = (Track.from_dict(json.loads(row["payload"])), None, None,
                                  bool(row["removed"]))
        return known

    def save_scanned_track(self, track, size, modified_ns, restore=False):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            track = self._save_track_row(db, track, restore)
            db.execute("INSERT OR REPLACE INTO scanned_files VALUES (?,?,?,?)",
                       (track.path, track.id, size, modified_ns))
        return track

    def remove_track(self, track_id):
        with self.connect() as db:
            db.execute("UPDATE tracks SET removed=1 WHERE id=?", (track_id,))
            db.execute("DELETE FROM favorites WHERE track_id=?", (track_id,))
            db.execute("DELETE FROM members WHERE track_id=?", (track_id,))

    def favorites(self):
        with self.connect() as db:
            return {r[0] for r in db.execute("SELECT track_id FROM favorites")}

    def toggle_favorite(self, track):
        self.save_track(track, restore=True)
        with self.connect() as db:
            if db.execute("SELECT 1 FROM favorites WHERE track_id=?", (track.id,)).fetchone():
                db.execute("DELETE FROM favorites WHERE track_id=?", (track.id,))
                return False
            db.execute("INSERT INTO favorites VALUES (?)", (track.id,))
            return True

    def collections(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("""SELECT c.*, COUNT(m.track_id) AS count FROM collections c
                LEFT JOIN members m ON m.collection_id=c.id GROUP BY c.id ORDER BY c.title COLLATE NOCASE""")]

    def create_collection(self, title, kind="playlist", source="", source_id="", url="", cover=""):
        if not title.strip() or kind not in ("playlist", "album"):
            raise ValueError("Escribe un nombre para la colección.")
        key = uuid.uuid4().hex
        with self.connect() as db:
            db.execute("INSERT INTO collections(id,title,kind,source,source_id,url,cover) VALUES (?,?,?,?,?,?,?)",
                       (key, title.strip(), kind, source, source_id, url, cover))
        return key

    def collection_by_source(self, source, source_id, kind=None):
        with self.connect() as db:
            sql = "SELECT * FROM collections WHERE source=? AND source_id=?"
            values = [source, source_id]
            if kind:
                sql += " AND kind=?"
                values.append(kind)
            row = db.execute(sql, values).fetchone()
        return dict(row) if row else None

    def replace_collection(self, key, title, tracks, source="", source_id="", url="", cover=""):
        """Replace metadata and members atomically, even for very large playlists."""
        unique = []
        seen = set()
        for track in tracks:
            if track.id not in seen:
                unique.append(track)
                seen.add(track.id)
        if not cover:
            cover = next((track.cover for track in unique if getattr(track, "cover", "")), "")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE collections SET title=?,source=?,source_id=?,url=?,cover=? WHERE id=?",
                       (title.strip(), source, source_id, url, cover, key))
            db.execute("DELETE FROM members WHERE collection_id=?", (key,))
            now = time.time()
            for position, track in enumerate(unique):
                self._save_track_row(db, track, restore=True, added=now + position * 1e-6)
            db.executemany(
                "INSERT INTO members(collection_id,track_id,position) VALUES (?,?,?)",
                [(key, track.id, position) for position, track in enumerate(unique)],
            )

    def rename_collection(self, key, title):
        if not title.strip():
            raise ValueError("El nombre no puede estar vacío.")
        with self.connect() as db:
            db.execute("UPDATE collections SET title=? WHERE id=?", (title.strip(), key))

    def delete_collection(self, key):
        with self.connect() as db:
            db.execute("DELETE FROM collections WHERE id=?", (key,))

    def add_member(self, key, track):
        self.save_track(track, restore=True)
        with self.connect() as db:
            db.execute("""INSERT OR IGNORE INTO members VALUES (?,?,
                (SELECT COALESCE(MAX(position),-1)+1 FROM members WHERE collection_id=?))""", (key, track.id, key))
            if track.cover:
                db.execute("UPDATE collections SET cover=? WHERE id=? AND (cover='' OR cover IS NULL)",
                           (track.cover, key))

    def set_collection_cover(self, key, cover):
        if not cover:
            return
        with self.connect() as db:
            db.execute("UPDATE collections SET cover=? WHERE id=?", (cover, key))

    def remove_member(self, key, track_id):
        with self.connect() as db:
            db.execute("DELETE FROM members WHERE collection_id=? AND track_id=?", (key, track_id))

    def reorder_members(self, key, ids):
        with self.connect() as db:
            actual = {r[0] for r in db.execute("SELECT track_id FROM members WHERE collection_id=?", (key,))}
            if len(ids) != len(set(ids)) or set(ids) != actual:
                raise ValueError("El orden debe contener todas las canciones una sola vez.")
            db.executemany("UPDATE members SET position=? WHERE collection_id=? AND track_id=?",
                           [(i, key, track_id) for i, track_id in enumerate(ids)])

    def setting(self, key, default=None):
        with self.connect() as db:
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def set_setting(self, key, value):
        with self.connect() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES (?,?)", (key, json.dumps(value)))

    def cache_get(self, key, ttl=1800):
        with self.connect() as db:
            row = db.execute("SELECT value,saved FROM cache WHERE key=?", (key,)).fetchone()
        if row and (ttl is None or time.time() - row[1] < ttl):
            return json.loads(row[0])
        return None

    def cache_put(self, key, value):
        with self.connect() as db:
            db.execute("INSERT OR REPLACE INTO cache VALUES (?,?,?)", (key, json.dumps(value), time.time()))

    def add_search(self, query):
        with self.connect() as db:
            db.execute("INSERT OR REPLACE INTO searches VALUES (?,?)", (query.strip(), time.time()))
            db.execute("DELETE FROM searches WHERE query NOT IN (SELECT query FROM searches ORDER BY searched DESC LIMIT 12)")

    def searches(self):
        with self.connect() as db:
            return [r[0] for r in db.execute("SELECT query FROM searches ORDER BY searched DESC")]

    def clear_searches(self):
        with self.connect() as db:
            db.execute("DELETE FROM searches")
            db.execute("DELETE FROM cache WHERE key LIKE 'search:%'")

    def record_play(self, track):
        self.save_track(track)
        with self.connect() as db:
            return db.execute("INSERT INTO history(track_id,played) VALUES (?,?)", (track.id, time.time())).lastrowid

    def completion(self, history_id, ratio):
        with self.connect() as db:
            db.execute("UPDATE history SET completion=MAX(completion,?) WHERE id=?", (max(0, min(1, ratio)), history_id))

    def set_download(self, track, status, progress=0, error=""):
        with self.connect() as db:
            db.execute("INSERT OR REPLACE INTO downloads VALUES (?,?,?,?,?)",
                       (track.id, json.dumps(track.to_dict()), status, progress, error))

    def queue_downloads(self, tracks):
        """Persist a download batch without one transaction per track."""
        items = tuple(tracks)
        now = time.time()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            merged = tuple(
                self._save_track_row(db, track, restore=True, added=now + index * 1e-6)
                for index, track in enumerate(items)
            )
            db.executemany(
                "INSERT OR REPLACE INTO downloads VALUES (?,?,?,?,?)",
                [(track.id, json.dumps(track.to_dict()), "queued", 0, "") for track in merged],
            )
        return merged

    def downloads(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM downloads ORDER BY rowid DESC")]

    def export_backup(self, destination):
        with self.connect() as db, sqlite3.connect(destination) as target:
            db.backup(target)
