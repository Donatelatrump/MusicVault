"""Run with bundled Windows Python against an existing 0.9.7 Wine installation."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
installed = Path(os.environ['LOCALAPPDATA']) / 'Programs/MusicVault'
payload = ROOT / 'build/update'
updater = ROOT / 'dist/MusicVault-Windows-x64-0.9.7-Update1.exe'
assert (installed / 'MusicVault.exe').is_file(), 'Install Windows 0.9.7 first'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


runtime = {str(p): digest(p) for p in (installed / 'runtime').glob('*') if p.is_file()}
runtime[str(installed / 'python/python.exe')] = digest(installed / 'python/python.exe')
data = installed / 'Data'
data.mkdir(exist_ok=True)
sentinel = data / 'update-test.sqlite3'
with sqlite3.connect(sentinel) as db:
    db.execute('CREATE TABLE IF NOT EXISTS preserved(value TEXT)')
    db.execute('INSERT INTO preserved VALUES (?)', ('Library, favorites and settings',))
before = {str(p.relative_to(data)): digest(p) for p in data.rglob('*') if p.is_file()}
original_layout = digest(installed / 'musicvault/layout.py')
child = subprocess.Popen([str(installed / 'python/pythonw.exe'), '-c', 'import time;time.sleep(60)'],
                         creationflags=subprocess.CREATE_NO_WINDOW)
try:
    time.sleep(0.3)
    result = subprocess.run([str(updater), '/S'], timeout=60)
    assert result.returncode != 0, 'Update must refuse an active installation'
    assert original_layout == digest(installed / 'musicvault/layout.py')
finally:
    child.terminate()
    child.wait(timeout=10)
print('PASS: running installation detected; no files changed.', flush=True)
result = subprocess.run([str(updater), '/S'], timeout=90)
assert result.returncode == 0, result.returncode
manifest = json.loads((payload / 'manifest.json').read_text())
for name, expected in manifest['files'].items():
    assert digest(installed / name) == expected, name
assert before == {str(p.relative_to(data)): digest(p) for p in data.rglob('*') if p.is_file()}
assert all(digest(Path(name)) == expected for name, expected in runtime.items())
print('PASS: automatic discovery, patch hashes, Data and installed runtimes preserved.', flush=True)
with tempfile.TemporaryDirectory() as folder:
    folder = Path(folder)
    bad = json.loads((payload / 'manifest.json').read_text())
    bad['files']['musicvault/layout.py'] = '0' * 64
    bad_manifest = folder / 'manifest.json'
    bad_manifest.write_text(json.dumps(bad))
    result = subprocess.run([str(installed / 'python/python.exe'), str(ROOT / 'packaging/apply_update.py'),
                             '--root', str(installed), '--payload', str(payload / 'payload.zip'),
                             '--manifest', str(bad_manifest)], capture_output=True, timeout=30)
    assert result.returncode != 0
    assert digest(installed / 'musicvault/layout.py') == manifest['files']['musicvault/layout.py']
    screenshot = folder / 'updated.png'
    result = subprocess.run([str(installed / 'MusicVault.exe'), '--smoke-test', '--data-dir', str(folder / 'Library'),
                             '--screenshot', str(screenshot)], timeout=60)
    assert result.returncode == 0 and screenshot.stat().st_size > 1000
print('PASS: corrupt payload rejected before modifying installation; updated GUI starts.', flush=True)
