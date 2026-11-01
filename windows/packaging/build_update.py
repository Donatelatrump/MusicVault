"""Build the small Windows update; reuses the installed embedded runtime."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    stage = ROOT / 'dist/MusicVault-Windows-x64-0.9.7'
    output = ROOT / 'build/update'
    output.mkdir(parents=True, exist_ok=True)
    files = [stage / name for name in ('MusicVault.exe', 'launch.py', 'README.md', 'VERIFICATION.md', 'THIRD_PARTY_NOTICES.md')]
    files += [p for p in (stage / 'musicvault').rglob('*')
              if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc']
    files += [p for p in (stage / 'runtime/newpipe').rglob('*') if p.is_file()]
    manifest = {'dependencies': json.loads((stage / 'dependencies.lock.json').read_text()), 'files': {}}
    with zipfile.ZipFile(output / 'payload.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):
            name = path.relative_to(stage).as_posix()
            manifest['files'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
            archive.write(path, name)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    compiler = shutil.which('makensis')
    if not compiler:
        raise RuntimeError('Instala NSIS y agrega makensis al PATH.')
    executable = ROOT / 'dist/MusicVault-Windows-x64-0.9.7-Update1.exe'
    subprocess.run([compiler, '-V2', '-DPAYLOAD_DIR=' + str(output),
                    '-DOUTPUT_FILE=' + str(executable), str(ROOT / 'packaging/updater.nsi')], check=True)
    print(executable, executable.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
