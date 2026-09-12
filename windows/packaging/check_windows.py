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

# Exercise the real Windows pipe reader/publication path without network access.
import sys
import threading
from musicvault import services
from musicvault.models import Track
from musicvault.processes import process_options, stop_process
import subprocess

with tempfile.TemporaryDirectory(prefix='musicvault-download-check-') as folder:
    os.environ['MUSICVAULT_DATA_DIR'] = folder
    script = Path(folder) / 'fake_downloader.py'
    script.write_text('''import sys, wave
from pathlib import Path
path = Path(sys.argv[sys.argv.index('--output') + 1].replace('%(ext)s', 'wav'))
with wave.open(str(path), 'wb') as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(8000)
    out.writeframes(b'\\x00\\x01' * 8000)
print('MV_PROGRESS:100%')
print('MV_FILE:' + str(path))
''')
    original = services.downloader_command
    services.downloader_command = lambda: [sys.executable, str(script)]
    try:
        result = services.download(Track('check', 'Check'), Path(folder) / 'downloads', threading.Event(), lambda value: None)
        assert Path(result.path).is_file()
    finally:
        services.downloader_command = original
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], **process_options())
    stop_process(child)
    assert child.wait(timeout=10) != 0
print('PASS: Windows download pipes, audio publication and process cancellation.', flush=True)
