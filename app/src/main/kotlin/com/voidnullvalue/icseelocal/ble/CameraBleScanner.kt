package com.voidnullvalue.icseelocal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BleCameraBeacon(val address: String, val name: String?, val rssi: Int)

/**
 * Scans for cameras advertising the pairing GATT service directly (filtering
 * on [CameraBleGatt.SERVICE_UUID]) rather than trying to replicate the vendor
 * app's proprietary manufacturer-data beacon parsing -- a standard BLE
 * service-UUID scan filter is simpler and just as reliable for "is this a
 * pairable camera" without needing to reverse-engineer that payload format.
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
                trySend(BleCameraBeacon(result.device.address, result.device.name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed, errorCode=$errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CameraBleGatt.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
