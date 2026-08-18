package com.voidnullvalue.icseelocal.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A single press-and-hold PTZ direction/stop button. Uses the low-level
 * `awaitEachGesture` pointer API (not `clickable`/`detectTapGestures`)
 * specifically so press and release are two distinct events -- required for
 * "move while held, stop on release" semantics. While held it lights up in
 * the accent colour so the control feels responsive. Keyed by `Unit` so
 * recomposition never restarts (and therefore never resends) the gesture.
 *
 * PTZ buttons share the width of their row instead of using a fixed diameter.
 * This lets the 3x3 pad consume its half of the controls row, which in turn
 * keeps the preset grid aligned to the right edge instead of leaving unused
 * horizontal space after it.
 */
@Composable
fun RowScope.PtzPadButton(
    icon: ImageVector,
    contentDescription: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 40,
    iconDp: Int = 20,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "ptzBg",
    )
    val fg = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .weight(1f)
            .aspectRatio(1f)
            .defaultMinSize(minWidth = sizeDp.dp, minHeight = sizeDp.dp)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onDown()
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) onUp() else onCancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(iconDp.dp))
    }
}
