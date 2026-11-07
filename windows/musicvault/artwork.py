"""Validated artwork downloads, with high resolution YouTube thumbnails first."""
import os
from pathlib import Path
import tempfile
from urllib.parse import urlsplit

from PyQt6.QtGui import QImageReader

from .models import data_dir, stable_id


def candidates(source):
    source = 'https:' + source if source.startswith('//') else source.replace('http://', 'https://', 1)
    parsed = urlsplit(source)
    parts = parsed.path.split('/')
    if parsed.hostname in ('i.ytimg.com', 'img.youtube.com') and len(parts) >= 4 and parts[1] in ('vi', 'vi_webp'):
        base = 'https://i.ytimg.com/vi/' + parts[2]
        return list(dict.fromkeys([base+'/maxresdefault.jpg', source, base+'/hqdefault.jpg']))
    return [source]


def valid(path):
    reader = QImageReader(str(path))
    reader.setDecideFormatFromContent(True)
    return not reader.read().isNull()


def cover_file(source):
    if not source or not source.startswith(('https://', 'http://', '//')):
        return source
    # Lazy import avoids the services/artwork circular dependency and allows the
    # same transport (and controlled transport in tests) on both platforms.
    from .services import get_bytes
    urls = candidates(source)
    path = data_dir() / 'covers' / (stable_id(urls[0]) + '.img')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and valid(path):
        return str(path)
    last_error = None
    for url in urls:
        temporary = None
        try:
            body = get_bytes(url)
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.cover-', delete=False) as output:
                temporary = Path(output.name)
                output.write(body)
            if not valid(temporary):
                raise ValueError('El proveedor no devolvió una imagen válida.')
            # Some YouTube endpoints return a tiny generic image with HTTP 200.
            if '/maxresdefault.jpg' in url and len(urlsplit(url).path.split('/')[2]) == 11:
                size = QImageReader(str(temporary)).size()
                if size.width() <= 120:
                    raise ValueError('La imagen de alta resolución no está disponible.')
            os.replace(temporary, path)
            return str(path)
        except Exception as error:
            last_error = error
        finally:
            if temporary:
                temporary.unlink(missing_ok=True)
    if path.exists() and not valid(path):
        path.unlink(missing_ok=True)
    raise last_error or ValueError('No se pudo obtener una imagen válida.')
