"""Portable dependency discovery and transactional relocation of managed files."""
from ctypes.util import find_library
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
_DLL_HANDLES = []


def configure():
    runtime = ROOT / "runtime"
    os.environ["PATH"] = str(runtime) + os.pathsep + os.environ.get("PATH", "")
    if sys.platform == "win32" and runtime.is_dir():
        _DLL_HANDLES.append(os.add_dll_directory(str(runtime)))


def mpv_library():
    if sys.platform == "win32":
        for name in ("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll"):
            path = ROOT / "runtime" / name
            if path.is_file():
                return str(path)
        return None
    return find_library("mpv")  # Allows the shared engine tests on Linux.


def relocate_library(database):
    from .models import data_dir
    current = str(data_dir().resolve())
    previous = database.setting("portable_data_root")
    if previous and previous != current:
        prefix = previous.replace("\\", "/").rstrip("/") + "/"
        def move(value):
            if isinstance(value, str):
                normalized = value.replace("\\", "/")
                if normalized.casefold().startswith(prefix.casefold()):
                    return str(Path(current).joinpath(*normalized[len(prefix):].split("/")))
            elif isinstance(value, dict):
                return {key: move(item) for key, item in value.items()}
            elif isinstance(value, list):
                return [move(item) for item in value]
            return value
        with database.connect() as db:
            for table, key, column in (("tracks", "id", "payload"), ("downloads", "track_id", "payload"),
                                       ("settings", "key", "value"), ("cache", "key", "value")):
                for row in db.execute(f"SELECT {key}, {column} FROM {table}").fetchall():
                    value = move(json.loads(row[1]))
                    db.execute(f"UPDATE {table} SET {column}=? WHERE {key}=?", (json.dumps(value), row[0]))
            for row in db.execute("SELECT id,cover FROM collections").fetchall():
                db.execute("UPDATE collections SET cover=? WHERE id=?", (move(row[1]), row[0]))
            db.execute("INSERT OR REPLACE INTO settings VALUES (?,?)", ("portable_data_root", json.dumps(current)))
    else:
        database.set_setting("portable_data_root", current)
