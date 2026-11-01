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
        cover_file("https://example.test/cover.jpg")
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


def fake_downloader(tmp_path, monkeypatch, script):
    binary = tmp_path / "yt-dlp"
    binary.write_text("#!/usr/bin/python3\n" + script)
    binary.chmod(0o755)
    monkeypatch.setenv("MUSICVAULT_YTDLP", str(binary))
    monkeypatch.setattr("musicvault.services.youtube_arguments", lambda: ["--js-runtimes", "node:/test/node"])
    real_which = __import__("shutil").which
    monkeypatch.setattr("musicvault.services.shutil.which", lambda name: str(binary) if name == "yt-dlp" else real_which(name))


def test_download_atomic_publish_arguments_and_validation(tmp_path, audio_file, monkeypatch):
    fixture = audio_file("source.wav")
    fake_downloader(tmp_path, monkeypatch, f"""import sys, pathlib, shutil
target = pathlib.Path(sys.argv[sys.argv.index('--output') + 1].replace('%(ext)s', 'wav'))
assert '--ignore-config' in sys.argv
assert sys.argv[-2] == '--'
shutil.copyfile({str(fixture)!r}, target)
print('MV_PROGRESS: 50.0%', flush=True)
print('MV_FILE:' + str(target), flush=True)
""")
    # The mock emits WAV to exercise validation, without network or external content.
    result = download(Track("remote", "Title; $(touch nope)", "Artist"), tmp_path / "downloads", threading.Event(), lambda value: None)
    assert Path(result.path).is_file()
    assert result.duration == 2
    assert not list((tmp_path / "downloads").glob(".musicvault-*"))
    assert not (tmp_path / "nope").exists()


def test_download_cancel_reaps_process_and_preserves_other_files(tmp_path, monkeypatch):
    pidfile = tmp_path / "pid"
    fake_downloader(tmp_path, monkeypatch, f"""import os, pathlib, time
pathlib.Path({str(pidfile)!r}).write_text(str(os.getpid()))
time.sleep(60)
""")
    destination = tmp_path / "downloads"
    destination.mkdir()
    original = destination / "my-song.mp3"
    original.write_bytes(b"original")
    cancel = threading.Event()
    timer = threading.Timer(0.4, cancel.set)
    timer.start()
    try:
        with pytest.raises(DownloadCancelled):
            download(Track("remote", "Title"), destination, cancel, lambda value: None)
    finally:
        timer.cancel()
    assert original.read_bytes() == b"original"
    assert not list(destination.glob(".musicvault-*"))
    if pidfile.exists():
        with pytest.raises(ProcessLookupError):
            os.kill(int(pidfile.read_text()), 0)


def test_youtube_metadata_and_source_specific_cache(db, tmp_path, monkeypatch):
    fake_downloader(tmp_path, monkeypatch, """import json, sys
assert '--flat-playlist' in sys.argv
assert sys.argv[-1] == 'ytsearch20:Test artist'
print(json.dumps({'id': 'a-b_c', 'title': 'A song', 'channel': 'Test artist', 'duration': 123}))
""")
    catalog = Catalog(db)
    result = catalog.search("Test artist", source="youtube")
    assert result["tracks"][0]["source"] == "youtube"
    assert result["tracks"][0]["url"] == "https://www.youtube.com/watch?v=a-b_c"
    assert db.cache_get("search:youtube:test artist") == result
    assert db.cache_get("search:test artist") is None
