package com.voidnullvalue.icseelocal.ble

import android.annotation.SuppressLint
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
        val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_NO_ADAPTER)
        val device = adapter.getRemoteDevice(address)

        val connected = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val mtuNegotiated = CompletableDeferred<Int>()
        val notificationsEnabled = CompletableDeferred<Unit>()
        val ackResult = CompletableDeferred<BleWifiProvisionCodec.WifiConfigAck>()
        var pendingWrite: CompletableDeferred<Unit>? = null
        val notifyBuffer = ByteArrayOutputStream()

        fun handleNotify(value: ByteArray) {
            notifyBuffer.write(value)
            val buffered = notifyBuffer.toByteArray()
            val expected = BleWifiProvisionCodec.expectedFrameLength(buffered) ?: return
            if (buffered.size < expected) return
            val frame = BleWifiProvisionCodec.parseFrame(buffered)
            if (frame == null) {
                if (!ackResult.isCompleted) ackResult.completeExceptionally(IllegalStateException("ack frame checksum/shape mismatch"))
                return
            }
            val ack = BleWifiProvisionCodec.parseWifiConfigAck(frame.content)
            if (ack != null) {
                if (!ackResult.isCompleted) ackResult.complete(ack)
            } else if (!ackResult.isCompleted) {
                ackResult.completeExceptionally(IllegalStateException("unparseable ack content"))
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> if (!connected.isCompleted) connected.complete(Unit)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val error = IllegalStateException("BLE disconnected (status=$status)")
                        if (!connected.isCompleted) connected.completeExceptionally(error)
                        if (!ackResult.isCompleted) ackResult.completeExceptionally(error)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesDiscovered.complete(Unit)
                } else {
                    servicesDiscovered.completeExceptionally(IllegalStateException("service discovery failed, status=$status"))
                }
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

            g.discoverServices()
            withTimeoutOrNull(GATT_OP_TIMEOUT_MILLIS) { servicesDiscovered.await() }
                ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_DISCOVER_TIMEOUT)

            val service = g.getService(CameraBleGatt.SERVICE_UUID)
                ?: return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_MISSING_SERVICE)
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
                val wrote = withTimeoutOrNull(GATT_OP_TIMEOUT_MILLIS) { chunkDeferred.await() }
                if (wrote == null || chunkDeferred.isCancelled) {
                    return BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_WRITE_TIMEOUT)
                }
                offset = end
            }

            withTimeoutOrNull(ACK_TIMEOUT_MILLIS) { ackResult.await() }
                ?: BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_ACK_TIMEOUT)
        } catch (e: Exception) {
            BleWifiProvisionCodec.WifiConfigAck.Failure(ERROR_EXCEPTION)
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

        const val ERROR_NO_ADAPTER = -1
        const val ERROR_CONNECT_TIMEOUT = -2
        const val ERROR_DISCOVER_TIMEOUT = -3
        const val ERROR_MISSING_SERVICE = -4
        const val ERROR_MISSING_CHARACTERISTIC = -5
        const val ERROR_NOTIFY_SETUP_TIMEOUT = -6
        const val ERROR_WRITE_TIMEOUT = -7
        const val ERROR_ACK_TIMEOUT = -8
        const val ERROR_EXCEPTION = -9
    }
}
