package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

private val ActivityGreen = Color(0xFF22C55E)
private val ContinuousBlue = Color(0xFF38BDF8)
private val GapColor = Color(0xFF2A2A2A)

/**
 * 24h day strip: dark gaps where nothing was recorded, continuous clips in blue,
 * motion/activity clips in green.
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
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..23 step 6).forEach { h ->
                Text(
                    "%02d".format(h),
                    fontSize = if (compact) 9.sp else 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 18.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GapColor),
        ) {
            if (dayStart != null) {
                // Continuous / non-activity first so activity paints on top.
                clips.filterNot { it.hasActivity }.forEach { clip ->
                    TimelineSegment(dayStart, clip, ContinuousBlue.copy(alpha = 0.85f), onClipClick)
                }
                clips.filter { it.hasActivity }.forEach { clip ->
                    TimelineSegment(dayStart, clip, ActivityGreen, onClipClick)
                }
            }
        }
        if (!compact) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LegendDot(GapColor, "No recording")
                LegendDot(ContinuousBlue, "Continuous")
                LegendDot(ActivityGreen, "Activity")
            }
        }
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
    onClick: (RecordedFile) -> Unit,
) {
    val start = parseTimelineDateTime(clip.beginTime) ?: return
    val end = parseTimelineDateTime(clip.endTime) ?: start.plusMinutes(1)
    val startMin = ChronoUnit.MINUTES.between(dayStart, start).toFloat().coerceIn(0f, 24f * 60f)
    val endMin = ChronoUnit.MINUTES.between(dayStart, end).toFloat().coerceIn(startMin + 1f, 24f * 60f)
    val fracStart = startMin / (24f * 60f)
    val fracWidth = max((endMin - startMin) / (24f * 60f), 0.008f)
    val remaining = (1f - fracStart).coerceAtLeast(0.01f)
    val widthInRemaining = (fracWidth / remaining).coerceIn(0.008f, 1f)
    Row(Modifier.fillMaxSize()) {
        if (fracStart > 0f) Spacer(Modifier.fillMaxWidth(fracStart).fillMaxHeight())
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(widthInRemaining)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .clickable { onClick(clip) },
        )
    }
}

fun parseTimelineDateTime(t: String): LocalDateTime? =
    runCatching {
        LocalDateTime.parse(t.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }.getOrNull()
