"""Read playlists with the embedded SongMirror connector or from its exports.

SongMirror exports are ordinary JSON or XML files.  This reader never opens its
web UI, data directory, or credentials; it only accepts a file deliberately
selected by the user. Spotify links use the read-only SongMirror-compatible
connector bundled with MusicVault; no separate server or Docker is required.
"""
from dataclasses import dataclass
from html import unescape
import hashlib
import json
from pathlib import Path
import re
import unicodedata
from xml.etree import ElementTree

from .models import Track


_MAX_FILE = 32 * 1024 * 1024
_SPACE = re.compile(r"\s+")


@dataclass(frozen=True)
class SongMirrorPlaylist:
    id: str
    title: str
    cover: str
    tracks: tuple[Track, ...]
    url: str = ""
    total: int = 0


def _text(value, fallback=""):
    if not isinstance(value, str):
        return fallback
    value = unicodedata.normalize("NFKC", unescape(value))
    value = "".join(char for char in value if char >= " " or char in "\n\t")
    return _SPACE.sub(" ", value).strip()[:500] or fallback


def _value(item, *keys):
    if not isinstance(item, dict):
        return None
    for key in keys:
        current = item
        for part in key.split("."):
            if not isinstance(current, dict):
                break
            current = current.get(part)
        else:
            if current not in (None, ""):
                return current
    return None


def _artists(value):
    if isinstance(value, str):
        return _text(value, "Artista desconocido")
    if isinstance(value, dict):
        return _text(value.get("name") or value.get("title"), "Artista desconocido")
    if isinstance(value, list):
        names = [_artists(item) for item in value]
        return ", ".join(name for name in names if name and name != "Artista desconocido")[:500] or "Artista desconocido"
    return "Artista desconocido"


def _cover(value):
    if isinstance(value, str) and value.startswith("https://"):
        return value
    if isinstance(value, dict):
        return _cover(value.get("url") or value.get("src") or value.get("href"))
    if isinstance(value, list):
        for item in value:
            result = _cover(item)
            if result:
                return result
    return ""


def _duration(value):
    try:
        value = float(value)
    except (TypeError, ValueError):
        return 0
    return max(0, value / 1000 if value > 36_000 else value)


def _track(item, position):
    if not isinstance(item, dict):
        return None
    nested = _value(item, "track")
    data = nested if isinstance(nested, dict) else item
    title = _text(_value(data, "title", "name"))
    if not title:
        return None
    artist = _artists(_value(data, "artists", "artist", "album_artist"))
    album_data = _value(data, "album")
    album = _text(_value(data, "album.name", "album.title", "album_name"), "Sin álbum")
    track_id = _text(_value(data, "id", "track_id", "provider_track_id", "uri", "isrc"))
    provider = _text(_value(data, "provider", "service", "source"), "songmirror").casefold()
    identity = track_id or hashlib.sha256(f"{title}\0{artist}\0{album}\0{position}".encode()).hexdigest()[:32]
    source_id = f"{provider}:{identity}"
    return Track(f"songmirror:{source_id}", title, artist, album,
                 _duration(_value(data, "duration_ms", "duration", "duration_seconds")),
                 cover=_cover(_value(data, "cover", "artwork", "image", "album.images") or
                              (album_data.get("images") if isinstance(album_data, dict) else None)),
                 source="songmirror", source_id=source_id, url=_text(_value(data, "url", "external_url", "link")))


def _spotify_track(item, position):
    """Convert SongMirror's normalized playlist-detail row to MusicVault."""
    if not isinstance(item, dict) or item.get("unavailable"):
        return None
    title = _text(_value(item, "name", "title"))
    if not title:
        return None
    track_id = _text(_value(item, "id", "track_id"))
    if not track_id:
        track_id = hashlib.sha256(
            f"{title}\0{_artists(item.get('artist'))}\0{position}".encode()
        ).hexdigest()[:32]
    elif track_id.casefold().startswith("spotify:track:"):
        track_id = track_id.rsplit(":", 1)[-1]
    album_value = item.get("album")
    album = _text(
        album_value.get("name") if isinstance(album_value, dict) else album_value,
        "Sin álbum",
    )
    return Track(
        f"spotify:{track_id}",
        title,
        _artists(_value(item, "artist", "artists")),
        album,
        _duration(_value(item, "duration_ms", "duration")),
        cover=_cover(_value(item, "image", "cover", "artwork")),
        source="spotify",
        source_id=track_id,
        url=_text(_value(item, "external_url", "url", "link")),
    )


def import_spotify_playlist(value, cache_get=None, cache_put=None, progress=None, fetch=None):
    """Read a Spotify playlist through the bundled SongMirror connector."""
    from .spotify import playlist_url
    from .songmirror_spotify import fetch_playlist

    playlist_id, canonical = playlist_url(value)
    key = f"songmirror:embedded:v1:spotify-playlist:{playlist_id}"
    cached = cache_get(key, 12 * 3600) if cache_get else None
    if cached:
        return SongMirrorPlaylist(
            cached["id"], cached["title"], cached.get("cover", ""),
            tuple(Track.from_dict(track) for track in cached["tracks"]),
            cached.get("url", canonical), cached.get("total", len(cached["tracks"])),
        )

    if progress:
        progress("Leyendo la playlist con el conector integrado…")
    detail = (fetch or fetch_playlist)(playlist_id, progress=progress)
    if not isinstance(detail, dict) or not isinstance(detail.get("tracks"), list):
        raise RuntimeError("El conector de Spotify no devolvió las canciones de la playlist.")
    tracks = tuple(
        track for position, item in enumerate(detail["tracks"])
        if (track := _spotify_track(item, position))
    )
    if not tracks:
        raise RuntimeError("Spotify no encontró canciones disponibles en esta playlist.")
    try:
        total = max(len(tracks), int(detail.get("count") or len(tracks)))
    except (TypeError, ValueError):
        total = len(tracks)
    result = SongMirrorPlaylist(
        playlist_id,
        _text(detail.get("name"), "Playlist de Spotify"),
        _cover(detail.get("image")),
        tracks,
        _text(detail.get("external_url"), canonical),
        total,
    )
    if cache_put:
        cache_put(key, {
            "id": result.id, "title": result.title, "cover": result.cover,
            "tracks": [track.to_dict() for track in result.tracks],
            "url": result.url, "total": result.total,
        })
    return result


def import_spotify_track(value, cache_get=None, cache_put=None, fetch=None):
    """Read one Spotify track through the authenticated embedded connector."""
    from .spotify import track_url
    from .songmirror_spotify import fetch_track

    track_id, _canonical = track_url(value)
    key = f"songmirror:embedded:v1:spotify-track:{track_id}"
    cached = cache_get(key, 12 * 3600) if cache_get else None
    if cached:
        return Track.from_dict(cached)
    detail = (fetch or fetch_track)(track_id)
    track = _spotify_track(detail, 0)
    if track is None:
        raise RuntimeError("Spotify no encontró esa canción o no está disponible.")
    if cache_put:
        cache_put(key, track.to_dict())
    return track


def _playlist(item, fallback_id):
    if not isinstance(item, dict):
        return None
    title = _text(_value(item, "name", "title"), "Playlist de SongMirror")
    entries = _value(item, "tracks", "items", "entries")
    if not isinstance(entries, list):
        return None
    playlist_id = _text(_value(item, "id", "playlist_id", "provider_playlist_id", "uri"))
    playlist_id = playlist_id or hashlib.sha256(f"{title}\0{fallback_id}".encode()).hexdigest()[:24]
    tracks = tuple(track for position, entry in enumerate(entries) if (track := _track(entry, position)))
    return SongMirrorPlaylist(playlist_id, title, _cover(_value(item, "cover", "artwork", "image", "images")), tracks,
                               _text(_value(item, "url", "external_url", "link")))


def _xml_value(element, *names):
    for name in names:
        child = element.find(name)
        if child is not None and child.text:
            return child.text
        if name in element.attrib:
            return element.attrib[name]
    return None


def _xml_playlists(raw):
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as exc:
        raise ValueError("El XML de SongMirror no es válido.") from exc
    candidates = [root] if root.tag.rsplit("}", 1)[-1] == "playlist" else [
        node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == "playlist"]
    result = []
    for number, node in enumerate(candidates):
        entries = []
        for entry in node.iter():
            if entry.tag.rsplit("}", 1)[-1] not in {"track", "item", "entry"}:
                continue
            artists = [child.text for child in entry.iter() if child.tag.rsplit("}", 1)[-1] == "artist" and child.text]
            entries.append({"id": _xml_value(entry, "id", "track_id", "isrc"), "title": _xml_value(entry, "title", "name"),
                            "artist": artists or _xml_value(entry, "artist"), "album_name": _xml_value(entry, "album", "album_name"),
                            "duration": _xml_value(entry, "duration", "duration_ms"), "cover": _xml_value(entry, "cover", "artwork", "image"),
                            "url": _xml_value(entry, "url", "link"), "provider": _xml_value(entry, "provider", "service")})
        item = {"id": _xml_value(node, "id", "playlist_id"), "name": _xml_value(node, "name", "title"),
                "cover": _xml_value(node, "cover", "artwork", "image"), "url": _xml_value(node, "url", "link"), "tracks": entries}
        playlist = _playlist(item, str(number))
        if playlist:
            result.append(playlist)
    return tuple(result)


def load_playlists(path):
    """Read SongMirror JSON/XML exports and return non-empty playlists."""
    source = Path(path)
    try:
        raw = source.read_bytes()
    except OSError as exc:
        raise ValueError("No se pudo leer el archivo de SongMirror.") from exc
    if not raw or len(raw) > _MAX_FILE:
        raise ValueError("El archivo de SongMirror está vacío o es demasiado grande.")
    if source.suffix.casefold() == ".xml" or raw.lstrip().startswith(b"<"):
        playlists = _xml_playlists(raw)
    else:
        try:
            payload = json.loads(raw.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("El JSON de SongMirror no es válido.") from exc
        if isinstance(payload, list):
            items = payload
        elif isinstance(payload, dict):
            items = _value(payload, "playlists", "items", "data.playlists")
            if not isinstance(items, list):
                items = [payload.get("playlist", payload)]
        else:
            raise ValueError("El archivo de SongMirror no contiene playlists.")
        playlists = tuple(playlist for number, item in enumerate(items) if (playlist := _playlist(item, str(number))))
    playlists = tuple(playlist for playlist in playlists if playlist.tracks)
    if not playlists:
        raise ValueError("No se encontraron canciones en la exportación de SongMirror.")
    return playlists
