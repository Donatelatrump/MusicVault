"""Install the pinned Firebase client used by the desktop JAMS bridge."""
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
npm = shutil.which('npm.cmd') or shutil.which('npm')
if not npm:
    raise SystemExit('Instala Node.js 22 o posterior (incluye npm).')
subprocess.run([npm, 'ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'],
               cwd=root / 'musicvault/jams_backend', check=True)
