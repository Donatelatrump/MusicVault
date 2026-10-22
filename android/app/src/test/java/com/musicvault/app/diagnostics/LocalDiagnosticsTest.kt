package com.musicvault.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class LocalDiagnosticsTest {
    @Test fun redactsSignedUrlsAndCredentials() {
        val result = LocalDiagnostics.redact("failed https://googlevideo.com/audio?sig=secret Authorization: bearer-value cookie=session123 token=abc")
        assertFalse(result.contains("googlevideo"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("bearer-value"))
        assertFalse(result.contains("session123"))
        assertFalse(result.contains("abc"))
    }
}
