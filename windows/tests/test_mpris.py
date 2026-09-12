import pytest
pytestmark = pytest.mark.skip(reason="MPRIS is Linux-only; Windows uses media_keys.py")
import os
import subprocess

import pytest
from PyQt6.QtTest import QTest

from musicvault.library import read_track
from musicvault.mpris import Mpris
from musicvault.player import Player
from musicvault.window import Window
from test_player import until


@pytest.mark.skipif(os.environ.get("MUSICVAULT_TEST_DBUS") != "1", reason="Run under a private dbus-run-session with MUSICVAULT_TEST_DBUS=1")
def test_dbus_introspection_metadata_and_remote_transport(app, db, audio_file):
    player = Player(db, audio_output="null")
    window = Window(db, player)

    def call(method, *arguments):
        process = subprocess.Popen(["gdbus", "call", "--session", "--dest", "org.mpris.MediaPlayer2.MusicVault",
            "--object-path", "/org/mpris/MediaPlayer2", "--method", method, *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        until(lambda: process.poll() is not None)
        stdout, stderr = process.communicate()
        assert process.returncode == 0, stderr
        return stdout

    try:
        window.mpris = Mpris(window, player)
        player.play_queue([read_track(audio_file(duration=10))])
        until(lambda: player.loaded)
        interface = "org.mpris.MediaPlayer2.Player"
        result = call("org.freedesktop.DBus.Properties.GetAll", interface)
        assert "Playing" in result and "xesam:title" in result and "sample" in result
        call(interface + ".Pause")
        assert not player.playing
        call("org.freedesktop.DBus.Properties.Set", interface, "Volume", "<0.42>")
        assert player.volume == 42
        call(interface + ".Play")
        assert player.playing
        call(interface + ".Seek", "1000000")
        assert player.position >= 1
        result = call("org.freedesktop.DBus.Introspectable.Introspect")
        assert "org.mpris.MediaPlayer2.Player" in result and "SetPosition" in result
        call(interface + ".Stop")
        assert not player.loaded
    finally:
        window.quit_app()
