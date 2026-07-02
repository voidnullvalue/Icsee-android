package com.voidnullvalue.icseelocal.model

enum class StreamType { MAIN, SUB }

/**
 * A camera the app knows about, either saved locally or freshly discovered.
 * Discovery metadata fields are null for manually-added cameras.
 */
data class CameraDescriptor(
    val id: String,
    val displayName: String,
    val host: String,
    val dvripPort: Int = 34567,
    val channel: Int = 0,
    val streamType: StreamType = StreamType.MAIN,
    val rtspFallbackEnabled: Boolean = false,
    val rtspPort: Int = 554,
    val mac: String? = null,
    val serialNumber: String? = null,
    val pid: String? = null,
    val firmwareVersion: String? = null,
    val httpPort: Int? = null,
    val sslPort: Int? = null,
    val udpPort: Int? = null,
) {
    companion object {
        /** Stable identity for dedup purposes: prefer MAC, then serial, then host. */
        fun identityKey(mac: String?, serialNumber: String?, host: String): String =
            mac?.takeIf { it.isNotBlank() }
                ?: serialNumber?.takeIf { it.isNotBlank() }
                ?: host
    }
}
