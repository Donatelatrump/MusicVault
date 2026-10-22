package com.musicvault.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenBrainzTest {
    @Test fun thresholdUsesHalfTheSongWithFourMinuteCap() {
        assertEquals(90_000L, ListenBrainz.listenThreshold(180_000))
        assertEquals(240_000L, ListenBrainz.listenThreshold(600_000))
        assertEquals(240_000L, ListenBrainz.listenThreshold(0))
    }
}
