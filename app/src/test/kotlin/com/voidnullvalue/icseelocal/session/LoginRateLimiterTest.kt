package com.voidnullvalue.icseelocal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRateLimiterTest {

    @Test
    fun `allows up to maxAttempts then refuses within the window`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 1000, maxAttempts = 3, clock = { now })

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        // Fourth in the same window is refused.
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `a refused attempt is not counted against the window`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 1000, maxAttempts = 1, clock = { now })

        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.countInWindow())
        // Refused calls must not accumulate -- declining costs nothing.
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
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

        // Past t=1000 the first attempt (t=0) ages out, freeing one slot.
        now = 1001
        assertEquals(1, limiter.countInWindow())
        assertTrue(limiter.tryAcquire())
        // Still one from t=500 plus the new one at t=1001 -> full again.
        assertFalse(limiter.tryAcquire())
    }
}
