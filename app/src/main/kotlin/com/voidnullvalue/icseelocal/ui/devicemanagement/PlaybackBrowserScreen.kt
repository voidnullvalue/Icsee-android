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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Calendar + day timeline for SD-card recordings. Distinguishes downloaded
 * (local thumb + play) vs remote (download). In-app ExoPlayer for local clips.
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
        all.filter { it.dayKey == selectedDay }.sortedBy { it.beginTime }
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
                    val selected = day == selectedDay
                    DayChip(
                        day = day,
                        count = count,
                        selected = selected,
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
                DayTimelineStrip(
                    day = selectedDay,
                    clips = dayClips,
                    downloading = state.downloadingClip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    onClipClick = { clip ->
                        if (clip.isDownloaded) viewModel.openLocalClip(clip)
                        else viewModel.downloadClip(clip)
                    },
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
                        anyDownloading = state.downloadingClip != null,
                        onClick = {
                            if (clip.isDownloaded) viewModel.openLocalClip(clip)
                            else viewModel.downloadClip(clip)
                        },
                    )
                }
            }
        }
    }

    state.playUri?.let { uri ->
        LocalClipPlayerDialog(uri = uri, onDismiss = viewModel::clearPlayUri)
    }
}

@Composable
private fun DayChip(day: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val label = runCatching {
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE MMM d"))
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
private fun DayTimelineStrip(
    day: String,
    clips: List<RecordedFile>,
    downloading: String?,
    modifier: Modifier = Modifier,
    onClipClick: (RecordedFile) -> Unit,
) {
    val dayStart = remember(day) {
        runCatching { LocalDate.parse(day).atStartOfDay() }.getOrNull()
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..23 step 6).forEach { h ->
                Text("%02d".format(h), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (dayStart != null) {
                clips.forEach { clip ->
                    val start = parseDateTime(clip.beginTime) ?: return@forEach
                    val end = parseDateTime(clip.endTime) ?: start.plusMinutes(1)
                    val startMin = ChronoUnit.MINUTES.between(dayStart, start).toFloat().coerceIn(0f, 24f * 60f)
                    val endMin = ChronoUnit.MINUTES.between(dayStart, end).toFloat().coerceIn(startMin + 1f, 24f * 60f)
                    val fracStart = startMin / (24f * 60f)
                    val fracWidth = max((endMin - startMin) / (24f * 60f), 0.01f)
                    val color = when {
                        downloading == clip.fileName -> MaterialTheme.colorScheme.tertiary
                        clip.isDownloaded -> MaterialTheme.colorScheme.primary
                        "[M]" in clip.fileName -> Color(0xFFFFB77C)
                        else -> MaterialTheme.colorScheme.secondary
                    }
                    BoxWithFraction(fracStart, fracWidth, color) { onClipClick(clip) }
                }
            }
        }
    }
}

@Composable
private fun BoxWithFraction(startFrac: Float, widthFrac: Float, color: Color, onClick: () -> Unit) {
    val remaining = (1f - startFrac).coerceAtLeast(0.01f)
    val widthInRemaining = (widthFrac / remaining).coerceIn(0.01f, 1f)
    Row(Modifier.fillMaxSize()) {
        if (startFrac > 0f) Spacer(Modifier.fillMaxWidth(startFrac).fillMaxHeight())
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(widthInRemaining)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ClipRow(
    clip: RecordedFile,
    downloading: Boolean,
    progressBytes: Long,
    anyDownloading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !anyDownloading || downloading, onClick = onClick)
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
                clipTag(clip.fileName)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
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
        when {
            downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            clip.isDownloaded -> Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Default.CloudDownload, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            clip.isDownloaded -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            "[M]" in clip.fileName -> Icon(Icons.Default.Sensors, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
            else -> Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@UnstableApi
@Composable
private fun LocalClipPlayerDialog(uri: String, onDismiss: () -> Unit) {
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99000000)),
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

private fun parseDateTime(t: String): LocalDateTime? =
    runCatching {
        LocalDateTime.parse(t.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }.getOrNull()

private fun clipTag(fileName: String): String? = when {
    "[M]" in fileName -> "motion"
    "[R]" in fileName -> "scheduled"
    else -> null
}

private fun timeOnly(t: String): String = t.substringAfter(' ', t).ifBlank { t }
