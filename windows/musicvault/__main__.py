import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import sys

from . import __version__
from .portable import configure, mpv_library, relocate_library


def doctor():
    from .downloader import downloader_command, javascript_runtime
    checks = {"windows_x86_64": sys.platform == "win32" and platform.machine().lower() in ("x86_64", "amd64"),
              "python": platform.python_version(), "libmpv": mpv_library()}
    for module in ("PyQt6", "mutagen"):
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
    configure()
    parser = argparse.ArgumentParser(description="MusicVault para Windows x64")
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
    if sys.platform != "win32" or platform.machine().lower() not in ("x86_64", "amd64"):
        parser.error("Esta distribución está preparada para Windows x64 (64 bits).")
    try:
        # Qt WebEngine must be imported before QApplication is constructed.
        # Importing it lazily from the Spotify dialog is too late on Qt 6.10+.
        from PyQt6 import QtWebEngineWidgets as _qt_webengine_widgets
        from PyQt6.QtCore import QTimer, QLockFile, QUrl
        from PyQt6.QtWidgets import QApplication, QMessageBox
        from .database import Database
        from .models import data_dir
        from .player import Player
        from .window import Window
    except ImportError as exc:
        print(f"Falta una dependencia: {exc}. Ejecuta el lanzador con --doctor (Diagnostico.cmd desde la carpeta portable) y consulta README.md.", file=sys.stderr)
        return 1
    app = QApplication([sys.argv[0]])
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName("MusicVault")
    app.setApplicationVersion(__version__)
    app.setOrganizationName("MusicVault")
    app.setDesktopFileName("com.musicvault.MusicVault")
    lock = QLockFile(str(data_dir() / "musicvault.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        QMessageBox.information(None, "MusicVault", "MusicVault ya está abierto para esta biblioteca.")
        return 0
    player = None
    try:
        db = Database()
        relocate_library(db)
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
        from .media_keys import MediaKeys
        window.mpris = MediaKeys(app, player)
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
