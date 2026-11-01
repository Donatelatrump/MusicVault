import asyncio
import threading
from types import SimpleNamespace
import unittest
from musicvault_discord.player import GuildPlayer


class Voice:
    def __init__(self):
        self.after = None
        self.played = []
        self.disconnected = False

    def is_connected(self):
        return not self.disconnected

    def play(self, source, after):
        self.played.append(source)
        self.after = after

    def stop(self):
        if self.after:
            callback, self.after = self.after, None
            callback(None)

    async def disconnect(self, force=False):
        self.disconnected = True


class Provider:
    def resolve(self, song, cancel):
        if song.title == "broken":
            raise RuntimeError("private provider URL")
        return song.title


def song(title):
    return SimpleNamespace(title=title, artist="Artist")


async def until(predicate):
    async def wait():
        while not predicate():
            await asyncio.sleep(0.001)
    await asyncio.wait_for(wait(), 2)


class PlayerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.voice = Voice()
        self.messages = []
        async def notify(message):
            self.messages.append(message)
        self.player = GuildPlayer(self.voice, Provider(), lambda url: url, notify)

    async def asyncTearDown(self):
        await self.player.close()

    async def test_advances_and_ignores_late_callback(self):
        self.player.enqueue(song("first"))
        self.player.enqueue(song("second"))
        await until(lambda: len(self.voice.played) == 1)
        callback = self.voice.after
        thread = threading.Thread(target=self.voice.stop)
        thread.start()
        thread.join()
        await until(lambda: len(self.voice.played) == 2)
        callback(None)
        await asyncio.sleep(0.01)
        self.assertEqual("second", self.player.current.title)
        self.voice.stop()
        await asyncio.wait_for(self.player.queue.join(), 2)

    async def test_failed_song_does_not_block_next(self):
        self.player.enqueue(song("broken"))
        self.player.enqueue(song("good"))
        await until(lambda: self.voice.played == ["good"])
        self.assertTrue(any("No pude" in message for message in self.messages))
        self.assertFalse(any("private" in message for message in self.messages))

    async def test_skip_cancels_resolution(self):
        entered = threading.Event()
        def resolve(track, cancel):
            if track.title == "slow":
                entered.set()
                cancel.wait(2)
            return track.title
        self.player.provider.resolve = resolve
        self.player.enqueue(song("slow"))
        self.player.enqueue(song("next"))
        await until(entered.is_set)
        self.player.skip()
        await until(lambda: self.voice.played == ["next"])

    async def test_idle_disconnect(self):
        await self.player.close()
        self.player = GuildPlayer(self.voice, Provider(), lambda url: url, self.player.notify, idle_timeout=0.01)
        await asyncio.wait_for(self.player.task, 2)
        self.assertTrue(self.voice.disconnected)
        with self.assertRaises(ValueError):
            self.player.enqueue(song("late"))

    async def test_queue_limit_and_close_clears_queue(self):
        for i in range(100):
            self.player.enqueue(song(str(i)))
        with self.assertRaises(ValueError):
            self.player.enqueue(song("overflow"))
        await self.player.close()
        self.assertEqual([], self.player.pending())

    async def test_separate_sessions(self):
        voice = Voice()
        other = GuildPlayer(voice, Provider(), lambda url: url, self.player.notify)
        try:
            self.player.enqueue(song("one"))
            other.enqueue(song("two"))
            await until(lambda: self.voice.played and voice.played)
            self.player.skip()
            self.assertEqual(["two"], voice.played)
            self.assertFalse(other.closed)
        finally:
            await other.close()
