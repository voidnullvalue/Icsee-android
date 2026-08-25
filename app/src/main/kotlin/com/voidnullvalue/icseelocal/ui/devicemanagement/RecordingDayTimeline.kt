package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

private val ActivityGreen = Color(0xFF22C55E)
private val ContinuousBlue = Color(0xFF38BDF8)
private val GapColor = Color(0xFF2A2A2A)

private val MinutesPerDay = 24f * 60f
private const val MinZoom = 1f
private const val MaxZoom = 8f

/**
 * 24h day strip: dark gaps where nothing was recorded, continuous clips in blue,
 * motion/activity clips in green. Horizontally scrollable and pinch-/button-zoomable
 * so short activity bursts are easier to hit.
 */
@Composable
fun RecordingDayTimeline(
    day: String,
    clips: List<RecordedFile>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClipClick: (RecordedFile) -> Unit = {},
) {
    val dayStart = remember(day) {
        runCatching { LocalDate.parse(day).atStartOfDay() }.getOrNull()
    }
    val baseHourWidth = if (compact) 56.dp else 64.dp
    val stripHeight = if (compact) 28.dp else 36.dp
    var zoom by remember(day) { mutableFloatStateOf(1f) }
    var pendingScrollFrac by remember { mutableFloatStateOf(Float.NaN) }
    var pendingViewportFocus by remember { mutableFloatStateOf(0f) }
    val hourWidth = baseHourWidth * zoom
    val contentWidth = hourWidth * 24
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val showHalfHours = zoom >= 2.5f
    val showQuarterHours = zoom >= 5f

    BoxWithConstraints(modifier) {
        val viewportPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val contentWidthPx = with(density) { contentWidth.toPx() }

        fun applyZoom(next: Float, focalXInViewport: Float = viewportPx / 2f) {
            val coerced = next.coerceIn(MinZoom, MaxZoom)
            if (coerced == zoom) return
            val oldWidth = contentWidthPx
            val focalContent = scroll.value + focalXInViewport
            pendingScrollFrac = if (oldWidth > 0f) focalContent / oldWidth else 0.5f
            pendingViewportFocus = focalXInViewport
            zoom = coerced
        }

        LaunchedEffect(contentWidthPx, pendingScrollFrac) {
            val frac = pendingScrollFrac
            if (!frac.isNaN() && contentWidthPx > 0f) {
                val maxScroll = (contentWidthPx - viewportPx).toInt().coerceAtLeast(0)
                val target = (frac * contentWidthPx - pendingViewportFocus)
                    .roundToInt()
                    .coerceIn(0, maxScroll)
                scroll.scrollTo(target)
                pendingScrollFrac = Float.NaN
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(scroll)
                        .pointerInput(zoom, viewportPx) {
                            detectTransformGestures { centroid, _, zoomChange, _ ->
                                applyZoom(zoom * zoomChange, focalXInViewport = centroid.x)
                            }
                        },
                ) {
                    Box(Modifier.width(contentWidth).height(if (compact) 12.dp else 14.dp)) {
                        for (h in 0 until 24) {
                            Text(
                                "%02d".format(h),
                                fontSize = if (compact) 9.sp else 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(x = hourWidth * h),
                            )
                            if (showHalfHours) {
                                Text(
                                    ":30",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.offset(x = hourWidth * h + hourWidth / 2),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                    Box(
                        Modifier
                            .width(contentWidth)
                            .height(stripHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(GapColor),
                    ) {
                        for (h in 1 until 24) {
                            Box(
                                Modifier
                                    .offset(x = hourWidth * h)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(Color.White.copy(alpha = 0.12f)),
                            )
                        }
                        if (showHalfHours) {
                            for (h in 0 until 24) {
                                Box(
                                    Modifier
                                        .offset(x = hourWidth * h + hourWidth / 2)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.06f)),
                                )
                            }
                        }
                        if (showQuarterHours) {
                            for (h in 0 until 24) {
                                for (q in listOf(0.25f, 0.75f)) {
                                    Box(
                                        Modifier
                                            .offset(x = hourWidth * h + hourWidth * q)
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(Color.White.copy(alpha = 0.04f)),
                                    )
                                }
                            }
                        }
                        if (dayStart != null) {
                            clips.filterNot { it.hasActivity }.forEach { clip ->
                                TimelineSegment(
                                    dayStart = dayStart,
                                    clip = clip,
                                    color = ContinuousBlue.copy(alpha = 0.85f),
                                    contentWidth = contentWidth,
                                    minWidth = 4.dp,
                                    onClick = onClipClick,
                                )
                            }
                            clips.filter { it.hasActivity }.forEach { clip ->
                                TimelineSegment(
                                    dayStart = dayStart,
                                    clip = clip,
                                    color = ActivityGreen,
                                    contentWidth = contentWidth,
                                    minWidth = 4.dp,
                                    onClick = onClipClick,
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TimelineZoomButton(
                        icon = Icons.Default.Add,
                        enabled = zoom < MaxZoom,
                        onClick = { applyZoom(zoom * 1.35f) },
                    )
                    TimelineZoomButton(
                        icon = Icons.Default.Remove,
                        enabled = zoom > MinZoom,
                        onClick = { applyZoom(zoom / 1.35f) },
                    )
                }
            }

            if (!compact) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot(GapColor, "No recording")
                    LegendDot(ContinuousBlue, "Continuous")
                    LegendDot(ActivityGreen, "Activity")
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(zoom * 10f).roundToInt() / 10f}× · pinch or ±",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineZoomButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(12.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelineSegment(
    dayStart: LocalDateTime,
    clip: RecordedFile,
    color: Color,
    contentWidth: Dp,
    minWidth: Dp,
    onClick: (RecordedFile) -> Unit,
) {
    val start = parseTimelineDateTime(clip.beginTime) ?: return
    val end = parseTimelineDateTime(clip.endTime) ?: start.plusMinutes(1)
    val startMin = ChronoUnit.MINUTES.between(dayStart, start).toFloat().coerceIn(0f, MinutesPerDay)
    val endMin = ChronoUnit.MINUTES.between(dayStart, end).toFloat().coerceIn(startMin + 1f, MinutesPerDay)
    val fracStart = startMin / MinutesPerDay
    val fracWidth = max((endMin - startMin) / MinutesPerDay, 0.001f)
    val x = contentWidth * fracStart
    val w = (contentWidth * fracWidth).coerceAtLeast(minWidth)
    Box(
        Modifier
            .offset(x = x)
            .width(w)
            .fillMaxHeight()
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .clickable { onClick(clip) },
    )
}

fun parseTimelineDateTime(t: String): LocalDateTime? =
    runCatching {
        LocalDateTime.parse(t.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }.getOrNull()
