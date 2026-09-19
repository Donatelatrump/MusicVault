"""Embedded SongMirror-compatible Spotify playlist reader.

This is a small read-only adaptation of SongMirror's cookie backend and of the
SpotifyScraper token exchange it uses. Both upstream projects are MIT licensed;
see THIRD_PARTY_NOTICES.md. It deliberately implements only the two operations
MusicVault needs and never handles passwords or audio playback URLs.
"""

import hashlib
import hmac
import json
import os
import re
import struct
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .models import data_dir


_PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
_SERVER_TIME = "https://open.spotify.com/api/server-time"
_TOKEN = "https://open.spotify.com/api/token"
_WEB = "https://open.spotify.com/"
_WEB_API = "https://api.spotify.com/v1/tracks/"
_APP_VERSION = "1.2.95.312.gda5d7e47"
_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36"
_PLAYLIST_HASH = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
_MAX_RESPONSE = 32 * 1024 * 1024
_MAX_TRACKS = 10_000
_TOTP_SECRETS = (
    (61, ',7/*F("rLJ2oxaKL^f+E1xvP@N'),
    (60, 'OmE{ZA.J^":0FG\\Uz?[@WW'),
    (59, "{iOFn;4}<1PFYKPV?5{%u14]M>/V0hDH"),
)
_token_cache = None


class SpotifySessionError(RuntimeError):
    pass


class _HTTPError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def session_path():
    return data_dir() / "spotify_sp_dc.private"


def session_configured():
    try:
        return bool(session_path().read_text(encoding="utf-8").strip())
    except OSError:
        return False


def captured_session_value(name, value, domain):
    """Return a bare sp_dc value only for a genuine Spotify cookie."""
    if isinstance(name, bytes):
        name = name.decode("ascii", errors="ignore")
    else:
        try:
            name = bytes(name).decode("ascii", errors="ignore")
        except (TypeError, ValueError):
            name = str(name or "")
    domain = str(domain or "").strip().casefold().lstrip(".")
    if name != "sp_dc" or not (domain == "spotify.com" or domain.endswith(".spotify.com")):
        return None
    if not isinstance(value, bytes):
        try:
            value = bytes(value)
        except (TypeError, ValueError):
            value = str(value or "").encode("utf-8")
    try:
        value = value.decode("utf-8", errors="strict").strip()
    except UnicodeDecodeError:
        return None
    if not value or len(value) > 4096 or any(char.isspace() or ord(char) < 32 for char in value):
        return None
    return value


def save_session(value):
    """Persist a bare sp_dc value in an owner-only file."""
    global _token_cache
    value = str(value or "").strip()
    if value.startswith("sp_dc="):
        value = value.split("=", 1)[1].split(";", 1)[0].strip()
    if not value or len(value) > 4096 or any(char.isspace() or ord(char) < 32 for char in value):
        raise ValueError("Spotify no proporcionó una sesión válida.")
    path = session_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(descriptor, value.encode("utf-8"))
    finally:
        os.close(descriptor)
    path.chmod(0o600)
    _token_cache = None


def clear_session():
    global _token_cache
    try:
        session_path().unlink()
    except FileNotFoundError:
        pass
    _token_cache = None


def _session():
    try:
        value = session_path().read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise SpotifySessionError(
            "Conecta Spotify desde Ajustes → Cuenta antes de importar el enlace."
        ) from exc
    if not value:
        raise SpotifySessionError(
            "Conecta Spotify desde Ajustes → Cuenta antes de importar el enlace."
        )
    return value


def _request(url, method="GET", headers=None, payload=None, maximum=_MAX_RESPONSE):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(url, data=data, method=method, headers={
        "User-Agent": _UA, "Accept": "application/json", **(headers or {}),
    })
    try:
        with urlopen(request, timeout=30) as response:
            raw = response.read(maximum + 1)
    except HTTPError as exc:
        detail = exc.read(64 * 1024).decode("utf-8", errors="replace")
        raise _HTTPError(exc.code, detail) from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise SpotifySessionError("No se pudo conectar con Spotify. Revisa tu conexión.") from exc
    if len(raw) > maximum:
        raise SpotifySessionError("Spotify devolvió una respuesta demasiado grande.")
    return raw


def _json_request(url, method="GET", headers=None, payload=None):
    try:
        return json.loads(_request(url, method, headers, payload))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SpotifySessionError("Spotify devolvió una respuesta no válida.") from exc


def _totp(secret, timestamp):
    key = "".join(
        str(ord(char) ^ (index % 33 + 9)) for index, char in enumerate(secret)
    ).encode()
    counter = int(timestamp) // 30
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return str(code % 1_000_000).zfill(6)


def _access_token(cookie, request_json):
    global _token_cache
    fingerprint = hashlib.sha256(cookie.encode()).digest()
    now_ms = time.time_ns() // 1_000_000
    if _token_cache and _token_cache[0] == fingerprint and now_ms < _token_cache[2] - 60_000:
        return _token_cache[1]
    cookie_header = {"Cookie": f"sp_dc={cookie}"}
    try:
        server = request_json(_SERVER_TIME, "GET", cookie_header, None)
        server_time = int(server["serverTime"])
        now = now_ms // 1000
        for version, secret in _TOTP_SECRETS:
            query = urlencode({
                "reason": "init", "productType": "web-player",
                "totp": _totp(secret, now), "totpServer": _totp(secret, server_time),
                "totpVer": version,
            })
            body = request_json(f"{_TOKEN}?{query}", "GET", cookie_header, None)
            if body.get("error") == "totpVerExpired" or body.get("totpVerExpired") is True:
                continue
            token = body.get("accessToken")
            expires = body.get("accessTokenExpirationTimestampMs")
            if token and isinstance(expires, int) and not body.get("isAnonymous", True):
                _token_cache = (fingerprint, token, expires)
                return token
    except (KeyError, TypeError, ValueError, _HTTPError) as exc:
        raise SpotifySessionError(
            "Spotify rechazó la sesión. Vuelve a conectarla desde Ajustes → Cuenta."
        ) from exc
    raise SpotifySessionError(
        "Spotify rechazó la sesión o cambió su autenticación. Actualiza MusicVault o vuelve a conectarla."
    )


def _headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "app-platform": "WebPlayer",
        "spotify-app-version": _APP_VERSION,
        "Origin": _WEB,
        "Referer": _WEB,
        "Content-Type": "application/json;charset=UTF-8",
    }


def _pathfinder(operation, variables, token, request_json, query_hash):
    body = {
        "variables": variables,
        "operationName": operation,
        "extensions": {"persistedQuery": {"version": 1, "sha256Hash": query_hash}},
    }
    try:
        result = request_json(_PATHFINDER, "POST", _headers(token), body)
    except _HTTPError as exc:
        if exc.status in {401, 403}:
            raise SpotifySessionError(
                "Spotify rechazó la sesión. Vuelve a conectarla desde Ajustes → Cuenta."
            ) from exc
        raise
    errors = result.get("errors") or []
    if errors:
        if any("PersistedQueryNotFound" in str(error) for error in errors):
            raise SpotifySessionError("QUERY_HASH_EXPIRED")
        raise SpotifySessionError(f"Spotify no pudo leer la playlist: {errors[0]}")
    return result.get("data") or {}


def _refresh_playlist_hash(cookie, request_bytes):
    """Find SongMirror's two read operations in the current web-player bundle."""
    shell = request_bytes(_WEB, "GET", {"Cookie": f"sp_dc={cookie}"}, None, 4 * 1024 * 1024)
    urls = set(re.findall(
        rb"https://open\.spotifycdn\.com/cdn/build/web-player/[^\"']+\.js", shell,
    ))
    pattern = re.compile(rb'\.l\("fetchPlaylistContents","query","([a-f0-9]{64})"')
    for raw_url in list(urls)[:40]:
        blob = request_bytes(raw_url.decode(), "GET", None, None, 8 * 1024 * 1024)
        match = pattern.search(blob)
        if match:
            return match.group(1).decode()
    raise SpotifySessionError(
        "Spotify cambió su lector de playlists y MusicVault no pudo actualizarlo automáticamente."
    )


def _run_pathfinder(operation, variables, token, cookie, request_json, request_bytes, query_hash):
    try:
        return _pathfinder(operation, variables, token, request_json, query_hash), query_hash
    except SpotifySessionError as exc:
        if str(exc) != "QUERY_HASH_EXPIRED":
            raise
    refreshed = _refresh_playlist_hash(cookie, request_bytes)
    return _pathfinder(operation, variables, token, request_json, refreshed), refreshed


def fetch_playlist(playlist_id, progress=None, cookie=None, request_json=None, request_bytes=None):
    """Return complete normalized metadata for one Spotify playlist."""
    cookie = cookie or _session()
    request_json = request_json or _json_request
    request_bytes = request_bytes or _request
    token = _access_token(cookie, request_json)
    query_hash = _PLAYLIST_HASH
    uri = f"spotify:playlist:{playlist_id}"
    data, query_hash = _run_pathfinder(
        "fetchPlaylist",
        {"uri": uri, "offset": 0, "limit": 0, "enableWatchFeedEntrypoint": False},
        token, cookie, request_json, request_bytes, query_hash,
    )
    playlist = data.get("playlistV2") or {}
    if playlist.get("name") is None:
        raise SpotifySessionError("Spotify no encontró esa playlist o la cuenta no puede verla.")
    sources = []
    for image in (playlist.get("images") or {}).get("items") or []:
        sources.extend(image.get("sources") or [])
    cover = next((item.get("url") for item in sources if item.get("url")), "")
    expected = (playlist.get("content") or {}).get("totalCount")
    tracks, offset = [], 0
    while True:
        data, query_hash = _run_pathfinder(
            "fetchPlaylistContents", {"uri": uri, "offset": offset, "limit": 100},
            token, cookie, request_json, request_bytes, query_hash,
        )
        page = (data.get("playlistV2") or {}).get("content") or {}
        items = page.get("items") or []
        total = page.get("totalCount")
        if total is None:
            raise SpotifySessionError("Spotify devolvió una playlist incompleta.")
        for item in items:
            track = (item.get("itemV2") or {}).get("data") or {}
            track_uri = track.get("uri") or ""
            if not track_uri.startswith("spotify:track:"):
                continue
            album = track.get("albumOfTrack") or {}
            art = (album.get("coverArt") or {}).get("sources") or []
            image = next((candidate.get("url") for candidate in art if candidate.get("url")), "")
            artists = [
                (artist.get("profile") or {}).get("name", "")
                for artist in ((track.get("artists") or {}).get("items") or [])
            ]
            tracks.append({
                "id": track_uri.rsplit(":", 1)[-1], "name": track.get("name") or "",
                "artist": ", ".join(name for name in artists if name),
                "album": album.get("name") or "", "image": image,
                "duration_ms": (track.get("trackDuration") or {}).get("totalMilliseconds"),
                "external_url": f"https://open.spotify.com/track/{track_uri.rsplit(':', 1)[-1]}",
            })
        offset += len(items)
        if progress:
            progress(f"Leyendo canciones de Spotify: {min(offset, int(total))}/{total}")
        if offset >= int(total):
            break
        if not items or offset > _MAX_TRACKS:
            raise SpotifySessionError("Spotify devolvió una playlist incompleta.")
    return {
        "id": playlist_id, "name": playlist.get("name") or "Playlist de Spotify",
        "image": cover, "count": int(expected if expected is not None else len(tracks)),
        "external_url": f"https://open.spotify.com/playlist/{playlist_id}", "tracks": tracks,
    }


def fetch_track(track_id, cookie=None, request_json=None):
    """Return normalized metadata for one Spotify track using the saved session."""
    cookie = cookie or _session()
    request_json = request_json or _json_request
    token = _access_token(cookie, request_json)
    try:
        detail = request_json(
            _WEB_API + track_id, "GET", _headers(token), None,
        )
    except _HTTPError as exc:
        if exc.status in {401, 403}:
            raise SpotifySessionError(
                "Spotify rechazó la sesión. Vuelve a conectarla desde Ajustes → Cuenta."
            ) from exc
        if exc.status == 404:
            raise SpotifySessionError("Spotify no encontró esa canción o la cuenta no puede verla.") from exc
        if exc.status != 429:
            raise
        return _public_track(track_id, _request)
    if not isinstance(detail, dict) or not detail.get("name"):
        raise SpotifySessionError("Spotify no devolvió datos válidos para esa canción.")
    album = detail.get("album") or {}
    artists = [artist.get("name", "") for artist in detail.get("artists") or [] if isinstance(artist, dict)]
    images = album.get("images") or []
    cover = next((image.get("url") for image in images if isinstance(image, dict) and image.get("url")), "")
    return {
        "id": detail.get("id") or track_id,
        "name": detail.get("name") or "",
        "artist": ", ".join(name for name in artists if name),
        "album": album.get("name") or "",
        "image": cover,
        "duration_ms": detail.get("duration_ms"),
        "external_url": (detail.get("external_urls") or {}).get("spotify")
        or f"https://open.spotify.com/track/{track_id}",
    }


def _public_track(track_id, request_bytes):
    """Read the official Spotify embed when the Web API is temporarily limited."""
    raw = request_bytes(
        f"https://open.spotify.com/embed/track/{track_id}", "GET", None, None,
        4 * 1024 * 1024,
    )
    try:
        page = raw.decode("utf-8", errors="strict")
        match = re.search(
            r'<script[^>]*id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>',
            page, re.DOTALL,
        )
        payload = json.loads(match.group(1)) if match else {}
        entity = payload["props"]["pageProps"]["state"]["data"]["entity"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as exc:
        raise SpotifySessionError("Spotify no devolvió datos válidos para esa canción.") from exc
    if not isinstance(entity, dict) or entity.get("type") != "track" or not entity.get("name"):
        raise SpotifySessionError("Spotify no encontró esa canción o no está disponible.")
    artists = [artist.get("name", "") for artist in entity.get("artists") or [] if isinstance(artist, dict)]
    images = (entity.get("visualIdentity") or {}).get("image") or []
    cover = next((image.get("url") for image in images if isinstance(image, dict) and image.get("url")), "")
    return {
        "id": entity.get("id") or track_id,
        "name": entity.get("name") or "",
        "artist": ", ".join(name for name in artists if name),
        "album": "Spotify",
        "image": cover,
        "duration_ms": entity.get("duration"),
        "external_url": f"https://open.spotify.com/track/{track_id}",
    }


def validate_session(cookie=None, request_json=None):
    """Exchange the saved cookie without exposing the resulting bearer."""
    return bool(_access_token(cookie or _session(), request_json or _json_request))
