package com.voidnullvalue.icseelocal.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App-wide dark theme. Local security-camera controller — dark-first
 * (true-black background) with a teal accent and a red live/talking state.
 * Always dark regardless of system setting; a white background washes out
 * the video preview.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF54E0C7),
    onPrimary = Color(0xFF00201A),
    primaryContainer = Color(0xFF00463A),
    onPrimaryContainer = Color(0xFF76F7DE),
    secondary = Color(0xFF8FD3FF),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF15303D),
    onSecondaryContainer = Color(0xFFCDE7FF),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF4A2800),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF7A1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EAED),
    surface = Color(0xFF0C0E10),
    onSurface = Color(0xFFE6EAED),
    surfaceVariant = Color(0xFF1B2126),
    onSurfaceVariant = Color(0xFFB6C1C7),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101315),
    surfaceContainer = Color(0xFF15181B),
    surfaceContainerHigh = Color(0xFF1D2226),
    surfaceContainerHighest = Color(0xFF262C31),
    outline = Color(0xFF3B454B),
    outlineVariant = Color(0xFF262D32),
)

/** M3 Expressive-inspired shapes: softer large surfaces, tighter controls. */
private val IcseeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val IcseeTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Stream / connection status colors beyond the Material color scheme. */
@Immutable
data class StatusColorTokens(
    val live: Color = Color(0xFFFF3B4A),
    val ok: Color = Color(0xFF34D399),
    val buffering: Color = Color(0xFF8FD3FF),
    val reconnecting: Color = Color(0xFFFFB77C),
    val offline: Color = Color(0xFF6B747A),
    val authFailed: Color = Color(0xFFFF6B6B),
    val recording: Color = Color(0xFFFF3B4A),
)

val LocalStatusColors = staticCompositionLocalOf { StatusColorTokens() }

/** Shared motion specs for overlays, grid focus, and chrome fades. */
object IcseeMotion {
    val overlayEnter = tween<Float>(durationMillis = 180)
    val overlayExit = tween<Float>(durationMillis = 220)
    val chromeSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val layoutSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    const val CONTROLS_AUTO_HIDE_MS = 3_200L
}

val MaterialTheme.statusColors: StatusColorTokens
    @Composable
    get() = LocalStatusColors.current

@Composable
fun IcseeTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides StatusColorTokens()) {
        MaterialTheme(
            colorScheme = DarkColors,
            shapes = IcseeShapes,
            typography = IcseeTypography,
            content = content,
        )
    }
}
