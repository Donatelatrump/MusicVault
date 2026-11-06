package com.musicvault.app.providers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudiusAudioResolverTest {
    private fun track() = JSONObject("""{"id":"Ab12","title":"Canción","user":{"name":"Artista"},"is_downloadable":true,"is_original_available":true,"orig_filename":"song.flac","access":{"download":true}}""")
    private fun choose(row: JSONObject) = AudiusAudioResolver.candidates(JSONArray().put(row), "Cancion", "ARTISTA")
    @Test fun acceptsExactTitleAndArtistWithOriginalPermission() {
        assertEquals("Ab12", choose(track()).single().id)
        assertTrue(choose(track().put("title", "Canción live")).isEmpty())
        assertTrue(choose(track().put("user", JSONObject().put("name", "Cover band"))).isEmpty())
    }
    @Test fun rejectsGatesUnavailableOriginalsAndLossyFiles() {
        assertTrue(choose(track().put("is_downloadable", false)).isEmpty())
        assertTrue(choose(track().put("is_original_available", false)).isEmpty())
        assertTrue(choose(track().put("download_conditions", JSONObject())).isEmpty())
        assertTrue(choose(track().put("access", JSONObject().put("download", false))).isEmpty())
        assertTrue(choose(track().put("orig_filename", "audio.mp3")).isEmpty())
        assertTrue(choose(track().put("id", "../other")).isEmpty())
    }
    @Test fun validatesActualAudioHeaders() {
        assertEquals("flac", AudiusAudioResolver.sniff("fLaC".toByteArray()))
        val wav = ByteArray(44)
        "RIFF0000WAVEfmt ".toByteArray().copyInto(wav)
        wav[16] = 16
        wav[20] = 1
        assertEquals("wav", AudiusAudioResolver.sniff(wav))
        wav[20] = 85 // MPEG Layer III in a WAV container is still lossy.
        assertNull(AudiusAudioResolver.sniff(wav))
        assertEquals("aiff", AudiusAudioResolver.sniff("FORM0000AIFF".toByteArray()))
        assertNull(AudiusAudioResolver.sniff("ID3mp3".toByteArray()))
        assertNull(AudiusAudioResolver.sniff("{\"error\":1}".toByteArray()))
        assertNull(AudiusAudioResolver.sniff(byteArrayOf()))
    }
}
