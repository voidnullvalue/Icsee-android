package com.voidnullvalue.icseelocal.ui.devicemanagement

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
private val ActivityGreen = Color(0xFF22C55E)

/**
 * Calendar + day timeline for SD-card recordings.
 * Tap plays in-app (stream/cache); download button saves to the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlaybackBrowserScreen(
    onBack: () -> Unit,
    viewModel: DeviceManagementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshTime()
        viewModel.loadAllRecordings()
    }

    val all = state.recordings.orEmpty()
    val dayKeys = remember(all) { all.map { it.dayKey }.distinct().sortedDescending() }
    val selectedDay = state.selectedRecordingDay ?: dayKeys.firstOrNull()
    val dayClips = remember(all, selectedDay) {
        all.filter { it.dayKey == selectedDay }.sortedByDescending { it.endTime.ifBlank { it.beginTime } }
    }

    com.voidnullvalue.icseelocal.ui.components.AppScaffold(title = "Recordings", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = { viewModel.loadAllRecordings() }, enabled = !state.recordingsQuerying) {
                    Text(if (state.recordingsQuerying) "Loading…" else "Refresh")
                }
                OutlinedButton(onClick = { viewModel.setCameraClock() }, enabled = !state.busy) {
                    Text("Set clock")
                }
            }
            state.deviceTime?.let {
                Text(
                    "Camera clock: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            state.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            state.errorMessage?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }

            if (state.recordingsQuerying) {
                CircularProgressIndicator(Modifier.padding(top = 16.dp))
                return@Column
            }

            if (all.isEmpty()) {
                Text("No recordings on the SD card.", modifier = Modifier.padding(top = 16.dp))
                return@Column
            }

            Text(
                "Days",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            var exportExpanded by remember { mutableStateOf(false) }
            val busy = state.downloadingClip != null || state.playBuffering
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dayKeys.forEach { day ->
                        val count = all.count { it.dayKey == day }
                        DayChip(
                            day = day,
                            count = count,
                            selected = day == selectedDay,
                            onClick = { viewModel.selectRecordingDay(day) },
                        )
                    }
                }
                if (selectedDay != null) {
                    TimeRangeExportToggle(
                        expanded = exportExpanded,
                        onToggle = { exportExpanded = !exportExpanded },
                    )
                }
            }
            if (exportExpanded && selectedDay != null) {
                TimeRangeExportPanel(
                    selectedDay = selectedDay,
                    busy = busy,
                    onPlay = viewModel::playTimeRange,
                    onDownload = viewModel::downloadTimeRange,
                )
            }

            Text(
                selectedDay?.let { "Timeline · $it · ${dayClips.size} clip(s)" } ?: "Timeline",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            )

            if (selectedDay != null) {
                RecordingDayTimeline(
                    day = selectedDay,
                    clips = dayClips,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    onClipClick = viewModel::playClip,
                )
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(dayClips, key = { it.fileName + it.beginTime }) { clip ->
                    ClipRow(
                        clip = clip,
                        downloading = state.downloadingClip == clip.fileName,
                        progressBytes = if (state.downloadingClip == clip.fileName) state.downloadProgressBytes else 0,
                        busy = state.downloadingClip != null || state.playBuffering,
                        onPlay = { viewModel.playClip(clip) },
                        onDownload = { viewModel.downloadClip(clip) },
                    )
                }
            }
        }
    }

    if (state.playBuffering || state.playUri != null || state.playError != null) {
        ClipPlayerDialog(
            uri = state.playUri,
            buffering = state.playBuffering,
            progressBytes = state.playProgressBytes,
            title = state.playTitle,
            error = state.playError,
            onDismiss = viewModel::clearPlayUri,
        )
    }
}

@Composable
private fun DayChip(day: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val label = runCatching {
        val parsed = LocalDate.parse(day)
        val today = LocalDate.now()
        when (parsed) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> parsed.format(DateTimeFormatter.ofPattern("EEE MMM d"))
        }
    }.getOrDefault(day)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("$count", color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun ClipRow(
    clip: RecordedFile,
    downloading: Boolean,
    progressBytes: Long,
    busy: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy || downloading, onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Activity / continuous vertical rail
        Box(
            Modifier
                .width(4.dp)
                .height(64.dp)
                .background(if (clip.hasActivity) ActivityGreen else Color(0xFF38BDF8).copy(alpha = 0.45f)),
        )
        Row(
            Modifier
                .weight(1f)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThumbBox(clip)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${timeOnly(clip.beginTime)} → ${timeOnly(clip.endTime)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        clip.activityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (clip.hasActivity) ActivityGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (clip.sizeText.isNotBlank()) {
                        Text(clip.sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (downloading) {
                    Text(
                        "Downloading… ${"%.1f".format(progressBytes / 1_000_000.0)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            if (!clip.isDownloaded) {
                IconButton(
                    onClick = onDownload,
                    enabled = !busy || downloading,
                ) {
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    "Saved",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ThumbBox(clip: RecordedFile) {
    val bitmap = remember(clip.thumbPath) {
        clip.thumbPath?.let { path ->
            val f = File(path)
            if (f.exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
        }
    }
    Box(
        Modifier
            .size(64.dp, 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            clip.hasActivity -> Icon(Icons.Default.Sensors, null, tint = ActivityGreen, modifier = Modifier.size(22.dp))
            else -> Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@UnstableApi
@Composable
fun ClipPlayerDialog(
    uri: String?,
    buffering: Boolean,
    progressBytes: Long,
    title: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var playerError by remember { mutableStateOf<String?>(null) }
    var speedIndex by remember {
        mutableIntStateOf(com.voidnullvalue.icseelocal.video.RobustClipPlayer.DEFAULT_SPEED_INDEX)
    }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val pendingStart = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val speeds = com.voidnullvalue.icseelocal.video.RobustClipPlayer.SPEED_STEPS
    val speed = speeds[speedIndex.coerceIn(0, speeds.lastIndex)]

    val player = remember(context.applicationContext) {
        com.voidnullvalue.icseelocal.video.RobustClipPlayer.create(
            context = context,
            pendingStart = pendingStart,
        ) { msg -> playerError = msg }
    }
    LaunchedEffect(uri) {
        playerError = null
        speedIndex = com.voidnullvalue.icseelocal.video.RobustClipPlayer.DEFAULT_SPEED_INDEX
        volume = 1f
        muted = false
        player.volume = 1f
        if (uri.isNullOrBlank()) {
            pendingStart.set(false)
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        com.voidnullvalue.icseelocal.video.RobustClipPlayer.play(player, uri, pendingStart)
    }
    LaunchedEffect(volume, muted) {
        player.volume = if (muted) 0f else volume.coerceIn(0f, 1f)
    }
    // Forward: ExoPlayer rate. Reverse: scrub seek loop (−1×…−4×).
    LaunchedEffect(speed, uri) {
        if (uri.isNullOrBlank()) return@LaunchedEffect
        if (speed > 0f) {
            com.voidnullvalue.icseelocal.video.RobustClipPlayer.applyForwardSpeed(player, speed)
            return@LaunchedEffect
        }
        // Reverse scrub — pause normal play and step backward by |speed|.
        player.setPlaybackSpeed(1f)
        player.playWhenReady = false
        val tickMs = 200L
        val absSpeed = abs(speed).coerceAtLeast(0.25f)
        while (isActive) {
            val pos = player.currentPosition
            if (pos <= 0L) break
            val step = (tickMs * absSpeed).toLong().coerceAtLeast(1L)
            player.seekTo((pos - step).coerceAtLeast(0L))
            delay(tickMs)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (uri != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (buffering && uri == null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Loading recording…", color = Color.White, fontWeight = FontWeight.Medium)
                    Text(
                        "Waiting for a complete remux before play",
                        color = Color.White.copy(0.65f),
                        fontSize = 12.sp,
                    )
                    if (progressBytes > 0) {
                        Text(
                            "%.1f MB received".format(progressBytes / 1e6),
                            color = Color.White.copy(0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            val shownError = error ?: playerError
            shownError?.let {
                Text(
                    it,
                    color = Color(0xFFFF8A80),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
            title?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 20.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x66000000)),
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }

            if (uri != null && !buffering) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 64.dp),
                ) {
                    IconButton(
                        onClick = { settingsOpen = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x66000000)),
                    ) {
                        Icon(Icons.Default.Settings, "Playback settings", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = settingsOpen,
                        onDismissRequest = { settingsOpen = false },
                        modifier = Modifier
                            .width(280.dp)
                            .background(Color(0xFF1F2937)),
                    ) {
                        Text(
                            "Volume",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { muted = !muted }) {
                                Icon(
                                    if (muted || volume <= 0.01f) Icons.AutoMirrored.Filled.VolumeOff
                                    else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (muted) "Unmute" else "Mute",
                                    tint = Color.White,
                                )
                            }
                            Slider(
                                value = if (muted) 0f else volume,
                                onValueChange = {
                                    volume = it
                                    if (it > 0.01f) muted = false
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "Speed",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            IconButton(
                                onClick = { if (speedIndex > 0) speedIndex-- },
                                enabled = speedIndex > 0,
                            ) {
                                Text("−", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                com.voidnullvalue.icseelocal.video.RobustClipPlayer.formatSpeed(speed),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            IconButton(
                                onClick = { if (speedIndex < speeds.lastIndex) speedIndex++ },
                                enabled = speedIndex < speeds.lastIndex,
                            ) {
                                Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            "Range −4× … 4× · default 1×",
                            color = Color.White.copy(0.55f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun timeOnly(t: String): String = t.substringAfter(' ', t).ifBlank { t }
