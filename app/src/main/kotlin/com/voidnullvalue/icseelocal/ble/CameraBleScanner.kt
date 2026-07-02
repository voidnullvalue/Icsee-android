package com.voidnullvalue.icseelocal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BleCameraBeacon(val address: String, val name: String?, val rssi: Int)

/**
 * Scans for cameras in pairing mode. This camera does NOT advertise its pairing
 * GATT service ([CameraBleGatt.SERVICE_UUID]) in its advertising packet -- that
 * service only exists once you connect. So, matching the vendor app (whose
 * `SearchRequest` sets no scan filter and identifies cameras in software via
 * `XMBleManager.n()`), we scan every nearby LE device with no service filter and
 * keep only those whose manufacturer-specific data carries the camera beacon
 * prefix ([CAMERA_MANUFACTURER_PREFIXES]). A prior version filtered on the
 * service UUID directly, which matched nothing because the camera never
 * advertises it.
 *
 * Caller is responsible for holding the runtime BLE permission
 * (`BLUETOOTH_SCAN` on API 31+, `ACCESS_FINE_LOCATION` below that) before
 * collecting -- see `BlePairingScreen`.
 */
@SuppressLint("MissingPermission")
class CameraBleScanner(context: Context) {
    private val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun scan(): Flow<BleCameraBeacon> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth adapter unavailable or disabled"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isCameraBeacon(result)) {
                    trySend(BleCameraBeacon(result.device.address, result.device.name, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed, errorCode=$errorCode"))
            }
        }
        // No scan filter -- matches the vendor, which identifies cameras in software.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    /**
     * A camera if it either advertises the pairing service UUID (some firmware
     * puts it in the scan response) or -- like the vendor relies on -- carries
     * manufacturer-specific data beginning with a known camera beacon prefix.
     */
    private fun isCameraBeacon(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        record.serviceUuids?.let { uuids ->
            if (uuids.any { it.uuid == CameraBleGatt.SERVICE_UUID }) return true
        }
        val manufacturerData = record.manufacturerSpecificData ?: return false
        for (i in 0 until manufacturerData.size()) {
            val companyId = manufacturerData.keyAt(i)
            val payload = manufacturerData.valueAt(i) ?: continue
            // Reconstruct the raw AD bytes the vendor inspects: the 2 little-endian
            // company-id bytes followed by the payload, then hex it.
            val full = ByteArray(payload.size + 2)
            full[0] = (companyId and 0xFF).toByte()
            full[1] = ((companyId shr 8) and 0xFF).toByte()
            payload.copyInto(full, destinationOffset = 2)
            val hex = full.joinToString("") { "%02X".format(it) }
            if (CAMERA_MANUFACTURER_PREFIXES.any { hex.startsWith(it) }) return true
        }
        return false
    }

    companion object {
        /** Beacon prefixes the vendor treats as a pairable camera (see `XMBleManager.n()`). */
        private val CAMERA_MANUFACTURER_PREFIXES = listOf("8B8B", "8B8D", "8BB8")
    }
}
