package com.voidnullvalue.icseelocal.discovery

import android.content.Context
import android.net.wifi.WifiManager

/** Real Android multicast lock, required to reliably receive broadcast beacons on some devices. */
class AndroidMulticastLockController(context: Context) : MulticastLockController {
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("icsee-local-discovery")
        .apply { setReferenceCounted(true) }

    override fun acquire() {
        if (!lock.isHeld) lock.acquire()
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
