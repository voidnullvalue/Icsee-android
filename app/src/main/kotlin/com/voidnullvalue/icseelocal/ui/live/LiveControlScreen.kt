package com.voidnullvalue.icseelocal.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import kotlin.math.atan2
import kotlin.math.hypot

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
    // Connecting/disconnecting is driven centrally by MainActivity (see
    // enterFocus/leaveFocus on the ViewModel) based on which Live-family screen
    // (this one or Diagnostics) is actually on screen -- not from here, since this
    // composable also unmounts when navigating to Diagnostics (same family, same
    // session must keep running) and there's no local way to tell that apart from
    // navigating away entirely.
    val camera by viewModel.camera.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val speed by viewModel.speedStep.collectAsState()
    val talking by viewModel.talking.collectAsState()
    val talkError by viewModel.talkError.collectAsState()
    val talkFrames by viewModel.talkFrames.collectAsState()
    val rtspState by viewModel.rtspState.collectAsState()
    val danceModeTriggered by viewModel.danceModeTriggered.collectAsState()

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

                PresetBar(onGoto = viewModel::gotoPreset, onSave = viewModel::setPreset)
                SpeedControl(speed = speed, onChange = viewModel::setSpeedStep)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Drag anywhere on the video to steer the camera: the drag
                    // direction maps to the same 8-way PTZ commands the on-screen
                    // pad uses. Tap-to-close is intentionally gone (it collided
                    // with dragging) -- use the X button instead.
                    .pointerInput(Unit) {
                        var current: PtzCommand? = null
                        var total = androidx.compose.ui.geometry.Offset.Zero
                        detectDragGestures(
                            onDragStart = { total = androidx.compose.ui.geometry.Offset.Zero; current = null },
                            onDragEnd = { if (current != null) viewModel.onPtzUp(); current = null },
                            onDragCancel = { if (current != null) viewModel.onPtzCancel(); current = null },
                            onDrag = { change, amount ->
                                change.consume()
                                total += amount
                                val next = dragToPtz(total.x, total.y)
                                if (next != null && next != current) {
                                    if (current == null) viewModel.onPtzDown(next) else viewModel.onPtzDirectionChange(next)
                                    current = next
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                VideoSurface(viewModel, rtspState, Modifier.fillMaxSize())
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Exit full screen", tint = Color.White)
                }
            }
        }
    }

    // Easter egg: Konami code on the PTZ pad (Up Up Down Down Left Right Left
    // Right) unlocks this. See KonamiCodeDetector/DanceChoreography.
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

/**
 * Easter egg overlay: shows YouTube's own official embedded player (same as
 * embedding a video on any website -- nothing downloaded, stored, or
 * redistributed) muted, purely for the on-screen visual, while the camera
 * speaker separately plays a local track via [onStart]
 * (LiveControlViewModel.startDance/FileAudioSource) -- muted so the phone
 * doesn't also play the video's own audio out loud alongside it. An earlier
 * version tried to relay the WebView's own audio via MediaProjection playback
 * capture instead of a local file; that didn't crash but produced no sound,
 * consistent with Android's playback-capture API refusing to capture
 * DRM-protected content (which embedded YouTube audio typically is).
 */
@Composable
private fun FunkytownDanceDialog(onDismiss: () -> Unit, onStart: () -> Unit) {
    LaunchedEffect(Unit) { onStart() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).also { webView ->
                        webView.settings.javaScriptEnabled = true
                        // The YouTube iframe player relies on DOM storage (localStorage/
                        // sessionStorage) for its own state -- without this it commonly
                        // fails silently and renders a blank white page instead of erroring.
                        webView.settings.domStorageEnabled = true
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        webView.settings.loadWithOverviewMode = true
                        webView.settings.useWideViewPort = true
                        // Without an explicit client, some WebView/Chromium versions won't
                        // fully load the embed's own sub-resources.
                        webView.webViewClient = WebViewClient()
                        webView.webChromeClient = WebChromeClient()
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(webView, true)
                        // mute=1: purely visual here -- audio comes from the local track via
                        // onStart/FileAudioSource instead, so the phone doesn't also play the
                        // video's own audio out loud alongside the camera speaker.
                        webView.loadUrl("https://www.youtube.com/embed/Z6dqIYKIBSU?autoplay=1&playsinline=1&mute=1")
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Stop dance", tint = Color.White)
            }
        }
    }
}

/**
 * Maps a drag vector (screen coords: +x right, +y down) to the 8-way PTZ
 * command for that visual direction, using the same left/right-swapped
 * convention as [PtzPad] (this camera pans opposite its DirectionLeft/Right
 * wire names). Returns null inside a small dead zone so a stationary press
 * doesn't move the camera.
 */
private fun dragToPtz(dx: Float, dy: Float): PtzCommand? {
    if (hypot(dx, dy) < 48f) return null
    // atan2 with -dy so 0deg = visual right, 90deg = visual up.
    val deg = ((Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())) % 360) + 360) % 360
    return when {
        deg < 22.5 || deg >= 337.5 -> PtzCommand.DIRECTION_LEFT       // right
        deg < 67.5 -> PtzCommand.DIRECTION_LEFT_UP                     // up-right
        deg < 112.5 -> PtzCommand.DIRECTION_UP                         // up
        deg < 157.5 -> PtzCommand.DIRECTION_RIGHT_UP                   // up-left
        deg < 202.5 -> PtzCommand.DIRECTION_RIGHT                      // left
        deg < 247.5 -> PtzCommand.DIRECTION_RIGHT_DOWN                 // down-left
        deg < 292.5 -> PtzCommand.DIRECTION_DOWN                       // down
        else -> PtzCommand.DIRECTION_LEFT_DOWN                         // down-right
    }
}

private val ConnectionState.needsAttention: Boolean
    get() = this is ConnectionState.Failed ||
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
    // PlayerView's underlying SurfaceView often comes back from a backgrounded app
    // with a blank/stale surface -- ExoPlayer itself keeps playing (rtspState never
    // leaves Playing, so nothing here recomposes on resume), it just isn't being
    // drawn to this particular View anymore. Reassigning the *same* player instance
    // is a no-op in PlayerView.setPlayer, which is why simple recomposition doesn't
    // fix it -- only a genuinely new PlayerView (e.g. the one Fullscreen creates)
    // gets a fresh bind. So force that same detach/reattach explicitly whenever the
    // surrounding Activity restarts (covers both real app-switch resume and the
    // window-surface churn Android does around backgrounding).
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView = this
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetBar(onGoto: (Int) -> Unit, onSave: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "Presets — tap to recall, hold to save",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            (1..4).forEach { n ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .combinedClickable(onClick = { onGoto(n) }, onLongClick = { onSave(n) }),
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
