package com.voidnullvalue.icseelocal.ui

import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon

sealed class Screen {
    data object CameraList : Screen()
    data class CameraSettings(val cameraId: String?, val prefillBeacon: DiscoveryBeacon? = null, val prefillBle: BlePairedCamera? = null) : Screen()
    data class LiveControl(val cameraId: String) : Screen()
    data class Diagnostics(val cameraId: String) : Screen()
    data class DeviceManagement(val cameraId: String) : Screen()
    data class ConfigEditor(val cameraId: String, val configName: String, val label: String) : Screen()
    data class ImageSettings(val cameraId: String) : Screen()
    data object BlePairing : Screen()
}
