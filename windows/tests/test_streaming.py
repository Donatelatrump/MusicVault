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

    def run(command, cancel=None, timeout=30):
        calls.append(command)
        if "--get-url" in command:
            return "https://audio.example.test/stream\n"
        if any(str(part).startswith("ytsearch1:") for part in command):
            return "https://www.youtube.com/watch?v=abc123\n"
        return json.dumps({"id": "playlist123", "title": "Prueba", "playlist_count": 2}) + "\n"

    monkeypatch.setattr(streaming, "_run", run)
    monkeypatch.setattr(streaming, "downloader_command", lambda: ["yt-dlp.exe"])
    monkeypatch.setattr(streaming, "youtube_arguments", lambda: [])
    playlist = streaming.youtube_playlist_search("prueba")[0]
    assert playlist["id"] == "playlist123"
    track = Track("deezer:one", "Canción", "Artista", source="deezer")
    service = streaming.StreamingService(db)
    assert service.resolve(track) == "https://audio.example.test/stream"
    count = len(calls)
    assert service.resolve(track) == "https://audio.example.test/stream"
    assert len(calls) == count
    assert "--encoding" in calls[0] and "utf-8" in calls[0]


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
