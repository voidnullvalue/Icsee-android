package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.West
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.video.RtspPlayerState

private val StatusGreen = Color(0xFF4ADE80)
private val StatusAmber = Color(0xFFFBBF24)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveControlScreen(
    cameraId: String,
    onOpenDiagnostics: () -> Unit,
    onOpenDeviceManagement: () -> Unit,
    onBack: () -> Unit,
    viewModel: LiveControlViewModel = viewModel(),
) {
    LaunchedEffect(cameraId) { viewModel.load(cameraId) }
    val camera by viewModel.camera.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val speed by viewModel.speedStep.collectAsState()
    val talking by viewModel.talking.collectAsState()
    val talkError by viewModel.talkError.collectAsState()
    val talkFrames by viewModel.talkFrames.collectAsState()
    val rtspState by viewModel.rtspState.collectAsState()

    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    // Ask for mic permission as soon as the screen opens so the first talk press
    // actually captures instead of only triggering the permission prompt.
    LaunchedEffect(Unit) {
        if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    var fullscreen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(camera?.displayName ?: "Live", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDeviceManagement) {
                        Icon(Icons.Default.Settings, contentDescription = "Device management")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Default.Refresh, contentDescription = "Diagnostics")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Full-bleed video with rounded bottom + status/fullscreen overlays.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(Color.Black)
                    .clickable { fullscreen = true },
            ) {
                if (!fullscreen) {
                    VideoSurface(viewModel, rtspState, Modifier.fillMaxSize())
                }
                StatusPill(
                    state = state,
                    playing = rtspState is RtspPlayerState.Playing,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

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
                if (!hasMicPermission) {
                    Text(
                        "Microphone permission needed — tap the mic to grant.",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp,
                    )
                }
                talkError?.let {
                    Text(
                        "Talk error: $it",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                if (talking) {
                    Text(
                        "mic frames sent: $talkFrames",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }

                // PTZ pad expands to absorb free vertical space and stays centred.
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    PtzPad(
                        onDown = viewModel::onPtzDown,
                        onUp = viewModel::onPtzUp,
                        onCancel = viewModel::onPtzCancel,
                        onDirectionChange = viewModel::onPtzDirectionChange,
                    )
                }

                SpeedControl(speed = speed, onChange = viewModel::setSpeedStep)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { fullscreen = false },
                contentAlignment = Alignment.Center,
            ) {
                VideoSurface(viewModel, rtspState, Modifier.fillMaxSize())
            }
        }
    }
}

private val ConnectionState.needsAttention: Boolean
    get() = this is ConnectionState.Failed ||
        this is ConnectionState.Reconnecting ||
        this is ConnectionState.Disconnected

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
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                is ConnectionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})…"
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

    // Subtle pulse while transmitting so it's obvious the mic is hot.
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
            .height(84.dp)
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
        Icon(Icons.Default.Mic, contentDescription = null, tint = content, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            if (talking) "Talking…" else "Hold to talk",
            color = content,
            fontSize = 18.sp,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.rtspPlayer.exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
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

@Composable
private fun PtzPad(
    onDown: (PtzCommand) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
    onDirectionChange: (PtzCommand) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Left/right swapped relative to the DirectionLeft/DirectionRight wire names --
        // confirmed by visually watching this camera's actual pan direction (matches the
        // reference client's wire strings, so the mismatch is this camera's motor/mounting,
        // not a protocol bug). Swapped here rather than renaming PtzCommand so the enum keeps
        // matching the protocol's own naming.
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
