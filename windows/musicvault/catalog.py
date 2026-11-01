"""Mejoras de catálogo para Windows, inspiradas en Android:
SearchScorer (orden por relevancia), CoverImageCache (caché de portadas con TTL y límite)
y MusicBrainz como proveedor adicional de álbumes y artistas."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import hashlib
import os
import re
import threading
import time
from urllib.parse import urlencode, urlsplit

from PyQt6.QtCore import QObject, pyqtSignal
from PyQt6.QtGui import QImageReader

from .services import get_json, USER_AGENT
from .models import stable_id, data_dir


@dataclass(frozen=True)
class SearchResult:
    """Resultado de búsqueda con puntuación de relevancia."""
    kind: str  # "track", "album", "artist", "playlist"
    id: str
    title: str
    subtitle: str = ""
    cover: str = ""
    source: str = ""
    source_id: str = ""
    url: str = ""
    score: float = 0.0


class SearchScorer:
    """Puntaje de relevancia para ordenar resultados de búsqueda (mismo modelo que Android)."""

    def __init__(self, query: str):
        self.query = query.strip().casefold()
        self.tokens = [t for t in re.split(r"\s+", self.query) if t]

    def score(self, title: str, subtitle: str = "", artist: str = "") -> float:
        title_l = (title or "").casefold()
        sub_l = (subtitle or "").casefold()
        artist_l = (artist or "").casefold()
        haystack = " ".join(part for part in (title_l, sub_l, artist_l) if part)

        if not self.query:
            return 0.0

        # Coincidencia exacta del título completo
        if title_l == self.query:
            return 1.0

        # El título empieza con la consulta
        if title_l.startswith(self.query):
            return 0.9

        # El título contiene la consulta completa
        if self.query in title_l:
            return 0.8

        # Coincidencia de artista exacto
        if artist_l == self.query:
            return 0.75

        # Coincidencia de artista
        if self.query in artist_l:
            return 0.7

        # Tokens: cuántos aparecen en el título
        matched = sum(1 for tok in self.tokens if tok in title_l)
        if self.tokens and matched == len(self.tokens):
            return 0.65
        if matched > 0:
            return 0.5 * (matched / len(self.tokens))

        # Tokens en cualquier parte (título + artista + subtítulo)
        matched_any = sum(1 for tok in self.tokens if tok in haystack)
        if matched_any > 0:
            return 0.4 * (matched_any / len(self.tokens))

        return 0.0


class CoverImageCache(QObject):
    """Caché de portadas con TTL y límite de tamaño, similar a Android.

    Descarga y valida imágenes de carátula, manteniendo un límite máximo de
    bytes y expiración por tiempo. Emite `updated` cuando se guarda una nueva.
    """
    updated = pyqtSignal(str, str)  # url, local_path

    def __init__(self, max_bytes: int = 50 * 1024 * 1024, ttl_seconds: int = 30 * 86400):
        super().__init__()
        self.cache_dir = data_dir() / "covers"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.max_bytes = max_bytes
        self.ttl_seconds = ttl_seconds
        self._lock = threading.Lock()

    def _path_for(self, url: str) -> Path:
        return self.cache_dir / (stable_id(url) + ".img")

    def _valid(self, candidate: Path) -> bool:
        reader = QImageReader(str(candidate))
        reader.setDecideFormatFromContent(True)
        return not reader.read().isNull()

    def _cleanup(self):
        """Elimina portadas expiradas y, si aún se pasa el límite, las más antiguas."""
        now = time.time()
        files = []
        with self._lock:
            for path in self.cache_dir.glob("*"):
                if not path.is_file():
                    continue
                try:
                    stat = path.stat()
                except OSError:
                    continue
                if now - stat.st_mtime > self.ttl_seconds:
                    try:
                        path.unlink()
                    except OSError:
                        pass
                    continue
                files.append((stat.st_mtime, stat.st_size, path))
            total = sum(size for _, size, _ in files)
            if total > self.max_bytes:
                for _, size, path in sorted(files):
                    try:
                        path.unlink()
                        total -= size
                    except OSError:
                        pass
                    if total <= self.max_bytes:
                        break

    def get(self, url: str) -> str | None:
        """Devuelve la ruta local de la portada si está en caché y es válida."""
        if not url or not url.startswith(("https://", "http://", "//")):
            return url
        url = "https:" + url if url.startswith("//") else url.replace("http://", "https://", 1)
        path = self._path_for(url)
        if path.exists():
            if not self._valid(path):
                try:
                    path.unlink()
                except OSError:
                    pass
                return None
            return str(path)
        return None

    def put(self, url: str) -> str | None:
        """Descarga, valida y guarda la portada; devuelve la ruta local o None."""
        if not url or not url.startswith(("https://", "http://", "//")):
            return url
        url = "https:" + url if url.startswith("//") else url.replace("http://", "https://", 1)
        path = self._path_for(url)

        if path.exists() and self._valid(path):
            return str(path)

        candidates = [url]
        parsed = urlsplit(url)
        parts = parsed.path.split("/")
        if parsed.hostname in ("i.ytimg.com", "img.youtube.com") and len(parts) >= 4 and parts[1] in ("vi", "vi_webp"):
            fallback = f"https://i.ytimg.com/vi/{parts[2]}/hqdefault.jpg"
            if fallback != url:
                candidates.append(fallback)

        last_error = None
        for candidate in candidates:
            temporary = None
            try:
                body = get_bytes(candidate, maximum=8 * 1024 * 1024)
                temporary = Path(__file__).resolve().parents[1] / "Data" / "covers" / (".tmp-" + stable_id(candidate) + ".img")
                temporary.parent.mkdir(parents=True, exist_ok=True)
                temporary.write_bytes(body)
                if not self._valid(temporary):
                    raise ValueError("No es una imagen válida.")
                os.replace(temporary, path)
                self.updated.emit(candidate, str(path))
                self._cleanup()
                return str(path)
            except Exception as error:
                last_error = error
            finally:
                if temporary:
                    temporary.unlink(missing_ok=True)
        return None


class MusicBrainzProvider:
    """Búsqueda de álbumes y artistas mediante MusicBrainz (rate-limitado: 1 req/s)."""

    _lock = threading.Lock()
    _last = 0.0

    def __init__(self, app_name: str = "MusicVault/0.9.7", contact: str = "https://example.com"):
        self.app_name = app_name
        self.contact = contact

    def _throttle(self):
        with self._lock:
            now = time.monotonic()
            wait = max(0, 1.1 - (now - self._last))
            if wait:
                time.sleep(wait)
            self._last = time.monotonic()

    def _headers(self):
        return {"User-Agent": f"{self.app_name} ({self.contact})", "Accept": "application/json"}

    def search_albums(self, query: str, limit: int = 10) -> list[dict]:
        self._throttle()
        params = {"query": query, "fmt": "json", "limit": str(min(limit, 25))}
        data = get_json("https://musicbrainz.org/ws/2/release/", **params)
        albums = []
        for rel in data.get("releases", []) or []:
            title = rel.get("title", "Sin título")
            artist = "Artista desconocido"
            for credit in rel.get("artist-credit", []) or []:
                if isinstance(credit, dict):
                    artist = credit.get("artist", {}).get("name", artist)
                    break
            cover = ""
            art = rel.get("cover-art-archive") or {}
            if art.get("artwork"):
                rid = rel.get("id")
                cover = f"https://coverartarchive.org/release/{rid}/front"
            albums.append({
                "id": rel.get("id", ""),
                "title": title,
                "artist": artist,
                "cover": cover,
                "source": "musicbrainz",
                "source_id": rel.get("id", ""),
                "url": "https://musicbrainz.org/release/" + rel.get("id", ""),
            })
        return albums

    def search_artists(self, query: str, limit: int = 10) -> list[dict]:
        self._throttle()
        params = {"query": query, "fmt": "json", "limit": str(min(limit, 25))}
        data = get_json("https://musicbrainz.org/ws/2/artist/", **params)
        artists = []
        for ar in data.get("artists", []) or []:
            artists.append({
                "id": ar.get("id", ""),
                "title": ar.get("name", "Sin nombre"),
                "subtitle": ar.get("disambiguation", ""),
                "cover": "",
                "source": "musicbrainz",
                "source_id": ar.get("id", ""),
                "url": "https://musicbrainz.org/artist/" + ar.get("id", ""),
            })
        return artists


_catalog_scorer_cache: dict[str, SearchScorer] = {}


def scorer_for(query: str) -> SearchScorer:
    key = query.strip().casefold()
    cached = _catalog_scorer_cache.get(key)
    if cached is None:
        cached = SearchScorer(query)
        _catalog_scorer_cache[key] = cached
    return cached


def sort_results(results: list[SearchResult], query: str) -> list[SearchResult]:
    scorer = scorer_for(query)
    for r in results:
        r = SearchResult(
            kind=r.kind, id=r.id, title=r.title, subtitle=r.subtitle, cover=r.cover,
            source=r.source, source_id=r.source_id, url=r.url,
            score=scorer.score(r.title, r.subtitle, ""),
        )
    return sorted(results, key=lambda r: r.score, reverse=True)
