import os
import tempfile
import threading
import unittest
from unittest.mock import patch
from musicvault_discord.provider import MusicProvider


class ProviderTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.environment = patch.dict(os.environ, {"MUSICVAULT_BOT_DATA_DIR": self.directory.name})
        self.environment.start()
        self.provider = MusicProvider()
        self.cancel = threading.Event()

    def tearDown(self):
        self.environment.stop()
        self.directory.cleanup()

    def test_existing_musicvault_engine_and_isolated_data(self):
        self.assertEqual("musicvault.streaming", type(self.provider.streaming).__module__)
        self.assertTrue(str(self.provider.streaming.db.path).startswith(self.directory.name))

    def test_youtube_links(self):
        for url in ["https://youtu.be/abcdefghijk", "https://youtube.com/watch?v=abcdefghijk&list=abc",
                    "https://music.youtube.com/watch?v=abcdefghijk", "https://youtube.com/shorts/abcdefghijk"]:
            self.assertEqual("abcdefghijk", self.provider.search(url, self.cancel).source_id)

    def test_rejects_unsupported_and_malformed_urls(self):
        for url in ["https://example.com/a.mp3", "file:///etc/passwd", "https://youtube.com.evil.test/watch?v=abcdefghijk",
                    "http://youtube.com/watch?v=abcdefghijk", "https://youtube.com/watch?v=short"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.provider.search(url, self.cancel)

    def test_search_uses_musicvault(self):
        expected = object()
        with patch.object(self.provider, "search_youtube", return_value=[expected]) as search:
            self.assertIs(expected, self.provider.search("Artist Song", self.cancel))
            search.assert_called_once_with("Artist Song", self.cancel, limit=1)

    def test_empty_results_and_empty_queries(self):
        with patch.object(self.provider, "search_youtube", return_value=[]), self.assertRaises(ValueError):
            self.provider.search("Missing", self.cancel)
        for value in [" ", "x" * 301]:
            with self.assertRaises(ValueError):
                self.provider.search(value, self.cancel)

    def test_search_many_returns_five_choices(self):
        tracks = [object() for _ in range(5)]
        with patch.object(self.provider, "search_youtube", return_value=tracks) as search:
            self.assertEqual(tracks, self.provider.search_many("Artist Song", self.cancel))
            search.assert_called_once_with("Artist Song", self.cancel, limit=5)
