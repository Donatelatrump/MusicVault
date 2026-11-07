from PyQt6.QtGui import QImage
import pytest

from musicvault.artwork import cover_file, candidates
from musicvault.models import data_dir


def test_youtube_requests_full_resolution_even_for_cached_search_thumbnail(tmp_path, monkeypatch):
    image = tmp_path / 'large.png'
    assert QImage(1280, 720, QImage.Format.Format_RGB32).save(str(image))
    calls = []
    monkeypatch.setattr('musicvault.services.get_bytes', lambda url: calls.append(url) or image.read_bytes())
    source = 'https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg'
    path = cover_file(source)
    assert calls == ['https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg']
    assert QImage(path).width() == 1280
    assert cover_file(source) == path
    assert len(calls) == 1


def test_youtube_placeholder_falls_back_to_real_artwork(tmp_path, monkeypatch):
    small, large = tmp_path / 'small.png', tmp_path / 'large.png'
    assert QImage(120, 90, QImage.Format.Format_RGB32).save(str(small))
    assert QImage(480, 360, QImage.Format.Format_RGB32).save(str(large))
    calls = []
    def fetch(url):
        calls.append(url)
        return (small if 'maxres' in url else large).read_bytes()
    monkeypatch.setattr('musicvault.services.get_bytes', fetch)
    path = cover_file('https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg')
    assert QImage(path).width() == 480
    assert len(calls) == 2


def test_failed_download_can_retry_and_leaves_no_temporary_files(tmp_path, monkeypatch):
    monkeypatch.setattr('musicvault.services.get_bytes', lambda url: b'not an image')
    with pytest.raises(ValueError, match='imagen'):
        cover_file('https://example.test/cover.png')
    assert list((data_dir() / 'covers').iterdir()) == []
    image = tmp_path / 'valid.png'
    assert QImage(600, 600, QImage.Format.Format_RGB32).save(str(image))
    monkeypatch.setattr('musicvault.services.get_bytes', lambda url: image.read_bytes())
    assert QImage(cover_file('https://example.test/cover.png')).width() == 600


def test_protocol_relative_and_webp_urls_keep_fallback():
    urls = candidates('//i.ytimg.com/vi_webp/abcdefghijk/mqdefault.webp')
    assert urls[0].endswith('/maxresdefault.jpg')
    assert urls[1].startswith('https://')
    assert urls[-1].endswith('/hqdefault.jpg')
