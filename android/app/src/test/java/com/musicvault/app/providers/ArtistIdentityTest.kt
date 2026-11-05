package com.musicvault.app.providers

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ArtistIdentityTest {
    @Test fun choosesEstablishedExactArtistRatherThanFirstHomonym() {
        val rows = JSONArray("""[{"id":1,"name":"Queen","nb_fan":23},{"id":412,"name":"Queen","nb_fan":12000000},{"id":5,"name":"Queen Naija","nb_fan":99999999}]""")
        assertEquals(412, RelatedArtists.selectArtist(rows, "Queen")!!.getInt("id"))
    }
    @Test fun rejectsUnrelatedSearchResultsAndMatchesAccents() {
        val rows = JSONArray("""[{"id":1,"name":"Måneskin","nb_fan":100},{"id":2,"name":"Muse","nb_fan":500}]""")
        assertEquals(1, RelatedArtists.selectArtist(rows, "Maneskin")!!.getInt("id"))
        assertNull(RelatedArtists.selectArtist(rows, "Unknown"))
    }
}
