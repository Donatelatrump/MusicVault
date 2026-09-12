import stat
import json

import pytest

from musicvault import songmirror_spotify as spotify


def test_session_is_stored_privately_and_can_be_removed():
    spotify.save_session("sp_dc=test-cookie-value")
    path = spotify.session_path()

    assert path.read_text() == "test-cookie-value"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert spotify.session_configured()

    spotify.clear_session()
    assert not path.exists()
    assert not spotify.session_configured()


def test_rejects_malformed_session_values():
    with pytest.raises(ValueError):
        spotify.save_session("")
    with pytest.raises(ValueError):
        spotify.save_session("value with spaces")


def test_captures_only_spotify_sp_dc_cookie():
    assert spotify.captured_session_value(b"sp_dc", b"secret", ".spotify.com") == "secret"
    assert spotify.captured_session_value(b"sp_dc", b"secret", "open.spotify.com") == "secret"
    assert spotify.captured_session_value(b"other", b"secret", ".spotify.com") is None
    assert spotify.captured_session_value(b"sp_dc", b"secret", "spotify.com.example.test") is None
    assert spotify.captured_session_value(b"sp_dc", b"bad value", ".spotify.com") is None


def test_fetch_playlist_exchanges_session_and_reads_every_page(monkeypatch):
    monkeypatch.setattr(spotify, "_token_cache", None)
    requests = []

    def request(url, method, headers, payload):
        requests.append((url, method, headers, payload))
        if url == spotify._SERVER_TIME:
            return {"serverTime": 1_800_000_000}
        if url.startswith(spotify._TOKEN):
            return {
                "accessToken": "temporary-token",
                "accessTokenExpirationTimestampMs": 9_999_999_999_999,
                "isAnonymous": False,
            }
        operation = payload["operationName"]
        if operation == "fetchPlaylist":
            return {"data": {"playlistV2": {
                "name": "Viaje", "content": {"totalCount": 2},
                "images": {"items": [{"sources": [{"url": "https://images.example/list.jpg"}]}]},
            }}}
        offset = payload["variables"]["offset"]
        items = [] if offset else [
            {"itemV2": {"data": {
                "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "name": "Canción",
                "artists": {"items": [{"profile": {"name": "Artista"}}]},
                "albumOfTrack": {"name": "Álbum", "coverArt": {"sources": [
                    {"url": "https://images.example/cover.jpg"}
                ]}},
                "trackDuration": {"totalMilliseconds": 189000},
            }}},
            {"itemV2": {"data": {"uri": "spotify:episode:ignored"}}},
        ]
        return {"data": {"playlistV2": {"content": {"items": items, "totalCount": 2}}}}

    result = spotify.fetch_playlist(
        "37i9dQZF1DXcBWIGoYBM5M", cookie="test-cookie", request_json=request,
    )

    assert result["name"] == "Viaje"
    assert result["count"] == 2
    assert len(result["tracks"]) == 1
    assert result["tracks"][0]["artist"] == "Artista"
    assert any(call[3] and call[3].get("operationName") == "fetchPlaylistContents" for call in requests)


def test_fetch_rejects_anonymous_cookie(monkeypatch):
    monkeypatch.setattr(spotify, "_token_cache", None)

    def request(url, method, headers, payload):
        if url == spotify._SERVER_TIME:
            return {"serverTime": 1_800_000_000}
        return {"isAnonymous": True}

    with pytest.raises(spotify.SpotifySessionError, match="rechazó"):
        spotify.fetch_playlist("37i9dQZF1DXcBWIGoYBM5M", cookie="bad", request_json=request)


def test_fetch_track_uses_connected_session_and_normalizes_metadata(monkeypatch):
    monkeypatch.setattr(spotify, "_token_cache", None)

    def request(url, method, headers, payload):
        if url == spotify._SERVER_TIME:
            return {"serverTime": 1_800_000_000}
        if url.startswith(spotify._TOKEN):
            return {
                "accessToken": "temporary-token",
                "accessTokenExpirationTimestampMs": 9_999_999_999_999,
                "isAnonymous": False,
            }
        assert url == spotify._WEB_API + "4uLU6hMCjMI75M1A2tKUQC"
        assert headers["Authorization"] == "Bearer temporary-token"
        return {
            "id": "4uLU6hMCjMI75M1A2tKUQC", "name": "Canción", "duration_ms": 189000,
            "artists": [{"name": "Artista"}],
            "album": {"name": "Álbum", "images": [{"url": "https://images.example/cover.jpg"}]},
            "external_urls": {"spotify": "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"},
        }

    result = spotify.fetch_track(
        "4uLU6hMCjMI75M1A2tKUQC", cookie="test-cookie", request_json=request,
    )

    assert result["name"] == "Canción"
    assert result["artist"] == "Artista"
    assert result["album"] == "Álbum"
    assert result["duration_ms"] == 189000


def test_fetch_track_falls_back_to_official_embed_when_api_is_limited(monkeypatch):
    monkeypatch.setattr(spotify, "_token_cache", None)

    def request(url, method, headers, payload):
        if url == spotify._SERVER_TIME:
            return {"serverTime": 1_800_000_000}
        if url.startswith(spotify._TOKEN):
            return {
                "accessToken": "temporary-token",
                "accessTokenExpirationTimestampMs": 9_999_999_999_999,
                "isAnonymous": False,
            }
        raise spotify._HTTPError(429, "rate limited")

    entity = {
        "type": "track", "id": "4uLU6hMCjMI75M1A2tKUQC", "name": "Canción",
        "artists": [{"name": "Artista"}], "duration": 189000,
        "visualIdentity": {"image": [{"url": "https://images.example/cover.jpg"}]},
    }
    page = {"props": {"pageProps": {"state": {"data": {"entity": entity}}}}}
    embedded = (
        '<script id="__NEXT_DATA__" type="application/json">'
        + json.dumps(page)
        + "</script>"
    ).encode()
    monkeypatch.setattr(spotify, "_request", lambda *args: embedded)

    result = spotify.fetch_track(
        "4uLU6hMCjMI75M1A2tKUQC", cookie="test-cookie", request_json=request,
    )

    assert result["name"] == "Canción"
    assert result["artist"] == "Artista"
    assert result["image"] == "https://images.example/cover.jpg"
