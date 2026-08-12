package com.deepsky.camera.ui

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
 * One dark palette, warm accent, no light theme.
 *
 * Not a style preference. Eyes take about twenty minutes to fully dark-adapt and
 * one white screen undoes it, so the app ignores the system light theme entirely
 * — there is no correct bright rendering of a screen used in a dark field.
 *
 * The accent is amber rather than the obvious red. Long wavelengths preserve
 * night vision just as well, and amber stays legible against a near-black
 * background at low screen brightness where a saturated red goes muddy and
 * vibrates against dark grey.
 */
object Ink {
    /** Page background. Very slightly blue, which reads as darker than pure black. */
    val Background = Color(0xFF07080A)

    /** Raised panels: the control deck, cards, the settings sheet. */
    val Surface = Color(0xFF101217)
    val SurfaceHigh = Color(0xFF181B22)

    /** Hairlines and dividers. Never heavier than this. */
    val Line = Color(0xFF262A33)

    val TextPrimary = Color(0xFFF3F4F7)
    val TextSecondary = Color(0xFF8B909B)
    val TextTertiary = Color(0xFF565B66)

    val Accent = Color(0xFFFF9F4A)
    val AccentSoft = Color(0x24FF9F4A)
    val Danger = Color(0xFFFF5F4A)

    /** Scrims over the live preview, so controls stay readable on any sky. */
    val Scrim = Color(0xB3000000)
    val ScrimLight = Color(0x66000000)
}

/**
 * Two families, used for different jobs.
 *
 * Monospace is reserved strictly for numbers that change — shutter, ISO, frame
 * count, elapsed time. Digits then keep a fixed width, so a readout ticking from
 * 9 to 10 frames does not shove the rest of the line sideways. Everything a
 * person *reads* rather than *checks* is set in the system sans, because a whole
 * interface in monospace looks like a log file rather than a camera.
 */
object Type {
    val Readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    )
    val ReadoutSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        letterSpacing = 0.sp,
    )
    val Label = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.15.sp,
    )
    val Caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
    )
    /** Section headers in Settings. Small, wide, quiet. */
    val Overline = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        letterSpacing = 1.4.sp,
    )
    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        letterSpacing = 0.sp,
    )
    val Countdown = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
    )
}

private val NightColors = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Color(0xFF1A0E03),
    primaryContainer = Ink.SurfaceHigh,
    onPrimaryContainer = Ink.TextPrimary,
    secondary = Ink.TextSecondary,
    background = Ink.Background,
    onBackground = Ink.TextPrimary,
    surface = Ink.Surface,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.SurfaceHigh,
    onSurfaceVariant = Ink.TextSecondary,
    outline = Ink.Line,
    error = Ink.Danger,
)

@Composable
fun DeepSkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColors,
        typography = Typography(
            titleMedium = Type.Title,
            labelLarge = Type.Label,
            labelMedium = Type.Caption,
        ),
        content = content,
    )
}
