"""Fast YouTube resolution, screen-idle warmup and temporary stream cache for Windows."""
from dataclasses import replace
from pathlib import Path
from urllib.parse import quote_plus
import json
import os
import shutil
import subprocess
import threading
import time

from .downloader import downloader_command, youtube_arguments
from .models import Track, data_dir, stable_id
from .processes import process_options, stop_process

STREAM_URL_TTL = 10 * 60
MATCH_TTL = 60 * 60
MEDIA_TTL = 24 * 60 * 60
MEDIA_MAX_BYTES = 1024 * 1024 * 1024


class StreamCancelled(Exception):
    pass


def _terminate(process):
    if process.poll() is not None:
        return
    try:
        stop_process(process)
        process.wait(timeout=2)
    except Exception:
        try:
            stop_process(process, force=True)
        except Exception:
            pass
        try:
            process.wait(timeout=2)
        except Exception:
            pass


def _run(command, cancel=None, timeout=30):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               **process_options())
    deadline = time.monotonic() + timeout
    try:
        while True:
            if cancel is not None and cancel.is_set():
                raise StreamCancelled()
            if time.monotonic() > deadline:
                raise RuntimeError("YouTube tardó demasiado en responder.")
            try:
                stdout, stderr = process.communicate(timeout=0.15)
                break
            except subprocess.TimeoutExpired:
                continue
        if process.returncode:
            detail = stderr.decode("utf-8", errors="replace").strip()[-1200:]
            raise RuntimeError(detail or "El proveedor de YouTube no respondió correctamente.")
        return stdout.decode("utf-8", errors="replace")
    finally:
        if process.poll() is None:
            _terminate(process)
        if process.stdout:
            process.stdout.close()
        if process.stderr:
            process.stderr.close()


def _yt_base():
    return downloader_command() + youtube_arguments() + [
        "--encoding", "utf-8", "--ignore-config", "--cache-dir", str(data_dir() / "cache/yt-dlp"),
        "--no-warnings", "--socket-timeout", "10", "--retries", "1",
    ]


def _youtube_track(item):
    video_id = str(item.get("id") or item.get("url") or "").strip()
    if not video_id:
        return None
    if video_id.startswith("http"):
        # Flat results normally expose the id, but keep URL-only rows usable.
        marker = "v="
        video_id = video_id.split(marker, 1)[1].split("&", 1)[0] if marker in video_id else stable_id(video_id)[:12]
    thumbnails = item.get("thumbnails") or []
    thumbnail = item.get("thumbnail") or (thumbnails[-1].get("url", "") if thumbnails else "")
    return Track(
        "youtube:" + video_id,
        item.get("title") or "Sin título",
        item.get("artist") or item.get("uploader") or item.get("channel") or "YouTube",
        item.get("album") or "YouTube",
        float(item.get("duration") or 0),
        cover=thumbnail or f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        source="youtube", source_id=video_id,
        url=f"https://www.youtube.com/watch?v={video_id}",
    )


def youtube_search(query, cancel=None, limit=20):
    """Line-oriented flat search: materially cheaper than one large single JSON document."""
    command = _yt_base() + ["--flat-playlist", "--dump-json", "--skip-download",
                            "--playlist-end", str(limit), "--", f"ytsearch{limit}:{query}"]
    output = _run(command, cancel, timeout=24)
    tracks = []
    for line in output.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        track = _youtube_track(item)
        if track:
            tracks.append(track)
    return tracks


def _playlist_from_item(item):
    playlist_id = str(item.get("id") or item.get("playlist_id") or "").strip()
    if not playlist_id:
        url = str(item.get("url") or item.get("webpage_url") or "")
        if "list=" in url:
            playlist_id = url.split("list=", 1)[1].split("&", 1)[0]
    if not playlist_id:
        return None
    thumbnails = item.get("thumbnails") or []
    cover = item.get("thumbnail") or (thumbnails[-1].get("url", "") if thumbnails else "")
    return {
        "id": playlist_id,
        "title": item.get("title") or "Playlist de YouTube",
        "artist": item.get("uploader") or item.get("channel") or item.get("channel_name") or "YouTube",
        "cover": cover,
        "source": "youtube",
        "url": f"https://www.youtube.com/playlist?list={playlist_id}",
        "count": int(item.get("playlist_count") or item.get("n_entries") or item.get("playlist_n_entries") or 0),
    }


def youtube_playlist_search(query, cancel=None, limit=16):
    """Search playlist entities on YouTube. A direct playlist URL is accepted too."""
    query = query.strip()
    if "youtube.com/playlist" in query or "youtu.be/playlist" in query:
        targets = [query]
    else:
        # YouTube's playlist result filter. yt-dlp understands the search result page
        # and exposes playlist entities in flat mode on current releases.
        targets = [f"https://www.youtube.com/results?search_query={quote_plus(query)}&sp=EgIQAw%253D%253D"]
    playlists = []
    for target in targets:
        command = _yt_base() + ["--flat-playlist", "--dump-json", "--skip-download",
                                "--playlist-end", str(limit), "--", target]
        try:
            output = _run(command, cancel, timeout=25)
        except Exception:
            if cancel is not None and cancel.is_set():
                raise StreamCancelled()
            continue
        seen = set()
        for line in output.splitlines():
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            playlist = _playlist_from_item(item)
            if playlist and playlist["id"] not in seen:
                playlists.append(playlist)
                seen.add(playlist["id"])
                if len(playlists) >= limit:
                    return playlists
    return playlists


def youtube_playlist_tracks(playlist, cancel=None, limit=500):
    url = playlist.get("url") or f"https://www.youtube.com/playlist?list={playlist['id']}"
    command = _yt_base() + ["--flat-playlist", "--dump-json", "--skip-download", "--ignore-errors",
                            "--playlist-end", str(limit), "--", url]
    output = _run(command, cancel, timeout=55)
    tracks = []
    for line in output.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        track = _youtube_track(item)
        if track:
            tracks.append(track)
    return tracks


class StreamingService:
    def __init__(self, db):
        self.db = db
        self.cache_dir = data_dir() / "stream-cache"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._url_cache = {}
        self._locks = {}
        self._guard = threading.Lock()
        self.prune()

    def _lock(self, key):
        with self._guard:
            return self._locks.setdefault(key, threading.Lock())

    @staticmethod
    def _key(track):
        return track.id

    def _url_get(self, track):
        row = self._url_cache.get(self._key(track))
        if row and time.monotonic() - row[1] < STREAM_URL_TTL:
            return row[0]
        self._url_cache.pop(self._key(track), None)
        return ""

    def _url_put(self, track, url):
        self._url_cache[self._key(track)] = (url, time.monotonic())

    def match(self, track, cancel=None):
        if track.source == "youtube" and track.source_id:
            return track.url or f"https://www.youtube.com/watch?v={track.source_id}"
        cache_key = "stream:match:" + self._key(track)
        cached = self.db.cache_get(cache_key, MATCH_TTL)
        if isinstance(cached, str) and cached.startswith("http"):
            return cached
        query = " ".join(part for part in (track.artist, track.title, "audio") if part).strip()
        command = _yt_base() + ["--flat-playlist", "--skip-download", "--print", "%(webpage_url)s",
                                "--", "ytsearch1:" + query]
        output = _run(command, cancel, timeout=22)
        match = next((line.strip() for line in output.splitlines() if line.strip().startswith("http")), "")
        if not match:
            raise RuntimeError(f"No se encontró una fuente reproducible para «{track.title}».")
        self.db.cache_put(cache_key, match)
        return match

    def resolve(self, track, cancel=None):
        cached = self._url_get(track)
        if cached:
            return cached
        lock = self._lock(self._key(track))
        while not lock.acquire(timeout=0.1):
            if cancel is not None and cancel.is_set():
                raise StreamCancelled()
        try:
            cached = self._url_get(track)
            if cached:
                return cached
            match = self.match(track, cancel)
            common = ["--no-playlist", "--skip-download", "-f", "bestaudio/best", "--get-url"]
            errors = []
            for extra in (["--extractor-args", "youtube:skip=hls,dash"], []):
                try:
                    output = _run(_yt_base() + extra + common + ["--", match], cancel, timeout=26)
                    direct = next((line.strip() for line in output.splitlines()
                                   if line.strip().startswith("http") and "youtube.com/watch" not in line), "")
                    if direct:
                        self._url_put(track, direct)
                        return direct
                except StreamCancelled:
                    raise
                except Exception as exc:
                    errors.append(str(exc))
            raise RuntimeError((errors[-1] if errors else "") or f"No se pudo preparar «{track.title}» para streaming.")
        finally:
            lock.release()

    def cached_media(self, track, refresh=True):
        row = self.db.cache_get("stream:media:" + self._key(track), MEDIA_TTL)
        if not isinstance(row, dict):
            return ""
        path = Path(row.get("path", ""))
        if not path.is_file() or path.stat().st_size < 32768:
            return ""
        if refresh:
            try:
                os.utime(path, None)
            except OSError:
                pass
            self.db.cache_put("stream:media:" + self._key(track), {"path": str(path), "size": path.stat().st_size})
        return str(path)

    def record_path(self, track):
        path = self.cache_dir / (stable_id(track.id) + ".part")
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass
        return str(path)

    def mark_cached(self, track, partial_path):
        source = Path(partial_path)
        if not source.is_file() or source.stat().st_size < 32768:
            return ""
        final = self.cache_dir / (stable_id(track.id) + ".media")
        try:
            os.replace(source, final)
        except OSError:
            return ""
        self.db.cache_put("stream:media:" + self._key(track), {"path": str(final), "size": final.stat().st_size})
        self.prune()
        return str(final)

    def promote_cached(self, track, destination, cancel=None, progress=None):
        cached = self.cached_media(track)
        if not cached or not shutil.which("ffmpeg"):
            return None
        if cancel is not None and cancel.is_set():
            raise StreamCancelled()
        destination = Path(destination).expanduser().resolve()
        destination.mkdir(parents=True, exist_ok=True)
        output = destination / (stable_id(track.id) + ".mka")
        temporary = output.with_suffix(".mka.part")
        command = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", cached,
                   "-map", "0:a:0", "-c:a", "copy", "-f", "matroska", str(temporary)]
        try:
            _run(command, cancel, timeout=90)
        except Exception:
            temporary.unlink(missing_ok=True)
            return None
        if not temporary.is_file() or temporary.stat().st_size < 32768:
            temporary.unlink(missing_ok=True)
            return None
        os.replace(temporary, output)
        if progress:
            progress(100)
        return replace(track, path=str(output))

    def cache_usage(self):
        total = 0
        for path in self.cache_dir.glob("*"):
            if path.is_file():
                try:
                    total += path.stat().st_size
                except OSError:
                    pass
        return total

    def clear_cache(self):
        for path in self.cache_dir.glob("*"):
            if path.is_file():
                try:
                    path.unlink()
                except OSError:
                    pass
        with self._guard:
            self._url_cache.clear()
        return True

    def prune(self):
        now = time.time()
        files = []
        for path in self.cache_dir.glob("*"):
            if not path.is_file():
                continue
            try:
                stat = path.stat()
            except OSError:
                continue
            max_age = 3600 if path.suffix == ".part" else MEDIA_TTL
            if now - stat.st_mtime > max_age:
                try:
                    path.unlink()
                except OSError:
                    pass
                continue
            files.append((stat.st_mtime, stat.st_size, path))
        total = sum(size for _, size, _ in files)
        if total <= MEDIA_MAX_BYTES:
            return
        for _, size, path in sorted(files):
            try:
                path.unlink()
                total -= size
            except OSError:
                pass
            if total <= MEDIA_MAX_BYTES:
                break
