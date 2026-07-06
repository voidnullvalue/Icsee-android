package com.voidnullvalue.icseelocal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRateLimiterTest {

    @Test
    fun `allows up to maxAttempts then refuses within the window`() {
        var now = 0L
        // minInterval 0 so this test isolates the count limit.
        val limiter = LoginRateLimiter(windowMillis = 100_000, maxAttempts = 3, minIntervalMillis = 0, clock = { now })

        assertTrue(limiter.tryAcquire()); now += 1
        assertTrue(limiter.tryAcquire()); now += 1
        assertTrue(limiter.tryAcquire()); now += 1
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `refuses a burst even when well under the count limit`() {
        var now = 0L
        // This is the case that actually trips Ret:205: several logins bunched up.
        val limiter = LoginRateLimiter(windowMillis = 10 * 60_000, maxAttempts = 6, minIntervalMillis = 15_000, clock = { now })

        assertTrue(limiter.tryAcquire()) // t=0
        now = 1_000
        assertFalse("second login 1s later is a burst -- refused", limiter.tryAcquire())
        now = 14_999
        assertFalse("still inside the min interval", limiter.tryAcquire())
        now = 15_000
        assertTrue("once spaced far enough, allowed again", limiter.tryAcquire())
    }

    @Test
    fun `a refused attempt is not counted against either limit`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 100_000, maxAttempts = 5, minIntervalMillis = 10_000, clock = { now })

        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.countInWindow())
        // Hammered refusals must not accumulate or push out the next allowed time.
        repeat(5) { assertFalse(limiter.tryAcquire()) }
        assertEquals(1, limiter.countInWindow())
        now = 10_000
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.countInWindow())
    }

    @Test
    fun `attempts leave the window as time passes`() {
        var now = 0L
        val limiter = LoginRateLimiter(windowMillis = 1000, maxAttempts = 2, minIntervalMillis = 0, clock = { now })

        assertTrue(limiter.tryAcquire()) // t=0
        now = 500
        assertTrue(limiter.tryAcquire()) // t=500
        assertFalse(limiter.tryAcquire()) // full

        now = 1001 // t=0 attempt ages out
        assertEquals(1, limiter.countInWindow())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }
}
