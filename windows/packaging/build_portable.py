#!/usr/bin/env python3
"""Assemble Windows x64 binaries without cross-compiling; runs on Linux or Windows."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'build/downloads'
STAGE = ROOT / 'dist/MusicVault-Windows-x64-0.9.7'
SOURCES = {
 'python.zip': ('https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip', None),
 'mpv.7z': ('https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260903/mpv-dev-x86_64-20260903-git-69e63f425a.7z', 'fac135c68a35b7639e39d72c0c365104edbaebdea39a0dfdd8c36e8c8e80faef'),
 'node.zip': ('https://nodejs.org/dist/v22.16.0/node-v22.16.0-win-x64.zip', None),
 'ffmpeg.zip': ('https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip', None),
}


def fetch(name, url, digest):
    path = CACHE / name
    if not path.exists():
        print('Downloading', url, flush=True)
        temporary = path.with_suffix(path.suffix + '.partial')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        temporary.replace(path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest and actual != digest:
        raise RuntimeError(f'SHA256 mismatch: {name}')
    return path, actual


def main():
    if not (ROOT / 'MusicVault.exe').is_file():
        raise RuntimeError('Run python packaging/build_launcher.py with MinGW-w64 first.')
    CACHE.mkdir(parents=True, exist_ok=True)
    lock_path = ROOT / 'packaging/dependencies.lock.json'
    locked = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    manifest = {}
    for name, (url, digest) in SOURCES.items():
        if name in locked:
            url, digest = locked[name]['url'], locked[name]['sha256']
        _, actual = fetch(name, url, digest)
        manifest[name] = {'url': url, 'sha256': actual}
    wheels = CACHE / 'wheels'
    subprocess.run([sys.executable, '-m', 'pip', 'download', '--only-binary=:all:',
                    '--platform', 'win_amd64', '--python-version', '312', '--implementation', 'cp',
                    '--abi', 'cp312', '--find-links', str(wheels), '--dest', str(wheels), '-r', str(ROOT / 'requirements.txt')], check=True)
    STAGE.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'musicvault', STAGE / 'musicvault', dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'yt-dlp', 'mpris.py'))
    for name in ('MusicVault.exe', 'MusicVault.cmd', 'Diagnostico.cmd', 'launch.py', 'README.md', 'VERIFICATION.md', 'THIRD_PARTY_NOTICES.md'):
        shutil.copy2(ROOT / name, STAGE / name)
    runtime, python = STAGE / 'runtime', STAGE / 'python'
    runtime.mkdir(exist_ok=True)
    python.mkdir(exist_ok=True)
    with zipfile.ZipFile(CACHE / 'python.zip') as archive:
        archive.extractall(python)
    (python / 'python312._pth').write_text('python312.zip\n.\nLib/site-packages\n..\nimport site\n')
    for wheel in wheels.glob('*.whl'):
        actual = hashlib.sha256(wheel.read_bytes()).hexdigest()
        if wheel.name in locked and actual != locked[wheel.name]['sha256']:
            raise RuntimeError(f'SHA256 mismatch: {wheel.name}')
        manifest[wheel.name] = {'sha256': actual, 'url': 'https://pypi.org/'}
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(python / 'Lib/site-packages')
    for name in ('node.zip', 'ffmpeg.zip'):
        with zipfile.ZipFile(CACHE / name) as archive:
            for item in archive.infolist():
                if Path(item.filename).name in ('node.exe', 'ffmpeg.exe', 'ffprobe.exe'):
                    with archive.open(item) as source, (runtime / Path(item.filename).name).open('wb') as output:
                        shutil.copyfileobj(source, output)
                elif not item.is_dir() and any(word in item.filename.lower() for word in ('license', 'copying', 'readme')):
                    dest = STAGE / 'licenses' / name / item.filename
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(archive.read(item))
    mpv = ROOT / 'build/mpv'
    seven = shutil.which('7z') or shutil.which('7zz') or shutil.which('7zr')
    if not seven:
        raise RuntimeError('Install 7-Zip or the standalone 7zr extractor on the build machine.')
    subprocess.run([seven, 'x', '-y', str(CACHE / 'mpv.7z'), '-o' + str(mpv)], check=True, stdout=subprocess.DEVNULL)
    for dll in mpv.rglob('*.dll'):
        shutil.copy2(dll, runtime / dll.name)
    for required in ('libmpv-2.dll', 'node.exe', 'ffmpeg.exe', 'ffprobe.exe'):
        if not (runtime / required).is_file():
            raise RuntimeError('Missing runtime: ' + required)
    subprocess.run([sys.executable, str(ROOT / 'packaging/build_newpipe.py')], check=True)
    lock_path.write_text(json.dumps(manifest, indent=2) + '\n')
    (STAGE / 'dependencies.lock.json').write_text(lock_path.read_text())
    archive = Path(str(STAGE) + '.zip')
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in STAGE.rglob('*'):
            relative = path.relative_to(STAGE)
            if 'Data' in relative.parts[:1] or '__pycache__' in relative.parts or path.suffix == '.pyc':
                continue
            if path.is_file():
                bundle.write(path, Path(STAGE.name) / relative)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    Path(str(archive) + '.sha256').write_text(digest + '  ' + archive.name + '\n')
    print('Portable:', STAGE, '\nZIP:', archive, flush=True)


if __name__ == '__main__':
    main()
