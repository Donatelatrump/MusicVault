from pathlib import Path
import os
import threading
import time

import pytest

from musicvault.models import Track
from musicvault.services import Catalog, DownloadCancelled, download, fetch_lyrics


def test_catalog_fallback_cache_and_offline(db, monkeypatch):
    calls = []

    def get(url, **params):
        calls.append(url)
        if "deezer" in url:
            raise OSError("offline")
        return {"results": [{"trackId": 7, "trackName": "Song", "artistName": "Artist", "collectionName": "Album",
                            "collectionId": 8, "trackTimeMillis": 90000}]}

    monkeypatch.setattr("musicvault.services.get_json", get)
    catalog = Catalog(db)
    result = catalog.search("Artist")
    assert result["tracks"][0]["id"] == "itunes:7"
    assert result["albums"][0]["id"] == "8"
    count = len(calls)
    assert catalog.search("Artist") == result
    assert len(calls) == count
    with db.connect() as connection:
        connection.execute("UPDATE cache SET saved=0")
    monkeypatch.setattr("musicvault.services.get_json", lambda *a, **kw: (_ for _ in ()).throw(OSError("offline")))
    assert catalog.search("Artist")["notice"].startswith("Sin conexión")


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
print(json.dumps({'entries': [{'id': 'a-b_c', 'title': 'A song', 'channel': 'Test artist', 'duration': 123}]}))
""")
    catalog = Catalog(db)
    result = catalog.search("Test artist", source="youtube")
    assert result["tracks"][0]["source"] == "youtube"
    assert result["tracks"][0]["url"] == "https://www.youtube.com/watch?v=a-b_c"
    assert db.cache_get("search:youtube:test artist") == result
    assert db.cache_get("search:test artist") is None
