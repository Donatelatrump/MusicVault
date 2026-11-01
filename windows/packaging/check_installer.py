"""Install, launch and uninstall the Windows package under Wine."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
environment = dict(os.environ, WINEPREFIX=os.environ.get(
    'WINEPREFIX', str(ROOT / 'build/wine-home/prefix')), WINEDEBUG='-all')
wine = '/opt/wine-staging/bin/wine'


def windows_path(path):
    drive = Path(environment['WINEPREFIX']) / 'drive_c'
    if path.resolve().is_relative_to(drive.resolve()):
        return 'C:\\' + str(path.resolve().relative_to(drive.resolve())).replace('/', '\\')
    return 'Z:' + str(path.resolve()).replace('/', '\\')


def run(executable, *arguments):
    result = subprocess.run([wine, str(executable), *arguments], env=environment,
                            capture_output=True, text=True, timeout=180)
    assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
    return result.stdout


with tempfile.TemporaryDirectory(prefix='MVCheck-', dir=Path(environment['WINEPREFIX']) / 'drive_c') as folder:
    base = Path(folder)
    # NSIS /D= must be unquoted; Wine quotes an argument containing spaces.
    installed = base / 'MusicVault'
    run(ROOT / 'dist/MusicVault-Windows-x64-0.9.7-Setup.exe',
        '/S', '/D=' + windows_path(installed))
    stage = ROOT / 'dist/MusicVault-Windows-x64-0.9.7'
    for source in stage.rglob('*'):
        relative = source.relative_to(stage)
        if not source.is_file() or 'Data' in relative.parts or '__pycache__' in relative.parts or source.suffix == '.pyc':
            continue
        target = installed / relative
        assert target.is_file(), relative
        assert hashlib.sha256(source.read_bytes()).digest() == hashlib.sha256(target.read_bytes()).digest(), relative
    assert '0.9.7' in run(installed / 'MusicVault.exe', '--version')
    assert all(json.loads(run(installed / 'MusicVault.exe', '--doctor')).values())
    screenshot = base / 'installed.png'
    run(installed / 'MusicVault.exe', '--smoke-test', '--screenshot', windows_path(screenshot))
    assert screenshot.stat().st_size > 1000
    data = installed / 'Data'
    data.mkdir(exist_ok=True)
    sentinel = data / 'keep-user-data.txt'
    sentinel.write_text('User library must survive uninstall')
    run(installed / 'Uninstall.exe', '/S')
    deadline = time.monotonic() + 30
    while (installed / 'MusicVault.exe').exists() and time.monotonic() < deadline:
        time.sleep(0.2)
    assert not (installed / 'MusicVault.exe').exists()
    assert not (installed / 'python').exists()
    assert sentinel.read_text() == 'User library must survive uninstall'
    print('PASS: installer payload hashes, installed EXE, dependencies, GUI, uninstall and preserved user data.')
