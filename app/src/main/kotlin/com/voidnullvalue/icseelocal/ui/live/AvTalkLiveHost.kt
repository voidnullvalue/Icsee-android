package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.avtalk.AvTalkLens
import com.voidnullvalue.icseelocal.avtalk.AvTalkLiveBroadcaster
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementViewModel

/**
 * Live-screen host that wires the AVTalk dialog into the live controls without
 * coupling [LiveControlScreen] to Camera2/MediaCodec details.
 */
@UnstableApi
@Composable
fun LiveControlWithAvTalk(
    cameraId: String,
    onOpenDiagnostics: () -> Unit,
    onOpenDeviceManagement: () -> Unit,
    onOpenImageSettings: () -> Unit,
    onOpenMotionDetect: () -> Unit,
    onOpenFullRecordings: () -> Unit,
    onBack: () -> Unit,
    liveControlViewModel: LiveControlViewModel,
    deviceManagementViewModel: DeviceManagementViewModel,
) {
    val camera by liveControlViewModel.camera.collectAsState()
    var avTalkOpen by rememberSaveable(cameraId) { mutableStateOf(false) }

    LiveControlScreen(
        cameraId = cameraId,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenDeviceManagement = onOpenDeviceManagement,
        onOpenImageSettings = onOpenImageSettings,
        onOpenMotionDetect = onOpenMotionDetect,
        onOpenFullRecordings = onOpenFullRecordings,
        onOpenAvTalk = camera?.let { { avTalkOpen = true } },
        onBack = onBack,
        viewModel = liveControlViewModel,
        deviceManagementViewModel = deviceManagementViewModel,
    )

    if (avTalkOpen) {
        camera?.let { descriptor ->
            AvTalkBroadcastDialog(
                camera = descriptor,
                onDismiss = { avTalkOpen = false },
            )
        }
    }
}

@Composable
private fun AvTalkBroadcastDialog(
    camera: CameraDescriptor,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val broadcaster = remember(camera.id) { AvTalkLiveBroadcaster(context, camera) }
    val state by broadcaster.state.collectAsState()

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var pendingMicEnable by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<TextureView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        micGranted = result[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (pendingMicEnable) {
            pendingMicEnable = false
            broadcaster.setMicEnabled(micGranted)
        }
    }

    fun requestNeededPermissions(includeMic: Boolean = state.micEnabled) {
        val permissions = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (includeMic && !micGranted) add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    fun dismiss() {
        broadcaster.close()
        onDismiss()
    }

    LaunchedEffect(Unit) {
        if (!micGranted) broadcaster.setMicEnabled(false)
        if (!cameraGranted) requestNeededPermissions(includeMic = true)
    }

    LaunchedEffect(previewView, cameraGranted) {
        val view = previewView
        if (view != null && cameraGranted && view.isAvailable) {
            broadcaster.attachPreview(view)
        }
    }

    DisposableEffect(broadcaster) {
        onDispose { broadcaster.close() }
    }

    DisposableEffect(lifecycleOwner, broadcaster) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send to camera screen", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Broadcast this phone's camera and microphone to ${camera.displayName}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = ::dismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                if (cameraGranted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .heightIn(max = 420.dp)
                            .background(Color.Black, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val texture = TextureView(ctx)
                                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                        previewView = texture
                                    }

                                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                        broadcaster.detachPreview(texture)
                                        if (previewView === texture) previewView = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                                }
                                if (texture.isAvailable) previewView = texture
                                texture
                            },
                            update = { texture ->
                                if (texture.isAvailable && previewView !== texture) previewView = texture
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (state.previewStarting || !state.previewReady) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (state.previewStarting) "Starting phone camera…" else "Waiting for phone camera…",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Camera permission is required to send live video.")
                        Button(onClick = { requestNeededPermissions(includeMic = true) }) {
                            Text("Allow camera")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = broadcaster::switchLens,
                        enabled = cameraGranted && !state.previewStarting && !state.starting && !state.stopping,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.lens == AvTalkLens.FRONT) "Front" else "Back")
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (state.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Mic")
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = state.micEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !micGranted) {
                                    pendingMicEnable = true
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                } else {
                                    broadcaster.setMicEnabled(enabled)
                                }
                            },
                        )
                    }
                }

                val statusText = when {
                    state.starting -> "Connecting AVTalk…"
                    state.stopping -> "Stopping AVTalk…"
                    state.broadcasting -> "Broadcasting to camera screen"
                    state.previewReady -> "Ready to broadcast"
                    else -> "Preparing phone camera"
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.broadcasting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.broadcasting) {
                    Text(
                        "Video ${state.videoFramesSent} frames  •  Audio ${state.audioFramesSent} chunks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.broadcasting || state.stopping) {
                    Button(
                        onClick = broadcaster::stopBroadcast,
                        enabled = !state.stopping,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.stopping) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.stopping) "Stopping…" else "Stop broadcast")
                    }
                } else {
                    Button(
                        onClick = {
                            when {
                                !cameraGranted -> requestNeededPermissions(includeMic = state.micEnabled)
                                state.micEnabled && !micGranted -> {
                                    pendingMicEnable = true
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                                else -> broadcaster.startBroadcast()
                            }
                        },
                        enabled = state.previewReady && !state.starting && !state.stopping,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.starting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.ScreenShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.starting) "Connecting…" else "Start broadcast")
                    }
                }
            }
        }
    }
}
