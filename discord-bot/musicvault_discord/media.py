"""Route media extraction and FFmpeg through the same loopback HTTP proxy."""
import os
import shlex
from urllib.parse import urlsplit


def media_proxy():
    value = os.environ.get('MUSICVAULT_MEDIA_PROXY', '').strip()
    if not value:
        return None
    parsed = urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1')
            or not parsed.port or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise ValueError('MUSICVAULT_MEDIA_PROXY debe ser un proxy HTTP local con puerto.')
    return value


def configure_media_proxy():
    proxy = media_proxy()
    if proxy:
        # yt-dlp uses urllib.request.getproxies() when --proxy is not specified.
        # discord.py uses its own aiohttp transport, without trust_env.
        for name in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY'):
            os.environ[name] = proxy
        for name in ('no_proxy', 'NO_PROXY'):
            os.environ[name] = '127.0.0.1,localhost,::1'
    return proxy


def ffmpeg_input_options():
    options = ['-nostdin', '-reconnect', '1', '-reconnect_streamed', '1',
               '-reconnect_delay_max', '5', '-rw_timeout', '15000000']
    proxy = media_proxy()
    if proxy:
        options += ['-http_proxy', proxy]
    return shlex.join(options)
