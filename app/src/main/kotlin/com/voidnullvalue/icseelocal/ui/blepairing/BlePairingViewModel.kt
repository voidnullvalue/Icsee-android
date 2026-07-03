package com.voidnullvalue.icseelocal.ui.blepairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.ble.BleCameraBeacon
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.ble.BleWifiProvisionCodec
import com.voidnullvalue.icseelocal.ble.CameraBlePairingClient
import com.voidnullvalue.icseelocal.ble.CameraBleScanner
import com.voidnullvalue.icseelocal.config.ChangeRandomUserClient
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

sealed class CredentialChangeState {
    data object Idle : CredentialChangeState()
    data object InProgress : CredentialChangeState()
    data object Success : CredentialChangeState()
    data class Failed(val detail: String) : CredentialChangeState()
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

    private val _credentialChangeState = MutableStateFlow<CredentialChangeState>(CredentialChangeState.Idle)
    val credentialChangeState: StateFlow<CredentialChangeState> = _credentialChangeState.asStateFlow()

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

    /**
     * After BLE pairing succeeds, offer the user the chance to replace the
     * camera's factory-assigned random credentials with a custom login. Updates
     * [BlePairingUiState.Success] with new credentials if successful, or leaves
     * them unchanged if the operation fails (user can still proceed with random
     * credentials).
     */
    fun changeRandomUserCredentials(host: String, currentUsername: String, currentPassword: String, newUsername: String, newPassword: String) {
        val currentState = _pairingState.value as? BlePairingUiState.Success ?: return
        _credentialChangeState.value = CredentialChangeState.InProgress
        viewModelScope.launch {
            val result = ChangeRandomUserClient.changeUser(host, 34567, currentUsername, currentPassword, newUsername, newPassword)
            if (result is ChangeRandomUserClient.Result.Success) {
                _pairingState.value = BlePairingUiState.Success(
                    currentState.camera.copy(username = newUsername, password = newPassword),
                )
                _credentialChangeState.value = CredentialChangeState.Success
            } else {
                val failure = result as ChangeRandomUserClient.Result.Failure
                _credentialChangeState.value = CredentialChangeState.Failed(failure.detail ?: "Failed to set credentials")
            }
        }
    }

    fun reset() {
        pairingClient.cancel()
        _pairingState.value = BlePairingUiState.Idle
        _credentialChangeState.value = CredentialChangeState.Idle
    }

    override fun onCleared() {
        stopScan()
        pairingClient.cancel()
    }
}
