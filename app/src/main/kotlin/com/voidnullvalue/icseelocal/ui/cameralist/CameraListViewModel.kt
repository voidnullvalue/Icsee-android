package com.voidnullvalue.icseelocal.ui.cameralist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.discovery.AndroidMulticastLockController
import com.voidnullvalue.icseelocal.discovery.CameraDiscoveryClient
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.storage.CameraStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CameraListViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraStore(application)
    private val discoveryClient = CameraDiscoveryClient(multicastLock = AndroidMulticastLockController(application))

    val savedCameras: StateFlow<List<CameraDescriptor>> = store.cameras
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discovered = MutableStateFlow<List<DiscoveryBeacon>>(emptyList())

    // Cameras already saved must never also appear in "Discovered on LAN" --
    // both lists render in the same LazyColumn keyed by the same identity
    // (beacon.identityKey == CameraDescriptor.id), and Compose crashes on a
    // duplicate key across items() calls in one scope.
    val discovered: StateFlow<List<DiscoveryBeacon>> = combine(_discovered, savedCameras) { beacons, saved ->
        val savedIds = saved.map { it.id }.toSet()
        beacons.filterNot { it.identityKey in savedIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    fun refreshDiscovery() {
        if (_discovering.value) return
        viewModelScope.launch {
            _discovering.value = true
            try {
                _discovered.value = discoveryClient.discoverOnce()
            } finally {
                _discovering.value = false
            }
        }
    }

    fun deleteCamera(id: String) {
        viewModelScope.launch { store.delete(id) }
    }
}
