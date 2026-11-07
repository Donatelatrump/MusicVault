import hashlib
import io
import threading
from pathlib import Path
import pytest

from musicvault import updates


def manifest(code=1001, body=b'installer'):
    return {'schemaVersion':1,'packageName':updates.PACKAGE,'versionCode':code,'versionName':'0.10.1',
            'notes':'New version','assets':[{'arch':'x86_64','kind':'installer',
            'url':updates.PREFIX+'windows-v0.10.1/MusicVault-Setup.exe',
            'sha256':hashlib.sha256(body).hexdigest(),'size':len(body)}]}


def test_platform_version_and_download_policy():
    assert updates.parse_manifest(manifest()).versionCode==1001
    assert updates.parse_manifest(manifest(1000)) is None
    android=manifest();android['packageName']='com.musicvault.app'
    assert updates.parse_manifest(android) is None
    for url in ['https://example.test/Setup.exe',updates.PREFIX+'../Setup.exe',
                updates.PREFIX+'tag/%2e%2e/Setup.exe',updates.PREFIX+'tag/Setup.exe?x=1',
                'https://github.com@evil.test/Setup.exe']:
        invalid=manifest();invalid['assets'][0]['url']=url
        assert updates.parse_manifest(invalid) is None
    for payload in [None,[],{},dict(manifest(),assets=['bad']),dict(manifest(),assets={})]:
        assert updates.parse_manifest(payload) is None


def test_checks_stable_windows_manifests_and_chooses_highest(monkeypatch):
    good=manifest();new=manifest(1002)
    def release(tag,payload,**extra):
        return dict({'draft':False,'prerelease':False,'assets':[
            {'name':updates.MANIFEST,'browser_download_url':updates.PREFIX+tag+'/'+updates.MANIFEST},
            {'name':'Setup.exe','browser_download_url':payload['assets'][0]['url'],'size':payload['assets'][0]['size']}]},**extra)
    releases=[{'draft':False,'assets':[{'name':'musicvault-android-update.json'}]},
              release('old',good),release('new',new),release('beta',manifest(9999),prerelease=True)]
    def fetch(url,*args):
        if url.startswith(updates.API):return releases
        return good if '/old/' in url else new
    monkeypatch.setattr(updates,'json_request',fetch)
    assert updates.find_update().versionCode==1002


def test_installer_download_verifies_size_hash_and_reuses_cache(tmp_path,monkeypatch):
    body=b'MZ'+b'installer-content'*100
    release=updates.parse_manifest(manifest(body=body))
    calls=[]
    monkeypatch.setattr(updates,'urlopen',lambda *a,**kw:calls.append(a[0]) or io.BytesIO(body))
    progress=[]
    path=Path(updates.download_installer(release,tmp_path,threading.Event(),progress.append))
    assert path.read_bytes()==body and progress[-1]==100
    assert updates.download_installer(release,tmp_path,threading.Event(),progress.append)==str(path)
    assert len(calls)==1


def test_corrupt_and_cancelled_updates_never_become_executables(tmp_path,monkeypatch):
    release=updates.parse_manifest(manifest(body=b'1234'))
    monkeypatch.setattr(updates,'urlopen',lambda *a,**kw:io.BytesIO(b'xxxx'))
    with pytest.raises(ValueError,match='SHA-256'):
        updates.download_installer(release,tmp_path,threading.Event(),lambda _:None)
    assert list(tmp_path.iterdir())==[]
    cancelled=threading.Event();cancelled.set()
    with pytest.raises(RuntimeError,match='cancelada'):
        updates.download_installer(release,tmp_path,cancelled,lambda _:None)
    assert list(tmp_path.iterdir())==[]


def test_manual_check_ignores_dismissed_prompt_and_auto_check_is_throttled(app,db,monkeypatch):
    from musicvault.services import Jobs
    from PyQt6.QtCore import QObject
    from PyQt6.QtTest import QTest
    class Window(QObject):
        _quitting=False
    window=Window();window.db=db;window.jobs=Jobs(window)
    manager=updates.UpdateManager(window)
    release=updates.parse_manifest(manifest())
    monkeypatch.setattr(updates,'find_update',lambda *a:release)
    prompts=[];monkeypatch.setattr(manager,'offer',lambda:prompts.append(manager.available.versionCode))
    db.set_setting('windows_update_dismissed',1001)
    try:
        manager.check(False);QTest.qWait(100)
        assert prompts==[] and manager.available==release
        manager.check(True);QTest.qWait(100)
        assert prompts==[1001]
        assert db.setting('windows_update_available')['versionCode']==1001
        monkeypatch.delenv('MUSICVAULT_DISABLE_UPDATES',raising=False)
        manager.auto_check();assert not manager.busy
    finally:
        manager.timer.stop();window._quitting=True;window.jobs.close()
