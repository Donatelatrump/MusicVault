from PyQt6.QtCore import Qt
from PyQt6.QtTest import QTest
from musicvault.artist_profile import ArtistProfileService
from musicvault.models import Track


def test_artist_profile_uses_exact_identity_and_filters_tracks(db,monkeypatch):
    calls=[]
    def fetch(url,**params):
        calls.append((url,params))
        if 'search/artist' in url:
            return {'data':[{'id':1,'name':'Artist Tribute','nb_fan':900}, {'id':2,'name':'Artist','nb_fan':100}]}
        return {'data':[{'id':11,'title':'Correct','artist':{'id':2,'name':'Artist'}},
                        {'id':12,'title':'Wrong artist','artist':{'id':1,'name':'Artist Tribute'}}]}
    monkeypatch.setattr('musicvault.artist_profile.get_json',fetch)
    service=ArtistProfileService(db)
    artist=service.artists('Artist')[0]
    assert artist['id']==2
    assert [t.title for t in service.tracks(artist,'song')]==['Correct']
    assert service.artists('Artist')[0]['id']==2
    assert len(calls)==2


def test_discography_pagination_and_artist_identity(db,monkeypatch):
    calls=[]
    def fetch(url,**params):
        calls.append(params['index'])
        return {'data':[{'id':params['index']+1,'title':'Album','release_date':'2020-01-01','cover_xl':'https://example.test/art.jpg'}], 'total':2}
    monkeypatch.setattr('musicvault.artist_profile.get_json',fetch)
    service=ArtistProfileService(db)
    artist={'id':2,'name':'Artist'}
    page,more,offset=service.albums(artist)
    assert page[0]['artist']=='Artist'
    assert page[0]['year']=='2020'
    assert more and offset==1
    _,more,offset=service.albums(artist,offset)
    assert not more and offset==2
    assert calls==[0,1]


def test_artist_name_opens_profile_and_album_can_play(app,db,monkeypatch):
    from musicvault.window import Window
    from musicvault.player import Player
    db.set_setting('taste_onboarding_complete',True);db.set_setting('auto_scan',False)
    track=Track('deezer:11','Song','Artist',source='deezer')
    artist={'id':2,'name':'Artist','nb_fan':100}
    album={'id':'3','title':'Album','artist':'Artist','source':'deezer','cover':'','year':'2020'}
    monkeypatch.setattr('musicvault.artist_profile.ArtistProfileService.artists',lambda *a:[artist])
    monkeypatch.setattr('musicvault.artist_profile.ArtistProfileService.tracks',lambda *a:[track])
    monkeypatch.setattr('musicvault.artist_profile.ArtistProfileService.albums',lambda *a:([album],False,1))
    monkeypatch.setattr('musicvault.artist_profile.portrait',lambda *a:'')
    player=Player(db,audio_output='null');window=Window(db,player)
    player.queue=[track];player.index=0;window.track_changed(track);window.show()
    played=[]
    monkeypatch.setattr(player,'play_queue',lambda tracks,index=0:played.append((tracks,index)))
    monkeypatch.setattr(window.catalog,'album_tracks',lambda album:[track])
    try:
        QTest.mouseClick(window.current_artist,Qt.MouseButton.LeftButton)
        QTest.qWait(250)
        profile=window.artist_profile
        assert profile.isVisible()
        assert profile.artist['id']==2
        assert profile.model.rowCount()==1
        assert profile.albums.count()==1
        profile.open_album(profile.albums.item(0));QTest.qWait(100)
        assert profile.back.isVisible()
        profile.play(0)
        assert played==[([track],0)]
    finally:
        window.quit_app()
