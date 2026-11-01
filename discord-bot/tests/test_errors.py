import unittest
from musicvault_discord.errors import playback_error_message, safe_diagnostic


class ErrorTests(unittest.TestCase):
    def test_youtube_host_block_is_actionable(self):
        error = RuntimeError("Sign in to confirm you’re not a bot. Use --cookies")
        self.assertIn("YouTube bloqueó la IP", playback_error_message(error))

    def test_diagnostics_remove_signed_urls_and_tokens(self):
        token = 'A' * 24 + '.' + 'B' * 6 + '.' + 'C' * 38
        text = safe_diagnostic(RuntimeError('https://example.test/audio?secret=123 ' + token))
        self.assertNotIn('secret', text)
        self.assertNotIn(token, text)

    def test_generic_errors_do_not_expose_provider_details(self):
        self.assertNotIn("private detail", playback_error_message(RuntimeError("private detail")))
