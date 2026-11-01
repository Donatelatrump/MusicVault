import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock
from musicvault_discord.bot import MusicBot, SongPicker


class BotSelectionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.bot = MusicBot(object())
        self.bot.user_channel = Mock()
        self.player = SimpleNamespace(enqueue=Mock())
        self.bot.get_player = AsyncMock(return_value=self.player)
        self.track = SimpleNamespace(title="Song", artist="Artist")

    async def asyncTearDown(self):
        await self.bot.close()

    def interaction(self, user_id=2):
        return SimpleNamespace(guild_id=1, user=SimpleNamespace(id=user_id),
                               response=SimpleNamespace(defer=AsyncMock(), send_message=AsyncMock()),
                               followup=SimpleNamespace(send=AsyncMock()))

    async def test_double_choice_only_enqueues_once(self):
        self.bot.searches.save((1, 2), [self.track])
        results = await asyncio.gather(self.bot.choose_song(self.interaction(), 1),
                                       self.bot.choose_song(self.interaction(), 1), return_exceptions=True)
        self.player.enqueue.assert_called_once_with(self.track)
        self.assertEqual(1, sum(isinstance(x, ValueError) for x in results))

    async def test_menu_rejects_other_users(self):
        result = self.bot.searches.save((1, 2), [self.track])
        picker = SongPicker(self.bot, (1, 2), result)
        self.assertFalse(await picker.interaction_check(self.interaction(user_id=3)))
        self.player.enqueue.assert_not_called()
        picker.stop()

    async def test_selection_stays_available_if_queue_is_full(self):
        self.bot.searches.save((1, 2), [self.track])
        self.player.enqueue.side_effect = ValueError("Cola llena")
        with self.assertRaises(ValueError):
            await self.bot.choose_song(self.interaction(), 1)
        self.assertIsNotNone(self.bot.searches.get((1, 2), 1))

    async def test_publishes_global_commands_and_immediate_guild_commands(self):
        from unittest.mock import patch
        self.bot.tree.sync = AsyncMock()
        self.bot.tree.copy_global_to = Mock()
        with patch.dict("os.environ", {"DISCORD_GUILD_ID": "868606906707640330"}):
            await self.bot.setup_hook()
        self.assertEqual(2, self.bot.tree.sync.await_count)
        self.assertEqual({}, self.bot.tree.sync.await_args_list[0].kwargs)
        self.bot.tree.copy_global_to.assert_called_once()
