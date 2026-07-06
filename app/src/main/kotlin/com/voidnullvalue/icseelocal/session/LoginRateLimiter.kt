package com.voidnullvalue.icseelocal.session

/**
 * Ceiling on how often, and how fast, the app authenticates to one camera. This
 * firmware counts login *rate* -- not just failed passwords -- toward its Ret:205
 * temporary lockout, and it punishes *bursts* especially: a handful of logins in a
 * few seconds trips it even though that's a tiny number over any longer window. So
 * this enforces two independent limits:
 *
 *  - a rolling-window **count** ([maxAttempts] within [windowMillis]), and
 *  - a **minimum spacing** between any two logins ([minIntervalMillis]).
 *
 * The spacing limit is the one that actually matters against a lockout: without it,
 * a run of quick reconnects/retries could fire the whole window's worth of logins
 * back to back in a few seconds, which is exactly the shape the camera locks on.
 * (The app no longer auto-reconnects at all -- see CameraSessionManager -- but this
 * spacing floor still guards manual reconnect mashing and any future retry path.)
 * Both limits have to span every code path that can log in
 * (live view, device management, credential-change verification) AND survive a
 * [CameraSessionManager] being torn down and rebuilt -- which is why this is a
 * standalone object [CameraSessionRegistry] keeps one of per camera for the life of
 * the app and hands to every manager it builds for that camera.
 *
 * Thread-safe: [tryAcquire] is called from lifecycle callbacks (main thread) and
 * from the manager's IO reconnect coroutine.
 */
class LoginRateLimiter(
    val windowMillis: Long = 10 * 60_000L,
    val maxAttempts: Int = 4,
    val minIntervalMillis: Long = 15_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attempts = ArrayDeque<Long>()
    // Null until the first successful acquire, so the spacing check is simply skipped
    // the first time (rather than doing arithmetic against a sentinel, which can
    // overflow and wrongly refuse the very first login).
    private var lastAttempt: Long? = null

    /**
     * Records a login attempt and returns true if it is within budget -- i.e. under
     * the window count AND far enough after the previous login. When it returns
     * false the caller must NOT log in; a refused attempt is not recorded, so
     * declining costs nothing against either limit (unlike actually logging in,
     * which is what the camera is counting).
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        prune(now)
        if (attempts.size >= maxAttempts) return false
        val previous = lastAttempt
        if (previous != null && now - previous < minIntervalMillis) return false
        attempts.addLast(now)
        lastAttempt = now
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
