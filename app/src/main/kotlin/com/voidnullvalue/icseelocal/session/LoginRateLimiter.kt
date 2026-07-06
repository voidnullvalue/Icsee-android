package com.voidnullvalue.icseelocal.session

/**
 * Rolling-window ceiling on DVRIP logins for one camera. This firmware counts
 * login *rate* -- not just failed passwords -- toward its Ret:205 temporary
 * lockout, so the app must hold a hard cap on how often it authenticates, and
 * that cap has to span every code path that can log in (live view, device
 * management, credential-change verification, discovery) AND survive a
 * [CameraSessionManager] being torn down and rebuilt. That is exactly why this
 * is a standalone object rather than per-manager state: [CameraSessionRegistry]
 * keeps one per camera for the life of the app, and hands the same instance to
 * every manager it builds for that camera, so the budget is genuinely global.
 *
 * Thread-safe: [tryAcquire] is called from lifecycle callbacks (main thread) and
 * from the manager's IO reconnect coroutine.
 */
class LoginRateLimiter(
    val windowMillis: Long = 10 * 60_000L,
    val maxAttempts: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attempts = ArrayDeque<Long>()

    /**
     * Records a login attempt and returns true if it is within budget. When it
     * returns false the caller must NOT log in -- a refused attempt is *not*
     * recorded, so declining costs nothing against the window (unlike actually
     * logging in, which is what the camera is counting).
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        prune()
        if (attempts.size >= maxAttempts) return false
        attempts.addLast(clock())
        return true
    }

    /** Number of logins currently inside the window -- for diagnostics / messaging only. */
    @Synchronized
    fun countInWindow(): Int {
        prune()
        return attempts.size
    }

    private fun prune() {
        val cutoff = clock() - windowMillis
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
    }
}
