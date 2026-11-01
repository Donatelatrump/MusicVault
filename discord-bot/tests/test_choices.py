import unittest
from unittest.mock import patch
from musicvault_discord.choices import Searches


class ChoiceTests(unittest.TestCase):
    def test_selection_is_consumed_once(self):
        choices = Searches()
        result = choices.save((1, 2), ["first", "second"])
        self.assertEqual("second", choices.consume((1, 2), 2, result))
        with self.assertRaises(ValueError):
            choices.consume((1, 2), 2, result)

    def test_results_are_scoped_to_guild_and_user(self):
        choices = Searches()
        result = choices.save((1, 2), ["song"])
        for key in [(2, 2), (1, 3)]:
            with self.assertRaises(ValueError):
                choices.get(key, 1, result)

    def test_new_search_invalidates_old_menu(self):
        choices = Searches()
        old = choices.save((1, 2), ["old"])
        new = choices.save((1, 2), ["new"])
        with self.assertRaises(ValueError):
            choices.consume((1, 2), 1, old)
        self.assertEqual("new", choices.consume((1, 2), 1, new))

    def test_expired_search_cannot_be_selected(self):
        choices = Searches()
        with patch('musicvault_discord.choices.time.monotonic', return_value=10):
            result = choices.save((1, 2), ["song"])
        with patch('musicvault_discord.choices.time.monotonic', return_value=131), self.assertRaises(ValueError):
            choices.get((1, 2), 1, result)

    def test_invalid_number_keeps_results_available(self):
        choices = Searches()
        result = choices.save((1, 2), ["song"])
        with self.assertRaises(ValueError):
            choices.consume((1, 2), 2, result)
        self.assertEqual("song", choices.consume((1, 2), 1, result))
