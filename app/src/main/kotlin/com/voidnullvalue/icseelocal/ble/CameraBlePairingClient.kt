package com.voidnullvalue.icseelocal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Drives the BLE pairing GATT handshake against [CameraBleGatt]: connect,
 * discover services, negotiate MTU, subscribe to the notify characteristic,
 * chunk-write the WiFi-config frame built by [BleWifiProvisionCodec], then
 * reassemble and parse the camera's ack from notifications.
 *
 * **Not yet validated against a real camera in pairing mode** -- the frame
 * codec is byte-verified against the decompiled vendor app (see
 * [BleWifiProvisionCodecTest]), but this class's GATT choreography (MTU
 * negotiation, chunk size, write pacing) is our own implementation using
 * standard Android BLE idioms, not a byte-for-byte port of the vendor's own
 * GATT client. Confirm end-to-end on real hardware before relying on it.
 */
@SuppressLint("MissingPermission")
class CameraBlePairingClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null

    suspend fun pair(address: String, ssid: String, password: String): BleWifiProvisionCodec.WifiConfigAck {
        // The BluetoothManager's adapter can transiently return null on Qualcomm stacks
        // during BT state transitions (e.g. right after a prior attempt closed its GATT),
        // which surfaced as a bogus -1. The vendor always uses the static default adapter,
        // so fall back to it -- and give the manager a couple of retries before giving up.
        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        var adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        var adapterTries = 0
        while (adapter == null && adapterTries < ADAPTER_ACQUIRE_ATTEMPTS) {
            kotlinx.coroutines.delay(ADAPTER_RETRY_DELAY_MILLIS)
            adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            adapterTries++
        }
        if (adapter == null) {
            return BleWifiProvisionCodec.WifiConfigAck.Failure(
                ERROR_NO_ADAPTER,
                "adapter null after ${adapterTries + 1} tries (manager=${manager != null})",
            )
        }
        if (!adapter.isEnabled) {
            return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_BLUETOOTH_DISABLED)
        }
        val device = adapter.getRemoteDevice(address)

        val connected = CompletableDeferred<Unit>()
        // Reassigned per discovery attempt so we can retry (see vendor BleConnectRequest retry loop).
        var servicesDiscovered = CompletableDeferred<Int>()
        val mtuNegotiated = CompletableDeferred<Int>()
        val notificationsEnabled = CompletableDeferred<Unit>()
        val ackResult = CompletableDeferred<BleWifiProvisionCodec.WifiConfigAck>()
        var pendingWrite: CompletableDeferred<Unit>? = null
        val notifyBuffer = ByteArrayOutputStream()

        fun handleNotify(value: ByteArray) {
            notifyBuffer.write(value)
            // Drain every complete frame currently buffered. The camera sends a
            // CMD_RECEIVE receipt (cmdId=2, typically a non-zero "connecting" status)
            // BEFORE the CMD_CALLBACK result (cmdId=3) that carries the final success
            // and assigned credentials. Only the callback is the real result -- mirror
            // the vendor, which acts solely on getCmdId()==3. Completing on the receipt
            // was what surfaced a bogus "failed (code 1)" even though the camera joined.
            while (true) {
                val buffered = notifyBuffer.toByteArray()
                val expected = BleWifiProvisionCodec.expectedFrameLength(buffered) ?: return
                if (buffered.size < expected) return
                val frameBytes = buffered.copyOfRange(0, expected)
                notifyBuffer.reset()
                if (buffered.size > expected) notifyBuffer.write(buffered, expected, buffered.size - expected)

                val frame = BleWifiProvisionCodec.parseFrame(frameBytes) ?: continue
                if (frame.cmdId != BleWifiProvisionCodec.CMD_CALLBACK) continue // receipt/progress, keep waiting
                val ack = BleWifiProvisionCodec.parseWifiConfigAck(frame.content)
                if (ack != null && !ackResult.isCompleted) ackResult.complete(ack)
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> if (!connected.isCompleted) connected.complete(Unit)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val error = IllegalStateException("BLE disconnected (status=$status)")
                        if (!connected.isCompleted) connected.completeExceptionally(error)
                        // The camera often drops the link in the same instant it emits the
                        // final ACK notification. Drain anything already buffered before
                        // giving up, so a fully-received ACK that just hasn't been parsed
                        // yet still wins over the disconnect.
                        if (!ackResult.isCompleted) handleNotify(ByteArray(0))
                        if (!ackResult.isCompleted) ackResult.completeExceptionally(error)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!servicesDiscovered.isCompleted) servicesDiscovered.complete(status)
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                mtuNegotiated.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_ATT_MTU)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationsEnabled.complete(Unit)
                } else {
                    notificationsEnabled.completeExceptionally(IllegalStateException("enabling notifications failed, status=$status"))
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val current = pendingWrite ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) current.complete(Unit)
                else current.completeExceptionally(IllegalStateException("chunk write failed, status=$status"))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleNotify(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleNotify(characteristic.value ?: return)
            }
        }

        return try {
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g
            withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connected.await() }
                ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_CONNECT_TIMEOUT)

            // Ask for the fastest connection interval (~7.5ms vs the ~30-50ms
            // default). The camera joins Wi-Fi and drops BLE within a few tens of
            // ms of receiving the credentials, so the assigned-credentials ACK
            // notification only makes it out if the link is running fast enough --
            // this is the main reason the ACK (and thus the real per-device login)
            // was being missed. Best-effort: no callback to await.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            // The vendor's BLE library (inuker BleConnectRequest) waits 300ms after the
            // connection comes up before discovering services, and on an empty/failed
            // discovery it refreshes the GATT cache and retries. Skipping this on the
            // OnePlus/Qualcomm stack makes discoverServices() return before the ATT table
            // is populated -- status is SUCCESS but getService() yields null (our old -4).
            var service: android.bluetooth.BluetoothGattService? = null
            var attempt = 0
            while (attempt < SERVICE_DISCOVER_ATTEMPTS && service == null) {
                kotlinx.coroutines.delay(if (attempt == 0) POST_CONNECT_DELAY_MILLIS else DISCOVER_RETRY_DELAY_MILLIS)
                servicesDiscovered = CompletableDeferred()
                g.discoverServices()
                val status = withTimeoutOrNull(GATT_OP_TIMEOUT_MILLIS) { servicesDiscovered.await() }
                    ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_DISCOVER_TIMEOUT)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    service = g.getService(CameraBleGatt.SERVICE_UUID)
                }
                if (service == null) refreshGattCache(g)
                attempt++
            }
            if (service == null) {
                return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_MISSING_SERVICE)
            }
            val writeChar = service.getCharacteristic(CameraBleGatt.WRITE_CHARACTERISTIC_UUID)
                ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_MISSING_CHARACTERISTIC)
            val notifyChar = service.getCharacteristic(CameraBleGatt.NOTIFY_CHARACTERISTIC_UUID)
                ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_MISSING_CHARACTERISTIC)

            g.requestMtu(REQUESTED_MTU)
            val negotiatedMtu = withTimeoutOrNull(GATT_OP_TIMEOUT_MILLIS) { mtuNegotiated.await() } ?: DEFAULT_ATT_MTU

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CameraBleGatt.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd != null) {
                writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                withTimeoutOrNull(GATT_OP_TIMEOUT_MILLIS) { notificationsEnabled.await() }
                    ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_NOTIFY_SETUP_TIMEOUT)
            }

            val encryptType = BleWifiProvisionCodec.inferEncryptType(password)
            val frame = BleWifiProvisionCodec.buildWifiConfigFrame(ssid, password, encryptType)
            val chunkSize = (negotiatedMtu - ATT_WRITE_OVERHEAD).coerceAtLeast(MIN_CHUNK_SIZE)
            var offset = 0
            while (offset < frame.size) {
                val end = (offset + chunkSize).coerceAtMost(frame.size)
                val chunkDeferred = CompletableDeferred<Unit>()
                pendingWrite = chunkDeferred
                writeCharacteristicCompat(g, writeChar, frame.copyOfRange(offset, end))
                // Wait for the write callback to keep GATT ops serialized, but do NOT
                // hard-fail if it doesn't arrive. The vendor never gates success on the
                // write callback -- it paces writes ~50ms apart and treats the ACK
                // notification as the only success signal. On this camera the write
                // physically lands (it joins the router) even when Android doesn't
                // deliver onCharacteristicWrite, which is what produced the bogus -7.
                withTimeoutOrNull(WRITE_ACK_TIMEOUT_MILLIS) { chunkDeferred.await() }
                    ?: kotlinx.coroutines.delay(WRITE_PACING_MILLIS)
                offset = end
            }

            // The WiFi credentials are now on the wire. From here the camera typically
            // joins the router and, in doing so, drops the BLE link -- which fires
            // STATE_DISCONNECTED and completes ackResult exceptionally BEFORE the ACK
            // notification arrives. That is NOT a provisioning failure: the camera is on
            // the network. So catch the disconnect/timeout locally and report the distinct
            // "provisioned, no ACK" outcome instead of a generic error, so the UI can hand
            // the user off to add the camera by IP with its default login.
            try {
                withTimeoutOrNull(ACK_TIMEOUT_MILLIS) { ackResult.await() }
                    ?: BleWifiProvisionCodec.WifiConfigAck.Failure(
                        ERROR_PROVISIONED_NO_ACK,
                        "credentials sent; no ACK within ${ACK_TIMEOUT_MILLIS}ms",
                    )
            } catch (e: Exception) {
                BleWifiProvisionCodec.WifiConfigAck.Failure(
                    ERROR_PROVISIONED_NO_ACK,
                    "credentials sent; BLE dropped before ACK (${e.message})",
                )
            }
        } catch (e: Exception) {
            BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_EXCEPTION, e.toString())
        } finally {
            gatt?.close()
            gatt = null
        }
    }

    fun cancel() {
        gatt?.close()
        gatt = null
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    /**
     * Clears Android's cached GATT service table via the hidden [BluetoothGatt.refresh]
     * method, ported from the vendor library's `BluetoothUtils.refreshGattCache`. Without
     * this, a stale/empty cache from a prior failed connect keeps [BluetoothGatt.getService]
     * returning null on every subsequent attempt.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = BluetoothGatt::class.java.getMethod("refresh")
            (method.invoke(gatt) as? Boolean) ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    companion object {
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_WRITE_OVERHEAD = 3
        private const val MIN_CHUNK_SIZE = 20
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val GATT_OP_TIMEOUT_MILLIS = 10_000L
        private const val ACK_TIMEOUT_MILLIS = 20_000L
        // Vendor waits 300ms post-connect before discovering; retries discovery up to
        // ServiceDiscoverRetry(3)+1 times, 1s apart, refreshing the cache between tries.
        private const val POST_CONNECT_DELAY_MILLIS = 300L
        private const val DISCOVER_RETRY_DELAY_MILLIS = 1_000L
        private const val SERVICE_DISCOVER_ATTEMPTS = 4
        // Best-effort wait for each write callback, then vendor-style ~50ms pacing.
        private const val WRITE_ACK_TIMEOUT_MILLIS = 2_000L
        private const val WRITE_PACING_MILLIS = 50L
        private const val ADAPTER_ACQUIRE_ATTEMPTS = 3
        private const val ADAPTER_RETRY_DELAY_MILLIS = 200L

        const val ERROR_NO_ADAPTER = -1
        const val ERROR_CONNECT_TIMEOUT = -2
        const val ERROR_DISCOVER_TIMEOUT = -3
        const val ERROR_MISSING_SERVICE = -4
        const val ERROR_MISSING_CHARACTERISTIC = -5
        const val ERROR_NOTIFY_SETUP_TIMEOUT = -6
        const val ERROR_WRITE_TIMEOUT = -7
        const val ERROR_ACK_TIMEOUT = -8
        const val ERROR_BLUETOOTH_DISABLED = -9
        const val ERROR_EXCEPTION = -10
        /** Credentials were sent but the camera dropped BLE before ACKing -- it is still joining WiFi. */
        const val ERROR_PROVISIONED_NO_ACK = -15
    }
}
