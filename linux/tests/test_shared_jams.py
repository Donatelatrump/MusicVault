from musicvault.jams import SharedJams, target_position
from musicvault.models import Track


def test_timeline_paused_buffering_and_future_start():
    p = dict(positionMs=12000, effectiveAt=1000, playing=True)
    assert target_position(p, 3500) == 14.5
    assert target_position(dict(p, playing=False), 3500) == 12
    assert target_position(dict(p, buffering=True), 3500) == 12
    assert target_position(p, 500) == 12


def test_transport_controls_use_room_state_without_local_echo():
    class Player:
        position = 4.25
    class Fake:
        active = True
        connected = True
        identity = {'uid': 'guest'}
        player = Player()
        room = {'value': {'queue': {'a': {}, 'b': {}}, 'members': {'host': True},
                          'playback': {'songKey': 'a', 'playing': True}}}
        def command(self, **values):
            self.sent = values
    fake = Fake()
    assert SharedJams.transport(fake, 'toggle')
    assert fake.sent['playing'] is False
    assert fake.sent['positionMs'] == 4250
    SharedJams.transport(fake, 'next')
    assert fake.sent['songKey'] == 'b'
    assert fake.sent['buffering'] is True
    previous = fake.sent
    SharedJams.transport(fake, 'ended')
    assert fake.sent is previous  # only the elected leader advances automatically


def test_shared_queue_routes_play_and_append_but_leaves_local_playback_alone():
    class Fake:
        active = True
        added = []
        def add(self, track, play=False):
            self.added.append((track.id, play))
    fake = Fake()
    tracks = [Track('a', 'A'), Track('b', 'B')]
    assert SharedJams.add_queue(fake, tracks, 1, True)
    assert fake.added == [('b', True)]
    fake.active = False
    assert not SharedJams.add_queue(fake, tracks, 0, False)


def test_room_prepares_real_audio_and_controls_do_not_echo(app, db, audio_file, monkeypatch):
    import time
    from PyQt6.QtTest import QTest
    from musicvault.player import Player
    from musicvault.window import Window
    db.set_setting('taste_onboarding_complete', True)
    db.set_setting('auto_scan', False)
    player = Player(db, audio_output='null')
    window = Window(db, player)
    jams = window.shared_jams
    sent = []
    monkeypatch.setattr(jams, 'send', lambda action, **kw: sent.append((action, kw)))
    monkeypatch.setattr(window.streaming, 'resolve', lambda *args: str(audio_file(duration=8)))
    jams.identity = {'uid': 'a'}
    jams.connected = True
    jams.offset = 1000-time.monotonic()*1000
    jams.room = {'code':'test', 'value':{'members':{'a':True}, 'queue':{
        'song':{'videoId':'abcdefghijk','title':'Test','artist':'Test','durationMs':8000}},
        'playback':{'songKey':'song','playing':True,'buffering':True,'positionMs':0,'effectiveAt':1000,'revision':1}}}
    try:
        deadline = time.monotonic()+5
        while not jams.ready_key and time.monotonic()<deadline:
            QTest.qWait(50)
        assert jams.ready_key == 'song'
        assert any(action == 'ready' for action, _ in sent)
        assert player.loaded
        assert not player.playing
        jams.room['value']['playback'].update(buffering=False, effectiveAt=time.monotonic()*1000+jams.offset)
        QTest.qWait(250)
        assert player.playing
        before = len(sent)
        player.toggle()
        assert len(sent) == before+1
        assert sent[-1][1]['value']['playing'] is False
    finally:
        jams.room = None
        window.quit_app()
