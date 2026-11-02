from pathlib import Path
import os
import threading
import time

import pytest

from musicvault.models import Track
from musicvault.services import Catalog, DownloadCancelled, download, fetch_lyrics


def test_active_metadata_sources_match_android(db, monkeypatch):
    calls = []
    monkeypatch.setattr("musicvault.services.get_json", lambda url, **kw: calls.append(url) or {"data": []})
    catalog = Catalog(db)
    assert catalog.search_tracks("Missing") == []
    assert catalog.search_albums("Missing") == []
    assert calls == ["https://api.deezer.com/search", "https://api.deezer.com/search/album"]


def test_deezer_cover_falls_back_to_large_image():
    from musicvault.services import deezer_track
    track = deezer_track({"id": 1, "album": {"cover_medium": "", "cover_big": "https://example.test/big.jpg"}})
    assert track.cover == "https://example.test/big.jpg"


def test_cover_cache_repairs_corrupt_image_and_upgrades_http(tmp_path, monkeypatch):
    from PyQt6.QtGui import QImage
    from musicvault.models import data_dir, stable_id
    from musicvault.services import cover_file
    image = tmp_path / "cover.png"
    assert QImage(16, 16, QImage.Format.Format_RGB32).save(str(image))
    url = "https://example.test/cover.png"
    cached = data_dir() / "covers" / (stable_id(url) + ".img")
    cached.parent.mkdir(parents=True)
    cached.write_bytes(b"broken cached image")
    calls = []
    monkeypatch.setattr("musicvault.services.get_bytes", lambda source: calls.append(source) or image.read_bytes())
    assert cover_file("http://example.test/cover.png") == str(cached)
    assert not QImage(str(cached)).isNull()
    assert cover_file(url) == str(cached)
    assert calls == [url]


def test_non_image_response_is_not_cached(monkeypatch):
    from musicvault.models import data_dir
    from musicvault.services import cover_file
    monkeypatch.setattr("musicvault.services.get_bytes", lambda source: b"<html>temporarily unavailable</html>")
    with pytest.raises(ValueError, match="imagen"):
        cover_file("https://example.net/cover.jpg")
    assert list((data_dir() / "covers").iterdir()) == []


def test_youtube_cover_uses_stable_thumbnail_when_large_image_fails(tmp_path, monkeypatch):
    from PyQt6.QtGui import QImage
    from musicvault.services import cover_file
    image = tmp_path / "cover.png"
    assert QImage(16, 16, QImage.Format.Format_RGB32).save(str(image))
    calls = []
    def fetch(url):
        calls.append(url)
        if len(calls) == 1:
            raise OSError("Missing high resolution thumbnail")
        return image.read_bytes()
    monkeypatch.setattr("musicvault.services.get_bytes", fetch)
    path = cover_file("https://i.ytimg.com/vi/abc/maxresdefault.jpg")
    assert not QImage(path).isNull()
    assert calls == ["https://i.ytimg.com/vi/abc/maxresdefault.jpg", "https://i.ytimg.com/vi/abc/hqdefault.jpg"]


def test_deezer_cache_and_offline(db, monkeypatch):
    calls = []

    def get(url, **params):
        calls.append(url)
        assert url.startswith("https://api.deezer.com/")
        if "/search/album" in url:
            return {"data": [{"id": 8, "title": "Album", "artist": {"name": "Artist"}}]}
        return {"data": [{"id": 7, "title": "Song", "artist": {"name": "Artist"},
                          "album": {"id": 8, "title": "Album"}, "duration": 90}]}

    monkeypatch.setattr("musicvault.services.get_json", get)
    catalog = Catalog(db)
    result = catalog.search("Artist")
    assert result["tracks"][0]["id"] == "deezer:7"
    assert result["albums"][0]["id"] == "8"
    count = len(calls)
    assert catalog.search("Artist") == result
    assert len(calls) == count
    with db.connect() as connection:
        connection.execute("UPDATE cache SET saved=0")
    monkeypatch.setattr("musicvault.services.get_json", lambda *a, **kw: (_ for _ in ()).throw(OSError("offline")))
    assert catalog.search("Artist")["tracks"] == result["tracks"]
    assert catalog.search("Artist")["albums"] == result["albums"]


def test_lyrics_sidecar_wins_without_network(db, audio_file, monkeypatch):
    path = audio_file()
    path.with_suffix(".lrc").write_text("[00:01.20]Hola\n[00:02]Mundo")
    monkeypatch.setattr("musicvault.services.get_json", lambda *a, **kw: pytest.fail("Unexpected network request"))
    result = fetch_lyrics(Track("local", "Song", path=str(path)), db)
    assert result["synced"] == [(1.2, "Hola"), (2.0, "Mundo")]


class _FakeResponse:
    def __init__(self, body, status=200, headers=None):
        self.body = body
        self.status = status
        self.headers = headers or {}
    def __enter__(self):
        return self
    def __exit__(self, *a):
        return False
    def read(self, n=-1):
        return self.body


def fake_http_download(monkeypatch, body=b"\x00\x01\x02\x03fake_audio", status=200, headers=None):
    """Reemplaza urlopen para devolver audio directo sin yt-dlp."""
    import urllib.request
    def fake_urlopen(request, timeout=30):
        return _FakeResponse(body, status, headers or {"Content-Length": str(len(body))})
    monkeypatch.setattr("musicvault.services.urlopen", fake_urlopen)


def test_download_direct_http_publish(tmp_path, audio_file, monkeypatch):
    from musicvault.streaming import resolve_stream as _real_resolve
    fixture = audio_file("source.wav")
    # Simular que NewPipe resuelve una URL de stream válida
    def fake_resolve(track, cancel=None):
        return "https://audio.example.test/stream"
    monkeypatch.setattr("musicvault.services.resolve_stream", fake_resolve)
    fake_http_download(monkeypatch, body=fixture.read_bytes())
    result = download(Track("remote", "Title; $(touch nope)", "Artist"), tmp_path / "downloads", threading.Event(), lambda value: None)
    assert Path(result.path).is_file()
    assert result.duration == 2
    assert not list((tmp_path / "downloads").glob(".musicvault-*"))
    assert not (tmp_path / "nope").exists()


def test_download_cancel_during_http(tmp_path, monkeypatch):
    def fake_resolve(track, cancel=None):
        return "https://audio.example.test/stream"
    monkeypatch.setattr("musicvault.services.resolve_stream", fake_resolve)

    class _SlowResponse:
        def __enter__(self):
            return self
        def __exit__(self, *a):
            return False
        def read(self, n=-1):
            time.sleep(0.5)
            return b""
    import urllib.request
    monkeypatch.setattr("musicvault.services.urlopen", lambda request, timeout=30: _SlowResponse())

    destination = tmp_path / "downloads"
    destination.mkdir()
    original = destination / "my-song.mp3"
    original.write_bytes(b"original")
    cancel = threading.Event()
    timer = threading.Timer(0.2, cancel.set)
    timer.start()
    try:
        with pytest.raises(DownloadCancelled):
            download(Track("remote", "Title"), destination, cancel, lambda value: None)
    finally:
        timer.cancel()
    assert original.read_bytes() == b"original"
    assert not list(destination.glob(".musicvault-*"))


def test_youtube_metadata_and_source_specific_cache(db, monkeypatch):
    def fake_newpipe_search(query, cancel=None, limit=20):
        from musicvault.models import Track as T
        return [T("youtube:a-b_c", "A song", "Test artist", source="youtube",
                   url="https://www.youtube.com/watch?v=a-b_c", duration=123)]
    monkeypatch.setattr("musicvault.streaming.youtube_search", fake_newpipe_search)
    catalog = Catalog(db)
    result = catalog.search("Test artist", source="youtube")
    assert result["tracks"][0]["source"] == "youtube"
    assert result["tracks"][0]["url"] == "https://www.youtube.com/watch?v=a-b_c"
    assert db.cache_get("search:youtube:test artist") == result
    assert db.cache_get("search:test artist") is None
