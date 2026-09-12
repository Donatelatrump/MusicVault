"""MPRIS 2 controls for GNOME, KDE, media keys and playerctl."""
from pathlib import Path

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib
from PyQt6.QtCore import QTimer
from .models import stable_id

ROOT = "org.mpris.MediaPlayer2"
PLAYER = ROOT + ".Player"
PROPERTIES = "org.freedesktop.DBus.Properties"


class Mpris(dbus.service.Object):
    def __init__(self, window, player):
        DBusGMainLoop(set_as_default=True)
        self.bus = dbus.SessionBus()
        self.name = dbus.service.BusName(ROOT + ".MusicVault", self.bus, do_not_queue=True)
        super().__init__(self.name, "/org/mpris/MediaPlayer2")
        self.window, self.player = window, player
        self.timer = QTimer(window)
        self.timer.timeout.connect(self.pump)
        self.timer.start(50)
        player.changed.connect(self.publish)
        player.track_changed.connect(self.publish)
        player.queue_changed.connect(self.publish)
        player.seeked.connect(lambda position: self.Seeked(dbus.Int64(position * 1_000_000)))

    def pump(self):
        context = GLib.MainContext.default()
        for _ in range(20):
            if not context.pending():
                break
            context.iteration(False)

    def properties(self, interface):
        if interface == ROOT:
            return {"CanQuit": True, "CanRaise": True, "HasTrackList": False,
                    "Identity": "MusicVault", "DesktopEntry": "com.musicvault.MusicVault",
                    "SupportedUriSchemes": dbus.Array(["file"], signature="s"),
                    "SupportedMimeTypes": dbus.Array(["audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/x-wav"], signature="s")}
        if interface != PLAYER:
            raise dbus.exceptions.DBusException("Unknown interface", name="org.freedesktop.DBus.Error.UnknownInterface")
        p = self.player
        metadata = dbus.Dictionary({}, signature="sv")
        if p.current:
            t = p.current
            metadata.update({"mpris:trackid": dbus.ObjectPath("/com/musicvault/track/" + stable_id(t.id)),
                             "mpris:length": dbus.Int64(p.duration * 1_000_000 or t.duration * 1_000_000),
                             "xesam:title": t.title, "xesam:artist": dbus.Array([t.artist], signature="s"), "xesam:album": t.album})
            if t.path:
                metadata["xesam:url"] = Path(t.path).as_uri()
            if t.cover:
                metadata["mpris:artUrl"] = t.cover if t.cover.startswith("https://") else Path(t.cover).as_uri()
        return {"PlaybackStatus": "Playing" if p.playing else "Paused" if p.loaded else "Stopped",
                "LoopStatus": {"off": "None", "all": "Playlist", "one": "Track"}[p.repeat], "Rate": 1.0,
                "Shuffle": p.shuffle, "Metadata": metadata, "Volume": p.volume / 100,
                "Position": dbus.Int64(p.position * 1_000_000), "MinimumRate": 1.0, "MaximumRate": 1.0,
                "CanGoNext": p.next_index() is not None, "CanGoPrevious": bool(p.queue),
                "CanPlay": bool(p.queue), "CanPause": p.loaded, "CanSeek": p.loaded, "CanControl": True}

    @dbus.service.method(PROPERTIES, in_signature="ss", out_signature="v")
    def Get(self, interface, name):
        props = self.properties(interface)
        if name not in props:
            raise dbus.exceptions.DBusException("Unknown property", name="org.freedesktop.DBus.Error.UnknownProperty")
        return props[name]

    @dbus.service.method(PROPERTIES, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.properties(interface)

    @dbus.service.method(PROPERTIES, in_signature="ssv")
    def Set(self, interface, name, value):
        if interface == PLAYER and name == "Volume":
            self.player.set_volume(float(value) * 100)
        elif interface == PLAYER and name == "Shuffle":
            if bool(value) != self.player.shuffle:
                self.player.toggle_shuffle()
        elif interface == PLAYER and name == "LoopStatus" and value in ("None", "Track", "Playlist"):
            self.player.set_repeat({"None": "off", "Track": "one", "Playlist": "all"}[value])
        elif interface == PLAYER and name == "Rate" and float(value) == 1:
            pass
        else:
            raise dbus.exceptions.DBusException("Property is read-only or invalid", name="org.freedesktop.DBus.Error.InvalidArgs")

    @dbus.service.signal(PROPERTIES, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    @dbus.service.signal(PLAYER, signature="x")
    def Seeked(self, position):
        pass

    def publish(self, *_):
        props = self.properties(PLAYER)
        props.pop("Position")
        self.PropertiesChanged(PLAYER, props, [])

    @dbus.service.method(ROOT)
    def Raise(self):
        self.window.showNormal()
        self.window.raise_()
        self.window.activateWindow()

    @dbus.service.method(ROOT)
    def Quit(self):
        self.window.quit_app()

    @dbus.service.method(PLAYER)
    def PlayPause(self):
        self.player.toggle()

    @dbus.service.method(PLAYER)
    def Play(self):
        self.player.set_playing(True)

    @dbus.service.method(PLAYER)
    def Pause(self):
        self.player.set_playing(False)

    @dbus.service.method(PLAYER)
    def Stop(self):
        self.player.stop()

    @dbus.service.method(PLAYER)
    def Next(self):
        self.player.next()

    @dbus.service.method(PLAYER)
    def Previous(self):
        self.player.previous()

    @dbus.service.method(PLAYER, in_signature="x")
    def Seek(self, offset):
        self.player.seek(self.player.position + offset / 1_000_000)

    @dbus.service.method(PLAYER, in_signature="ox")
    def SetPosition(self, track_id, position):
        current = self.properties(PLAYER)["Metadata"].get("mpris:trackid")
        if track_id == current and 0 <= position <= self.player.duration * 1_000_000:
            self.player.seek(position / 1_000_000)

    @dbus.service.method(PLAYER, in_signature="s")
    def OpenUri(self, uri):
        from PyQt6.QtCore import QUrl
        url = QUrl(uri)
        if not url.isLocalFile():
            raise dbus.exceptions.DBusException("Only local files are supported", name="org.freedesktop.DBus.Error.InvalidArgs")
        self.window.import_paths([url.toLocalFile()], play=True)

    def close(self):
        self.timer.stop()
        self.remove_from_connection()
