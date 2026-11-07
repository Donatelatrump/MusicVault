import json
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QImage
from PyQt6.QtTest import QTest
from musicvault.artist_portraits import portrait, select_artist
from musicvault.experience_ui import OnboardingDialog


def test_artist_identity_uses_exact_name_and_most_popular_match():
    rows = [{'name':'Carin Leon Tribute','nb_fan':999999},
            {'name':'Carín León','nb_fan':10}, {'name':'Carin Leon','nb_fan':100}]
    assert select_artist(rows,'Carín León') is rows[2]
    assert select_artist(rows,'Other') is None


def test_artist_portrait_caches_real_photo_for_offline_reuse(tmp_path, monkeypatch):
    image = tmp_path/'portrait.png'
    assert QImage(500,500,QImage.Format.Format_RGB32).save(str(image))
    calls=[]
    def fetch(url, **kw):
        calls.append(url)
        if 'search/artist' in url:
            return json.dumps({'data':[{'name':'BTS','nb_fan':100,'picture_big':'https://example.test/bts.png'}]}).encode()
        return image.read_bytes()
    monkeypatch.setattr('musicvault.services.get_bytes',fetch)
    path=portrait('BTS')
    assert not QImage(path).isNull()
    monkeypatch.setattr('musicvault.services.get_bytes',lambda *a,**k: (_ for _ in ()).throw(AssertionError('Network on cache hit')))
    assert portrait('BTS')==path
    assert len(calls)==2


def test_portrait_grid_loads_without_losing_selection_on_filter(app,tmp_path,monkeypatch):
    monkeypatch.setattr('musicvault.onboarding_catalog.OnboardingCatalog.next', lambda *a: ([],False))
    image=tmp_path/'portrait.png'
    assert QImage(500,500,QImage.Format.Format_RGB32).save(str(image))
    monkeypatch.setattr('musicvault.artist_portraits.portrait',lambda name,cancel=None:str(image))
    dialog=OnboardingDialog()
    dialog.selected_genres={'Pop'}
    dialog.show();dialog._go_artists()
    try:
        QTest.qWait(300)
        assert dialog._portrait_icons
        first=dialog.artist_proxy.index(0,0)
        name=first.data()
        QTest.mouseClick(dialog.artist_list.viewport(),Qt.MouseButton.LeftButton,pos=dialog.artist_list.visualRect(first).center())
        assert name in dialog.selected_artists
        dialog.artist_search.setText('no such artist');QTest.qWait(50)
        assert name in dialog.selected_artists
        dialog.artist_search.clear();QTest.qWait(50)
        assert dialog.artist_proxy.index(0,0).data(Qt.ItemDataRole.CheckStateRole)==Qt.CheckState.Checked.value
        dialog._refill_artists()
        assert name in dialog.selected_artists
    finally:
        dialog.reject();dialog.portrait_jobs.close();dialog.catalog_jobs.close()
