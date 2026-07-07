package com.voidnullvalue.icseelocal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRateLimiterTest {

    @Test
    fun `allows up to maxAttempts then refuses within the window`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 100_000, maxAttempts = 3, clock = { now })

        assertTrue(limiter.tryAcquire()); now += 1
        assertTrue(limiter.tryAcquire()); now += 1
        assertTrue(limiter.tryAcquire()); now += 1
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `a refused attempt is not counted against the limit`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 100_000, maxAttempts = 1, clock = { now })

        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.countInWindow())
        // Hammered refusals must not accumulate.
        repeat(5) { assertFalse(limiter.tryAcquire()) }
        assertEquals(1, limiter.countInWindow())
        now = 100_000
        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.countInWindow())
    }

    @Test
    fun `attempts leave the window as time passes`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 1000, maxAttempts = 2, clock = { now })

        assertTrue(limiter.tryAcquire()) // t=0
        now = 500
        assertTrue(limiter.tryAcquire()) // t=500
        assertFalse(limiter.tryAcquire()) // full

        now = 1001 // t=0 attempt ages out
        assertEquals(1, limiter.countInWindow())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `does not artificially space back-to-back logins`() {
        // The removed behavior: back-to-back logins used to be refused even well
        // under the count limit, to avoid tripping what looked like a burst-
        // sensitive lockout. That guard is gone -- only the count matters now.
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 10 * 60_000, maxAttempts = 6, clock = { now })

        assertTrue(limiter.tryAcquire()) // t=0
        now = 1_000
        assertTrue("back-to-back logins are allowed as long as the count budget holds", limiter.tryAcquire())
    }
}
