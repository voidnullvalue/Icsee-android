package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementViewModel
import com.voidnullvalue.icseelocal.ui.devicemanagement.RecordedFile
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import java.io.File

private val StatusGreen = Color(0xFF4ADE80)
private val StatusAmber = Color(0xFFFBBF24)
private val OverlayBg = Color(0x99000000)

private enum class LiveBottomTab { Controls, Recordings }

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveControlScreen(
    cameraId: String,
    onOpenDiagnostics: () -> Unit,
    onOpenDeviceManagement: () -> Unit,
    onOpenImageSettings: () -> Unit,
    onOpenMotionDetect: () -> Unit,
    onOpenFullRecordings: () -> Unit,
    onBack: () -> Unit,
    viewModel: LiveControlViewModel = viewModel(),
    deviceManagementViewModel: DeviceManagementViewModel = viewModel(),
) {
    val camera by viewModel.camera.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val speed by viewModel.speedStep.collectAsState()
    val talking by viewModel.talking.collectAsState()
    val talkError by viewModel.talkError.collectAsState()
    val rtspState by viewModel.rtspState.collectAsState()
    val danceModeTriggered by viewModel.danceModeTriggered.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val bitrateBps by viewModel.bitrateBps.collectAsState()
    val cruiseActive by viewModel.cruiseActive.collectAsState()
    val dayNightMode by viewModel.dayNightMode.collectAsState()
    val statusToast by viewModel.statusToast.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val recordElapsedMs by viewModel.recordElapsedMs.collectAsState()
    val dmState by deviceManagementViewModel.state.collectAsState()

    val context = LocalContext.current
    val activity = context as? Activity
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var fullscreen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var bottomTab by remember { mutableStateOf(LiveBottomTab.Controls) }
    // Digital zoom (view transform — not PTZ optical zoom).
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun resetZoom() { scale = 1f; offsetX = 0f; offsetY = 0f }
    fun bumpZoom(factor: Float) {
        val next = (scale * factor).coerceIn(1f, 5f)
        scale = next
        if (next <= 1.01f) { offsetX = 0f; offsetY = 0f }
    }

    LaunchedEffect(statusToast) {
        if (statusToast != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearStatusToast()
        }
    }
    LaunchedEffect(bottomTab, state) {
        if (bottomTab == LiveBottomTab.Recordings && state is ConnectionState.Authenticated) {
            if (dmState.recordings == null && !dmState.recordingsQuerying) {
                deviceManagementViewModel.loadAllRecordings()
            }
        }
    }

    // Immersive chrome only — do NOT force landscape (that used to recreate the
    // Activity and drop the nav stack back to the camera list).
    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (fullscreen && window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { c ->
                c.hide(WindowInsetsCompat.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = fullscreen) {
        resetZoom()
        fullscreen = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding(),
    ) {
        // —— Video + light chrome ——
        Box(
            Modifier
                .fillMaxWidth()
                .weight(if (fullscreen) 1f else 0.42f)
                .clip(if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offsetX, translationY = offsetY,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            if (next > 1.01f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                        }
                    },
            ) {
                VideoSurface(viewModel, rtspState, Modifier.fillMaxSize())
            }

            // Top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (fullscreen) { resetZoom(); fullscreen = false } else onBack()
                }) {
                    Icon(
                        if (fullscreen) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(camera?.displayName ?: "Live", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 15.sp)
                    StatusPill(state, rtspState is RtspPlayerState.Playing)
                }
                if (!fullscreen) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Device management") }, onClick = { menuOpen = false; onOpenDeviceManagement() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                            DropdownMenuItem(text = { Text("Image settings") }, onClick = { menuOpen = false; onOpenImageSettings() }, leadingIcon = { Icon(Icons.Default.LightMode, null) })
                            DropdownMenuItem(text = { Text("Motion detection") }, onClick = { menuOpen = false; onOpenMotionDetect() }, leadingIcon = { Icon(Icons.Default.Sensors, null) })
                            DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menuOpen = false; onOpenDiagnostics() }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                        }
                    }
                }
            }

            if (bitrateBps > 0) {
                Text(
                    formatBitrate(bitrateBps),
                    color = Color.White.copy(0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 56.dp, top = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(OverlayBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (recording) {
                Text(
                    "REC ${formatDuration(recordElapsedMs)}",
                    color = Color(0xFFFF6B6B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 12.dp, top = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(OverlayBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Digital zoom controls on the video edge
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallOverlayBtn(Icons.Default.ZoomIn, "Zoom in") { bumpZoom(1.25f) }
                SmallOverlayBtn(Icons.Default.ZoomOut, "Zoom out") { bumpZoom(0.8f) }
                if (scale > 1.01f) {
                    SmallOverlayBtn(Icons.Default.Close, "Reset zoom") { resetZoom() }
                }
                if (!fullscreen) {
                    SmallOverlayBtn(Icons.Default.Fullscreen, "Fullscreen") { fullscreen = true }
                }
            }

            statusToast?.let { msg ->
                Text(
                    msg,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(OverlayBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (!fullscreen) {
            // —— Action row directly under stream ——
            StreamActionRow(
                mainStream = camera?.streamType != StreamType.SUB,
                muted = muted,
                recording = recording,
                talking = talking,
                hasMicPermission = hasMicPermission,
                onToggleQuality = {
                    viewModel.setStreamType(
                        if (camera?.streamType == StreamType.MAIN) StreamType.SUB else StreamType.MAIN,
                    )
                },
                onToggleMute = viewModel::toggleMute,
                onSnapshot = viewModel::takeSnapshot,
                onToggleRecording = viewModel::toggleRecording,
                onTalkPress = {
                    if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    else viewModel.startTalk()
                },
                onTalkRelease = viewModel::stopTalk,
            )

            // —— Tabs: Controls | Recordings ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabChip("Controls", bottomTab == LiveBottomTab.Controls) { bottomTab = LiveBottomTab.Controls }
                TabChip("Recordings", bottomTab == LiveBottomTab.Recordings) { bottomTab = LiveBottomTab.Recordings }
                Spacer(Modifier.weight(1f))
                if (bottomTab == LiveBottomTab.Recordings) {
                    TextButton(onClick = onOpenFullRecordings) { Text("Full timeline", fontSize = 12.sp) }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.58f)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                when (bottomTab) {
                    LiveBottomTab.Controls -> ControlsPanel(
                        state = state,
                        talkError = talkError,
                        speed = speed,
                        cruiseActive = cruiseActive,
                        dayNightMode = dayNightMode,
                        onReconnect = viewModel::reconnect,
                        onPtzDown = viewModel::onPtzDown,
                        onPtzUp = viewModel::onPtzUp,
                        onPtzCancel = viewModel::onPtzCancel,
                        onGotoPreset = viewModel::gotoPreset,
                        onSavePreset = viewModel::setPreset,
                        onSpeed = viewModel::setSpeedStep,
                        onToggleCruise = viewModel::toggleCruise,
                        onCycleDayNight = {
                            val next = when (dayNightMode) { 0 -> 1; 1 -> 2; else -> 0 }
                            viewModel.setDayNightMode(next)
                        },
                    )
                    LiveBottomTab.Recordings -> LiveRecordingsPanel(
                        querying = dmState.recordingsQuerying,
                        clips = dmState.recordings,
                        downloading = dmState.downloadingClip,
                        progress = dmState.downloadProgressBytes,
                        error = dmState.errorMessage,
                        onRefresh = deviceManagementViewModel::loadAllRecordings,
                        onClip = { clip ->
                            if (clip.isDownloaded) deviceManagementViewModel.openLocalClip(clip)
                            else deviceManagementViewModel.downloadClip(clip)
                        },
                    )
                }
            }
        }
    }

    dmState.playUri?.let { uri ->
        LocalPlayerDialog(uri = uri, onDismiss = deviceManagementViewModel::clearPlayUri)
    }

    if (danceModeTriggered) {
        FunkytownDanceDialog(
            onDismiss = { viewModel.stopDance(); viewModel.dismissDanceTrigger() },
            onStart = viewModel::startDance,
        )
    }
}

@Composable
private fun StreamActionRow(
    mainStream: Boolean,
    muted: Boolean,
    recording: Boolean,
    talking: Boolean,
    hasMicPermission: Boolean,
    onToggleQuality: () -> Unit,
    onToggleMute: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onTalkPress: () -> Unit,
    onTalkRelease: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionChip(
            label = if (mainStream) "FHD" else "HD",
            icon = if (mainStream) Icons.Default.HighQuality else Icons.Default.Hd,
            onClick = onToggleQuality,
        )
        ActionIcon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, onToggleMute)
        ActionIcon(Icons.Default.CameraAlt, onSnapshot)
        ActionIcon(
            Icons.Default.Videocam,
            onToggleRecording,
            tint = if (recording) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurface,
        )
        // Hold-to-talk fills remaining width
        val talkBg by animateColorAsState(
            if (talking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            label = "talk",
        )
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(talkBg)
                .pointerInput(hasMicPermission) {
                    awaitEachGesture {
                        awaitFirstDown()
                        onTalkPress()
                        waitForUpOrCancellation()
                        onTalkRelease()
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (talking) "Talking…" else "Hold to talk", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ControlsPanel(
    state: ConnectionState,
    talkError: String?,
    speed: Int,
    cruiseActive: Boolean,
    dayNightMode: Int,
    onReconnect: () -> Unit,
    onPtzDown: (PtzCommand) -> Unit,
    onPtzUp: () -> Unit,
    onPtzCancel: () -> Unit,
    onGotoPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
    onSpeed: (Int) -> Unit,
    onToggleCruise: () -> Unit,
    onCycleDayNight: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state is ConnectionState.Failed || state is ConnectionState.Disconnected) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (state as? ConnectionState.Failed)?.reason ?: state.label,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
                Button(onClick = onReconnect) { Text("Reconnect", fontSize = 12.sp) }
            }
        }
        talkError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }

        CompactPtzPad(onPtzDown, onPtzUp, onPtzCancel)

        // Presets + extras on one row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactPresets(onGotoPreset, onSavePreset)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniToggle(
                    icon = Icons.Default.Repeat,
                    active = cruiseActive,
                    label = "Cruise",
                    onClick = onToggleCruise,
                )
                MiniToggle(
                    icon = when (dayNightMode) {
                        1 -> Icons.Default.LightMode
                        2 -> Icons.Default.DarkMode
                        else -> Icons.Default.BrightnessAuto
                    },
                    active = dayNightMode != 0,
                    label = "Light",
                    onClick = onCycleDayNight,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", fontSize = 12.sp, modifier = Modifier.width(48.dp))
            Slider(
                value = speed.toFloat(),
                onValueChange = { onSpeed(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.weight(1f),
            )
            Text("$speed", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        }
    }
}

@Composable
private fun CompactPtzPad(
    onDown: (PtzCommand) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PtzPadButton(Icons.Default.NorthWest, "Up-left", { onDown(PtzCommand.DIRECTION_RIGHT_UP) }, onUp, onCancel)
            PtzPadButton(Icons.Default.North, "Up", { onDown(PtzCommand.DIRECTION_UP) }, onUp, onCancel)
            PtzPadButton(Icons.Default.NorthEast, "Up-right", { onDown(PtzCommand.DIRECTION_LEFT_UP) }, onUp, onCancel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PtzPadButton(Icons.Default.West, "Left", { onDown(PtzCommand.DIRECTION_RIGHT) }, onUp, onCancel)
            PtzPadButton(Icons.Default.Stop, "Stop", onUp, onUp, onUp)
            PtzPadButton(Icons.Default.East, "Right", { onDown(PtzCommand.DIRECTION_LEFT) }, onUp, onCancel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PtzPadButton(Icons.Default.SouthWest, "Down-left", { onDown(PtzCommand.DIRECTION_RIGHT_DOWN) }, onUp, onCancel)
            PtzPadButton(Icons.Default.South, "Down", { onDown(PtzCommand.DIRECTION_DOWN) }, onUp, onCancel)
            PtzPadButton(Icons.Default.SouthEast, "Down-right", { onDown(PtzCommand.DIRECTION_LEFT_DOWN) }, onUp, onCancel)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompactPresets(onGoto: (Int) -> Unit, onSave: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..4).forEach { n ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .combinedClickable(
                        onClick = { onGoto(n) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSave(n)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRecordingsPanel(
    querying: Boolean,
    clips: List<RecordedFile>?,
    downloading: String?,
    progress: Long,
    error: String?,
    onRefresh: () -> Unit,
    onClip: (RecordedFile) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SD card clips", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRefresh, enabled = !querying) {
                Text(if (querying) "Loading…" else "Refresh", fontSize = 12.sp)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        when {
            querying && clips == null -> CircularProgressIndicator(Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally))
            clips.isNullOrEmpty() -> Text("No recordings yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(clips.take(40), key = { it.fileName + it.beginTime }) { clip ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .combinedClickable(onClick = { onClip(clip) })
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = remember(clip.thumbPath) {
                            clip.thumbPath?.let { p ->
                                val f = File(p)
                                if (f.exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null
                            }
                        }
                        Box(
                            Modifier
                                .size(56.dp, 36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Default.Videocam, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(clip.beginTime.substringAfter(' ', clip.beginTime), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    downloading == clip.fileName -> "%.1f MB…".format(progress / 1e6)
                                    clip.isDownloaded -> "On device"
                                    "[M]" in clip.fileName -> "Motion"
                                    else -> "On camera"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            downloading == clip.fileName -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            clip.isDownloaded -> Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                            else -> Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 13.sp,
    )
}

@Composable
private fun ActionChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MiniToggle(icon: ImageVector, active: Boolean, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SmallOverlayBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).clip(CircleShape).background(OverlayBg),
    ) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatusPill(state: ConnectionState, playing: Boolean) {
    val (dot, text) = when {
        playing -> StatusGreen to "LIVE"
        state is ConnectionState.Authenticated || state is ConnectionState.Streaming -> StatusGreen to "CONNECTED"
        state is ConnectionState.Failed || state is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error to "OFFLINE"
        else -> StatusAmber to state.label.uppercase()
    }
    Row(
        Modifier.clip(CircleShape).background(OverlayBg).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatBitrate(bps: Long): String {
    val kbps = bps / 1000.0
    return if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000) else "%.0f kbps".format(kbps)
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000) % 60
    val m = (ms / 1000) / 60
    return "%d:%02d".format(m, s)
}

@UnstableApi
@Composable
private fun VideoSurface(viewModel: LiveControlViewModel, rtspState: RtspPlayerState, modifier: Modifier) {
    val exoPlayer = viewModel.rtspPlayer.exoPlayer
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                playerView?.let { it.player = null; it.player = exoPlayer }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.bindPlayerView(null)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView = this
                    viewModel.bindPlayerView(this)
                }
            },
            update = { v -> playerView = v; viewModel.bindPlayerView(v) },
        )
        if (rtspState !is RtspPlayerState.Playing) {
            Text(
                when (val s = rtspState) {
                    is RtspPlayerState.Idle -> "Idle"
                    is RtspPlayerState.Connecting -> "Connecting…"
                    is RtspPlayerState.Error -> "Video error: ${s.message}"
                    is RtspPlayerState.Playing -> ""
                },
                color = Color.White.copy(0.7f),
            )
        }
    }
}

@UnstableApi
@Composable
private fun LocalPlayerDialog(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(OverlayBg),
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

@UnstableApi
@Composable
private fun FunkytownDanceDialog(onDismiss: () -> Unit, onStart: () -> Unit) {
    LaunchedEffect(Unit) { onStart() }
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(LiveControlViewModel.DANCE_MEDIA_URL))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false } },
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(OverlayBg),
            ) {
                Icon(Icons.Default.Close, "Stop", tint = Color.White)
            }
        }
    }
}
