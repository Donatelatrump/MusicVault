import json

import pytest

from musicvault.songmirror import import_spotify_playlist, import_spotify_track, load_playlists


def test_loads_songmirror_json_preserving_playlist_order_and_metadata(tmp_path):
    export = {
        "schema_version": 1,
        "playlists": [{
            "id": "spotify:playlist:abc", "name": "Viaje", "cover": "https://images.example/cover.jpg",
            "tracks": [
                {"id": "track-1", "provider": "spotify", "title": "Canción", "artists": [{"name": "Artista"}],
                 "album": {"name": "Álbum", "images": [{"url": "https://images.example/album.jpg"}]}, "duration_ms": 189000},
                {"id": "track-2", "provider": "spotify", "name": "Otra", "artist": "Otra artista", "duration": 200},
            ],
        }],
    }
    path = tmp_path / "songmirror.json"
    path.write_text(json.dumps(export), encoding="utf-8")

    playlists = load_playlists(path)

    assert len(playlists) == 1
    playlist = playlists[0]
    assert playlist.title == "Viaje"
    assert playlist.cover == "https://images.example/cover.jpg"
    assert [track.title for track in playlist.tracks] == ["Canción", "Otra"]
    assert playlist.tracks[0].artist == "Artista"
    assert playlist.tracks[0].album == "Álbum"
    assert playlist.tracks[0].duration == 189
    assert playlist.tracks[0].source == "songmirror"


def test_loads_songmirror_xml(tmp_path):
    path = tmp_path / "songmirror.xml"
    path.write_text("""<?xml version='1.0'?>
    <playlists><playlist id='p1' name='Noche' cover='https://images.example/p.jpg'>
      <track id='t1' provider='spotify'><title>Una</title><artist>Autor</artist><album>Disco</album><duration_ms>120000</duration_ms></track>
    </playlist></playlists>""", encoding="utf-8")

    playlist = load_playlists(path)[0]

    assert playlist.title == "Noche"
    assert playlist.tracks[0].title == "Una"
    assert playlist.tracks[0].artist == "Autor"
    assert playlist.tracks[0].album == "Disco"
    assert playlist.tracks[0].duration == 120


def test_rejects_export_without_tracks(tmp_path):
    path = tmp_path / "empty.json"
    path.write_text(json.dumps({"schema_version": 1, "playlists": [{"name": "Vacía", "tracks": []}]}), encoding="utf-8")
    with pytest.raises(ValueError, match="No se encontraron"):
        load_playlists(path)


def test_imports_spotify_link_through_embedded_connector_and_caches_it():
    cached = {}

    def fetch(playlist_id, progress=None):
        assert playlist_id == "37i9dQZF1DXcBWIGoYBM5M"
        return {
            "name": "Viaje", "count": 2, "image": "https://images.example/list.jpg",
            "external_url": "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "tracks": [
                {"id": "4uLU6hMCjMI75M1A2tKUQC", "name": "Canción", "artist": "Artista",
                 "album": "Álbum", "duration_ms": 189000, "image": "https://images.example/a.jpg",
                 "external_url": "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"},
                {"id": "ignored", "name": "No disponible", "unavailable": True},
            ],
        }

    playlist = import_spotify_playlist(
        "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x",
        lambda key, ttl: cached.get(key), lambda key, value: cached.setdefault(key, value),
        fetch=fetch,
    )

    assert playlist.title == "Viaje"
    assert playlist.total == 2
    assert len(playlist.tracks) == 1
    assert playlist.tracks[0].id == "spotify:4uLU6hMCjMI75M1A2tKUQC"
    assert playlist.tracks[0].duration == 189
    again = import_spotify_playlist(
        playlist.url, cache_get=lambda key, ttl: cached.get(key),
        fetch=lambda *args, **kwargs: pytest.fail("embedded response should be cached"),
    )
    assert again == playlist


def test_imports_one_spotify_track_and_caches_it():
    cached = {}
    url = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"

    def fetch(track_id):
        assert track_id == "4uLU6hMCjMI75M1A2tKUQC"
        return {
            "id": track_id, "name": "Canción", "artist": "Artista", "album": "Álbum",
            "duration_ms": 189000, "image": "https://images.example/cover.jpg", "external_url": url,
        }

    track = import_spotify_track(
        url, lambda key, ttl: cached.get(key), lambda key, value: cached.setdefault(key, value), fetch,
    )

    assert track.id == "spotify:4uLU6hMCjMI75M1A2tKUQC"
    assert track.title == "Canción"
    assert track.artist == "Artista"
    assert track.duration == 189
    assert import_spotify_track(
        url, cache_get=lambda key, ttl: cached.get(key),
        fetch=lambda *_args: pytest.fail("track response should be cached"),
    ) == track
