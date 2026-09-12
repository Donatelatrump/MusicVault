"""Windows global playback keys, including while the app is in the tray."""
import ctypes
from ctypes import wintypes
from PyQt6.QtCore import QAbstractNativeEventFilter


class MediaKeys(QAbstractNativeEventFilter):
    def __init__(self, app, player):
        super().__init__()
        self.app = app
        self.actions = {1: player.next, 2: player.previous, 3: player.stop, 4: player.toggle}
        self.registered = []
        for identifier, key in ((1, 0xB0), (2, 0xB1), (3, 0xB2), (4, 0xB3)):
            if ctypes.windll.user32.RegisterHotKey(None, identifier, 0, key):
                self.registered.append(identifier)
        app.installNativeEventFilter(self)

    def nativeEventFilter(self, event_type, message):
        msg = wintypes.MSG.from_address(int(message))
        if msg.message == 0x0312 and msg.wParam in self.actions:
            self.actions[msg.wParam]()
            return True, 0
        return False, 0

    def close(self):
        self.app.removeNativeEventFilter(self)
        for identifier in self.registered:
            ctypes.windll.user32.UnregisterHotKey(None, identifier)
