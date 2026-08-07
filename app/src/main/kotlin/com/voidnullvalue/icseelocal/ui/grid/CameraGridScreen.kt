package com.voidnullvalue.icseelocal.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.ui.components.AppScaffold
import com.voidnullvalue.icseelocal.ui.components.CameraStreamPlayer
import com.voidnullvalue.icseelocal.ui.components.PlaybackStatusChip
import com.voidnullvalue.icseelocal.video.RtspPlayerState

/**
 * Adaptive multi-view grid. Phase 1: only the focused tile decodes RTSP;
 * double-tap expands into the full live control screen.
 */
@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraGridScreen(
    onOpenLive: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CameraGridViewModel = viewModel(),
) {
    val cameras by viewModel.cameras.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val focusedId by viewModel.focusedCameraId.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val mainStream by viewModel.mainStream.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.focusCamera(null) }
    }

    AppScaffold(title = "Multi-view", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridLayoutMode.entries.forEach { mode ->
                    FilterChip(
                        selected = layoutMode == mode,
                        onClick = { viewModel.setLayoutMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Tap focus · double-tap open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (cameras.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No cameras saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val aspect = if (maxWidth > maxHeight) 16f / 9f else 4f / 3f
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layoutMode.columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(cameras, key = { _, cam -> cam.id }) { index, cam ->
                            CameraGridTile(
                                camera = cam,
                                focused = cam.id == focusedId,
                                aspectRatio = aspect,
                                playbackState = if (cam.id == focusedId) playbackState else RtspPlayerState.Idle,
                                canMoveUp = index > 0,
                                canMoveDown = index < cameras.lastIndex,
                                onFocus = { viewModel.focusCamera(cam.id) },
                                onOpen = { onOpenLive(cam.id) },
                                onMoveUp = { viewModel.moveCamera(index, index - 1) },
                                onMoveDown = { viewModel.moveCamera(index, index + 1) },
                                playerContent = if (cam.id == focusedId) {
                                    {
                                        CameraStreamPlayer(
                                            exoPlayer = viewModel.streamManager.exoPlayer,
                                            playbackState = playbackState,
                                            modifier = Modifier.fillMaxSize(),
                                            mainStream = mainStream,
                                            muted = muted,
                                            showOverlay = true,
                                            overlayInitiallyVisible = false,
                                            onToggleMute = viewModel::toggleMute,
                                            onToggleQuality = viewModel::toggleQuality,
                                            onReconnect = viewModel::reconnect,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraGridTile(
    camera: CameraDescriptor,
    focused: Boolean,
    aspectRatio: Float,
    playbackState: RtspPlayerState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    playerContent: (@Composable () -> Unit)?,
) {
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(MaterialTheme.shapes.medium)
            .border(if (focused) 2.dp else 1.dp, borderColor, MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .combinedClickable(
                onClick = onFocus,
                onDoubleClick = onOpen,
            ),
    ) {
        if (playerContent != null) {
            playerContent()
        } else {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "Tap to live",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                camera.displayName,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (focused) {
                PlaybackStatusChip(playbackState)
            }
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowUpward, "Move up", tint = if (canMoveUp) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowDownward, "Move down", tint = if (canMoveDown) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
            }
        }
    }
}
