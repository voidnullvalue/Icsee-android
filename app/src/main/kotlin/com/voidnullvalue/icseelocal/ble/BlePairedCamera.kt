package com.voidnullvalue.icseelocal.ble

/** What a successful BLE pairing hands off to the "add camera" flow. */
data class BlePairedCamera(
    val host: String,
    val username: String,
    val password: String,
    val mac: String?,
    val xkfuUsername: String? = null,  // Real account (created during factory reset)
    val xkfuPassword: String? = null,   // Decrypted from camera
)
