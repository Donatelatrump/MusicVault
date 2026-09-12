import json
import subprocess
import sys

from musicvault.database import Database
from musicvault.models import Track
from musicvault.portable import relocate_library
from musicvault.processes import PipeSelector


def test_relocation_preserves_external_files_and_updates_all_managed_paths(tmp_path, monkeypatch):
    root = tmp_path / 'USB nueva' / 'Data'
    monkeypatch.setenv('MUSICVAULT_DATA_DIR', str(root))
    db = Database()
    db.set_setting('portable_data_root', 'E:\\MusicVault\\Data')
    db.set_setting('download_folder', 'E:\\MusicVault\\Data\\downloads')
    db.save_track(Track('inside', 'Portable', path='E:\\MusicVault\\Data\\downloads\\tema.mp3'))
    db.save_track(Track('outside', 'External', path='C:\\Music\\tema.mp3'))
    relocate_library(db)
    assert db.get_track('inside').path == str(root / 'downloads/tema.mp3')
    assert db.get_track('outside').path == 'C:\\Music\\tema.mp3'
    assert db.setting('download_folder') == str(root / 'downloads')
    relocate_library(db)
    assert db.get_track('inside').path == str(root / 'downloads/tema.mp3')


def test_pipe_reader_drains_output_and_eof():
    process = subprocess.Popen([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"x" * 100000)'], stdout=subprocess.PIPE)
    reader = PipeSelector()
    reader.register(process.stdout, 1)
    chunks = []
    while reader.select(5):
        chunk = reader.read()
        if not chunk:
            break
        chunks.append(chunk)
    assert b''.join(chunks) == b'x' * 100000
    assert process.wait(timeout=5) == 0
    process.stdout.close()
