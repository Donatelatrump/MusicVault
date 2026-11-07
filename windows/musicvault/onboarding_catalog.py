"""Android's full genre taxonomy and round-robin paged artist discovery."""
import json
import os
from pathlib import Path
import tempfile
import time

from .experience import GENRE_OPTIONS
from .models import data_dir, stable_id


def genre_options():
    names = (Path(__file__).parent / 'assets/music_genres.txt').read_text(encoding='utf-8').splitlines()
    seen, result = set(), []
    for name in [*GENRE_OPTIONS, *names]:
        if name.strip() and name.casefold() not in seen:
            seen.add(name.casefold()); result.append(name)
    return result


ALIASES = {
    'Alternativo':'alternative rock', 'Rap en español':'spanish hip hop',
    'Reggaetón':'reggaeton', 'Regional mexicano':'regional mexican', 'Pop latino':'latin pop',
    'Salsa / Bachata':'salsa', 'Funk / Disco':'funk', 'Jazz / Soul':'jazz',
    'Clásica':'classical', 'Lo-fi / Ambient':'ambient', 'Soundtracks':'soundtrack',
}


class OnboardingCatalog:
    def __init__(self, genres):
        self.tags = list(dict.fromkeys(ALIASES.get(g, g.lower()) for g in sorted(genres)))
        self.offsets = {}
        self.exhausted = set()
        self.cursor = 0

    def next(self, cancel=None):
        from .services import get_json
        available = [t for t in self.tags if t not in self.exhausted]
        if not available or (cancel and cancel.is_set()):
            return [], False
        tag = available[self.cursor % len(available)]
        offset = self.offsets.get(tag, 0)
        cache = data_dir() / 'onboarding-artists' / (stable_id(f'{tag}:{offset}')+'.json')
        page = None
        try:
            cached = json.loads(cache.read_text(encoding='utf-8'))
            if time.time()-cache.stat().st_mtime < 30*86400:
                page = cached
        except (OSError, ValueError):
            cached = None
        if page is None:
            safe = ''.join(c for c in tag if c.isalnum() or c in ' -/&')[:100]
            try:
                page = get_json('https://musicbrainz.org/ws/2/artist/', query=f'tag:"{safe}"',
                                limit=20, offset=offset, fmt='json')
            except Exception:
                if cached is None:
                    raise
                page = cached
            if cancel and cancel.is_set():
                return [], True
            cache.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=cache.parent, mode='w', encoding='utf-8', delete=False) as out:
                json.dump(page, out); temporary=out.name
            os.replace(temporary, cache)
        rows = page.get('artists', [])
        names = [r['name'] for r in rows if r.get('name') and r['name'].lower() not in ('various artists','[unknown]','[no artist]')]
        self.offsets[tag] = offset+20
        if offset+len(rows) >= page.get('count', 0):
            self.exhausted.add(tag)
        self.cursor += 1
        return list(dict.fromkeys(names)), any(t not in self.exhausted for t in self.tags)
