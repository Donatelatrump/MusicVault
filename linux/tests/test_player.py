from dataclasses import replace
import time

from PyQt6.QtTest import QTest

from musicvault.library import read_track
from musicvault.player import Player


def until(predicate, timeout=5000):
    deadline = time.monotonic() + timeout / 1000
    while time.monotonic() < deadline:
        if predicate():
            return
        QTest.qWait(25)
    assert predicate(), "Timed out waiting for playback state"


def test_real_decode_pause_seek_and_eof(app, db, audio_file):
    player = Player(db, audio_output="null")
    errors = []
    player.error.connect(errors.append)
    try:
        tracks = [read_track(audio_file(f"{i}.wav", 2)) for i in range(2)]
        player.play_queue(tracks)
        until(lambda: player.loaded and player.position > 0.15)
        player.set_playing(False)
        QTest.qWait(200)
        paused = player.position
        QTest.qWait(200)
        assert abs(player.position - paused) < 0.1
        player.seek(1.5)
        player.set_playing(True)
        until(lambda: player.index == 1 and player.loaded)
        until(lambda: not player.playing, 4000)
        assert not errors
    finally:
        player.close()


def test_real_crossfade_pause_and_queue_identity(app, db, audio_file):
    player = Player(db, audio_output="null")
    errors = []
    player.error.connect(errors.append)
    try:
        tracks = [read_track(audio_file(f"{i}.wav", 4)) for i in range(3)]
        player.crossfade = 1
        player.play_queue(tracks)
        until(lambda: player.loaded)
        player.seek(3.05)
        until(lambda: player.index == 1 and player.loaded and player.fade is not None)
        player.set_playing(False)
        elapsed = player.fade["elapsed"]
        QTest.qWait(300)
        assert player.fade["elapsed"] == elapsed
        player.set_playing(True)
        until(lambda: player.fade is None)
        assert abs(player.deck.number("volume") - player.volume) < 0.1
        current = player.current
        player.move(1, 2)
        assert player.current == current and player.index == 2
        player.remove(0)
        assert player.current == current and player.index == 1
        player.toggle_shuffle()
        assert player.current == current
        player.set_sleep(0.001)
        until(lambda: not player.playing)
        assert not errors
    finally:
        player.close()


def test_missing_remote_download_request_and_stop_invalidation(app, db, audio_file):
    player = Player(db, audio_output="null")
    requests = []
    player.need_track.connect(requests.append)
    try:
        track = replace(read_track(audio_file()), path="", source="deezer")
        player.play_queue([track])
        assert requests == [track]
        generation = player.request_generation
        player.set_playing(False)
        assert player.request_generation != generation
        player.set_repeat("all")
        assert player.next_index() == 0
        player.set_repeat("one")
        assert player.next_index(automatic=True) == 0
    finally:
        player.close()


def test_related_continuation_prioritizes_local_album(app, db, audio_file):
    player = Player(db, audio_output="null")
    try:
        tracks = [replace(read_track(audio_file(f"{i}.wav")), album="A" if i < 2 else "B") for i in range(3)]
        for track in tracks:
            db.save_track(track)
        player.play_queue([tracks[0]])
        assert player.related_successor() is None
        db.set_setting("auto_continue", True)
        index = player.related_successor()
        assert player.queue[index].id == tracks[1].id
    finally:
        player.close()
