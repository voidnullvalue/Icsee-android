package com.voidnullvalue.icseelocal.ui.components

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.ui.theme.IcseeMotion
import com.voidnullvalue.icseelocal.ui.theme.statusColors
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import com.voidnullvalue.icseelocal.video.isOnAir
import kotlinx.coroutines.delay

/**
 * Modern Media3 RTSP surface with a translucent, auto-hiding control overlay.
 *
 * The [ExoPlayer] is owned by the caller (typically [com.voidnullvalue.icseelocal.video.RtspStreamManager]);
 * this composable only binds a [PlayerView] and draws chrome.
 */
@UnstableApi
@Composable
fun CameraStreamPlayer(
    exoPlayer: ExoPlayer,
    playbackState: RtspPlayerState,
    modifier: Modifier = Modifier,
    title: String? = null,
    mainStream: Boolean = true,
    muted: Boolean = false,
    recording: Boolean = false,
    recordElapsedLabel: String? = null,
    bitrateLabel: String? = null,
    showOverlay: Boolean = true,
    overlayInitiallyVisible: Boolean = true,
    fullscreen: Boolean = false,
    onBindPlayerView: ((PlayerView?) -> Unit)? = null,
    onToggleMute: (() -> Unit)? = null,
    onToggleQuality: (() -> Unit)? = null,
    onSnapshot: (() -> Unit)? = null,
    onToggleRecording: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    onExitFullscreen: (() -> Unit)? = null,
    onEnterPip: (() -> Unit)? = null,
    onOverlayVisibilityChanged: ((Boolean) -> Unit)? = null,
) {
    var chromeVisible by remember { mutableStateOf(overlayInitiallyVisible) }
    val statusColors = MaterialTheme.statusColors

    LaunchedEffect(chromeVisible, showOverlay, fullscreen, playbackState) {
        onOverlayVisibilityChanged?.invoke(chromeVisible)
        if (showOverlay && chromeVisible) {
            delay(IcseeMotion.CONTROLS_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    // Keep chrome up while reconnecting / errors so the user can act.
    LaunchedEffect(playbackState) {
        when (playbackState) {
            is RtspPlayerState.Reconnecting,
            is RtspPlayerState.Offline,
            is RtspPlayerState.AuthenticationFailed,
            is RtspPlayerState.Error,
            -> chromeVisible = true
            else -> Unit
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (showOverlay) chromeVisible = !chromeVisible
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(
            exoPlayer = exoPlayer,
            onBindPlayerView = onBindPlayerView,
            modifier = Modifier.fillMaxSize(),
        )

        StatusScrim(playbackState)

        if (showOverlay) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(IcseeMotion.overlayEnter),
                exit = fadeOut(IcseeMotion.overlayExit),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Top gradient + status
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                                ),
                            )
                            .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (fullscreen && onExitFullscreen != null) {
                                OverlayIconButton(Icons.Default.Close, "Exit fullscreen", onExitFullscreen)
                            }
                            Column(Modifier.weight(1f)) {
                                if (title != null) {
                                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PlaybackStatusChip(playbackState)
                                    if (bitrateLabel != null) {
                                        Text(bitrateLabel, color = Color.White.copy(0.7f), fontSize = 11.sp)
                                    }
                                    if (recording) {
                                        Text(
                                            recordElapsedLabel?.let { "REC $it" } ?: "REC",
                                            color = statusColors.recording,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            if (onEnterPip != null) {
                                OverlayIconButton(Icons.Default.PictureInPictureAlt, "Picture in picture", onEnterPip)
                            }
                        }
                    }

                    // Bottom controls
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                                ),
                            )
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToggleQuality != null) {
                            OverlayIconButton(
                                if (mainStream) Icons.Default.Hd else Icons.Default.Sd,
                                if (mainStream) "Switch to SD" else "Switch to HD",
                                onToggleQuality,
                            )
                        }
                        if (onToggleMute != null) {
                            OverlayIconButton(
                                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                if (muted) "Unmute" else "Mute",
                                onToggleMute,
                            )
                        }
                        if (onSnapshot != null) {
                            OverlayIconButton(Icons.Default.CameraAlt, "Snapshot", onSnapshot)
                        }
                        if (onToggleRecording != null) {
                            OverlayIconButton(
                                if (recording) Icons.Default.Stop else Icons.Default.Videocam,
                                if (recording) "Stop recording" else "Record",
                                onToggleRecording,
                                tint = if (recording) statusColors.recording else Color.White,
                            )
                        }
                        if (onReconnect != null && !playbackState.isOnAir) {
                            OverlayIconButton(Icons.Default.Refresh, "Reconnect stream", onReconnect)
                        }
                        if (!fullscreen && onFullscreen != null) {
                            OverlayIconButton(Icons.Default.Fullscreen, "Fullscreen", onFullscreen)
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerSurface(
    exoPlayer: ExoPlayer,
    onBindPlayerView: ((PlayerView?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                playerView?.let { it.player = null; it.player = exoPlayer }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerView?.player = null
            onBindPlayerView?.invoke(null)
        }
    }
    AndroidView(
        modifier = modifier,
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
                onBindPlayerView?.invoke(this)
            }
        },
        update = { v ->
            if (v.player !== exoPlayer) v.player = exoPlayer
            playerView = v
            onBindPlayerView?.invoke(v)
        },
    )
}

@Composable
private fun StatusScrim(state: RtspPlayerState) {
    val statusColors = MaterialTheme.statusColors
    when (state) {
        is RtspPlayerState.Live -> Unit
        is RtspPlayerState.Idle -> {
            Text("Idle", color = Color.White.copy(0.6f))
        }
        is RtspPlayerState.Connecting -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = statusColors.buffering, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Text("Connecting…", color = Color.White.copy(0.8f), fontSize = 13.sp)
            }
        }
        is RtspPlayerState.Buffering -> {
            CircularProgressIndicator(color = statusColors.buffering, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        is RtspPlayerState.Reconnecting -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = statusColors.reconnecting, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Text("Reconnecting…", color = statusColors.reconnecting, fontSize = 13.sp)
            }
        }
        is RtspPlayerState.AuthenticationFailed -> {
            Text("Auth failed: ${state.message}", color = statusColors.authFailed, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        is RtspPlayerState.Offline -> {
            Text("Offline: ${state.message}", color = statusColors.offline, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        is RtspPlayerState.Error -> {
            Text("Video error: ${state.message}", color = statusColors.authFailed, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun PlaybackStatusChip(state: RtspPlayerState) {
    val statusColors = MaterialTheme.statusColors
    val (dot, label) = when (state) {
        is RtspPlayerState.Live -> statusColors.live to "LIVE"
        is RtspPlayerState.Buffering -> statusColors.buffering to "BUFFERING"
        is RtspPlayerState.Connecting -> statusColors.buffering to "CONNECTING"
        is RtspPlayerState.Reconnecting -> statusColors.reconnecting to "RECONNECTING"
        is RtspPlayerState.AuthenticationFailed -> statusColors.authFailed to "AUTH FAILED"
        is RtspPlayerState.Offline -> statusColors.offline to "OFFLINE"
        is RtspPlayerState.Error -> statusColors.authFailed to "ERROR"
        is RtspPlayerState.Idle -> statusColors.offline to "IDLE"
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}
