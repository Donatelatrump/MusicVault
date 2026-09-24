"""Exercise the native launcher under Wine from an unrelated working directory."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / 'dist/MusicVault-Windows-x64-0.9.7/MusicVault.exe'
WINE = '/opt/wine-staging/bin/wine'
environment = dict(os.environ, WINEPREFIX='/tmp/musicvault-windows-wine', WINEDEBUG='-all')

with tempfile.TemporaryDirectory(prefix='musicvault-launcher-') as directory:
    def run(*args):
        result = subprocess.run([WINE, str(EXE), *args], cwd=directory, env=environment,
                                capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        return result.stdout
    assert '0.8.0' in run('--version')
    checks = json.loads(run('--doctor'))
    assert all(checks.values()), checks
    data = 'Z:' + directory.replace('/', '\\') + '\\Biblioteca con acentos á'
    screenshot = 'Z:' + directory.replace('/', '\\') + '\\ventana.png'
    run('--smoke-test', '--data-dir', data, '--screenshot', screenshot)
    assert (Path(directory) / 'ventana.png').stat().st_size > 1000
    assert (Path(directory) / 'Biblioteca con acentos á/musicvault.sqlite3').exists()
    print('PASS: native EXE version, dependency diagnosis, GUI startup, arguments with spaces and accents, independent working directory.')
