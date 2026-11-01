package com.musicvault.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioScannerTest {
    @Test fun keepsMusicWithAudPrefix() {
        listOf("Audioslave - Like a Stone.mp3", "Audio.mp3", "AUD - Song.flac").forEach {
            assertFalse(it, LocalAudioScanner.shouldIgnoreFile(it))
        }
    }

    @Test fun keepsMusicTitlesContainingEightDigits() {
        listOf("Live 20241029.flac", "Song 12345678.mp3", "Artist 12 - Track 345678.ogg").forEach {
            assertFalse(it, LocalAudioScanner.shouldIgnoreFile(it))
        }
    }

    @Test fun stillExcludesVoiceMessagesAndNumericOnlyNames() {
        listOf("AUD-20241029-WA0001.opus", "aud_20241029_wa0002.ogg", "AUD20241029.amr",
            "2024-10-29.mp3", "20241029.mp3", "123456.ogg").forEach {
            assertTrue(it, LocalAudioScanner.shouldIgnoreFile(it))
        }
    }
}
