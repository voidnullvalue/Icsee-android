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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
            Row(
                Modifier
                    .fillMaxWidth()
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
    val player = remember {
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    playerError = e.message ?: "Player error ${e.errorCode}"
                }
            })
        }
    }
    LaunchedEffect(uri) {
        playerError = null
        if (uri.isNullOrBlank()) return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

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
                        }
                    },
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
                    Text("Starting stream…", color = Color.White)
                    if (progressBytes > 0) {
                        Text("%.1f MB received".format(progressBytes / 1e6), color = Color.White.copy(0.7f), fontSize = 12.sp)
                    }
                }
            }
            if (!buffering && uri != null && progressBytes > 0) {
                // Still receiving remainder after early start — subtle status only.
            }
            if (buffering && uri != null) {
                Text(
                    "Streaming… %.1f MB".format(progressBytes / 1e6),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
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
        }
    }
}

private fun timeOnly(t: String): String = t.substringAfter(' ', t).ifBlank { t }
