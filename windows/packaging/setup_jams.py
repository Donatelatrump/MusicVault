"""Install the pinned Firebase client used by the desktop JAMS bridge."""
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
npm = shutil.which('npm.cmd') or shutil.which('npm')
if not npm:
    raise SystemExit('Instala Node.js 22 o posterior (incluye npm).')
backend = root / 'musicvault/jams_backend'
subprocess.run([npm, 'ci', '--include=dev', '--ignore-scripts', '--no-audit', '--no-fund'],
               cwd=root / 'musicvault/jams_backend', check=True)
node = shutil.which('node')
if not node:
    raise SystemExit('No se encontró Node.js.')
subprocess.run([node, str(backend / 'node_modules/esbuild/bin/esbuild'),
                str(backend / 'bridge.mjs'), '--bundle', '--platform=node', '--format=esm',
                '--target=node22', '--legal-comments=inline',
                '--banner:js=import {createRequire as __mvCreateRequire} from "node:module";const require=__mvCreateRequire(import.meta.url);',
                '--outfile=' + str(backend / 'bridge.bundle.mjs')], check=True)
