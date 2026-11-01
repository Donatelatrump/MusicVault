import os
import shlex
import unittest
from unittest.mock import patch
from urllib.request import getproxies
from musicvault_discord.media import configure_media_proxy, ffmpeg_input_options


class MediaProxyTests(unittest.TestCase):
    def test_ytdlp_and_ffmpeg_use_same_proxy(self):
        with patch.dict(os.environ, {'MUSICVAULT_MEDIA_PROXY': 'http://127.0.0.1:40000'}, clear=True):
            configure_media_proxy()
            self.assertEqual('http://127.0.0.1:40000', getproxies()['https'])
            args = shlex.split(ffmpeg_input_options())
            self.assertEqual(getproxies()['https'], args[args.index('-http_proxy') + 1])
            self.assertIn('15000000', args)

    def test_default_has_no_media_proxy(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(configure_media_proxy())
            self.assertNotIn('-http_proxy', shlex.split(ffmpeg_input_options()))

    def test_unsupported_or_remote_proxy_rejected(self):
        for proxy in ['socks5://127.0.0.1:40000', 'http://example.com:40000',
                      'http://127.0.0.1', 'http://user:secret@127.0.0.1:40000',
                      'http://127.0.0.1:40000/not-a-proxy']:
            with self.subTest(proxy=proxy), patch.dict(os.environ, {'MUSICVAULT_MEDIA_PROXY': proxy}), self.assertRaises(ValueError):
                configure_media_proxy()
