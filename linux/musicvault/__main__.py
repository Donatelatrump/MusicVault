import argparse
from ctypes.util import find_library
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import sys

from . import __version__


def doctor():
    from .downloader import downloader_command, javascript_runtime
    checks = {"linux_x86_64": sys.platform == "linux" and platform.machine() in ("x86_64", "amd64"),
              "python": platform.python_version(), "libmpv": find_library("mpv")}
    for module in ("PyQt6", "mutagen", "dbus", "gi"):
        checks[module] = importlib.util.find_spec(module) is not None
    try:
        checks["PyQt6.QtWebEngineWidgets"] = importlib.util.find_spec("PyQt6.QtWebEngineWidgets") is not None
    except ModuleNotFoundError:
        checks["PyQt6.QtWebEngineWidgets"] = False
    for command in ("ffmpeg", "ffprobe", "yt-dlp"):
        checks[command] = shutil.which(command)
    try:
        checks["yt-dlp"] = downloader_command()
    except RuntimeError:
        checks["yt-dlp"] = None
    checks["javascript_runtime"] = javascript_runtime()
    print(json.dumps(checks, indent=2, ensure_ascii=False))
    return 0 if all(checks.values()) else 1


def main():
    parser = argparse.ArgumentParser(description="MusicVault para Linux x86_64")
    parser.add_argument("files", nargs="*", help="Archivos o carpetas para importar")
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--doctor", action="store_true", help="Comprobar dependencias sin abrir la interfaz")
    parser.add_argument("--data-dir", help="Directorio alternativo para la biblioteca")
    parser.add_argument("--smoke-test", action="store_true", help="Abrir y cerrar automáticamente (audio nulo)")
    parser.add_argument("--screenshot", help="Guardar captura de la ventana (usado con --smoke-test)")
    args = parser.parse_args()
    if args.doctor:
        return doctor()
    if args.data_dir:
        os.environ["MUSICVAULT_DATA_DIR"] = str(Path(args.data_dir).expanduser().resolve())
    if sys.platform != "linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("Esta distribución está preparada para Linux x86_64 (64 bits).")
    try:
        # Qt WebEngine must be imported before QApplication is constructed.
        # Importing it lazily from the Spotify dialog is too late on Qt 6.10+.
        from PyQt6 import QtWebEngineWidgets as _qt_webengine_widgets
        from PyQt6.QtCore import QTimer, QLockFile, QStandardPaths, QUrl
        from PyQt6.QtWidgets import QApplication, QMessageBox
        from .database import Database
        from .models import data_dir
        from .player import Player
        from .window import Window
    except ImportError as exc:
        print(f"Falta una dependencia: {exc}. Ejecuta el lanzador con --doctor (./run.sh --doctor desde linux/) y consulta README.md.", file=sys.stderr)
        return 1
    app = QApplication([sys.argv[0]])
    app.setApplicationName("MusicVault")
    app.setApplicationVersion(__version__)
    app.setOrganizationName("MusicVault")
    app.setDesktopFileName("com.musicvault.MusicVault")
    lock = QLockFile(str(data_dir() / "musicvault.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        # Hand files to the existing desktop process through its MPRIS service.
        try:
            import dbus
            obj = dbus.SessionBus().get_object("org.mpris.MediaPlayer2.MusicVault", "/org/mpris/MediaPlayer2")
            dbus.Interface(obj, "org.mpris.MediaPlayer2").Raise()
            for path in args.files:
                dbus.Interface(obj, "org.mpris.MediaPlayer2.Player").OpenUri(Path(path).resolve().as_uri())
            return 0
        except Exception:
            print("MusicVault ya está abierto para esta biblioteca.", file=sys.stderr)
            return 1
    player = None
    try:
        db = Database()
        player = Player(db, audio_output="null" if args.smoke_test else None)
        window = Window(db, player)
    except Exception as exc:
        if player:
            player.close()
        if args.smoke_test:
            print(str(exc), file=sys.stderr)
        else:
            QMessageBox.critical(None, "MusicVault", f"No se pudo iniciar MusicVault:\n{exc}")
        return 1
    try:
        from .mpris import Mpris
        window.mpris = Mpris(window, player)
    except Exception as exc:
        window.notify(f"Controles multimedia del sistema no disponibles: {exc}")
    window.show()
    if args.files:
        window.import_paths([QUrl(path).toLocalFile() if path.startswith("file:") else path for path in args.files])
    if args.smoke_test:
        def finish():
            if args.screenshot:
                path = Path(args.screenshot).resolve()
                path.parent.mkdir(parents=True, exist_ok=True)
                window.grab().save(str(path))
            window.quit_app()
        QTimer.singleShot(1200, finish)
    try:
        return app.exec()
    finally:
        player.close()
        lock.unlock()


if __name__ == "__main__":
    raise SystemExit(main())
