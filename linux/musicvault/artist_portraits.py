"""Optional artist photos using the same exact-name Deezer match as Android."""
import json
import tempfile
import os
import unicodedata
from urllib.parse import urlencode

from .models import data_dir, stable_id


def artist_key(name):
    return ''.join(c for c in unicodedata.normalize('NFD', name.casefold().strip())
                   if not unicodedata.combining(c))


def select_artist(rows, name):
    exact = [r for r in rows if artist_key(r.get('name', '')) == artist_key(name)]
    return max(exact, key=lambda r: r.get('nb_fan', 0), default=None)


def portrait(name, cancel=None):
    from .services import get_bytes, cover_file
    if cancel and cancel.is_set():
        return ''
    metadata = data_dir() / 'artist-portraits' / (stable_id(artist_key(name)) + '.json')
    photo = ''
    try:
        cached = json.loads(metadata.read_text(encoding='utf-8'))
        photo = cached.get('photo', '')
        if photo:
            from .artwork import candidates, valid
            path = data_dir() / 'covers' / (stable_id(candidates(photo)[0]) + '.img')
            if path.exists() and valid(path):
                return str(path)
    except (OSError, ValueError):
        pass
    if not photo:
        url = 'https://api.deezer.com/search/artist?' + urlencode({'q': name, 'limit': 50})
        rows = json.loads(get_bytes(url)).get('data', [])
        artist = select_artist(rows, name)
        if not artist:
            return ''
        photo = next((artist.get(k) for k in ('picture_big', 'picture_medium', 'picture')
                      if str(artist.get(k, '')).startswith('https://')), '')
    if not photo or (cancel and cancel.is_set()):
        return ''
    path = cover_file(photo)
    metadata.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=metadata.parent, mode='w', encoding='utf-8', delete=False) as out:
        json.dump({'photo': photo}, out)
        temporary = out.name
    os.replace(temporary, metadata)
    return path
