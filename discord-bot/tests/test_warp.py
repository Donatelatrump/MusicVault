import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import URLError
from musicvault_discord.warp import WarpProxy


class WarpStartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        (self.directory / 'bin').mkdir()
        (self.directory / 'bin/wireproxy').touch()
        (self.directory / 'wireproxy.conf').write_text('test config')
        self.process = Mock()
        self.process.poll.return_value = None
        self.process.wait.return_value = 0

    def tearDown(self):
        self.temp.cleanup()

    def test_refuses_unverified_egress_and_stops_child(self):
        opener = Mock()
        opener.open.side_effect = lambda *a, **k: io.BytesIO(b'warp=off\n')
        proxy = WarpProxy(self.directory)
        with patch('musicvault_discord.warp.subprocess.Popen', return_value=self.process), patch('musicvault_discord.warp.build_opener', return_value=opener):
            with self.assertRaisesRegex(RuntimeError, 'no confirmó'):
                proxy.__enter__()
        self.process.terminate.assert_called_once()
        self.assertTrue(proxy.log.closed)

    def test_unreachable_proxy_is_cleaned_up(self):
        opener = Mock()
        opener.open.side_effect = URLError('connection refused')
        proxy = WarpProxy(self.directory)
        with patch('musicvault_discord.warp.subprocess.Popen', return_value=self.process), patch('musicvault_discord.warp.build_opener', return_value=opener), patch('musicvault_discord.warp.time.sleep'):
            with self.assertRaises(RuntimeError):
                proxy.__enter__()
        self.process.terminate.assert_called_once()

    def test_confirmed_warp_starts_supervision(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(b'warp=on\nloc=FR\n')
        with patch('musicvault_discord.warp.subprocess.Popen', return_value=self.process), patch('musicvault_discord.warp.build_opener', return_value=opener), patch('musicvault_discord.warp.threading.Thread') as thread:
            with WarpProxy(self.directory):
                thread.return_value.start.assert_called_once()
        self.process.terminate.assert_called_once()

    def test_proxy_exit_stops_parent(self):
        proxy = WarpProxy(self.directory)
        proxy.process = self.process
        with patch('musicvault_discord.warp.os.kill') as kill:
            proxy._watch()
        kill.assert_called_once()
