package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Recorded-clip browser: shows every clip on the SD card as a flat grid of
 * tiles (no day picker -- [DeviceManagementViewModel.loadAllRecordings] reads
 * the recorded span from StorageInfo and merges every day). Tapping a tile
 * downloads the clip via DVRIP OPPlayBack, remuxes the camera's private-framed
 * HEVC to MP4 (see [com.voidnullvalue.icseelocal.video.RecordedClipExporter]),
 * and plays it in ExoPlayer. Also offers a one-tap camera-clock sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(topBar = { TopAppBar(title = { Text("Recordings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.loadAllRecordings() }, enabled = !state.recordingsQuerying) {
                    Text(if (state.recordingsQuerying) "Loading…" else "Refresh")
                }
                OutlinedButton(onClick = { viewModel.setCameraClock() }, enabled = !state.busy) {
                    Text("Set clock to phone")
                }
            }
            state.deviceTime?.let {
                Text("Camera clock: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
            state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }

            if (state.recordingsQuerying) CircularProgressIndicator(Modifier.padding(top = 12.dp))

            val recordings = state.recordings
            if (recordings != null && !state.recordingsQuerying) {
                if (recordings.isEmpty()) {
                    Text("No recordings on the SD card.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    Text("${recordings.size} clip(s)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(recordings) { f ->
                            ClipTile(
                                f = f,
                                downloading = state.downloadingClip == f.fileName,
                                progressBytes = if (state.downloadingClip == f.fileName) state.downloadProgressBytes else 0,
                                anyDownloading = state.downloadingClip != null,
                                onPlay = { viewModel.downloadAndPlay(f) },
                            )
                        }
                    }
                }
            }

            Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
        }
    }

    state.playbackFile?.let { path ->
        ClipPlaybackDialog(path = path, onDismiss = { viewModel.clearPlayback() })
    }
}

@Composable
private fun ClipTile(
    f: RecordedFile,
    downloading: Boolean,
    progressBytes: Long,
    anyDownloading: Boolean,
    onPlay: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !anyDownloading && f.fileName.isNotBlank(), onClick = onPlay),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(dateOnly(f.beginTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${timeOnly(f.beginTime)}", style = MaterialTheme.typography.titleMedium)
            Text("→ ${timeOnly(f.endTime)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val tag = clipTag(f.fileName)
            if (tag != null) {
                Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (downloading) {
                CircularProgressIndicator(Modifier.padding(top = 6.dp))
                Text("${"%.1f".format(progressBytes / 1_000_000.0)} MB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            } else {
                Text("Tap to play", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@UnstableApi
@Composable
private fun ClipPlaybackDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("file://$path"))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
                modifier = Modifier.fillMaxSize(),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Close", color = Color.White)
            }
        }
    }
}

/** "R" scheduled / "M" motion, parsed from the XM filename tag like `…[R]…` / `…[M]…`. */
private fun clipTag(fileName: String): String? = when {
    "[M]" in fileName -> "motion"
    "[R]" in fileName -> "scheduled"
    else -> null
}

/** OPFileQuery times are "YYYY-MM-DD hh:mm:ss". */
private fun timeOnly(t: String): String = t.substringAfter(' ', t).ifBlank { t }
private fun dateOnly(t: String): String = t.substringBefore(' ', t)
