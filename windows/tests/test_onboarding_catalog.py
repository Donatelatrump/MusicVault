from PyQt6.QtTest import QTest
from musicvault.onboarding_catalog import genre_options, OnboardingCatalog
from musicvault.experience_ui import OnboardingDialog


def test_complete_android_taxonomy_and_common_labels():
    genres=genre_options()
    assert len(genres)>2200
    assert genres[0]=='Pop'
    assert 'acid techno' in genres
    assert len(genres)==len({g.casefold() for g in genres})


def test_paging_rotates_genres_and_advances_only_successful_pages(monkeypatch):
    calls=[]
    def fetch(url,**kw):
        calls.append(kw)
        return {'artists':[{'name':f"Artist {kw['offset']}"} for _ in range(20)],'count':40}
    monkeypatch.setattr('musicvault.services.get_json',fetch)
    catalog=OnboardingCatalog({'Rock','Reggaetón'})
    assert catalog.next()[1]
    assert catalog.next()[1]
    assert catalog.next()[1]
    assert catalog.next()[1] is False
    assert [p['offset'] for p in calls]==[0,0,20,20]
    assert calls[0]['query']=='tag:"reggaeton"'
    assert catalog.next()==([],False)
    cached=OnboardingCatalog({'Rock','Reggaetón'})
    cached.next()
    assert len(calls)==4


def test_failed_page_can_retry_without_skipping_artists(monkeypatch):
    import pytest
    calls=[]
    def fetch(url,**kw):
        calls.append(kw['offset'])
        if len(calls)==1:
            raise OSError('offline')
        return {'artists':[{'name':'An artist'}],'count':1}
    monkeypatch.setattr('musicvault.services.get_json',fetch)
    catalog=OnboardingCatalog({'ambient'})
    with pytest.raises(OSError):
        catalog.next()
    assert catalog.next()==(['An artist'],False)
    assert calls==[0,0]


def test_genre_filter_and_remote_pages_preserve_selection(app,monkeypatch):
    monkeypatch.setattr('musicvault.artist_portraits.portrait',lambda *a:'')
    pages=iter([(['New artist A'],True),(['New artist B'],False)])
    monkeypatch.setattr('musicvault.onboarding_catalog.OnboardingCatalog.next',lambda *a:next(pages))
    dialog=OnboardingDialog();dialog.show()
    try:
        dialog.genre_search.setText('acid techno')
        assert dialog.genre_proxy.rowCount()>=1
        index=dialog.genre_proxy.index(0,0)
        name=index.data()
        dialog._genre_clicked(index)
        dialog.genre_search.clear()
        assert name in dialog.selected_genres
        dialog._go_artists();QTest.qWait(150)
        assert 'New artist A' in [dialog.artist_proxy.index(i,0).data() for i in range(dialog.artist_proxy.rowCount())]
        dialog._artist_clicked(dialog.artist_proxy.index(0,0))
        dialog._load_more_artists();QTest.qWait(150)
        assert 'New artist A' in dialog.selected_artists
        assert dialog.artist_proxy.rowCount()==2
        assert not dialog.artist_more.isEnabled()
    finally:
        dialog.reject();dialog.portrait_jobs.close();dialog.catalog_jobs.close()
