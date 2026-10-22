package com.musicvault.app.providers

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class ProviderErrorsTest {
    @Test fun offlineOffersReconnect() {
        val failure = ProviderErrors.classify(UnknownHostException("private-host"))
        assertEquals(ProviderErrorKind.OFFLINE, failure.kind)
        assertEquals(ProviderAction.RECONNECT, failure.action)
        assertFalse(failure.message.contains("private-host"))
    }
    @Test fun httpFailuresHaveSpecificActions() {
        assertEquals(ProviderAction.WAIT, ProviderErrors.classify(Exception("429"), 429).action)
        assertEquals(ProviderAction.RECONNECT, ProviderErrors.classify(Exception("401"), 401).action)
        assertEquals(ProviderAction.RETRY, ProviderErrors.classify(Exception("403"), 403).action)
        assertEquals(ProviderAction.CHOOSE_ANOTHER, ProviderErrors.classify(Exception("404"), 404).action)
        assertEquals(ProviderAction.UPDATE_EXTRACTOR, ProviderErrors.classify(Exception("nsig extraction failed")).action)
    }
    @Test fun cancellationIsPreserved() {
        val cancellation = CancellationException("stop")
        try { ProviderErrors.classify(cancellation); fail("Cancellation swallowed") }
        catch (caught: CancellationException) { assertSame(cancellation, caught) }
    }
    @Test fun wrappedCancellationIsPreserved() {
        val cancellation = CancellationException("stop")
        try { ProviderErrors.classify(IllegalStateException("wrapper", cancellation)); fail("Cancellation swallowed") }
        catch (caught: CancellationException) { assertSame(cancellation, caught) }
    }
}
