package com.deepsky.camera.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dark at all times, with a deep red accent.
 *
 * This is not a style choice. Eyes take twenty minutes to fully dark-adapt and a
 * single white screen undoes it instantly; long-wavelength red is the one colour
 * that leaves night vision largely intact. The app therefore ignores the system
 * light theme entirely — there is no correct light rendering of a screen you use
 * in a dark field.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFFE23B2E),
    onPrimary = Color(0xFF160000),
    primaryContainer = Color(0xFF3A0D08),
    onPrimaryContainer = Color(0xFFFFB4A8),
    secondary = Color(0xFF7FA3D8),
    onSecondary = Color(0xFF04101F),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E6E6),
    surface = Color(0xFF0B0B0E),
    onSurface = Color(0xFFE8E6E6),
    surfaceVariant = Color(0xFF1A1A20),
    onSurfaceVariant = Color(0xFFB9B6BC),
    outline = Color(0xFF3A3A42),
    error = Color(0xFFFF6B5E),
)

private val NightTypography = Typography(
    // The readouts — shutter, ISO, frame count — are monospaced so the numbers
    // stop jittering sideways as they tick upward during a capture.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun DeepSkyTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NightColors,
        typography = NightTypography,
        content = content,
    )
}
