package com.voidnullvalue.icseelocal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `first attempt has no delay`() {
        val backoff = ReconnectBackoff()
        assertEquals(0, backoff.delayForAttempt(0))
    }

    @Test
    fun `default reconnects are spaced out to avoid Ret205 login bursts`() {
        // Each reconnect is a real login and this firmware locks on rapid bursts, so
        // the very first retry must already be many seconds out (not sub-second).
        val backoff = ReconnectBackoff()
        assertTrue(
            "first reconnect must wait >=15s so a drop can't machine-gun logins",
            backoff.delayForAttempt(1) >= 15_000,
        )
    }

    @Test
    fun `delay grows exponentially and is bounded by the maximum`() {
        val backoff = ReconnectBackoff(baseDelayMillis = 1000, maxDelayMillis = 10_000, factor = 2.0)
        assertEquals(1000, backoff.delayForAttempt(1))
        assertEquals(2000, backoff.delayForAttempt(2))
        assertEquals(4000, backoff.delayForAttempt(3))
        assertEquals(8000, backoff.delayForAttempt(4))
        assertEquals(10_000, backoff.delayForAttempt(5)) // would be 16000, clamped
        assertEquals(10_000, backoff.delayForAttempt(20)) // stays clamped, never grows unbounded
    }

    @Test
    fun `never exceeds the configured maximum regardless of attempt count`() {
        val backoff = ReconnectBackoff(baseDelayMillis = 500, maxDelayMillis = 5000)
        for (attempt in 0..100) {
            assertTrue(backoff.delayForAttempt(attempt) <= 5000)
        }
    }
}
