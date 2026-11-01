"""Reuse MusicVault without importing Qt or touching the desktop library."""
import os
from pathlib import Path
import re
import sys
import threading
from urllib.parse import parse_qs, urlparse


class MusicProvider:
    def __init__(self):
        root = Path(__file__).resolve().parents[2]
        local = Path(__file__).resolve().parents[1]
        desktop = local / "musicvault_core"
        if not desktop.is_dir():
            desktop = root / ("windows" if sys.platform == "win32" else "linux")
        if not (desktop / "musicvault/streaming.py").is_file():
            raise RuntimeError("Ejecuta el bot desde el repositorio completo de MusicVault.")
        sys.path.insert(0, str(desktop))
        # A dedicated database/cache: never write into the desktop user's library.
        directory = Path(os.environ.get("MUSICVAULT_BOT_DATA_DIR", local / ".data")).expanduser().resolve()
        os.environ["MUSICVAULT_DATA_DIR"] = str(directory)
        from musicvault.database import Database
        from musicvault.streaming import StreamingService, youtube_search
        from musicvault.models import Track
        self.engine_lock = threading.Lock()
        self.track_type = Track
        self.search_youtube = youtube_search
        self.streaming = StreamingService(Database(directory / "bot.sqlite3"))

    def search(self, query, cancel):
        return self.search_many(query, cancel, limit=1)[0]

    def search_many(self, query, cancel, limit=5):
        query = query.strip()
        if not query or len(query) > 300:
            raise ValueError("Escribe una canción o enlace de YouTube de hasta 300 caracteres.")
        if "://" in query:
            parsed = urlparse(query)
            host = (parsed.hostname or "").lower()
            if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port:
                raise ValueError("Usa un enlace HTTPS de YouTube sin credenciales ni puerto.")
            if host in ("youtu.be", "www.youtu.be"):
                video = parsed.path.strip("/")
            elif host in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"):
                parts = parsed.path.strip("/").split("/")
                video = parse_qs(parsed.query).get("v", [""])[0] if parsed.path == "/watch" else (
                    parts[1] if len(parts) == 2 and parts[0] in ("shorts", "live") else "")
            else:
                video = ""
            if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video):
                raise ValueError("Ese enlace no identifica una canción de YouTube. También puedes buscar por nombre.")
            return [self.track_type("youtube:" + video, "YouTube · " + video, source="youtube", source_id=video,
                                   url="https://www.youtube.com/watch?v=" + video)]
        tracks = self._engine_call(lambda: self.search_youtube(query, cancel, limit=limit), cancel)
        if not tracks:
            raise ValueError("No se encontraron canciones.")
        return tracks[:limit]

    def resolve(self, track, cancel):
        return self._engine_call(lambda: self.streaming.resolve(track, cancel), cancel)

    def _engine_call(self, operation, cancel):
        while not self.engine_lock.acquire(timeout=0.1):
            if cancel.is_set():
                raise RuntimeError("Operación cancelada.")
        try:
            if cancel.is_set():
                raise RuntimeError("Operación cancelada.")
            return operation()
        finally:
            self.engine_lock.release()
