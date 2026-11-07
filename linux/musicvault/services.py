"""Network providers and cancelable downloads; run through Jobs, off the UI thread."""
from collections import OrderedDict
from dataclasses import replace
from pathlib import Path
import json
import ssl
import os
import selectors
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from PyQt6.QtCore import QObject, QRunnable, QThreadPool, pyqtSignal

from .library import read_track
from .downloader import downloader_command, youtube_arguments, explain_download_error
from .models import Track, data_dir, parse_lrc, stable_id
from .songmirror import import_spotify_playlist, import_spotify_track
from .streaming import (youtube_search as fast_youtube_search, youtube_playlist_search,
                        youtube_playlist_tracks, StreamCancelled)

USER_AGENT = "MusicVault/0.9.4 (Linux desktop music library)"
MUSICBRAINZ_LOCK = threading.Lock()
MUSICBRAINZ_LAST = 0.0


def image_tls_context():
    context = ssl.create_default_context()
    try:
        import certifi
        context.load_verify_locations(cafile=certifi.where())
    except ImportError:
        pass
    return context


def get_bytes(url, maximum=12 * 1024 * 1024):
    if not url.startswith("https://"):
        raise ValueError("El proveedor requiere HTTPS.")
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json,image/*"})
    with urlopen(request, timeout=8, context=image_tls_context()) as response:
        body = response.read(maximum + 1)
        if len(body) > maximum:
            raise ValueError("La respuesta del proveedor excede el tamaño permitido.")
        return body


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
                 float(item.get("duration", 0)), cover=album.get("cover_medium", ""),
                 source="deezer", source_id=str(item["id"]), url=item.get("link", ""))


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
        key = "search:tracks:" + ("youtube:" if source == "youtube" else "") + query.casefold()
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
        # A healthy Deezer page is published immediately. Slower providers are
        # fallbacks only, mirroring the Android fast-search path.
        if not tracks:
            try:
                rows = get_json("https://itunes.apple.com/search", term=query, entity="song", limit=40)
                tracks = [itunes_track(t).to_dict() for t in rows.get("results", []) if "trackId" in t]
            except Exception as exc:
                errors.append(f"iTunes: {exc}")
        if cancel and cancel.is_set():
            return []
        if not tracks:
            try:
                rows = get_json("https://musicbrainz.org/ws/2/recording/", query=query, fmt="json", limit=25)
                for row in rows.get("recordings", []):
                    releases = row.get("releases", [])
                    album = releases[0] if releases else {}
                    artist = "".join(a.get("name", "") + a.get("joinphrase", "") for a in row.get("artist-credit", []) if isinstance(a, dict))
                    tracks.append(Track("mb:" + row["id"], row["title"], artist or "Artista desconocido",
                                        album.get("title", "Sin álbum"), row.get("length", 0) / 1000,
                                        source="musicbrainz", source_id=row["id"]).to_dict())
            except Exception as exc:
                errors.append(f"MusicBrainz: {exc}")
        if not tracks and errors:
            stale = self.db.cache_get(key, ttl=None)
            if stale is not None:
                return stale
            raise RuntimeError("No se pudo consultar canciones. " + " · ".join(errors))
        self.db.cache_put(key, tracks)
        return tracks

    def search_albums(self, query, cancel=None):
        query = query.strip()
        key = "search:albums:" + query.casefold()
        cached = self.db.cache_get(key)
        if cached is not None:
            return cached
        errors, albums = [], []
        try:
            rows = get_json("https://api.deezer.com/search/album", q=query, limit=18)
            albums = [{"id": str(a["id"]), "title": a["title"], "artist": a["artist"]["name"],
                       "cover": a.get("cover_medium", ""), "source": "deezer"} for a in rows.get("data", [])]
        except Exception as exc:
            errors.append(f"Deezer: {exc}")
        if cancel and cancel.is_set():
            return []
        if not albums:
            try:
                rows = get_json("https://itunes.apple.com/search", term=query, entity="album", limit=18)
                albums = [{"id": str(a["collectionId"]), "title": a.get("collectionName", ""),
                           "artist": a.get("artistName", ""),
                           "cover": a.get("artworkUrl100", "").replace("100x100", "600x600"),
                           "source": "itunes"}
                          for a in rows.get("results", []) if "collectionId" in a]
            except Exception as exc:
                errors.append(f"iTunes: {exc}")
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
    command = downloader_command() + youtube_arguments() + ["--ignore-config", "--cache-dir", str(data_dir()/"cache/yt-dlp"), "--flat-playlist", "--dump-single-json", "--skip-download",
               "--no-warnings", "--socket-timeout", "10", "--retries", "0", "--", "ytsearch20:" + query]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    deadline = time.monotonic() + 35
    try:
        while True:
            if cancel and cancel.is_set():
                return []
            if time.monotonic() > deadline:
                raise RuntimeError("YouTube tardó demasiado en responder.")
            try:
                stdout, stderr = process.communicate(timeout=0.2)
                break
            except subprocess.TimeoutExpired:
                continue
        if process.returncode:
            raise RuntimeError(stderr.decode(errors="replace")[-1000:])
        result = json.loads(stdout)
        tracks = []
        for item in result.get("entries", []):
            if not item or not item.get("id"):
                continue
            tracks.append(Track("youtube:" + item["id"], item.get("title") or "Sin título",
                                item.get("artist") or item.get("uploader") or item.get("channel") or "YouTube",
                                item.get("album") or "YouTube", float(item.get("duration") or 0),
                                cover="https://i.ytimg.com/vi/" + item["id"] + "/hqdefault.jpg",
                                source="youtube", source_id=item["id"], url="https://www.youtube.com/watch?v=" + item["id"]))
        return tracks
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
        process.stdout.close()
        process.stderr.close()


def cover_file(url):
    from .artwork import cover_file as fetch_artwork
    return fetch_artwork(url)


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
    """A private staging directory makes cancellation safe and final publication atomic."""
    downloader = downloader_command()
    runtime = youtube_arguments()
    if not shutil.which("ffmpeg"):
        raise RuntimeError("Instala FFmpeg para descargar música.")
    destination = Path(destination).expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)
    if cancel.is_set():
        raise DownloadCancelled()
    target = track.url if track.source == "youtube" and track.url.startswith("https://") else f"ytsearch1:{track.artist} {track.title} audio"
    with tempfile.TemporaryDirectory(prefix=".musicvault-", dir=destination) as work:
        command = downloader + runtime + ["--ignore-config", "--cache-dir", str(data_dir()/"cache/yt-dlp"), "--no-playlist", "--no-colors", "--newline", "--progress", "--no-simulate",
                   "--socket-timeout", "15", "--retries", "2", "--extract-audio", "--audio-format", "mp3",
                   "--audio-quality", "0", "--embed-metadata", "--progress-template",
                   "download:MV_PROGRESS:%(progress._percent_str)s", "--print", "after_move:MV_FILE:%(filepath)s",
                   "--output", str(Path(work) / "audio.%(ext)s"), "--", target]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        tail, buffer, final = [], b"", None
        deadline = time.monotonic() + 1800
        try:
            while True:
                if cancel.is_set():
                    raise DownloadCancelled()
                if time.monotonic() > deadline:
                    raise RuntimeError("La descarga excedió el tiempo máximo de 30 minutos.")
                ready = selector.select(0.2)
                if ready:
                    chunk = os.read(process.stdout.fileno(), 8192)
                    buffer += chunk
                    while b"\n" in buffer:
                        raw, buffer = buffer.split(b"\n", 1)
                        line = raw.decode("utf-8", errors="replace").strip()
                        tail.append(line)
                        tail = tail[-12:]
                        if line.startswith("MV_PROGRESS:"):
                            try:
                                progress(min(99, int(float(line.split(":", 1)[1].strip().rstrip("%")))))
                            except ValueError:
                                pass
                        elif line.startswith("MV_FILE:"):
                            final = Path(line.split(":", 1)[1]).resolve()
                    if not chunk:
                        break
                elif process.poll() is not None:
                    break
            if process.wait(timeout=5) != 0:
                details = "\n".join(tail)
                raise RuntimeError(explain_download_error(details) + "\n\nDetalle técnico:\n" + details)
            if cancel.is_set():
                raise DownloadCancelled()
            if not final or not final.is_relative_to(Path(work).resolve()) or not final.is_file():
                raise RuntimeError("La descarga terminó sin un archivo de audio válido.")
            info = read_track(final)
            filename = stable_id(track.id) + ".mp3"
            output = destination / filename
            os.replace(final, output)
            return replace(track, path=str(output), duration=info.duration)
        finally:
            selector.close()
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            process.stdout.close()
