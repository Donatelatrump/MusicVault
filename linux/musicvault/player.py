"""Native libmpv playback, two independent decks for crossfade, Qt event polling."""
import ctypes as C
from ctypes.util import find_library
import locale
from pathlib import Path
import random
import time

from PyQt6.QtCore import QObject, QTimer, pyqtSignal


class MpvEvent(C.Structure):
    _fields_ = [("event_id", C.c_int), ("error", C.c_int), ("reply_userdata", C.c_uint64), ("data", C.c_void_p)]


class EndFile(C.Structure):
    _fields_ = [("reason", C.c_int), ("error", C.c_int)]


class Mpv:
    def __init__(self, audio_output=None):
        locale.setlocale(locale.LC_NUMERIC, "C")
        library = find_library("mpv")
        if not library:
            raise RuntimeError("Falta libmpv. Instala libmpv2 (Debian/Ubuntu) o mpv-libs (Fedora).")
        self.lib = C.CDLL(library)
        signatures = {
            "mpv_create": ([], C.c_void_p),
            "mpv_initialize": ([C.c_void_p], C.c_int),
            "mpv_set_option_string": ([C.c_void_p, C.c_char_p, C.c_char_p], C.c_int),
            "mpv_command": ([C.c_void_p, C.POINTER(C.c_char_p)], C.c_int),
            "mpv_get_property_string": ([C.c_void_p, C.c_char_p], C.c_void_p),
            "mpv_set_property_string": ([C.c_void_p, C.c_char_p, C.c_char_p], C.c_int),
            "mpv_wait_event": ([C.c_void_p, C.c_double], C.POINTER(MpvEvent)),
            "mpv_free": ([C.c_void_p], None),
            "mpv_terminate_destroy": ([C.c_void_p], None),
            "mpv_error_string": ([C.c_int], C.c_char_p),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.lib, name)
            function.argtypes, function.restype = arguments, result
        self.handle = self.lib.mpv_create()
        if not self.handle:
            raise RuntimeError("No se pudo crear el reproductor de audio.")
        try:
            options = {"vid": "no", "terminal": "no", "config": "no", "idle": "yes",
                       "keep-open": "no", "audio-display": "no", "ytdl": "no", "volume": "75"}
            if audio_output:
                options["ao"] = audio_output
            for key, value in options.items():
                self.check(self.lib.mpv_set_option_string(self.handle, key.encode(), value.encode()))
            self.check(self.lib.mpv_initialize(self.handle))
        except Exception:
            self.close()
            raise

    def check(self, result):
        if result < 0:
            raise RuntimeError(self.lib.mpv_error_string(result).decode())

    def command(self, *arguments):
        values = (C.c_char_p * (len(arguments) + 1))(*[str(a).encode() for a in arguments], None)
        self.check(self.lib.mpv_command(self.handle, values))

    def get(self, name, default=""):
        pointer = self.lib.mpv_get_property_string(self.handle, name.encode())
        if not pointer:
            return default
        try:
            return C.string_at(pointer).decode("utf-8", errors="replace")
        finally:
            self.lib.mpv_free(pointer)

    def number(self, name, default=0):
        try:
            return float(self.get(name, str(default)))
        except ValueError:
            return default

    def set(self, name, value):
        self.check(self.lib.mpv_set_property_string(self.handle, name.encode(), str(value).encode()))

    def events(self):
        result = []
        for _ in range(200):
            event = self.lib.mpv_wait_event(self.handle, 0).contents
            if not event.event_id:
                break
            if event.event_id == 7 and event.data:
                end = C.cast(event.data, C.POINTER(EndFile)).contents
                result.append((7, end.reason, end.error))
            else:
                result.append((event.event_id, 0, event.error))
        return result

    def close(self):
        if self.handle:
            self.lib.mpv_terminate_destroy(self.handle)
            self.handle = None


PRESETS = {
    "Plano": [0, 0, 0, 0, 0], "Bass Boost": [9.75, 8.25, 3, 0, 0],
    "Volume Boost": [5.25, 3, 1.5, 1.5, 2.25], "Claridad de voz": [-4.5, -1.5, 6.75, 4.5, -1.5],
    "Rock": [6.75, 3.75, -1.5, 4.5, 6], "Pop": [2.25, 4.5, 6, 3.75, 3],
    "Jazz": [4.5, 3.75, 1.5, 3, 4.5], "Electrónica": [10.5, 5.25, -3, 1.5, 8.25],
}


class Player(QObject):
    changed = pyqtSignal()
    track_changed = pyqtSignal(object)
    progress = pyqtSignal(float, float)
    queue_changed = pyqtSignal()
    error = pyqtSignal(str)
    need_track = pyqtSignal(object)
    seeked = pyqtSignal(float)

    def __init__(self, db, parent=None, audio_output=None, deck_factory=Mpv):
        super().__init__(parent)
        self.db = db
        self.decks = [deck_factory(audio_output)]
        try:
            self.decks.append(deck_factory(audio_output))
        except Exception:
            self.decks[0].close()
            raise
        self.active = 0
        self.queue, self.index = [], -1
        self.playing, self.loaded = False, False
        self.position = self.duration = 0
        self.volume = db.setting("volume", 75)
        self.shuffle = db.setting("shuffle", False)
        self.repeat = db.setting("repeat", "off")
        self.crossfade = db.setting("crossfade", 0)
        self.fade = None
        self.history_id = None
        self.history_tick = 0
        self.sleep_deadline = None
        self.closed = False
        self.request_generation = 0
        self.timer = QTimer(self)
        self.timer.setInterval(100)
        self.timer.timeout.connect(self.tick)
        self.timer.start()
        self.set_volume(self.volume)
        self.apply_equalizer(db.setting("equalizer", [0] * 5), db.setting("loudness", 0), db.setting("stereo", 1))
        saved = db.setting("queue", {})
        self.queue = [t for key in saved.get("ids", []) if (t := db.get_track(key))]
        self.index = min(saved.get("index", 0), len(self.queue) - 1)

    @property
    def deck(self):
        return self.decks[self.active]

    @property
    def current(self):
        return self.queue[self.index] if 0 <= self.index < len(self.queue) else None

    def persist_queue(self):
        self.db.set_setting("queue", {"ids": [t.id for t in self.queue], "index": self.index})
        self.queue_changed.emit()

    def play_queue(self, tracks, index=0):
        if not tracks:
            return
        self._save_progress()
        self.stop()
        self.queue = [self.db.save_track(t, restore=True) for t in tracks]
        self.index = max(0, min(index, len(self.queue) - 1))
        if self.shuffle:
            current = self.queue.pop(self.index)
            random.shuffle(self.queue)
            self.queue.insert(0, current)
            self.index = 0
        self.play_index(self.index)

    def play_index(self, index, crossfade=False):
        if not 0 <= index < len(self.queue):
            return
        self.request_generation += 1
        track = self.db.resolve_track(self.queue[index])
        if not track.path or not Path(track.path).is_file():
            self.stop()
            self.index = index
            self.persist_queue()
            self.track_changed.emit(track)
            if track.source != "local":
                self.need_track.emit(track)
            else:
                self.error.emit(f"No se encuentra el archivo: {track.path}")
            return
        self._save_progress()
        self._finish_fade()
        if crossfade and self.loaded:
            outgoing = self.active
            self.active = 1 - self.active
            self.fade = {"outgoing": outgoing, "elapsed": 0.0, "last": time.monotonic()}
            self.deck.set("volume", 0)
        else:
            self.deck.command("stop")
            self.deck.set("volume", self.volume)
        self.deck.events()
        self.queue[index] = track
        self.index, self.position, self.duration = index, 0, track.duration
        self.loaded, self.playing = False, True
        self.deck.set("pause", "no")
        self.deck.command("loadfile", track.path, "replace")
        self.history_id = self.db.record_play(track)
        self.persist_queue()
        self.track_changed.emit(track)
        self.changed.emit()

    def downloaded(self, track):
        for i, existing in enumerate(self.queue):
            if existing.id == track.id:
                self.queue[i] = track
        self.persist_queue()

    def toggle(self):
        if not self.loaded:
            if self.current:
                self.play_index(self.index)
            return
        self.set_playing(not self.playing)

    def set_playing(self, value):
        if value and not self.loaded:
            if self.current:
                self.play_index(self.index)
            return
        if not value and not self.loaded:
            self.request_generation += 1
        self.playing = bool(value) and self.loaded
        self.deck.set("pause", "no" if self.playing else "yes")
        if self.fade:
            self.decks[self.fade["outgoing"]].set("pause", "no" if self.playing else "yes")
            self.fade["last"] = time.monotonic()
        self.changed.emit()

    def next_index(self, automatic=False):
        if not self.queue:
            return None
        if automatic and self.repeat == "one":
            return self.index
        if self.index + 1 < len(self.queue):
            return self.index + 1
        return 0 if self.repeat == "all" else None

    def related_successor(self):
        """Continue offline from the library, favoring the current album/artist and favorites."""
        if not self.current or not self.db.setting("auto_continue", False):
            return None
        current = self.current
        recent = {t.id for t in self.queue[max(0, self.index - 9):self.index + 1]}
        candidates = [t for t in self.db.tracks() if t.id != current.id and t.path and Path(t.path).is_file()]
        unheard = [t for t in candidates if t.id not in recent]
        candidates = unheard or candidates
        if not candidates:
            return None
        favorites = self.db.favorites()
        random.shuffle(candidates)
        successor = max(candidates, key=lambda t: (t.album == current.album and t.album != "Sin álbum",
                                                   t.artist == current.artist, t.id in favorites))
        self.append(successor)
        return len(self.queue) - 1

    def next(self):
        index = self.next_index()
        if index is None:
            self.stop()
        else:
            self.play_index(index)

    def previous(self):
        if self.position > 3:
            self.seek(0)
        elif self.index > 0:
            self.play_index(self.index - 1)
        elif self.queue:
            self.play_index(0)

    def seek(self, position):
        if self.loaded:
            self._finish_fade()
            self.position = max(0, min(float(position), self.duration))
            self.deck.command("seek", self.position, "absolute+exact")
            self.seeked.emit(self.position)

    def set_volume(self, volume):
        self.volume = max(0, min(float(volume), 100))
        if not self.fade:
            self.deck.set("volume", self.volume)
        self.db.set_setting("volume", self.volume)
        self.changed.emit()

    def toggle_shuffle(self):
        self.shuffle = not self.shuffle
        if self.shuffle:
            tail = self.queue[self.index + 1:]
            random.shuffle(tail)
            self.queue[self.index + 1:] = tail
        self.db.set_setting("shuffle", self.shuffle)
        self.persist_queue()
        self.changed.emit()

    def set_repeat(self, value):
        if value not in ("off", "all", "one"):
            return
        self.repeat = value
        self.db.set_setting("repeat", value)
        self.changed.emit()

    def append(self, track):
        self.queue.append(self.db.save_track(track, restore=True))
        if self.index < 0:
            self.index = 0
        self.persist_queue()

    def move(self, source, target):
        if not (0 <= source < len(self.queue) and 0 <= target < len(self.queue)):
            return
        current_index = self.index
        self.queue.insert(target, self.queue.pop(source))
        if current_index == source:
            self.index = target
        elif source < current_index <= target:
            self.index -= 1
        elif target <= current_index < source:
            self.index += 1
        self.persist_queue()

    def remove(self, index):
        if not 0 <= index < len(self.queue):
            return
        current = index == self.index
        if current:
            self.stop()
        self.queue.pop(index)
        if index < self.index:
            self.index -= 1
        self.index = min(self.index, len(self.queue) - 1)
        self.persist_queue()
        if current and self.queue:
            self.play_index(self.index)
        elif not self.queue:
            self.track_changed.emit(None)

    def set_sleep(self, minutes):
        self.sleep_deadline = time.monotonic() + minutes * 60 if minutes > 0 else None

    def apply_equalizer(self, bands, loudness=0, stereo=1):
        bands = [max(-15, min(float(g), 15)) for g in bands]
        if len(bands) != 5:
            raise ValueError("El ecualizador requiere cinco bandas.")
        loudness, stereo = max(0, min(float(loudness), 12)), max(0, min(float(stereo), 2))
        filters = [f"equalizer=f={frequency}:t=o:w=1:g={gain}" for frequency, gain in zip((60, 230, 910, 3600, 14000), bands) if gain]
        if stereo != 1:
            filters.append(f"extrastereo=m={stereo}")
        if loudness:
            filters.append(f"volume={loudness}dB")
        if filters:
            filters.append("alimiter=limit=0.95:level=false")
        expression = "lavfi=[" + ",".join(filters) + "]" if filters else ""
        for deck in self.decks:
            deck.set("af", expression)
        self.db.set_setting("equalizer", bands)
        self.db.set_setting("loudness", loudness)
        self.db.set_setting("stereo", stereo)

    def _save_progress(self):
        if self.history_id and self.duration:
            self.db.completion(self.history_id, self.position / self.duration)

    def _finish_fade(self):
        if self.fade:
            self.decks[self.fade["outgoing"]].command("stop")
            self.fade = None
        self.deck.set("volume", self.volume)

    def stop(self):
        self.request_generation += 1
        self._save_progress()
        self._finish_fade()
        self.deck.command("stop")
        self.loaded = self.playing = False
        self.position = 0
        self.changed.emit()

    def tick(self):
        try:
            self._tick()
        except Exception as exc:
            self.playing = False
            self.error.emit(str(exc))
            self.changed.emit()

    def _tick(self):
        if self.sleep_deadline and time.monotonic() >= self.sleep_deadline:
            self.sleep_deadline = None
            self.set_playing(False)
        self.decks[1 - self.active].events()
        for event, reason, error in self.deck.events():
            if event == 8:
                self.loaded = True
                if self.fade:
                    self.fade["last"] = time.monotonic()
            elif event == 7 and reason == 0:
                self.position = self.duration
                self._save_progress()
                successor = self.next_index(automatic=True)
                if successor is None:
                    successor = self.related_successor()
                if successor is None:
                    self.stop()
                else:
                    self.play_index(successor)
                return
            elif event == 7 and error < 0:
                self.stop()
                self.error.emit("No se pudo reproducir el archivo de audio.")
                return
        if not self.loaded:
            return
        self.position = self.deck.number("time-pos", self.position)
        self.duration = self.deck.number("duration", self.duration)
        self.progress.emit(self.position, self.duration)
        now = time.monotonic()
        if now - self.history_tick > 10:
            self._save_progress()
            self.history_tick = now
        if self.fade:
            delta = now - self.fade["last"]
            self.fade["last"] = now
            if self.playing:
                self.fade["elapsed"] += delta
            ratio = min(1, self.fade["elapsed"] / max(0.1, self.crossfade))
            self.deck.set("volume", self.volume * ratio)
            self.decks[self.fade["outgoing"]].set("volume", self.volume * (1 - ratio))
            if ratio >= 1:
                self._finish_fade()
        elif self.playing and self.crossfade > 0 and self.duration > self.crossfade * 2:
            successor = self.next_index(automatic=True)
            if successor is not None and successor != self.index and 0 < self.duration - self.position <= self.crossfade:
                track = self.db.get_track(self.queue[successor].id) or self.queue[successor]
                if track.path and Path(track.path).is_file():
                    self.play_index(successor, crossfade=True)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.timer.stop()
        self._save_progress()
        self.persist_queue()
        for deck in self.decks:
            deck.close()
