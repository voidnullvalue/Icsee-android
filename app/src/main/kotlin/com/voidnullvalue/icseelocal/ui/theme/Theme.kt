package com.voidnullvalue.icseelocal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App-wide dark theme. This is a local security-camera controller -- it's
 * used in the dark and on a phone held up to a camera, so it's dark-first
 * (true-black background) with a single teal accent and a red "live/talking"
 * state. Always dark regardless of the system setting; a white background
 * washes out the video preview and was explicitly unwanted.
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

@Composable
fun IcseeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
