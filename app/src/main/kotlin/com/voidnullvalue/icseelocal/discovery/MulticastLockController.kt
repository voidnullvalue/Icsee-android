package com.voidnullvalue.icseelocal.discovery

/** Abstraction over Android's WifiManager multicast lock so discovery logic is unit-testable. */
interface MulticastLockController {
    fun acquire()
    fun release()
}

object NoOpMulticastLockController : MulticastLockController {
    override fun acquire() = Unit
    override fun release() = Unit
}
