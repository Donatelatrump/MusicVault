package com.musicvault.app.jams

import org.junit.Assert.*
import org.junit.Test

class JamInvitationTest {
    private val code = "a".repeat(32)
    @Test fun sharedRoomLinkRoundTripsToTheSameRoom() {
        assertEquals(JamInvitation.Link(JamInvitation.Kind.ROOM, code), JamInvitation.parse(JamInvitation.roomUrl(code)))
        assertEquals(code, JamInvitation.roomCode(JamInvitation.roomUrl(code)))
        assertEquals(JamInvitation.Link(JamInvitation.Kind.ROOM, code), JamInvitation.parse("musicvault://jam/$code"))
    }
    @Test fun friendLinksRemainDistinctFromRoomInvitations() {
        assertEquals(JamInvitation.Link(JamInvitation.Kind.FRIEND, code), JamInvitation.parse(JamInvitation.friendUrl(code)))
        assertEquals(JamInvitation.Link(JamInvitation.Kind.FRIEND, code), JamInvitation.parse("musicvault://friend/$code"))
        assertNull(JamInvitation.roomCode(JamInvitation.friendUrl(code)))
    }
    @Test fun untrustedHostsCredentialsAndMalformedPathsCannotTriggerActions() {
        for (link in listOf("https://evil.test/jam/$code", "https://${JamInvitation.HOST}.evil.test/jam/$code",
            "https://user@${JamInvitation.HOST}/jam/$code", "http://${JamInvitation.HOST}/jam/$code",
            "https://${JamInvitation.HOST}/jam/$code/extra", "https://${JamInvitation.HOST}/jam/short",
            "musicvault://evil/$code", "https://${JamInvitation.HOST}:9999/jam/$code")) assertNull(link, JamInvitation.parse(link))
    }
}
