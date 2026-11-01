"""Package Android's pinned NewPipeExtractor with a private Windows Java runtime."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    lock_path = ROOT / 'packaging/newpipe-dependencies.lock.json'
    lock = json.loads(lock_path.read_text())
    cache = ROOT / 'build/downloads'
    cache.mkdir(parents=True, exist_ok=True)
    bundle = ROOT / 'build/newpipe'
    libraries = bundle / 'lib'
    libraries.mkdir(parents=True, exist_ok=True)
    for item in [lock['java'], *lock['libraries']]:
        path = cache / item['name']
        if not path.exists() and item.get('gradle_path'):
            for candidate in (Path.home() / '.gradle/caches/modules-2/files-2.1' / item['gradle_path']).glob('*/*.jar'):
                if hashlib.sha256(candidate.read_bytes()).hexdigest() == item['sha256']:
                    shutil.copy2(candidate, path)
                    break
        if not path.exists():
            temporary = path.with_suffix('.partial')
            with urllib.request.urlopen(item['url'], timeout=120) as source, temporary.open('wb') as target:
                shutil.copyfileobj(source, target)
            temporary.replace(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise RuntimeError('SHA256 mismatch: ' + path.name)
        if item in lock['libraries']:
            shutil.copy2(path, libraries / path.name)
    java = bundle / 'jre'
    with zipfile.ZipFile(cache / lock['java']['name']) as archive:
        for member in archive.infolist():
            relative = Path(*Path(member.filename).parts[1:])
            if member.is_dir() or not relative.parts:
                continue
            target = java / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target.open('wb') as dest:
                shutil.copyfileobj(source, dest)
    classes = ROOT / 'build/newpipe-classes'
    classes.mkdir(parents=True, exist_ok=True)
    classpath = str(libraries / '*')
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath,
                    '-d', str(classes), str(ROOT / 'packaging/newpipe/MusicVaultNewPipe.java')], check=True)
    subprocess.run(['jar', '--create', '--file', str(bundle / 'bridge.jar'), '-C', str(classes), '.'], check=True)
    shutil.copy2(lock_path, bundle / lock_path.name)
    (bundle / 'source').mkdir(exist_ok=True)
    shutil.copy2(ROOT / 'packaging/newpipe/MusicVaultNewPipe.java', bundle / 'source/MusicVaultNewPipe.java')
    stage = ROOT / 'dist/MusicVault-Windows-x64-0.9.7/runtime/newpipe'
    shutil.copytree(bundle, stage, dirs_exist_ok=True)
    print('NewPipeExtractor', lock['extractor_version'], 'packaged at', stage)


if __name__ == '__main__':
    main()
