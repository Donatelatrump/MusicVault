from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib
import os
import re


@dataclass(frozen=True)
class Track:
    id: str
    title: str
    artist: str = "Artista desconocido"
    album: str = "Sin álbum"
    duration: float = 0
    path: str = ""
    cover: str = ""
    source: str = "local"
    source_id: str = ""
    url: str = ""

    def to_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, data):
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


def stable_id(value):
    return hashlib.sha256(value.encode("utf-8", errors="surrogateescape")).hexdigest()[:32]


def data_dir():
    root = os.environ.get("MUSICVAULT_DATA_DIR")
    path = Path(root) if root else Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "musicvault"
    path.mkdir(parents=True, exist_ok=True)
    return path


def clock_text(seconds):
    seconds = max(0, int(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


def parse_lrc(raw):
    pattern = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
    offset = re.search(r"\[offset:([+-]?\d+)\]", raw, re.I)
    shift = int(offset[1]) / 1000 if offset else 0
    lines = []
    for line in raw.splitlines():
        stamps = list(pattern.finditer(line))
        if not stamps:
            continue
        text = line[stamps[-1].end():].strip()
        for stamp in stamps:
            fraction = float("0." + stamp[3]) if stamp[3] else 0
            lines.append((max(0, int(stamp[1]) * 60 + int(stamp[2]) + fraction + shift), text))
    return sorted(lines, key=lambda line: line[0])
