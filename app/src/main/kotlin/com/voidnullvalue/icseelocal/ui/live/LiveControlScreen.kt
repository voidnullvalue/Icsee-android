package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenShare
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.ui.MainActivity
import com.voidnullvalue.icseelocal.ui.components.CameraStreamPlayer
import com.voidnullvalue.icseelocal.ui.components.PlaybackStatusChip
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementViewModel
import com.voidnullvalue.icseelocal.ui.devicemanagement.RecordedFile
import com.voidnullvalue.icseelocal.ui.devicemanagement.RecordingDayTimeline
import com.voidnullvalue.icseelocal.video.isOnAir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val OverlayBg = Color(0x99000000)

private enum class LiveBottomTab { Controls, Recordings, Saved }

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
    onOpenAvTalk: (() -> Unit)?,
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
    val mainStream by viewModel.mainStream.collectAsState()
    val cruiseActive by viewModel.cruiseActive.collectAsState()
    val lightOn by viewModel.lightOn.collectAsState()
    val lightingCaps by viewModel.lightingCaps.collectAsState()
    val statusToast by viewModel.statusToast.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val recordElapsedMs by viewModel.recordElapsedMs.collectAsState()
    val presetThumbs by viewModel.presetThumbPaths.collectAsState()
    val presetThumbEpoch by viewModel.presetThumbEpoch.collectAsState()
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
    var fsChromeVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var bottomTab by remember { mutableStateOf(LiveBottomTab.Controls) }
    // Digital zoom (view transform — not PTZ optical zoom).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampPan(raw: Offset, s: Float): Offset {
        if (s <= 1.01f || viewportSize.width == 0) return Offset.Zero
        val maxX = viewportSize.width * (s - 1f) / 2f
        val maxY = viewportSize.height * (s - 1f) / 2f
        return Offset(
            raw.x.coerceIn(-maxX, maxX),
            raw.y.coerceIn(-maxY, maxY),
        )
    }
    fun resetZoom() { scale = 1f; offset = Offset.Zero }
    fun bumpZoom(factor: Float) {
        val next = (scale * factor).coerceIn(1f, 5f)
        scale = next
        offset = clampPan(offset, next)
    }

    var localMedia by remember { mutableStateOf<List<com.voidnullvalue.icseelocal.storage.LocalMediaItem>>(emptyList()) }
    var localMediaPlayUri by remember { mutableStateOf<String?>(null) }
    var localMediaImageUri by remember { mutableStateOf<String?>(null) }

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
        if (bottomTab == LiveBottomTab.Saved) {
            localMedia = withContext(Dispatchers.IO) {
                com.voidnullvalue.icseelocal.storage.LocalMediaLibrary.listAll(context)
            }
        }
    }
    LaunchedEffect(fullscreen, fsChromeVisible) {
        if (fullscreen && fsChromeVisible) {
            kotlinx.coroutines.delay(3500)
            fsChromeVisible = false
        }
    }

    val keepAwake = fullscreen ||
        rtspState.isOnAir ||
        dmState.playUri != null ||
        dmState.playBuffering ||
        localMediaPlayUri != null ||
        localMediaImageUri != null
    DisposableEffect(keepAwake) {
        val window = activity?.window
        if (keepAwake && window != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val mainActivity = activity as? MainActivity
    DisposableEffect(rtspState.isOnAir) {
        mainActivity?.setPipEligible(rtspState.isOnAir)
        onDispose { mainActivity?.setPipEligible(false) }
    }

    // Immersive chrome + force landscape in fullscreen. configChanges on the
    // Activity keeps the nav stack alive across the orientation change.
    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            fsChromeVisible = true
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).let { c ->
                    c.hide(WindowInsetsCompat.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
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
            .background(Color.Black),
    ) {
        if (!fullscreen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F14))
                    .statusBarsPadding()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(camera?.displayName ?: "Live", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaybackStatusChip(rtspState)
                        if (bitrateBps > 0) {
                            Text(formatBitrate(bitrateBps), color = Color.White.copy(0.7f), fontSize = 11.sp)
                        }
                        if (recording) {
                            Text("REC ${formatDuration(recordElapsedMs)}", color = Color(0xFFFF6B6B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Device management") }, onClick = { menuOpen = false; onOpenDeviceManagement() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                        DropdownMenuItem(text = { Text("Image settings") }, onClick = { menuOpen = false; onOpenImageSettings() }, leadingIcon = { Icon(Icons.Default.LightMode, null) })
                        DropdownMenuItem(text = { Text("Motion detection") }, onClick = { menuOpen = false; onOpenMotionDetect() }, leadingIcon = { Icon(Icons.Default.Sensors, null) })
                        DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menuOpen = false; onOpenDiagnostics() }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                        DropdownMenuItem(text = { Text("Light features…") }, onClick = { menuOpen = false; viewModel.reportLightingCaps() }, leadingIcon = { Icon(Icons.Default.Info, null) })
                    }
                }
            }
            statusToast?.let { msg ->
                Text(
                    msg,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2330))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(if (fullscreen) 1f else 0.42f)
                .clip(if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(0.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(fullscreen) {
                        detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                            val oldScale = scale
                            val newScale = (oldScale * zoom).coerceIn(1f, 5f)
                            if (newScale <= 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                                return@detectTransformGestures
                            }
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val focus = Offset(centroid.x - cx, centroid.y - cy)
                            val ratio = newScale / oldScale
                            val zoomed = Offset(
                                offset.x * ratio + focus.x * (1f - ratio),
                                offset.y * ratio + focus.y * (1f - ratio),
                            )
                            scale = newScale
                            offset = clampPan(zoomed + pan, newScale)
                            if (fullscreen) fsChromeVisible = true
                        }
                    }
                    .pointerInput(fullscreen) {
                        if (fullscreen) {
                            detectTapGestures { fsChromeVisible = !fsChromeVisible }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                CameraStreamPlayer(
                    exoPlayer = viewModel.rtspPlayer.exoPlayer,
                    playbackState = rtspState,
                    modifier = Modifier.fillMaxSize(),
                    title = if (fullscreen) camera?.displayName else null,
                    mainStream = mainStream,
                    muted = muted,
                    recording = recording,
                    recordElapsedLabel = if (recording) formatDuration(recordElapsedMs) else null,
                    bitrateLabel = if (bitrateBps > 0) formatBitrate(bitrateBps) else null,
                    showOverlay = true,
                    overlayInitiallyVisible = fullscreen,
                    fullscreen = fullscreen,
                    onBindPlayerView = viewModel::bindPlayerView,
                    onToggleMute = viewModel::toggleMute,
                    onToggleQuality = {
                        viewModel.setStreamType(
                            if (mainStream) StreamType.SUB else StreamType.MAIN,
                        )
                    },
                    onSnapshot = viewModel::takeSnapshot,
                    onToggleRecording = viewModel::toggleRecording,
                    onReconnect = viewModel::reconnectRtsp,
                    onFullscreen = { fullscreen = true },
                    onExitFullscreen = { resetZoom(); fullscreen = false },
                    onEnterPip = { mainActivity?.enterPipIfEligible() },
                    onOverlayVisibilityChanged = { visible -> if (fullscreen) fsChromeVisible = visible },
                )
            }

            // Legacy fullscreen top chrome removed — CameraStreamPlayer owns overlay.
            if (fullscreen) {
                statusToast?.let { msg ->
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(OverlayBg)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (!fullscreen) {
            // —— Action row directly under stream (zoom / talk live here) ——
            StreamActionRow(
                mainStream = mainStream,
                muted = muted,
                recording = recording,
                talking = talking,
                hasMicPermission = hasMicPermission,
                canResetZoom = scale > 1.01f,
                onToggleQuality = {
                    viewModel.setStreamType(
                        if (mainStream) StreamType.SUB else StreamType.MAIN,
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
                onZoomIn = { bumpZoom(1.25f) },
                onZoomOut = { bumpZoom(0.8f) },
                onResetZoom = { resetZoom() },
                onFullscreen = { fullscreen = true },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabChip("Controls", bottomTab == LiveBottomTab.Controls) { bottomTab = LiveBottomTab.Controls }
                TabChip("Recordings", bottomTab == LiveBottomTab.Recordings) { bottomTab = LiveBottomTab.Recordings }
                TabChip("Saved", bottomTab == LiveBottomTab.Saved) { bottomTab = LiveBottomTab.Saved }
                Spacer(Modifier.weight(1f))
                if (bottomTab == LiveBottomTab.Recordings) {
                    TextButton(onClick = onOpenFullRecordings) { Text("Full timeline", fontSize = 12.sp) }
                }
                if (bottomTab == LiveBottomTab.Saved) {
                    TextButton(onClick = {
                        localMedia = com.voidnullvalue.icseelocal.storage.LocalMediaLibrary.listAll(context)
                    }) { Text("Refresh", fontSize = 12.sp) }
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
                        lightOn = lightOn,
                        lightingSupported = lightingCaps.hasAnyLightControl,
                        lightingSummary = lightingCaps.summary,
                        presetThumbs = presetThumbs,
                        presetThumbEpoch = presetThumbEpoch,
                        onReconnect = viewModel::reconnect,
                        onPtzDown = viewModel::onPtzDown,
                        onPtzUp = viewModel::onPtzUp,
                        onPtzCancel = viewModel::onPtzCancel,
                        onGotoPreset = viewModel::gotoPreset,
                        onSavePreset = viewModel::setPreset,
                        onOpenAvTalk = onOpenAvTalk,
                        onSpeed = viewModel::setSpeedStep,
                        onToggleCruise = viewModel::toggleCruise,
                        onToggleLight = viewModel::toggleLight,
                        onLongPressLight = viewModel::reportLightingCaps,
                    )
                    LiveBottomTab.Recordings -> LiveRecordingsPanel(
                        querying = dmState.recordingsQuerying,
                        clips = dmState.recordings,
                        selectedDay = dmState.selectedRecordingDay,
                        downloading = dmState.downloadingClip,
                        progress = dmState.downloadProgressBytes,
                        playBuffering = dmState.playBuffering,
                        error = dmState.errorMessage,
                        onRefresh = deviceManagementViewModel::loadAllRecordings,
                        onSelectDay = deviceManagementViewModel::selectRecordingDay,
                        onPlay = deviceManagementViewModel::playClip,
                        onDownload = deviceManagementViewModel::downloadClip,
                        onPlayRange = deviceManagementViewModel::playTimeRange,
                        onDownloadRange = deviceManagementViewModel::downloadTimeRange,
                    )
                    LiveBottomTab.Saved -> SavedMediaPanel(
                        items = localMedia,
                        onOpen = { item ->
                            if (item.isVideo) {
                                localMediaImageUri = null
                                localMediaPlayUri = item.uri
                            } else {
                                localMediaPlayUri = null
                                localMediaImageUri = item.uri
                            }
                        },
                    )
                }
            }
        }
    }

    if (dmState.playBuffering || dmState.playUri != null || dmState.playError != null) {
        com.voidnullvalue.icseelocal.ui.devicemanagement.ClipPlayerDialog(
            uri = dmState.playUri,
            buffering = dmState.playBuffering,
            progressBytes = dmState.playProgressBytes,
            title = dmState.playTitle,
            error = dmState.playError,
            onDismiss = deviceManagementViewModel::clearPlayUri,
        )
    }
    localMediaPlayUri?.let { uri ->
        com.voidnullvalue.icseelocal.ui.devicemanagement.ClipPlayerDialog(
            uri = uri,
            buffering = false,
            progressBytes = 0,
            title = "Saved video",
            error = null,
            onDismiss = { localMediaPlayUri = null },
        )
    }
    localMediaImageUri?.let { uri ->
        SavedImageDialog(uri = uri, onDismiss = { localMediaImageUri = null })
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
    canResetZoom: Boolean,
    onToggleQuality: () -> Unit,
    onToggleMute: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onTalkPress: () -> Unit,
    onTalkRelease: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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
        ActionIcon(Icons.Default.ZoomIn, onZoomIn)
        ActionIcon(Icons.Default.ZoomOut, onZoomOut)
        if (canResetZoom) {
            ActionIcon(Icons.Default.RestartAlt, onResetZoom)
        }
        ActionIcon(Icons.Default.Fullscreen, onFullscreen)
        val talkBg by animateColorAsState(
            if (talking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            label = "talk",
        )
        Row(
            Modifier
                .widthIn(min = 120.dp)
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
                }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (talking) "Talking…" else "Hold to talk", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlsPanel(
    state: ConnectionState,
    talkError: String?,
    speed: Int,
    cruiseActive: Boolean,
    lightOn: Boolean,
    lightingSupported: Boolean,
    lightingSummary: String,
    presetThumbs: Map<Int, String>,
    presetThumbEpoch: Long,
    onReconnect: () -> Unit,
    onPtzDown: (PtzCommand) -> Unit,
    onPtzUp: () -> Unit,
    onPtzCancel: () -> Unit,
    onGotoPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
    onOpenAvTalk: (() -> Unit)?,
    onSpeed: (Int) -> Unit,
    onToggleCruise: () -> Unit,
    onToggleLight: () -> Unit,
    onLongPressLight: () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val hasStatusMessage = state is ConnectionState.Failed ||
            state is ConnectionState.Disconnected ||
            talkError != null
        val useScrollableLayout = maxHeight < 300.dp || hasStatusMessage
        val scrollState = rememberScrollState()
        val panelModifier = if (useScrollableLayout) {
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        } else {
            Modifier.fillMaxSize()
        }

        Column(
            panelModifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = if (useScrollableLayout) {
                Arrangement.spacedBy(8.dp)
            } else {
                Arrangement.SpaceEvenly
            },
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactPtzPad(onPtzDown, onPtzUp, onPtzCancel, Modifier.weight(1f, fill = false))
                CompactPresets(
                    thumbs = presetThumbs,
                    thumbEpoch = presetThumbEpoch,
                    onGoto = onGotoPreset,
                    onSave = onSavePreset,
                    onOpenAvTalk = onOpenAvTalk,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniToggle(
                    icon = Icons.Default.Repeat,
                    active = cruiseActive,
                    label = "Cruise",
                    onClick = onToggleCruise,
                )
                Box(
                    Modifier.combinedClickable(
                        onClick = onToggleLight,
                        onLongClick = onLongPressLight,
                    ),
                ) {
                    MiniToggle(
                        icon = if (lightOn) Icons.Default.LightMode else Icons.Default.DarkMode,
                        active = lightOn || lightingSupported,
                        label = when {
                            !lightingSupported -> "Light?"
                            lightOn -> "Light on"
                            else -> "Light"
                        },
                        onClick = onToggleLight,
                    )
                }
                Text(
                    lightingSummary,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
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
}

@Composable
private fun CompactPtzPad(
    onDown: (PtzCommand) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PtzPadButton(Icons.Default.NorthWest, "Up-left", { onDown(PtzCommand.DIRECTION_LEFT_UP) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.North, "Up", { onDown(PtzCommand.DIRECTION_UP) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.NorthEast, "Up-right", { onDown(PtzCommand.DIRECTION_RIGHT_UP) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PtzPadButton(Icons.Default.West, "Left", { onDown(PtzCommand.DIRECTION_LEFT) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.Stop, "Stop", onUp, onUp, onUp, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.East, "Right", { onDown(PtzCommand.DIRECTION_RIGHT) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PtzPadButton(Icons.Default.SouthWest, "Down-left", { onDown(PtzCommand.DIRECTION_LEFT_DOWN) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.South, "Down", { onDown(PtzCommand.DIRECTION_DOWN) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
            PtzPadButton(Icons.Default.SouthEast, "Down-right", { onDown(PtzCommand.DIRECTION_RIGHT_DOWN) }, onUp, onCancel, sizeDp = 56, iconDp = 28)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactPresets(
    thumbs: Map<Int, String>,
    thumbEpoch: Long,
    onGoto: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onOpenAvTalk: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Tap go · hold save",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        listOf(1 to 2, 3 to 4).forEach { (a, b) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(a, b).forEach { n ->
                    val path = thumbs[n]
                    val bmp = remember(path, thumbEpoch) {
                        path?.let { p ->
                            val f = File(p)
                            if (f.exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1F26))
                            .combinedClickable(
                                onClick = { onGoto(n) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSave(n)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(
                                    id = com.voidnullvalue.icseelocal.R.drawable.ic_preset_placeholder,
                                ),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Text(
                            "$n",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        onOpenAvTalk?.let { openAvTalk ->
            OutlinedButton(
                onClick = openAvTalk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send to screen", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRecordingsPanel(
    querying: Boolean,
    clips: List<RecordedFile>?,
    selectedDay: String?,
    downloading: String?,
    progress: Long,
    playBuffering: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelectDay: (String) -> Unit,
    onPlay: (RecordedFile) -> Unit,
    onDownload: (RecordedFile) -> Unit,
    onPlayRange: (date: String, startTime: String, endTime: String) -> Unit = { _, _, _ -> },
    onDownloadRange: (date: String, startTime: String, endTime: String) -> Unit = { _, _, _ -> },
) {
    val all = clips.orEmpty()
    val dayKeys = remember(all) { all.map { it.dayKey }.distinct().sortedDescending() }
    val day = selectedDay ?: dayKeys.firstOrNull()
    val dayClips = remember(all, day) {
        all.filter { it.dayKey == day }.sortedByDescending { it.endTime.ifBlank { it.beginTime } }
    }
    val busy = downloading != null || playBuffering

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRefresh, enabled = !querying) {
                Text(if (querying) "Loading…" else "Refresh", fontSize = 12.sp)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }

        TimeRangeExportCard(
            selectedDay = day,
            busy = busy,
            onPlay = onPlayRange,
            onDownload = onDownloadRange,
        )

        if (dayKeys.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dayKeys.forEach { key ->
                    val count = all.count { it.dayKey == key }
                    RecordingDayChip(
                        label = recordingDayLabel(key),
                        count = count,
                        selected = key == day,
                        onClick = { onSelectDay(key) },
                    )
                }
            }
        }

        if (day != null && dayClips.isNotEmpty()) {
            RecordingDayTimeline(
                day = day,
                clips = dayClips,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                onClipClick = onPlay,
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            querying && clips == null -> CircularProgressIndicator(Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally))
            dayClips.isEmpty() -> Text(
                if (querying) "Loading…" else "No recordings for this day.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(dayClips, key = { it.fileName + it.beginTime }) { clip ->
                    LiveClipRow(
                        clip = clip,
                        downloading = downloading == clip.fileName,
                        progress = if (downloading == clip.fileName) progress else 0,
                        busy = busy,
                        onPlay = { onPlay(clip) },
                        onDownload = { onDownload(clip) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingDayChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("$count", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveClipRow(
    clip: RecordedFile,
    downloading: Boolean,
    progress: Long,
    busy: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val activityGreen = Color(0xFF22C55E)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(enabled = !busy || downloading, onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(60.dp)
                .background(if (clip.hasActivity) activityGreen else Color(0xFF38BDF8).copy(alpha = 0.45f)),
        )
        Row(
            Modifier
                .weight(1f)
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
                    .size(72.dp, 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        if (clip.hasActivity) Icons.Default.Sensors else Icons.Default.Videocam,
                        null,
                        Modifier.size(18.dp),
                        tint = if (clip.hasActivity) activityGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(clip.beginTime.substringAfter(' ', clip.beginTime), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clip.activityLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (clip.hasActivity) activityGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            downloading -> "%.1f MB…".format(progress / 1e6)
                            clip.isDownloaded -> "On device"
                            else -> "On camera"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            if (!clip.isDownloaded) {
                IconButton(onClick = onDownload, enabled = !busy || downloading) {
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun recordingDayLabel(day: String): String {
    val parsed = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return day
    val today = java.time.LocalDate.now()
    return when (parsed) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> parsed.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d"))
    }
}

@Composable
private fun SavedMediaPanel(
    items: List<com.voidnullvalue.icseelocal.storage.LocalMediaItem>,
    onOpen: (com.voidnullvalue.icseelocal.storage.LocalMediaItem) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Screenshots & downloads", fontWeight = FontWeight.SemiBold)
        Text(
            "From Pictures/Movies → iCSeeLocalControl",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (items.isEmpty()) {
            Text("No saved media yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.uri }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpen(item) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (item.isVideo) Icons.Default.Videocam else Icons.Default.CameraAlt,
                                null,
                                Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                if (item.isVideo) "Video" else "Screenshot",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (item.isVideo) Icons.Default.PlayArrow else Icons.Default.Fullscreen,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedImageDialog(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            val parsed = android.net.Uri.parse(uri)
            if (uri.startsWith("content:")) {
                context.contentResolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                val path = parsed.path ?: uri.removePrefix("file://")
                android.graphics.BitmapFactory.decodeFile(path)
            }?.asImageBitmap()
        }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Screenshot",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    "Couldn’t open image",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(OverlayBg),
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
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

@Composable
private fun TimeRangeExportCard(
    selectedDay: String?,
    busy: Boolean,
    onPlay: (date: String, startTime: String, endTime: String) -> Unit,
    onDownload: (date: String, startTime: String, endTime: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var startHour by remember { mutableIntStateOf(0) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(23) }
    var endMinute by remember { mutableIntStateOf(59) }

    val day = selectedDay ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Export by time range", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text("Date: $day", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Start", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TimeSpinner(hour = startHour, minute = startMinute, onHourChange = { startHour = it }, onMinuteChange = { startMinute = it })
                }
                Column(Modifier.weight(1f)) {
                    Text("End", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TimeSpinner(hour = endHour, minute = endMinute, onHourChange = { endHour = it }, onMinuteChange = { endMinute = it })
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val startStr = "%02d:%02d".format(startHour, startMinute)
                val endStr = "%02d:%02d".format(endHour, endMinute)
                val valid = startHour < endHour || (startHour == endHour && startMinute < endMinute)
                OutlinedButton(
                    onClick = { onPlay(day, startStr, endStr) },
                    enabled = !busy && valid,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", fontSize = 12.sp)
                }
                Button(
                    onClick = { onDownload(day, startStr, endStr) },
                    enabled = !busy && valid,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("Save", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TimeSpinner(hour: Int, minute: Int, onHourChange: (Int) -> Unit, onMinuteChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberSpinnerField(value = hour, range = 0..23, onChange = onHourChange, modifier = Modifier.width(44.dp))
        Text(":", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
        NumberSpinnerField(value = minute, range = 0..59, onChange = onMinuteChange, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun NumberSpinnerField(value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf("%02d".format(value)) }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            digits.toIntOrNull()?.coerceIn(range)?.let(onChange)
        },
        modifier = modifier.height(44.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
}
