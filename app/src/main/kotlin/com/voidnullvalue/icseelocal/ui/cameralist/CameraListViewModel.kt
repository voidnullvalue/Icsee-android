package com.voidnullvalue.icseelocal.ui.cameralist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.discovery.AndroidMulticastLockController
import com.voidnullvalue.icseelocal.discovery.CameraDiscoveryClient
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.storage.CameraStore
import com.voidnullvalue.icseelocal.storage.CameraThumbStore
import com.voidnullvalue.icseelocal.storage.LocalMediaLibrary
import com.voidnullvalue.icseelocal.storage.PresetThumbStore
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraListViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraStore(application)
    private val cameraThumbs = CameraThumbStore(application)
    private val presetThumbs = PresetThumbStore(application)
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

    private val _onlineCameras = MutableStateFlow<Set<String>>(emptySet())
    val onlineCameras: StateFlow<Set<String>> = _onlineCameras.asStateFlow()

    private val _previewPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val previewPaths: StateFlow<Map<String, String>> = _previewPaths.asStateFlow()

    private var onlineProbeJob: Job? = null

    init {
        viewModelScope.launch {
            var seenRealList = false
            savedCameras.collect { cameras ->
                refreshPreviews(cameras)
                // stateIn replays emptyList() before DataStore emits. Skip that
                // placeholder so we don't mark every camera offline, then never
                // probe again.
                val placeholder = !seenRealList && cameras.isEmpty()
                seenRealList = true
                if (!placeholder) startOnlineProbe(cameras)
            }
        }
    }

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

    /**
     * Broadcast discovery can't cross a routed VPN (WireGuard). This sweeps a
     * `/24` with unicast TCP knocks instead, which does. [prefix] is the first
     * three octets, e.g. "192.168.88".
     */
    fun sweepSubnet(prefix: String) {
        if (_discovering.value) return
        val clean = prefix.trim().removeSuffix(".")
        if (!clean.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}"""))) return
        // Don't probe cameras we already have saved -- they're filtered out of the
        // discovered list anyway, so a probe to them is wasted network to a device we
        // already know about. (The probe itself no longer authenticates, but skipping
        // is still the right call.)
        val savedIps = savedCameras.value.map { it.host }.toSet()
        viewModelScope.launch {
            _discovering.value = true
            try {
                _discovered.value = discoveryClient.discoverSweep(clean, skipHosts = savedIps)
            } finally {
                _discovering.value = false
            }
        }
    }

    /** First three octets of an already-saved camera, to prefill the sweep field. */
    fun suggestedSubnet(): String =
        savedCameras.value.firstOrNull()?.host?.substringBeforeLast('.', "")?.takeIf { it.count { c -> c == '.' } == 2 } ?: ""

    fun refreshPreviews() {
        viewModelScope.launch { refreshPreviews(savedCameras.value) }
    }

    private suspend fun refreshPreviews(cameras: List<CameraDescriptor>) {
        val presets = presetThumbs.all()
        _previewPaths.value = buildMap {
            cameras.forEach { cam ->
                val dedicated = cameraThumbs.pathIfExists(cam.id)
                val fromPreset = presets
                    .filter { it.cameraId == cam.id && File(it.thumbPath).exists() }
                    .maxByOrNull { it.savedAtMs }
                    ?.thumbPath
                val path = dedicated ?: fromPreset
                if (path != null) put(cam.id, path)
            }
        }
    }

    fun checkOnlineStatus() {
        val cameras = savedCameras.value
        if (cameras.isEmpty()) return
        startOnlineProbe(cameras)
    }

    /**
     * Pulls the newest gallery snapshot for [camera] into [CameraThumbStore]
     * so the list row can show it as the thumbnail, then refreshes preview paths.
     */
    fun applyLastScreenshotThumb(camera: CameraDescriptor) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val latest = LocalMediaLibrary.latestImageForCamera(getApplication(), camera.displayName)
                    ?: return@withContext
                val uri = Uri.parse(latest.uri)
                if (uri.scheme == "file") {
                    uri.path?.let { cameraThumbs.copyFrom(camera.id, File(it)) }
                } else {
                    cameraThumbs.copyFromUri(camera.id, uri)
                }
            }
            refreshPreviews(savedCameras.value)
        }
    }

    private fun startOnlineProbe(cameras: List<CameraDescriptor>) {
        onlineProbeJob?.cancel()
        onlineProbeJob = viewModelScope.launch {
            _onlineCameras.value = probeReachable(cameras)
        }
    }

    private suspend fun probeReachable(cameras: List<CameraDescriptor>): Set<String> {
        if (cameras.isEmpty()) return emptySet()
        return coroutineScope {
            cameras.map { cam ->
                async(Dispatchers.IO) {
                    try {
                        if (discoveryClient.isReachable(cam.host, cam.dvripPort)) cam.id else null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
    }

    fun deleteCamera(id: String) {
        viewModelScope.launch { store.delete(id) }
    }
}
