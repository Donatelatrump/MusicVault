"""Windows stream resolution, cancellation, and cache promotion."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import threading

import pytest
from PyQt6.QtTest import QTest

from musicvault.database import Database
from musicvault.models import Track
from musicvault import streaming
from musicvault.player import Player
from musicvault.window import Window


def test_youtube_playlists_and_stream_url_use_cached_resolution(tmp_path, monkeypatch):
    db = Database(tmp_path / "library.sqlite3")
    calls = []

    def newpipe(mode, query, cancel=None, limit=25):
        calls.append(mode)
        if mode == 'search':
            return [{"url": "https://www.youtube.com/watch?v=abc123"}]
        if mode == 'playlists':
            return [{"id": "playlist123", "title": "Prueba", "playlist_count": 2}]
        if mode == 'playlist':
            return [{"id": "v1", "title": "Song 1", "uploader": "Artist", "duration": 120}]
        if mode == 'resolve':
            return {"url": "https://audio.example.test/stream"}
        return []

    monkeypatch.setattr(streaming, "_newpipe", newpipe)
    playlist = streaming.youtube_playlist_search("prueba")[0]
    assert playlist["id"] == "playlist123"
    track = Track("deezer:one", "Canción", "Artista", source="deezer")
    service = streaming.StreamingService(db)
    assert service.resolve(track) == "https://audio.example.test/stream"
    count = len(calls)
    assert service.resolve(track) == "https://audio.example.test/stream"
    assert len(calls) == count


def test_stream_subprocess_cancels_on_windows():
    cancel = threading.Event()
    timer = threading.Timer(0.2, cancel.set)
    timer.start()
    try:
        with pytest.raises(streaming.StreamCancelled):
            streaming._run([sys.executable, "-c", "import time; time.sleep(30)"], cancel, timeout=5)
    finally:
        timer.cancel()


def test_complete_stream_cache_can_be_promoted_without_redownload(tmp_path):
    db = Database(tmp_path / "library.sqlite3")
    track = Track("youtube:cached", "Canción", "Artista", source="youtube")
    source = tmp_path / "audio.mp3"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                    "sine=frequency=440:duration=2", "-ac", "2", "-b:a", "320k", str(source)], check=True)
    service = streaming.StreamingService(db)
    partial = Path(service.record_path(track))
    shutil.copyfile(source, partial)
    assert service.mark_cached(track, partial)
    result = service.promote_cached(track, tmp_path / "downloads")
    assert result is not None and Path(result.path).is_file()
    assert Path(result.path).suffix == ".mka"


def test_remote_play_starts_stream_without_queuing_download(app, db, monkeypatch):
    monkeypatch.setattr(Window, "start_taste_experience", lambda self: None)
    db.set_setting("auto_scan", False)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    played = []
    monkeypatch.setattr(window.streaming, "resolve", lambda track, cancel: "https://audio.example.test/stream")
    monkeypatch.setattr(player, "play_resolved", lambda track, source, **kwargs: played.append((track.id, source)))
    try:
        track = Track("youtube:abc", "Canción", source="youtube")
        player.play_queue([track])
        for _ in range(50):
            if played:
                break
            QTest.qWait(20)
        assert played == [(track.id, "https://audio.example.test/stream")]
        assert not window.download_jobs
        assert window.search_playlists_tab.text() == "Playlists"
    finally:
        window.quit_app()


def test_newpipe_search_returns_tracks(monkeypatch):
    from musicvault import streaming
    calls = []
    def newpipe(mode, query, cancel=None, limit=25):
        calls.append(mode)
        return [{"id": "video", "title": "Song", "uploader": "Artist",
                 "thumbnail": "https://i.ytimg.com/vi/video/hqdefault.jpg"}]
    monkeypatch.setattr(streaming, "_newpipe", newpipe)
    result = streaming.youtube_search("Song")
    assert calls == ["search"]
    assert result[0].id == "youtube:video" and result[0].cover


def test_newpipe_resolves_stream_directly(db, monkeypatch):
    from musicvault import streaming
    from musicvault.models import Track
    monkeypatch.setattr(streaming, "_newpipe", lambda *a, **k: {"url": "https://audio.example.test/direct"})
    service = streaming.StreamingService(db)
    assert service.resolve(Track("youtube:v", "Song", source="youtube", source_id="v")) == "https://audio.example.test/direct"
