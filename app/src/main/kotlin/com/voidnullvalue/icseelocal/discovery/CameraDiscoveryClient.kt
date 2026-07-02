package com.voidnullvalue.icseelocal.discovery

import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Passive-with-a-nudge LAN discovery: broadcasts the client discovery probe
 * (message 1530, zero-length payload -- verified on the wire, see
 * PROTOCOL_NOTES.md) once, then listens for a bounded window rather than
 * continuously scanning the subnet.
 */
class CameraDiscoveryClient(
    private val multicastLock: MulticastLockController = NoOpMulticastLockController,
    private val discoveryPort: Int = DISCOVERY_PORT,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
) {
    private val _results = MutableStateFlow<List<DiscoveryBeacon>>(emptyList())
    val results: StateFlow<List<DiscoveryBeacon>> = _results.asStateFlow()

    private val probeFrameBytes: ByteArray by lazy {
        // type=0 matches the exact bytes captured on the wire (see PROTOCOL_NOTES.md);
        // every other captured frame uses type=1, so this is not a copy/paste default.
        DvripFrame.of(0u, 0u, DvripMessageIds.DISCOVERY_PROBE, ByteArray(0), type = 0).encode()
    }

    /**
     * Runs one bounded discovery window on [Dispatchers.IO] and returns the
     * deduplicated beacons found. Cooperative-cancellation friendly: caller
     * cancelling this coroutine tears the socket down promptly instead of
     * blocking on `receive()` until the OS times out.
     */
    suspend fun discoverOnce(): List<DiscoveryBeacon> = withContext(Dispatchers.IO) {
        _results.value = emptyList()
        val seen = linkedMapOf<String, DiscoveryBeacon>()
        var socket: DatagramSocket? = null
        try {
            multicastLock.acquire()
            socket = socketFactory().apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_POLL_TIMEOUT_MILLIS
                bind(InetSocketAddress(discoveryPort))
            }
            val probePacket = DatagramPacket(
                probeFrameBytes,
                probeFrameBytes.size,
                InetAddress.getByName(BROADCAST_ADDRESS),
                discoveryPort,
            )
            socket.send(probePacket)

            val deadline = System.currentTimeMillis() + windowMillis
            val buf = ByteArray(MAX_DATAGRAM_SIZE)
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    parseBeaconDatagram(bytes)?.let { beacon ->
                        seen[beacon.identityKey] = beacon
                        _results.value = seen.values.toList()
                    }
                } catch (e: SocketTimeoutException) {
                    // Expected: just means no datagram arrived in this poll slice; keep looping until deadline.
                } catch (e: SocketException) {
                    if (socket.isClosed) break else throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            socket?.close()
            multicastLock.release()
        }
        seen.values.toList()
    }

    companion object {
        const val DISCOVERY_PORT = 34569
        const val DEFAULT_WINDOW_MILLIS = 6000L
        private const val SOCKET_POLL_TIMEOUT_MILLIS = 500
        private const val MAX_DATAGRAM_SIZE = 8192
        private const val BROADCAST_ADDRESS = "255.255.255.255"

        /** A camera beacon datagram is a plain DVRIP frame: 20-byte header + JSON payload. */
        fun parseBeaconDatagram(bytes: ByteArray): DiscoveryBeacon? {
            if (bytes.size < DvripHeader.HEADER_LEN) return null
            return try {
                val header = DvripHeader.decode(bytes)
                val payloadEnd = DvripHeader.HEADER_LEN + header.payloadLength
                if (payloadEnd > bytes.size) return null
                val payload = bytes.copyOfRange(DvripHeader.HEADER_LEN, payloadEnd)
                val json = DvripPayloads.decodeJsonOrNull(payload) ?: return null
                DiscoveryBeaconParser.parse(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}
