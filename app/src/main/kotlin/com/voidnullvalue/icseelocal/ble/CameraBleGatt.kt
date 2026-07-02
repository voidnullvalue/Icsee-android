package com.voidnullvalue.icseelocal.ble

import java.util.UUID

/**
 * GATT UUIDs for this camera's BLE pairing service, read directly out of the
 * decompiled vendor app (`XMBleManager.java`) rather than guessed -- see
 * [[project-icsee-ble-pairing]] memory / PROTOCOL_NOTES.md.
 */
object CameraBleGatt {
    val SERVICE_UUID: UUID = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb")

    /** Standard Bluetooth SIG Client Characteristic Configuration descriptor, not camera-specific. */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
