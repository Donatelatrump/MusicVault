"""Shared Android protocol v2 rooms; network work runs in a private Node process."""
import json
import hashlib
import os
from pathlib import Path
import re
import shutil
import time

from PyQt6.QtCore import QObject, QProcess, QTimer, pyqtSignal
from PyQt6.QtWidgets import QApplication
from .models import Track


def identity_dir():
    # Deliberately outside the library, SQLite backups and portable Data folder.
    base = Path(os.environ.get('LOCALAPPDATA', Path.home() / '.local/share'))
    location = os.path.normcase(str(Path(__file__).resolve().parents[1]))
    install = hashlib.sha256(location.encode()).hexdigest()[:24]
    return base / 'MusicVault' / 'installations' / install


def target_position(playback, server_now):
    elapsed = max(0, server_now - playback.get('effectiveAt', server_now)) if playback.get('playing') and not playback.get('buffering') else 0
    return max(0, playback.get('positionMs', 0) + elapsed) / 1000


class SharedJams(QObject):
    updated = pyqtSignal()

    def __init__(self, window):
        super().__init__(window)
        self.window, self.player = window, window.player
        self.identity, self.room, self.contacts = {}, None, {}
        self.connected = False
        self.message = 'Conecta para usar JAMS y Amigos.'
        self.offset = 0
        self.loaded_key = self.ready_key = None
        self.preparing = False
        self.generation = 0
        self.failed_key = None
        self.last_seek = 0
        self.dialog = None
        self.buffer = b''
        self.pending = []
        self.process = QProcess(self)
        self.process.readyReadStandardOutput.connect(self.read)
        self.process.errorOccurred.connect(lambda _: self.error('No se pudo ejecutar Node.js para JAMS.'))
        self.process.finished.connect(self.finished)
        self.timer = QTimer(self)
        self.timer.setInterval(100)
        self.timer.timeout.connect(self.align)
        self.timer.start()
        self.player.jam_transport = self.transport
        self.player.jam_add = self.add_queue

    @property
    def active(self):
        return bool(self.room and self.room.get('value'))

    def error(self, message):
        self.message = message
        self.updated.emit()

    def finished(self):
        self.connected = False
        if self.active:
            self.player.set_playing(False, _jam=True)
        self.error('JAMS se desconectó. Cierra y vuelve a abrir JAMS para reintentar.')

    def start(self):
        if self.process.state() != QProcess.ProcessState.NotRunning:
            return
        backend = Path(__file__).parent / 'jams_backend'
        bundled = Path(__file__).resolve().parents[1] / 'runtime/node.exe'
        node = str(bundled) if bundled.exists() else shutil.which('node')
        bundle = backend / 'bridge.bundle.mjs'
        if not node or (not bundle.is_file() and not (backend / 'node_modules/firebase').is_dir()):
            self.error('Faltan Node.js o Firebase. Ejecuta packaging/setup_jams.py.')
            return
        self.message = 'Conectando…'
        self.process.start(node, [str(bundle if bundle.is_file() else backend / 'bridge.mjs'), str(identity_dir())])

    def send(self, action, **data):
        self.start()
        if self.process.state() == QProcess.ProcessState.NotRunning:
            return
        self.process.write((json.dumps({'action': action, **data}) + '\n').encode())

    def read(self):
        self.buffer += bytes(self.process.readAllStandardOutput())
        while b'\n' in self.buffer:
            line, self.buffer = self.buffer.split(b'\n', 1)
            try:
                event = json.loads(line)
            except ValueError:
                continue
            name, value = event['event'], event['data']
            if name == 'identity':
                self.identity = value
                self.message = 'Conectado'
            elif name == 'connected':
                self.connected = value
                if not value and self.active:
                    self.player.set_playing(False, _jam=True)
                    self.ready_key = None
            elif name == 'room':
                old = self.active
                previous_code = self.room.get('code') if self.room else None
                self.room = value
                if previous_code != (value.get('code') if value else None):
                    self.generation += 1
                    self.failed_key = None
                if value:
                    self.offset = value['serverNow'] - time.monotonic() * 1000
                if not self.active:
                    self.generation += 1
                    self.loaded_key = self.ready_key = None
                    if old:
                        self.player.stop()
                        self.player.deck.set('speed', '1')
                elif not old:
                    self.window.stop_jam()
                    self.player.stop()
                self.message = 'Sala conectada' if self.active else 'Sin sala'
            elif name in ('friends', 'friendRequests', 'roomInvites'):
                self.contacts[name] = value
            elif name == 'link':
                QApplication.clipboard().setText(value)
                self.message = 'Enlace de amistad copiado'
            elif name == 'error':
                self.message = value
            self.updated.emit()

    def command(self, **changes):
        if not self.active or not self.connected:
            return
        self.send('playback', value=changes)

    def transport(self, action, value=None):
        if not self.active:
            return False
        p = self.room['value'].get('playback', {})
        keys = sorted(self.room['value'].get('queue', {}))
        key = p.get('songKey', '')
        if action in ('next', 'previous', 'ended'):
            if action == 'ended' and self.identity.get('uid') != min((u for u, online in self.room['value'].get('members', {}).items() if online), default=''):
                return True
            index = keys.index(key) if key in keys else -1
            index += -1 if action == 'previous' else 1
            if 0 <= index < len(keys):
                self.command(songKey=keys[index], playing=True, positionMs=0, buffering=True, action='select')
            else:
                self.command(playing=False, positionMs=int(self.player.position*1000), buffering=False, action='pause')
        elif action == 'seek':
            self.command(positionMs=int(max(0, value)*1000), action='seek')
        elif action in ('toggle', 'playing'):
            playing = not p.get('playing') if action == 'toggle' else bool(value)
            if not key and keys:
                key = keys[0]
            if key:
                self.command(songKey=key, playing=playing, positionMs=int(self.player.position*1000), buffering=playing and self.loaded_key != key, action='play' if playing else 'pause')
        return True

    def add_queue(self, tracks, index, play):
        if not self.active:
            return False
        if tracks:
            if play:
                self.add(tracks[index], True)
            else:
                for track in tracks:
                    self.add(track)
        return True

    def add(self, track, play=False):
        generation = self.generation
        def work(cancel, progress):
            url = self.window.streaming.match(track, cancel)
            video = re.search(r'(?:[?&]v=|youtu\.be/)([A-Za-z0-9_-]{11})', url or '')
            if not video:
                raise ValueError('No se encontró una versión de YouTube para compartir.')
            return video.group(1)
        def ready(video):
            if self.active and generation == self.generation:
                self.send('add', song={'videoId': video, 'title': track.title[:300], 'artist': track.artist[:300],
                                     'durationMs': int(min(86400000, max(0, track.duration*1000))), 'sourceTrackId': track.id[:256]}, play=play)
        self.window.jobs.submit(work, ready, lambda e: self.error(str(e)))

    def align(self):
        if not self.active or not self.connected:
            return
        data = self.room['value']
        p = data.get('playback', {})
        key = p.get('songKey')
        song = data.get('queue', {}).get(key)
        if not song:
            self.player.set_playing(False, _jam=True)
            return
        if self.failed_key == key:
            return
        if self.loaded_key != key:
            if self.preparing:
                return
            self.preparing = True
            self.player.set_playing(False, _jam=True)
            track = Track('youtube:jam:'+song['videoId'], song['title'], song['artist'], 'JAMS', song['durationMs']/1000,
                          source='youtube', source_id=song['videoId'], url='https://www.youtube.com/watch?v='+song['videoId'])
            code = self.room['code']
            generation = self.generation
            def work(cancel, progress):
                return self.window.streaming.resolve(track, cancel)
            def ready(source):
                self.preparing = False
                if not self.active or self.generation != generation or self.room['code'] != code or self.room['value'].get('playback', {}).get('songKey') != key:
                    return
                self.player.db.save_track(track)
                self.player.queue, self.player.index = [track], 0
                self.player.play_resolved(track, source)
                self.player.set_playing(False, _jam=True)
                self.loaded_key, self.ready_key = key, None
                self.failed_key = None
            def failed(e):
                self.preparing = False
                self.failed_key = key
                self.error('Error al preparar audio: '+str(e))
            self.window.jobs.submit(work, ready, failed)
            return
        if not self.player.loaded:
            return
        if self.ready_key != key:
            self.ready_key = key
            self.send('ready', key=key)
        now = time.monotonic()*1000+self.offset
        members = sorted(u for u, online in data.get('members', {}).items() if online)
        if p.get('buffering') and p.get('playing') and members and members[0] == self.identity.get('uid'):
            if all(data.get('ready', {}).get(u) == key for u in members) or now-p.get('effectiveAt', now) >= 12000:
                revision = p['revision']
                if getattr(self, '_started_revision', None) != revision:
                    self._started_revision = revision
                    self.send('playback', value={'buffering':False, 'action':'ready'}, expected=revision)
        target = target_position(p, now)
        drift = target - self.player.position
        buffering = self.player.deck.get('paused-for-cache', 'no') == 'yes'
        if abs(drift) > .65 and not buffering and time.monotonic()-self.last_seek > 1:
            self.player.seek(target, _jam=True)
            self.last_seek = time.monotonic()
        speed = 1.03 if drift > .08 else .97 if drift < -.08 else 1
        self.player.deck.set('speed', str(speed if not p.get('buffering') and p.get('playing') else 1))
        playing = bool(p.get('playing') and not p.get('buffering') and now >= p.get('effectiveAt', 0))
        if self.player.playing != playing:
            self.player.set_playing(playing, _jam=True)

    def close(self):
        self.timer.stop()
        if self.process.state() != QProcess.ProcessState.NotRunning:
            self.send('shutdown')
            if not self.process.waitForFinished(2500):
                self.process.kill()
                self.process.waitForFinished(1000)

    def retry_audio(self):
        self.failed_key = self.loaded_key = self.ready_key = None

    def show(self):
        self.start()
        if self.dialog is None:
            from .jams_ui import JamsDialog
            self.dialog = JamsDialog(self)
        self.dialog.refresh()
        self.dialog.show()
        self.dialog.raise_()
        self.dialog.activateWindow()
