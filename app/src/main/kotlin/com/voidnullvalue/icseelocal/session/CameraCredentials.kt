package com.voidnullvalue.icseelocal.session

/** Never logged, displayed, or persisted outside Keystore-backed storage. */
data class CameraCredentials(
    val username: String,
    val password: String,
)
