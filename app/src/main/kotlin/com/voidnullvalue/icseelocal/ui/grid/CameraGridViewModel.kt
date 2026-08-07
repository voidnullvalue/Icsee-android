package com.voidnullvalue.icseelocal.ui.grid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.storage.CameraStore
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import com.voidnullvalue.icseelocal.video.RtspStreamManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GridLayoutMode(val columns: Int, val label: String) {
    ONE(1, "1×1"),
    FOUR(2, "2×2"),
    NINE(3, "3×3"),
}

/**
 * Phase-1 multi-view: one [RtspStreamManager] bound to the focused tile only.
 * Unfocused tiles show placeholders (true concurrent decode is PR-F).
 */
@UnstableApi
class CameraGridViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraStore(application)

    val cameras: StateFlow<List<CameraDescriptor>> = store.cameras
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streamManager = RtspStreamManager(application)
    val playbackState: StateFlow<RtspPlayerState> = streamManager.state
    val muted: StateFlow<Boolean> = streamManager.muted
    val mainStream: StateFlow<Boolean> = streamManager.mainStream

    private val _layoutMode = MutableStateFlow(GridLayoutMode.FOUR)
    val layoutMode: StateFlow<GridLayoutMode> = _layoutMode.asStateFlow()

    private val _focusedCameraId = MutableStateFlow<String?>(null)
    val focusedCameraId: StateFlow<String?> = _focusedCameraId.asStateFlow()

    private var focusJob: Job? = null

    fun setLayoutMode(mode: GridLayoutMode) {
        _layoutMode.value = mode
    }

    fun focusCamera(cameraId: String?) {
        if (_focusedCameraId.value == cameraId) return
        _focusedCameraId.value = cameraId
        focusJob?.cancel()
        if (cameraId == null) {
            streamManager.stop()
            return
        }
        focusJob = viewModelScope.launch {
            val cam = cameras.value.firstOrNull { it.id == cameraId } ?: return@launch
            val creds = store.credentialsFor(cam.id) ?: CameraCredentials("", "")
            streamManager.start(
                host = cam.host,
                port = cam.rtspPort,
                username = creds.username,
                password = creds.password,
                channel = cam.channel + 1,
                mainStream = cam.streamType == StreamType.MAIN,
                preferFactoryRtspAccount = cam.rtspFallbackEnabled,
            )
        }
    }

    fun toggleMute() = streamManager.toggleMute()

    fun toggleQuality() {
        val id = _focusedCameraId.value ?: return
        val cam = cameras.value.firstOrNull { it.id == id } ?: return
        val next = if (streamManager.mainStream.value) StreamType.SUB else StreamType.MAIN
        viewModelScope.launch {
            val creds = store.credentialsFor(id)
            store.save(cam.copy(streamType = next), creds)
            streamManager.setMainStream(next == StreamType.MAIN)
        }
    }

    fun reconnect() = streamManager.reconnect()

    fun moveCamera(fromIndex: Int, toIndex: Int) {
        val list = cameras.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        viewModelScope.launch {
            store.reorder(list.map { it.id })
        }
    }

    override fun onCleared() {
        streamManager.release()
        super.onCleared()
    }
}
