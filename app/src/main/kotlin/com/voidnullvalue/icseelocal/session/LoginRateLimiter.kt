package com.voidnullvalue.icseelocal.session

/**
 * Ceiling on how often the app authenticates to one camera: a rolling-window
 * **count** ([maxAttempts] within [windowMillis]). This was originally paired with
 * a minimum-spacing guard between logins, added while Ret:205 looked like a pure
 * login-rate lockout -- but live testing (2026-07-07, see
 * `[[project-icsee-password-change]]`) points to Ret:205 actually being the
 * firmware time-expiring the `admin`/blank backdoor specifically, not a rate limit
 * at all. The forced spacing bought nothing but slower reconnects, so it's gone;
 * this window count remains as a cheap backstop against any code path that ends up
 * looping logins unboundedly.
 *
 * Has to span every code path that can log in (live view, device management,
 * credential-change verification) AND survive a [CameraSessionManager] being torn
 * down and rebuilt -- which is why this is a standalone object
 * [CameraSessionRegistry] keeps one of per camera for the life of the app and
 * hands to every manager it builds for that camera.
 *
 * Thread-safe: [tryAcquire] is called from lifecycle callbacks (main thread) and
 * from the manager's IO reconnect coroutine.
 */
class LoginRateLimiter(
    val windowMillis: Long = 10 * 60_000L,
    val maxAttempts: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attempts = ArrayDeque<Long>()

    /**
     * Records a login attempt and returns true if it is within the window budget.
     * When it returns false the caller must NOT log in; a refused attempt is not
     * recorded, so declining costs nothing against the limit (unlike actually
     * logging in, which is what the camera is counting).
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        prune(now)
        if (attempts.size >= maxAttempts) return false
        attempts.addLast(now)
        return true
    }

    /** Number of logins currently inside the window -- for diagnostics / messaging only. */
    @Synchronized
    fun countInWindow(): Int {
        prune(clock())
        return attempts.size
    }

    private fun prune(now: Long) {
        val cutoff = now - windowMillis
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
    }
}
