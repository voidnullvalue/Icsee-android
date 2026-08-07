package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import kotlin.math.atan2
import kotlin.math.hypot

private val StatusGreen = Color(0xFF4ADE80)
private val StatusAmber = Color(0xFFFBBF24)
private val OverlayBg = Color(0x99000000)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveControlScreen(
    cameraId: String,
    onOpenDiagnostics: () -> Unit,
    onOpenDeviceManagement: () -> Unit,
    onOpenImageSettings: () -> Unit,
    onOpenMotionDetect: () -> Unit,
    onBack: () -> Unit,
    viewModel: LiveControlViewModel = viewModel(),
) {
    val camera by viewModel.camera.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val speed by viewModel.speedStep.collectAsState()
    val talking by viewModel.talking.collectAsState()
    val talkError by viewModel.talkError.collectAsState()
    val talkFrames by viewModel.talkFrames.collectAsState()
    val rtspState by viewModel.rtspState.collectAsState()
    val danceModeTriggered by viewModel.danceModeTriggered.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val bitrateBps by viewModel.bitrateBps.collectAsState()
    val cruiseActive by viewModel.cruiseActive.collectAsState()
    val dayNightMode by viewModel.dayNightMode.collectAsState()
    val statusToast by viewModel.statusToast.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val recordElapsedMs by viewModel.recordElapsedMs.collectAsState()

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
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(statusToast) {
        if (statusToast != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearStatusToast()
        }
    }

    DisposableEffect(fullscreen) {
        val act = activity
        val window = act?.window
        if (fullscreen && act != null && window != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { c ->
                c.hide(WindowInsetsCompat.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else if (act != null && window != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (act != null && window != null) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = fullscreen) {
        scale = 1f; offsetX = 0f; offsetY = 0f
        fullscreen = false
    }

    fun exitFullscreen() {
        scale = 1f; offsetX = 0f; offsetY = 0f
        fullscreen = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Video layer
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offsetX, translationY = offsetY,
                )
                .pointerInput(fullscreen) {
                    if (fullscreen) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1.01f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                        }
                    }
                }
                .then(
                    if (fullscreen) Modifier.pointerInput(Unit) {
                        var current: PtzCommand? = null
                        var total = androidx.compose.ui.geometry.Offset.Zero
                        detectDragGestures(
                            onDragStart = { total = androidx.compose.ui.geometry.Offset.Zero; current = null },
                            onDragEnd = { if (current != null) viewModel.onPtzUp(); current = null },
                            onDragCancel = { if (current != null) viewModel.onPtzCancel(); current = null },
                            onDrag = { change, amount ->
                                if (scale > 1.05f) return@detectDragGestures
                                change.consume()
                                total += amount
                                val next = dragToPtz(total.x, total.y)
                                if (next != null && next != current) {
                                    if (current == null) viewModel.onPtzDown(next) else viewModel.onPtzDirectionChange(next)
                                    current = next
                                }
                            },
                        )
                    } else Modifier,
                ),
        ) {
            VideoSurface(viewModel, rtspState, Modifier.fillMaxSize())
        }

        // Top chrome
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (fullscreen) exitFullscreen() else onBack() }) {
                Icon(
                    if (fullscreen) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    camera?.displayName ?: "Live",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                StatusPill(state = state, playing = rtspState is RtspPlayerState.Playing)
            }
            if (!fullscreen) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Device management") },
                            onClick = { menuOpen = false; onOpenDeviceManagement() },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Image settings") },
                            onClick = { menuOpen = false; onOpenImageSettings() },
                            leadingIcon = { Icon(Icons.Default.LightMode, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Motion detection") },
                            onClick = { menuOpen = false; onOpenMotionDetect() },
                            leadingIcon = { Icon(Icons.Default.Sensors, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = { menuOpen = false; onOpenDiagnostics() },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        )
                    }
                }
            }
        }

        // Overlay action cluster (right)
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OverlayChip(
                label = if (camera?.streamType == StreamType.MAIN) "FHD" else "HD",
                icon = if (camera?.streamType == StreamType.MAIN) Icons.Default.HighQuality else Icons.Default.Hd,
            ) {
                val next = if (camera?.streamType == StreamType.MAIN) StreamType.SUB else StreamType.MAIN
                viewModel.setStreamType(next)
            }
            OverlayIcon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (muted) "Unmute" else "Mute") {
                viewModel.toggleMute()
            }
            OverlayIcon(Icons.Default.CameraAlt, "Snapshot") { viewModel.takeSnapshot() }
            OverlayIcon(
                Icons.Default.Videocam,
                if (recording) "Stop" else "Record",
                tint = if (recording) Color(0xFFFF6B6B) else Color.White,
            ) { viewModel.toggleRecording() }
            HoldOverlayIcon(Icons.Default.ZoomIn, "Zoom+") { down ->
                if (down) viewModel.zoomIn() else viewModel.zoomStop()
            }
            HoldOverlayIcon(Icons.Default.ZoomOut, "Zoom-") { down ->
                if (down) viewModel.zoomOut() else viewModel.zoomStop()
            }
            OverlayIcon(
                Icons.Default.Repeat,
                "Cruise",
                tint = if (cruiseActive) StatusGreen else Color.White,
            ) { viewModel.toggleCruise() }
            OverlayIcon(
                when (dayNightMode) {
                    1 -> Icons.Default.LightMode
                    2 -> Icons.Default.DarkMode
                    else -> Icons.Default.BrightnessAuto
                },
                "Day/Night",
            ) {
                val next = when (dayNightMode) { 0 -> 1; 1 -> 2; else -> 0 }
                viewModel.setDayNightMode(next)
            }
            if (!fullscreen) {
                OverlayIcon(Icons.Default.Fullscreen, "Fullscreen") { fullscreen = true }
            }
        }

        // Bitrate + record timer
        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 72.dp, top = 48.dp),
        ) {
            if (bitrateBps > 0) {
                Text(
                    formatBitrate(bitrateBps),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(OverlayBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (recording) {
                Text(
                    "REC ${formatDuration(recordElapsedMs)}",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OverlayBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        statusToast?.let { msg ->
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(OverlayBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Bottom controls (portrait / non-fullscreen)
        if (!fullscreen) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.needsAttention) {
                    ReconnectBanner(state, onReconnect = viewModel::reconnect)
                }
                TalkButton(
                    talking = talking,
                    onPress = {
                        if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else viewModel.startTalk()
                    },
                    onRelease = viewModel::stopTalk,
                    permissionKey = hasMicPermission,
                )
                talkError?.let {
                    Text("Talk error: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (talking) {
                    Text("mic frames sent: $talkFrames", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PtzPad(
                        onDown = viewModel::onPtzDown,
                        onUp = viewModel::onPtzUp,
                        onCancel = viewModel::onPtzCancel,
                        onDirectionChange = viewModel::onPtzDirectionChange,
                    )
                }
                PresetBar(onGoto = viewModel::gotoPreset, onSave = viewModel::setPreset)
                SpeedControl(speed = speed, onChange = viewModel::setSpeedStep)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (danceModeTriggered) {
        FunkytownDanceDialog(
            onDismiss = {
                viewModel.stopDance()
                viewModel.dismissDanceTrigger()
            },
            onStart = viewModel::startDance,
        )
    }
}

private fun formatBitrate(bps: Long): String {
    val kbps = bps / 1000.0
    return if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "%.0f kbps".format(kbps)
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000) % 60
    val m = (ms / 1000) / 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun OverlayIcon(icon: ImageVector, desc: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(OverlayBg),
    ) {
        Icon(icon, contentDescription = desc, tint = tint)
    }
}

@Composable
private fun HoldOverlayIcon(icon: ImageVector, desc: String, onHold: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(OverlayBg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onHold(true)
                    waitForUpOrCancellation()
                    onHold(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun OverlayChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(OverlayBg)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(OverlayBg),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Stop dance", tint = Color.White)
            }
        }
    }
}

private fun dragToPtz(dx: Float, dy: Float): PtzCommand? {
    if (hypot(dx, dy) < 48f) return null
    val deg = ((Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())) % 360) + 360) % 360
    return when {
        deg < 22.5 || deg >= 337.5 -> PtzCommand.DIRECTION_LEFT
        deg < 67.5 -> PtzCommand.DIRECTION_LEFT_UP
        deg < 112.5 -> PtzCommand.DIRECTION_UP
        deg < 157.5 -> PtzCommand.DIRECTION_RIGHT_UP
        deg < 202.5 -> PtzCommand.DIRECTION_RIGHT
        deg < 247.5 -> PtzCommand.DIRECTION_RIGHT_DOWN
        deg < 292.5 -> PtzCommand.DIRECTION_DOWN
        else -> PtzCommand.DIRECTION_LEFT_DOWN
    }
}

private val ConnectionState.needsAttention: Boolean
    get() = this is ConnectionState.Failed || this is ConnectionState.Disconnected

@Composable
private fun StatusPill(state: ConnectionState, playing: Boolean, modifier: Modifier = Modifier) {
    val (dot, text) = when {
        playing && (state is ConnectionState.Streaming || state is ConnectionState.Authenticated) -> StatusGreen to "LIVE"
        state is ConnectionState.Streaming || state is ConnectionState.Authenticated -> StatusGreen to "CONNECTED"
        state is ConnectionState.Failed -> MaterialTheme.colorScheme.error to "OFFLINE"
        state is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error to "OFFLINE"
        else -> StatusAmber to state.label.uppercase()
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(OverlayBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReconnectBanner(state: ConnectionState, onReconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (state) {
                is ConnectionState.Failed -> "Failed: ${state.reason}"
                else -> state.label
            },
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Button(onClick = onReconnect) { Text("Reconnect") }
    }
}

@Composable
private fun TalkButton(
    talking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    permissionKey: Boolean,
) {
    val container by animateColorAsState(
        if (talking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        label = "talkBg",
    )
    val content = if (talking) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val pulse = rememberInfiniteTransition(label = "talkPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (talking) 1.03f else 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "talkScale",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .pointerInput(permissionKey) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress()
                    waitForUpOrCancellation()
                    onRelease()
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            if (talking) "Talking…" else "Hold to talk",
            color = content,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SpeedControl(speed: Int, onChange: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PTZ speed", fontWeight = FontWeight.Medium)
            Text("$speed", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = speed.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
        )
    }
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
                playerView?.let {
                    it.player = null
                    it.player = exoPlayer
                }
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
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView = this
                    viewModel.bindPlayerView(this)
                }
            },
            update = { view ->
                playerView = view
                viewModel.bindPlayerView(view)
            },
        )
        if (rtspState !is RtspPlayerState.Playing) {
            Text(
                when (val s = rtspState) {
                    is RtspPlayerState.Idle -> "Idle"
                    is RtspPlayerState.Connecting -> "Connecting…"
                    is RtspPlayerState.Error -> "Video error: ${s.message}"
                    is RtspPlayerState.Playing -> ""
                },
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetBar(onGoto: (Int) -> Unit, onSave: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Presets — tap to recall, hold to save",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            (1..4).forEach { n ->
                Box(
                    Modifier
                        .size(44.dp)
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
                    Text("$n", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun PtzPad(
    onDown: (PtzCommand) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
    onDirectionChange: (PtzCommand) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzPadButton(Icons.Default.NorthWest, "Up-left", { onDown(PtzCommand.DIRECTION_RIGHT_UP) }, onUp, onCancel)
            PtzPadButton(Icons.Default.North, "Up", { onDown(PtzCommand.DIRECTION_UP) }, onUp, onCancel)
            PtzPadButton(Icons.Default.NorthEast, "Up-right", { onDown(PtzCommand.DIRECTION_LEFT_UP) }, onUp, onCancel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzPadButton(Icons.Default.West, "Left", { onDown(PtzCommand.DIRECTION_RIGHT) }, onUp, onCancel)
            PtzPadButton(Icons.Default.Stop, "Stop", onUp, onUp, onUp)
            PtzPadButton(Icons.Default.East, "Right", { onDown(PtzCommand.DIRECTION_LEFT) }, onUp, onCancel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzPadButton(Icons.Default.SouthWest, "Down-left", { onDown(PtzCommand.DIRECTION_RIGHT_DOWN) }, onUp, onCancel)
            PtzPadButton(Icons.Default.South, "Down", { onDown(PtzCommand.DIRECTION_DOWN) }, onUp, onCancel)
            PtzPadButton(Icons.Default.SouthEast, "Down-right", { onDown(PtzCommand.DIRECTION_LEFT_DOWN) }, onUp, onCancel)
        }
    }
}
