"""Run with the bundled python.exe on Windows (or Wine) before distribution."""
import os
from pathlib import Path
import tempfile
import wave

from PyQt6 import QtWebEngineWidgets  # Must precede QApplication.
from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QApplication
from musicvault.portable import configure, relocate_library
from musicvault.database import Database
from musicvault.library import read_track
from musicvault.player import Player
from musicvault.window import Window
from musicvault.media_keys import MediaKeys

configure()
app = QApplication([])
app.setQuitOnLastWindowClosed(False)
with tempfile.TemporaryDirectory(prefix='musicvault-check-') as folder:
    os.environ['MUSICVAULT_DATA_DIR'] = folder
    db = Database()
    relocate_library(db)
    path = Path(folder) / 'test.wav'
    with wave.open(str(path), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(8000)
        audio.writeframes(b'\x00\x01' * 80000)
    player = Player(db, audio_output='null')
    errors = []
    player.error.connect(errors.append)
    window = Window(db, player)
    keys = MediaKeys(app, player)
    window.show()
    assert not window.library_badge.isWindow(), 'Library badge became a separate window'
    assert window.library_badge not in app.topLevelWidgets()
    player.play_queue([read_track(path)])
    result = []
    def finish():
        result.append(player.loaded and player.position > 0)
        screenshot = Path(os.environ.get('MUSICVAULT_CHECK_SCREENSHOT', folder + '/window.png'))
        window.grab().save(str(screenshot))
        keys.close()
        window.quit_app()
        app.quit()
    QTimer.singleShot(3500, finish)
    app.exec()
    player.close()
    assert result == [True], 'Windows libmpv did not advance playback'
    assert not errors, errors
    print('PASS: Windows Qt/WebEngine imports, window render, media key registration, libmpv WAV decoding.', flush=True)

# Exercise the real Windows download path (NewPipe stream + direct HTTP) without network access.
import sys
import threading
from musicvault import services
from musicvault.models import Track
from musicvault.processes import process_options, stop_process
import subprocess
from urllib.request import Request, urlopen

class _FakeResponse:
    def __init__(self, body):
        self.body = body
        self.status = 200
        self.headers = {"Content-Length": str(len(body))}
    def __enter__(self):
        return self
    def __exit__(self, *a):
        return False
    def read(self, n=-1):
        return self.body

with tempfile.TemporaryDirectory(prefix='musicvault-download-check-') as folder:
    os.environ['MUSICVAULT_DATA_DIR'] = folder
    path = Path(folder) / 'test.wav'
    with wave.open(str(path), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(8000)
        audio.writeframes(b'\x00\x01' * 8000)
    def fake_resolve(track, cancel=None):
        return "https://audio.example.test/stream"
    def fake_urlopen(request, timeout=30):
        return _FakeResponse(path.read_bytes())
    services.resolve_stream = fake_resolve
    services.urlopen = fake_urlopen
    try:
        result = services.download(Track('check', 'Check'), Path(folder) / 'downloads', threading.Event(), lambda value: None)
        assert Path(result.path).is_file()
    finally:
        del services.resolve_stream
        del services.urlopen
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], **process_options())
    stop_process(child)
    assert child.wait(timeout=10) != 0
print('PASS: Windows download, audio publication and process cancellation.', flush=True)
