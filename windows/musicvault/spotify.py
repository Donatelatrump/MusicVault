"""Validation and clipboard discovery for Spotify links.

Resource contents are intentionally not fetched here. MusicVault delegates that
work to its authenticated, read-only Spotify connector.
"""

import re
from urllib.parse import urlparse


_RESOURCE = re.compile(
    r"^/(?:intl-[A-Za-z-]+/)?(?:embed/)?(track|playlist)/([A-Za-z0-9]{22})(?:/)?$"
)
_LINK = re.compile(r"https://(?:www\.)?open\.spotify\.com/[^\s<>\"']+", re.IGNORECASE)


def spotify_url(value):
    """Validate a supported Spotify link and return kind, id and canonical URL."""
    parsed = urlparse(str(value or "").strip())
    if parsed.scheme != "https" or parsed.hostname not in {"open.spotify.com", "www.open.spotify.com"}:
        raise ValueError("Pega un enlace de canción o playlist de open.spotify.com.")
    match = _RESOURCE.fullmatch(parsed.path)
    if not match:
        raise ValueError("El enlace no corresponde a una canción o playlist de Spotify.")
    kind, resource_id = match.groups()
    return kind, resource_id, f"https://open.spotify.com/{kind}/{resource_id}"


def playlist_url(value):
    """Validate a Spotify playlist link and return its id and canonical URL."""
    kind, resource_id, canonical = spotify_url(value)
    if kind != "playlist":
        raise ValueError("El enlace no corresponde a una playlist de Spotify.")
    return resource_id, canonical


def track_url(value):
    """Validate a Spotify track link and return its id and canonical URL."""
    kind, resource_id, canonical = spotify_url(value)
    if kind != "track":
        raise ValueError("El enlace no corresponde a una canción de Spotify.")
    return resource_id, canonical


def spotify_url_from_text(value):
    """Return the first supported Spotify URL embedded in copied plain text."""
    for match in _LINK.finditer(str(value or "")):
        candidate = match.group(0).rstrip(".,;:!?)]}")
        try:
            return spotify_url(candidate)
        except ValueError:
            continue
    return None
