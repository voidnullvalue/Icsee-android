package com.voidnullvalue.icseelocal.discovery

import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

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

    // A NON-authenticating DVRIP probe: message 1010 (pre-login negotiate) with an
    // empty JSON body. The camera answers message 1011 (its RSA public key) with no
    // login at all -- 1010 is in the camera's own confirmed NotEncryptMsgID list and
    // a `{}` body is live-verified to elicit 1011 (PROTOCOL_NOTES.md "probe_1010").
    //
    // This deliberately replaces the old login probe. Any login probe -- even a
    // successful backdoor one -- is still a real authentication that counts toward
    // this firmware's Ret:205 login-rate lockout, so a subnet sweep used to spend one
    // real login against every reachable camera on every run; re-scanning a few times
    // while troubleshooting a route could trip the lockout with nothing flaky about
    // the network. A 1010 negotiate costs zero logins and is just as good a "a real
    // DVRIP camera is here" discriminator: the VPN/router catch-all that ACKs every
    // TCP port (making a plain connect useless) forwards no real bytes, so only an
    // actual camera returns a valid 0xFF DVRIP frame in reply.
    private val negotiateProbeBytes: ByteArray by lazy {
        DvripFrame.of(0u, 0u, DvripMessageIds.PRE_LOGIN_NEGOTIATE_REQUEST, DvripPayloads.encodeJson("{}"), type = 1).encode()
    }

    /**
     * Discovery that works across a routed VPN (e.g. WireGuard), where the
     * broadcast probe of [discoverOnce] cannot reach the camera LAN: sweep a
     * `/24` with bounded-concurrency **unicast** probes on the DVRIP port.
     *
     * A plain TCP connect is NOT sufficient -- some routers/VPN endpoints ACK
     * connections for the whole subnet (every IP looks "open", confirmed live),
     * which would surface hundreds of phantom hosts. So each host must return a
     * **valid DVRIP negotiate response** (msg 1011, 0xFF magic) to count -- a
     * discriminator the catch-all can't fake (it forwards no real bytes) and, unlike
     * the old login probe, one that costs no authentication. Surfaced beacons are
     * IP-only (no MAC/serial; that rides the UDP beacon path, which doesn't answer
     * over TCP here).
     *
     * @param subnetPrefix the first three octets, e.g. "192.168.88"
     * @param skipHosts IPs to not probe at all -- typically cameras already saved,
     *   which are filtered out of the discovered list anyway, so probing them is
     *   pure wasted network to a device we already know about.
     */
    suspend fun discoverSweep(
        subnetPrefix: String,
        hostRange: IntRange = 1..254,
        tcpPort: Int = 34567,
        skipHosts: Set<String> = emptySet(),
    ): List<DiscoveryBeacon> = withContext(Dispatchers.IO) {
        _results.value = emptyList()
        val found = ConcurrentHashMap<String, DiscoveryBeacon>()
        val gate = Semaphore(SWEEP_CONCURRENCY)
        coroutineScope {
            for (i in hostRange) {
                val ip = "$subnetPrefix.$i"
                if (ip in skipHosts) continue
                launch {
                    gate.withPermit {
                        if (!currentCoroutineContext().isActive) return@withPermit
                        if (isDvripHost(ip, tcpPort)) {
                            found[ip] = DiscoveryBeacon(
                                hostIp = ip, hostName = ip, httpPort = 0, mac = "", pid = "",
                                serialNumber = "", sslPort = 0, tcpPort = tcpPort, udpPort = 0, version = "",
                            )
                            _results.value = found.values.sortedBy { it.hostIp }
                        }
                    }
                }
            }
        }
        found.values.sortedBy { it.hostIp }
    }

    /** True only if [ip]:[port] answers the non-authenticating negotiate probe with a valid DVRIP frame (0xFF magic, msg 1011). */
    private fun isDvripHost(ip: String, port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), TCP_KNOCK_TIMEOUT_MILLIS)
            s.soTimeout = DVRIP_READ_TIMEOUT_MILLIS
            s.getOutputStream().apply { write(negotiateProbeBytes); flush() }
            val header = ByteArray(DvripHeader.HEADER_LEN)
            val ins = s.getInputStream()
            var read = 0
            while (read < header.size) {
                val n = ins.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read >= DvripHeader.HEADER_LEN &&
                (header[0].toInt() and 0xFF) == DvripHeader.MAGIC &&
                DvripHeader.decode(header).messageId == DvripMessageIds.PRE_LOGIN_NEGOTIATE_RESPONSE
        }
    }.getOrDefault(false)

    companion object {
        const val DISCOVERY_PORT = 34569
        const val DEFAULT_WINDOW_MILLIS = 6000L
        private const val SWEEP_CONCURRENCY = 32
        private const val TCP_KNOCK_TIMEOUT_MILLIS = 800
        private const val DVRIP_READ_TIMEOUT_MILLIS = 1500
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
