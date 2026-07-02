package com.voidnullvalue.icseelocal.ui.blepairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.ble.BleCameraBeacon
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.ble.BleWifiProvisionCodec
import com.voidnullvalue.icseelocal.ble.CameraBlePairingClient
import com.voidnullvalue.icseelocal.ble.CameraBleScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BlePairingUiState {
    data object Idle : BlePairingUiState()
    data object Pairing : BlePairingUiState()
    data class Success(val camera: BlePairedCamera) : BlePairingUiState()
    data class Failed(val errorCode: Int, val detail: String? = null) : BlePairingUiState()
}

class BlePairingViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = CameraBleScanner(application)
    private val pairingClient = CameraBlePairingClient(application)

    private val _beacons = MutableStateFlow<List<BleCameraBeacon>>(emptyList())
    val beacons: StateFlow<List<BleCameraBeacon>> = _beacons.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _pairingState = MutableStateFlow<BlePairingUiState>(BlePairingUiState.Idle)
    val pairingState: StateFlow<BlePairingUiState> = _pairingState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _beacons.value = emptyList()
        _scanError.value = null
        val seen = linkedMapOf<String, BleCameraBeacon>()
        scanJob = viewModelScope.launch {
            runCatching {
                scanner.scan().collect { beacon ->
                    seen[beacon.address] = beacon
                    _beacons.value = seen.values.toList()
                }
            }.onFailure { _scanError.value = it.message ?: "BLE scan failed" }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun pair(address: String, ssid: String, password: String) {
        stopScan()
        _pairingState.value = BlePairingUiState.Pairing
        viewModelScope.launch {
            when (val ack = pairingClient.pair(address, ssid, password)) {
                is BleWifiProvisionCodec.WifiConfigAck.Success -> {
                    _pairingState.value = BlePairingUiState.Success(
                        BlePairedCamera(host = ack.ip, username = ack.assignedUsername, password = ack.assignedPassword, mac = ack.mac),
                    )
                }
                is BleWifiProvisionCodec.WifiConfigAck.Failure -> {
                    _pairingState.value = BlePairingUiState.Failed(ack.errorCode, ack.detail)
                }
            }
        }
    }

    fun reset() {
        pairingClient.cancel()
        _pairingState.value = BlePairingUiState.Idle
    }

    override fun onCleared() {
        stopScan()
        pairingClient.cancel()
    }
}
