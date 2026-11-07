"""Exercise the actual installer in an isolated folder without user integration."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import winreg
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from musicvault import __version__

stage=ROOT/f'dist/MusicVault-Windows-x64-{__version__}'
installer=ROOT/f'dist/MusicVault-Windows-x64-{__version__}-Setup.exe'
archive=ROOT/f'dist/MusicVault-Windows-x64-{__version__}.zip'
target=(ROOT/'build/installed release check/MusicVault').resolve()
assert target.is_relative_to((ROOT/'build').resolve())
target.mkdir(parents=True,exist_ok=True)
os.environ['MUSICVAULT_DATA_DIR']=str(target/'Data')
os.environ['MUSICVAULT_DISABLE_UPDATES']='1'
from musicvault.database import Database
from musicvault.models import Track


def registry_snapshot():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,r'Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault') as key:
            return dict(winreg.EnumValue(key,i)[:2] for i in range(winreg.QueryInfoKey(key)[1]))
    except FileNotFoundError:return None


def install():
    # NSIS requires its last /D= value without command-line quoting around it.
    return subprocess.run(f'"{installer}" /S /NOINTEGRATION /D={target}',timeout=240).returncode


def audit_archive():
    with zipfile.ZipFile(archive) as bundle:
        names=bundle.namelist()
        assert not any(Path(name).parts[1:2]==('Data',) or '/musicvault/jams_backend/node_modules/' in name or name.endswith('identity.json') for name in names)
        assert bundle.testzip() is None,'Corrupt portable ZIP'
        required=['python/python.exe','runtime/libmpv-2.dll','runtime/ffmpeg.exe','runtime/ffprobe.exe',
                  'runtime/node.exe','runtime/yt-dlp.exe','runtime/newpipe/jre/bin/java.exe',
                  'musicvault/jams_backend/bridge.bundle.mjs','musicvault/updates.py']
        for path in required:assert stage.name+'/'+path in names,path
    print('PASS: portable ZIP CRC, required dependencies and exclusion of private data',flush=True)


if __name__=='__main__':
    if '--archive-only' not in sys.argv:
        db=Database(target/'Data/musicvault.sqlite3')
        db.set_setting('taste_onboarding_complete',True);db.set_setting('auto_scan',False)
        if 'release-test:favorite' not in db.favorites():
            db.toggle_favorite(Track('release-test:favorite','Preserved favorite','Test artist',source='youtube'))
        marker=target/'Data/preserve-library.txt';marker.write_text('Preserve library and preferences',encoding='utf-8')
        before={p.relative_to(target):hashlib.sha256(p.read_bytes()).hexdigest() for p in (target/'Data').rglob('*') if p.is_file()}
        registry=registry_snapshot()
        assert install()==0,'Initial installer failed'
        for path,digest in before.items():assert hashlib.sha256((target/path).read_bytes()).hexdigest()==digest,path
        assert registry_snapshot()==registry,'Installer changed user registration in isolated mode'
        assert not (target/'Uninstall.exe').exists(),'Unexpected user integration'
        environment=dict(os.environ,PATH=str(target/'runtime')+os.pathsep+str(Path(os.environ['SystemRoot'])/'System32'),QT_QPA_PLATFORM='offscreen')
        doctor=subprocess.run([str(target/'MusicVault.exe'),'--doctor'],capture_output=True,text=True,timeout=60,env=environment)
        assert doctor.returncode==0,(doctor.stdout,doctor.stderr)
        checks=json.loads(doctor.stdout)
        assert all(checks.values()),checks
        assert str(target/'runtime/node.exe').lower() in checks['jams_node'].lower()
        assert not (target/'musicvault/jams_backend/node_modules').exists()
        print('PASS: installer includes all dependencies; runs with only packaged binaries and Windows system PATH',flush=True)
        screen=ROOT/'build/installed-release.png'
        smoke=subprocess.run([str(target/'MusicVault.exe'),'--smoke-test','--screenshot',str(screen)],timeout=60,env=environment)
        assert smoke.returncode==0 and screen.stat().st_size>1000,'Installed GUI smoke failed'
        busy=subprocess.Popen([str(target/'python/python.exe'),'-c','import time;time.sleep(60)'],env=environment)
        try:assert install()==2,'Installer overwrote a running installation'
        finally:busy.terminate();busy.wait(timeout=10)
        assert install()==0,'In-place update failed'
        assert marker.read_text(encoding='utf-8')=='Preserve library and preferences'
        with sqlite3.connect(target/'Data/musicvault.sqlite3') as connection:
            assert connection.execute("SELECT COUNT(*) FROM favorites WHERE track_id='release-test:favorite'").fetchone()[0]==1
        assert registry_snapshot()==registry
        print('PASS: running installation is protected; in-place upgrade preserves SQLite favorites, settings and Data',flush=True)
    audit_archive()
