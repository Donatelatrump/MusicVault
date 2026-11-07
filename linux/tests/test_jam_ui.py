"""Unit tests for the Jam (DJ para ti) UI integration on Windows."""

from dataclasses import replace
from unittest.mock import MagicMock

from PyQt6.QtWidgets import QApplication

from musicvault.database import Database
from musicvault.experience import DjUiState
from musicvault.models import Track
from musicvault.window import Window


class FakePlayer:
    """Stand-in for Player that avoids libmpv dependency."""

    def __init__(self, db):
        self.db = db
        self.current = None
        self.queue = []
        self.index = 0
        self.playing = False
        self.preparing = False
        self.shuffle = False
        self.repeat = "off"
        self.volume = 75
        self.duration = 0
        self.position = 0
        self.crossfade = 0
        self.sleep_deadline = None
        self.track_changed = MagicMock()
        self.changed = MagicMock()
        self.progress = MagicMock()
        self.queue_changed = MagicMock()
        self.error = MagicMock()
        self.need_track = MagicMock()
        self.stream_cached = MagicMock()

    def toggle(self):
        pass

    def next(self):
        pass

    def previous(self):
        pass

    def play_queue(self, tracks, start=0):
        self.queue = list(tracks)
        self.index = start
        self.current = tracks[start] if tracks else None

    def append(self, track):
        self.queue.append(track)

    def remove(self, index):
        if 0 <= index < len(self.queue):
            self.queue.pop(index)

    def move(self, source, target):
        if 0 <= source < len(self.queue) and 0 <= target < len(self.queue):
            item = self.queue.pop(source)
            self.queue.insert(target, item)

    def toggle_shuffle(self):
        self.shuffle = not self.shuffle

    def set_repeat(self, mode):
        self.repeat = mode

    def set_volume(self, value):
        self.volume = value

    def set_playing(self, value):
        self.playing = value

    def seek(self, position):
        self.position = position

    def set_sleep(self, value):
        self.sleep_deadline = value

    def next_index(self, automatic=False):
        return 0

    def close(self):
        pass

    def downloaded(self, track):
        pass

    def preparation_failed(self, track):
        pass

    def play_resolved(self, track, url, **kwargs):
        self.current = track

    def prepare_stream(self, track, url):
        pass

    def related_successor(self):
        return None


def test_jam_nav_button_exists(app, db):
    window = Window(db, FakePlayer(db))
    try:
        assert "Jam" in window.nav
        jam_btn = window.nav["Jam"]
        assert jam_btn.text() == "Jam"
        assert not window.jam_state.active
    finally:
        window.quit_app()


def test_jam_toggle_activates_and_deactivates(app, db):
    window = Window(db, FakePlayer(db))
    try:
        assert not window.jam_state.active
        window.toggle_jam()
        assert window.jam_state.active
        assert window.jam_state.loading
        window.toggle_jam()
        assert not window.jam_state.active
    finally:
        window.quit_app()


def test_jam_route_shows_jam_tracks(app, db):
    window = Window(db, FakePlayer(db))
    try:
        window.jam_state = DjUiState(
            active=True,
            loading=False,
            reason="Test Artist",
            upcoming=[
                Track("jam:1", "Jam Song 1", "Jam Artist", "Album", 180_000),
                Track("jam:2", "Jam Song 2", "Jam Artist", "Album", 200_000),
            ],
        )
        window.show_route("Jam")
        assert window.route == "Jam"
        assert len(window.shown_tracks) == 2
        assert window.shown_tracks[0].id == "jam:1"
    finally:
        window.quit_app()


def test_jam_route_refresh_uses_upcoming(app, db):
    window = Window(db, FakePlayer(db))
    try:
        window.jam_state = DjUiState(
            active=True,
            loading=False,
            reason="Test",
            upcoming=[Track("jam:x", "Track X", "Artist X", "Album", 100_000)],
        )
        window.show_route("Jam")
        window.refresh_tracks()
        assert len(window.shown_tracks) == 1
        assert window.shown_tracks[0].id == "jam:x"
    finally:
        window.quit_app()


def test_stop_jam_returns_to_inicio(app, db):
    window = Window(db, FakePlayer(db))
    try:
        window.jam_state = DjUiState(active=True, upcoming=[Track("j", "s", "a", "al", 1)])
        window.show_route("Jam")
        assert window.route == "Jam"
        window.stop_jam()
        assert not window.jam_state.active
        assert window.route == "Inicio"
    finally:
        window.quit_app()


def test_jam_button_icon_changes_with_active_state(app, db):
    window = Window(db, FakePlayer(db))
    try:
        jam_btn = window.nav["Jam"]
        default_icon = jam_btn.icon()
        window.jam_state = DjUiState(active=True)
        window._refresh_jam_ui()
        active_icon = jam_btn.icon()
        assert active_icon is not default_icon
        window.jam_state = DjUiState(active=False)
        window._refresh_jam_ui()
    finally:
        window.quit_app()
