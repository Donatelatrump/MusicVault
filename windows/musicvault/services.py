"""Network providers and cancelable downloads; run through Jobs, off the UI thread."""
from collections import OrderedDict
from dataclasses import replace
from pathlib import Path
import json
import os
from .processes import process_options
import shutil
import tempfile
import threading
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from PyQt6.QtCore import QObject, QRunnable, QThreadPool, pyqtSignal
from PyQt6.QtGui import QImageReader

from .library import read_track
from .models import Track, data_dir, parse_lrc, stable_id
from .songmirror import import_spotify_playlist, import_spotify_track
from .streaming import (youtube_search as fast_youtube_search, youtube_playlist_search,
                        youtube_playlist_tracks, resolve as resolve_stream, StreamCancelled)
from .catalog import SearchScorer, CoverImageCache, MusicBrainzProvider, SearchResult, sort_results

USER_AGENT = "MusicVault/0.9.7 (Windows desktop music library)"
MUSICBRAINZ_LOCK = threading.Lock()
MUSICBRAINZ_LAST = 0.0

COVER_CACHE = CoverImageCache()
MUSICBRAINZ = MusicBrainzProvider()


def get_bytes(url, maximum=12 * 1024 * 1024, retries=3):
    if not url.startswith("https://"):
        raise ValueError("El proveedor requiere HTTPS.")
    last_error = None
    for attempt in range(retries):
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json,image/*"})
            with urlopen(request, timeout=8) as response:
                body = response.read(maximum + 1)
                if len(body) > maximum:
                    raise ValueError("La respuesta del proveedor excede el tamaño permitido.")
                return body
        except (URLError, HTTPError, OSError, TimeoutError) as exc:
            last_error = exc
            if attempt < retries - 1:
                time.sleep(0.5 * (attempt + 1))
    raise last_error or ValueError("No se pudo obtener la respuesta del proveedor.")


def get_json(url, **params):
    global MUSICBRAINZ_LAST
    if "musicbrainz.org/" in url:
        with MUSICBRAINZ_LOCK:
            time.sleep(max(0, 1.05 - (time.monotonic() - MUSICBRAINZ_LAST)))
            MUSICBRAINZ_LAST = time.monotonic()
    return json.loads(get_bytes(url + ("?" + urlencode(params) if params else "")))


class JobSignals(QObject):
    result = pyqtSignal(object)
    error = pyqtSignal(str)
    finished = pyqtSignal()
    progress = pyqtSignal(object)


class Job(QRunnable):
    def __init__(self, function):
        super().__init__()
        self.function = function
        self.signals = JobSignals()
        self.cancel = threading.Event()

    def run(self):
        try:
            if not self.cancel.is_set():
                result = self.function(self.cancel, self.signals.progress.emit)
                if not self.cancel.is_set():
                    self.signals.result.emit(result)
        except Exception as exc:
            if not self.cancel.is_set():
                self.signals.error.emit(str(exc))
        finally:
            self.signals.finished.emit()


class Jobs(QObject):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.pool = QThreadPool(self)
        self.pool.setMaxThreadCount(4)
        self.active = set()

    def submit(self, function, result=None, error=None, progress=None, finished=None):
        job = Job(function)
        self.active.add(job)
        if result:
            job.signals.result.connect(result)
        if error:
            job.signals.error.connect(error)
        if progress:
            job.signals.progress.connect(progress)
        if finished:
            job.signals.finished.connect(finished)
        job.signals.finished.connect(lambda: self.active.discard(job))
        self.pool.start(job)
        return job

    def close(self):
        for job in list(self.active):
            job.cancel.set()
        self.pool.waitForDone()


def deezer_track(item, album=None):
    album = album or item.get("album", {})
    return Track("deezer:" + str(item["id"]), item.get("title", "Sin título"),
                 item.get("artist", {}).get("name", "Artista desconocido"), album.get("title", "Sin álbum"),
                 float(item.get("duration", 0)), cover=deezer_cover(album),
                 source="deezer", source_id=str(item["id"]), url=item.get("link", ""))


def deezer_cover(album):
    """Android uses medium artwork, falling back to the large album image."""
    return next((album.get(key) for key in ("cover_medium", "cover_big", "cover_xl", "cover")
                 if album.get(key)), "")


def itunes_track(item):
    return Track("itunes:" + str(item["trackId"]), item.get("trackName", "Sin título"),
                 item.get("artistName", "Artista desconocido"), item.get("collectionName", "Sin álbum"),
                 item.get("trackTimeMillis", 0) / 1000, cover=item.get("artworkUrl100", "").replace("100x100", "600x600"),
                 source="itunes", source_id=str(item["trackId"]), url=item.get("trackViewUrl", ""))


class Catalog:
    def __init__(self, db):
        self.db = db
        self._album_memory = OrderedDict()

    def search_tracks(self, query, source="catalog", cancel=None):
        query = query.strip()
        key = "search:tracks:newpipe-v1:" + ("youtube:" if source == "youtube" else "") + query.casefold()
        cached = self.db.cache_get(key)
        if cached is not None:
            return cached
        if source == "youtube":
            try:
                result = [t.to_dict() for t in fast_youtube_search(query, cancel)]
                self.db.cache_put(key, result)
                return result
            except Exception:
                stale = self.db.cache_get(key, ttl=None)
                if stale is not None:
                    return stale
                raise
        errors, tracks = [], []
        try:
            rows = get_json("https://api.deezer.com/search", q=query, limit=40)
            tracks = [t.to_dict() for t in map(deezer_track, rows.get("data", []))]
        except Exception as exc:
            errors.append(f"Deezer: {exc}")
        if cancel and cancel.is_set():
            return []
        # Ordenar por relevancia usando SearchScorer (mismo modelo que Android)
        scorer = SearchScorer(query)
        scored = []
        for t in tracks:
            d = t if isinstance(t, dict) else t.to_dict()
            score = scorer.score(d.get("title", ""), "", d.get("artist", ""))
            scored.append((score, d))
        scored.sort(key=lambda x: x[0], reverse=True)
        tracks = [d for _, d in scored]
        if not tracks and errors:
            stale = self.db.cache_get(key, ttl=None)
            if stale is not None:
                return stale
            raise RuntimeError("No se pudo consultar canciones. " + " · ".join(errors))
        self.db.cache_put(key, tracks)
        return tracks

    def search_albums(self, query, cancel=None):
        query = query.strip()
        key = "search:albums:v2:" + query.casefold()
        cached = self.db.cache_get(key)
        if cached is not None:
            return cached
        errors, albums = [], []
        try:
            rows = get_json("https://api.deezer.com/search/album", q=query, limit=18)
            albums = [{"id": str(a["id"]), "title": a["title"], "artist": a["artist"]["name"],
                       "cover": deezer_cover(a), "source": "deezer"} for a in rows.get("data", [])]
        except Exception as exc:
            errors.append(f"Deezer: {exc}")
        if cancel and cancel.is_set():
            return []
        # MusicBrainz como proveedor adicional (mismo modelo que Android)
        try:
            mb_albums = MUSICBRAINZ.search_albums(query, limit=12)
            for a in mb_albums:
                albums.append({"id": a["id"], "title": a["title"], "artist": a["artist"],
                               "cover": a.get("cover", ""), "source": "musicbrainz"})
        except Exception as exc:
            errors.append(f"MusicBrainz: {exc}")
        if cancel and cancel.is_set():
            return []
        # Ordenar por relevancia (título exacto primero, luego contiene, artista, etc.)
        scorer = SearchScorer(query)
        scored = []
        for a in albums:
            score = scorer.score(a.get("title", ""), "", a.get("artist", ""))
            scored.append((score, a))
        scored.sort(key=lambda x: x[0], reverse=True)
        albums = [a for _, a in scored]
        if not albums and errors:
            stale = self.db.cache_get(key, ttl=None)
            if stale is not None:
                return stale
        self.db.cache_put(key, albums)
        return albums

    def search(self, query, source="catalog", cancel=None):
        """Compatibility wrapper; the UI uses independent track/album jobs."""
        legacy_key = "search:" + ("youtube:" if source == "youtube" else "") + query.casefold().strip()
        cached = self.db.cache_get(legacy_key)
        if cached is not None:
            return cached
        try:
            tracks = self.search_tracks(query, source, cancel)
            if cancel and cancel.is_set():
                return {"tracks": [], "albums": []}
            albums = [] if source == "youtube" else self.search_albums(query, cancel)
            result = {"tracks": tracks, "albums": albums}
            self.db.cache_put(legacy_key, result)
            return result
        except Exception:
            stale = self.db.cache_get(legacy_key, ttl=None)
            if stale is not None:
                return {**stale, "notice": "Sin conexión: resultados guardados."}
            raise

    def album_tracks(self, album):
        key = f"album:{album['source']}:{album['id']}"
        if key in self._album_memory:
            tracks = self._album_memory.pop(key)
            self._album_memory[key] = tracks
            return list(tracks)
        cached = self.db.cache_get(key, 86400)
        if cached is not None:
            tracks = tuple(Track.from_dict(t) for t in cached)
            self._album_memory[key] = tracks
            while len(self._album_memory) > 12:
                self._album_memory.popitem(last=False)
            return list(tracks)
        if album["source"] == "deezer":
            detail = get_json("https://api.deezer.com/album/" + album["id"])
            tracks = [deezer_track(t, detail) for t in detail.get("tracks", {}).get("data", [])]
        else:
            detail = get_json("https://itunes.apple.com/lookup", id=album["id"], entity="song", limit=200)
            tracks = [itunes_track(t) for t in detail.get("results", []) if "trackId" in t]
        if not tracks:
            raise RuntimeError("El proveedor no devolvió canciones para este álbum.")
        self.db.cache_put(key, [t.to_dict() for t in tracks])
        self._album_memory[key] = tuple(tracks)
        while len(self._album_memory) > 12:
            self._album_memory.popitem(last=False)
        return tracks

    def search_artists(self, query, cancel=None):
        """Busca artistas mediante MusicBrainz (nuevo proveedor, igual que Android)."""
        query = query.strip()
        key = "search:artists:v1:" + query.casefold()
        cached = self.db.cache_get(key)
        if cached is not None:
            return cached
        try:
            artists = MUSICBRAINZ.search_artists(query, limit=12)
        except Exception as exc:
            stale = self.db.cache_get(key, ttl=None)
            if stale is not None:
                return stale
            raise RuntimeError(f"MusicBrainz: {exc}")
        if cancel and cancel.is_set():
            return []
        scorer = SearchScorer(query)
        scored = [(scorer.score(a.get("title", ""), a.get("subtitle", "")), a) for a in artists]
        scored.sort(key=lambda x: x[0], reverse=True)
        artists = [a for _, a in scored]
        self.db.cache_put(key, artists)
        return artists

    def search_playlists(self, query, cancel=None):
        query = query.strip()
        key = "search:playlists:youtube:" + query.casefold()
        cached = self.db.cache_get(key)
        if cached is not None:
            return cached
        try:
            playlists = youtube_playlist_search(query, cancel)
        except StreamCancelled:
            return []
        if playlists:
            self.db.cache_put(key, playlists)
            return playlists
        stale = self.db.cache_get(key, ttl=None)
        return stale or []

    def playlist_tracks(self, playlist, cancel=None):
        key = f"playlist:youtube:{playlist['id']}"
        cached = self.db.cache_get(key, 6 * 3600)
        if cached is not None:
            return [Track.from_dict(item) for item in cached]
        tracks = youtube_playlist_tracks(playlist, cancel, limit=500)
        if tracks:
            self.db.cache_put(key, [track.to_dict() for track in tracks])
        return tracks

    def spotify_playlist(self, url, progress=None):
        return import_spotify_playlist(
            url, self.db.cache_get, self.db.cache_put, progress,
        )

    def spotify_track(self, url):
        return import_spotify_track(url, self.db.cache_get, self.db.cache_put)


def youtube_search(query, cancel=None):
    """Búsqueda YouTube solo vía NewPipe (delegado a streaming.youtube_search)."""
    from .streaming import youtube_search as _yt_search
    return _yt_search(query, cancel)


def cover_file(url):
    if not url or not url.startswith(("https://", "http://", "//")):
        return url
    cached = COVER_CACHE.get(url)
    if cached:
        return cached
    downloaded = COVER_CACHE.put(url)
    return downloaded or url


def fetch_lyrics(track, db):
    if track.path:
        for suffix in (".lrc", ".txt"):
            path = Path(track.path).with_suffix(suffix)
            if path.is_file():
                raw = path.read_text(encoding="utf-8-sig", errors="replace")
                return {"plain": raw, "synced": parse_lrc(raw)}
    cached = db.cache_get("lyrics:" + track.id, ttl=None)
    if cached is not None:
        return cached
    candidates = []
    try:
        exact = get_json("https://lrclib.net/api/get", artist_name=track.artist, track_name=track.title,
                         album_name=track.album, duration=round(track.duration))
        candidates.append(exact)
    except HTTPError as exc:
        if exc.code != 404:
            raise
    if not candidates or not (candidates[0].get("syncedLyrics") or candidates[0].get("plainLyrics")):
        candidates = get_json("https://lrclib.net/api/search", artist_name=track.artist, track_name=track.title)
    candidates = [c for c in candidates if c.get("plainLyrics") or len(parse_lrc(c.get("syncedLyrics") or "")) >= 5]
    if not candidates:
        return {"plain": "No hay letras disponibles para esta canción.", "synced": []}
    candidate = min(candidates, key=lambda c: (0 if c.get("syncedLyrics") else 600,
                                               abs(float(c.get("duration") or 0) - track.duration)))
    result = {"plain": candidate.get("plainLyrics") or "", "synced": parse_lrc(candidate.get("syncedLyrics") or "")}
    db.cache_put("lyrics:" + track.id, result)
    return result


class DownloadCancelled(Exception):
    pass


def download(track, destination, cancel, progress):
    """Descarga directa: resuelve stream URL vía NewPipe y descarga con urllib (sin yt-dlp)."""
    destination = Path(destination).expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)
    if cancel.is_set():
        raise DownloadCancelled()

    try:
        stream_url = resolve_stream(track, cancel)
    except StreamCancelled:
        raise DownloadCancelled()
    except Exception as exc:
        raise RuntimeError(f"No se pudo obtener el stream de audio de YouTube: {exc}")

    if not stream_url or not stream_url.startswith("https://"):
        raise RuntimeError("NewPipe no devolvió una URL de stream válida.")

    with tempfile.TemporaryDirectory(prefix=".musicvault-", dir=destination) as work:
        tmp_file = Path(work) / "audio"
        try:
            request = Request(stream_url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
            with urlopen(request, timeout=30) as response:
                if not (200 <= response.status < 300):
                    raise RuntimeError(f"HTTP {response.status} {response.reason}")
                total = response.headers.get("Content-Length")
                total = int(total) if total else 0
                bytes_read = 0
                with tmp_file.open("wb") as out:
                    while True:
                        if cancel.is_set():
                            raise DownloadCancelled()
                        chunk = response.read(8192)
                        if not chunk:
                            break
                        out.write(chunk)
                        bytes_read += len(chunk)
                        if total > 0:
                            pct = min(99, int((bytes_read * 94) / total))
                            progress(pct)
        except DownloadCancelled:
            raise
        except Exception as exc:
            raise RuntimeError(f"Fallo al descargar el stream de audio: {exc}")

        if tmp_file.stat().st_size == 0:
            raise RuntimeError("El archivo descargado está vacío.")

        info = read_track(tmp_file)
        filename = stable_id(track.id) + ".mp3"
        output = destination / filename

        if cancel.is_set():
            raise DownloadCancelled()

        shutil.move(str(tmp_file), str(output))

        return replace(track, path=str(output), duration=info.duration)
