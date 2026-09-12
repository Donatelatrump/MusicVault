"""File imports preserve originals; metadata and embedded covers are cached."""
from dataclasses import replace
from pathlib import Path
import os

from mutagen import File

from .models import Track, data_dir, stable_id

AUDIO_EXTENSIONS = {".mp3", ".flac", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".aiff", ".aif", ".wma", ".ape", ".wv"}


def read_track(path, cache=None):
    path = Path(path).expanduser().resolve()
    if not path.is_file() or path.suffix.lower() not in AUDIO_EXTENSIONS:
        raise ValueError(f"Archivo de audio no compatible: {path.name}")
    audio = File(path, easy=True)
    if audio is None or not getattr(audio, "info", None):
        raise ValueError(f"No se pudo leer el audio: {path.name}")
    tags = audio.tags or {}

    def tag(name, fallback):
        value = tags.get(name)
        return str(value[0]) if value else fallback

    track = Track(stable_id(str(path)), tag("title", path.stem),
                  tag("artist", "Artista desconocido"), tag("album", "Sin álbum"),
                  float(audio.info.length), str(path))
    cover_dir = Path(cache) if cache else data_dir() / "covers"
    cover_dir.mkdir(parents=True, exist_ok=True)
    try:
        raw = File(path)
        images = getattr(raw, "pictures", [])
        picture = images[0].data if images else None
        if not picture and raw.tags:
            for key, value in raw.tags.items():
                if key.startswith("APIC"):
                    picture = value.data
                    break
                if key == "covr" and value:
                    picture = bytes(value[0])
                    break
        if picture:
            dest = cover_dir / (stable_id(str(path)) + ".img")
            dest.write_bytes(picture)
            return replace(track, cover=str(dest))
    except (OSError, ValueError, AttributeError):
        pass
    for name in ("cover.jpg", "folder.jpg", "cover.png", "folder.png"):
        candidate = path.parent / name
        if candidate.is_file():
            return replace(track, cover=str(candidate))
    return track


def scan(paths, db, cancel=None, progress=None, minimum=0, explicit=False):
    imported, errors, seen = [], [], set()
    existing_paths = {t.path: t for t in db.tracks(include_removed=True) if t.path}

    def candidates():
        for path in paths:
            path = Path(path).expanduser()
            if path.is_dir():
                for root, dirs, files in os.walk(path, followlinks=False):
                    dirs[:] = [d for d in dirs if not d.startswith(".")]
                    for name in files:
                        yield Path(root) / name
            else:
                yield path

    for path in candidates():
        if cancel and cancel.is_set():
            break
        if path.suffix.lower() not in AUDIO_EXTENSIONS:
            continue
        canonical = str(path.resolve())
        if canonical in seen:
            continue
        seen.add(canonical)
        try:
            track = read_track(path)
            if track.duration < minimum:
                continue
            # Downloads can retain a catalog ID, so avoid a duplicate local ID.
            prior = existing_paths.get(canonical)
            if prior:
                track = replace(track, id=prior.id, source=prior.source, source_id=prior.source_id, url=prior.url)
            db.save_track(track, restore=explicit)
            existing_paths[canonical] = track
            imported.append(track)
            if progress:
                progress(len(imported), path.name)
        except Exception as exc:
            errors.append(f"{path.name}: {exc}")
    return imported, errors
