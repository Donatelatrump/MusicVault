"""SQLite storage. Each operation owns a connection, including background jobs."""
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
import json
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
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL);
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

    def save_track(self, track, restore=False):
        with self.connect() as db:
            # Serialise read/merge/write so a stale catalog row cannot overwrite a
            # file path being published by the downloader on another thread.
            db.execute("BEGIN IMMEDIATE")
            # A rescan must not resurrect something removed by the user.
            prior = db.execute("SELECT payload FROM tracks WHERE id=?", (track.id,)).fetchone()
            if prior:
                old = Track.from_dict(json.loads(prior[0]))
                if old.path and (not track.path or (Path(old.path).is_file() and not Path(track.path).is_file())):
                    track = replace(track, path=old.path)
                if not track.cover and old.cover:
                    track = replace(track, cover=old.cover)
            db.execute("""INSERT INTO tracks(id,payload,added) VALUES (?,?,?)
                ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,
                removed=CASE WHEN ? THEN 0 ELSE tracks.removed END""",
                       (track.id, json.dumps(track.to_dict()), time.time(), restore))
        return track

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

    def library_tracks(self, query="", recent=False):
        """Only completed local audio belongs in the user's offline library."""
        records = {r['track_id']:r for r in self.downloads() if r['status']=='complete'}
        result=[]
        for item in self.tracks(query, recent=recent):
            if item.path and Path(item.path).is_file():
                result.append(item)
            elif item.id in records:
                saved=Track.from_dict(json.loads(records[item.id]['payload']))
                if saved.path and Path(saved.path).is_file():
                    result.append(self.save_track(replace(item,path=saved.path,duration=saved.duration)))
        return result

    def tracks(self, query="", favorites=False, collection=None, recent=False, include_removed=False):
        with self.connect() as db:
            if collection:
                rows = db.execute("""SELECT t.payload FROM members m JOIN tracks t ON t.id=m.track_id
                    WHERE m.collection_id=? AND t.removed=0 ORDER BY m.position""", (collection,)).fetchall()
            elif recent:
                rows = db.execute("""SELECT t.payload FROM tracks t JOIN history h ON h.track_id=t.id
                    WHERE t.removed=0 GROUP BY t.id ORDER BY MAX(h.played) DESC LIMIT 50""").fetchall()
            else:
                sql = "SELECT payload FROM tracks WHERE 1" if include_removed else "SELECT payload FROM tracks WHERE removed=0"
                if favorites:
                    sql += " AND id IN (SELECT track_id FROM favorites)"
                rows = db.execute(sql + " ORDER BY added DESC").fetchall()
        result = [Track.from_dict(json.loads(r[0])) for r in rows]
        words = query.casefold().split()
        return [t for t in result if all(w in f"{t.title} {t.artist} {t.album}".casefold() for w in words)]

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

    def create_collection(self, title, kind="playlist"):
        if not title.strip() or kind not in ("playlist", "album"):
            raise ValueError("Escribe un nombre para la colección.")
        key = uuid.uuid4().hex
        with self.connect() as db:
            db.execute("INSERT INTO collections VALUES (?,?,?)", (key, title.strip(), kind))
        return key

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

    def downloads(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM downloads ORDER BY rowid DESC")]

    def export_backup(self, destination):
        with self.connect() as db, sqlite3.connect(destination) as target:
            db.backup(target)
