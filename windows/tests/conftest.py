import os
from pathlib import Path
import struct
import wave

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PyQt6.QtWidgets import QApplication

from musicvault.database import Database


@pytest.fixture(scope="session")
def app():
    instance = QApplication.instance() or QApplication([])
    instance.setQuitOnLastWindowClosed(False)
    yield instance


@pytest.fixture
def db(tmp_path):
    return Database(tmp_path / "data/library.sqlite3")


@pytest.fixture(autouse=True)
def isolated_data(tmp_path, monkeypatch):
    monkeypatch.setenv("MUSICVAULT_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("MUSICVAULT_DISABLE_UPDATES", "1")


@pytest.fixture
def audio_file(tmp_path):
    def create(name="sample.wav", duration=2):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(8000)
            audio.writeframes(struct.pack("<h", 1000) * int(duration * 8000))
        return path
    return create
