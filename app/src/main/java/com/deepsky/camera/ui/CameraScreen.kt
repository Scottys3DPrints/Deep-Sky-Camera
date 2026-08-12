package com.deepsky.camera.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepsky.camera.camera.AstroCamera
import com.deepsky.camera.camera.CaptureMode
import com.deepsky.camera.camera.CapturePlan
import com.deepsky.camera.camera.ExposureLimit

/**
 * The whole app, on one screen.
 *
 * Laid out for the conditions it is used in: outdoors, in the dark, one-handed,
 * by eyes that have spent twenty minutes adapting. Everything needed to take a
 * photograph is reachable without leaving this screen, everything else lives in
 * Settings, and the preview is never covered by a control that could have sat on
 * the black band beside it.
 */
@Composable
fun CameraScreen(
    state: UiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSelectCamera: (AstroCamera) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
    onTimerChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLastPhoto: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var showTuning by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // The preview fills the width and keeps its true aspect ratio rather than
        // being cropped to fill the screen. Framing a constellation needs the whole
        // field visible, and the black bands left over are exactly where the
        // controls want to sit anyway.
        val camera = state.selected
        if (camera != null) {
            CameraPreview(
                previewSize = camera.previewSize,
                sensorOrientation = camera.sensorOrientation,
                onSurfaceReady = onSurfaceReady,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(camera.previewSize.height.toFloat() / camera.previewSize.width),
            )
        } else {
            Text(
                text = "Waking the camera…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        TopBar(
            cameras = state.cameras,
            selected = state.selected,
            enabled = !state.isCapturing && !state.isCountingDown,
            onSelect = onSelectCamera,
            onToggleTuning = { showTuning = !showTuning },
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (state.isCapturing) {
            CaptureReadouts(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 72.dp),
            )
        }

        state.countdown?.let { seconds ->
            CountdownOverlay(seconds, Modifier.align(Alignment.Center))
        }

        BottomControls(
            state = state,
            showTuning = showTuning,
            onSelectMode = onSelectMode,
            onShutter = onShutter,
            onFocusChange = onFocusChange,
            onEvChange = onEvChange,
            onTimerChange = onTimerChange,
            onOpenLastPhoto = onOpenLastPhoto,
            onDismissMessage = onDismissMessage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TopBar(
    cameras: List<AstroCamera>,
    selected: AstroCamera?,
    enabled: Boolean,
    onSelect: (AstroCamera) -> Unit,
    onToggleTuning: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Only cameras that can be driven manually are offered. One that cannot
            // be told its exposure is useless here: it would meter the night sky as
            // a black frame and hand back exactly that.
            cameras.filter { it.supportsManual && !it.isFrontFacing }.forEach { camera ->
                PillButton(
                    label = camera.label.substringBefore(" ("),
                    selected = camera.id == selected?.id,
                    enabled = enabled,
                    onClick = { onSelect(camera) },
                )
            }
        }

        IconButton(onClick = onToggleTuning) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "Focus and brightness",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A pill rather than a Material chip.
 *
 * Chips render a hairline outline and small text that all but vanishes at the
 * screen brightness this app is used at. These are larger, higher contrast, and
 * have a touch target that can be found without looking.
 */
@Composable
private fun PillButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.12f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun CaptureReadouts(state: UiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val target = state.plan?.frameCount ?: 0
        Text(
            text = if (target == Int.MAX_VALUE) {
                "${state.framesDone} frames"
            } else {
                "${state.framesDone} / $target frames"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Readout(formatElapsed(state.elapsedMs) + " elapsed")

        // What the sensor actually did. If a phone silently clamps the exposure,
        // this is where it becomes visible instead of quietly halving the light.
        state.honouredExposureNs?.let { actual ->
            Readout("shutter ${CapturePlan.formatSeconds(actual)}", dim = true)
        }

        val (shiftX, shiftY) = state.alignShift
        if (shiftX != 0 || shiftY != 0) {
            // Watching this creep upward is watching the sky rotate.
            Readout("drift $shiftX, $shiftY px", dim = true)
        }
    }
}

@Composable
private fun Readout(text: String, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (dim) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
private fun CountdownOverlay(seconds: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seconds.toString(),
            fontSize = 64.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun BottomControls(
    state: UiState,
    showTuning: Boolean,
    onSelectMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
    onTimerChange: (Int) -> Unit,
    onOpenLastPhoto: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTuning) {
            TuningPanel(
                focus = state.settings.focusDiopters,
                ev = state.settings.evOffset,
                onFocusChange = onFocusChange,
                onEvChange = onEvChange,
            )
        }

        PlanSummary(state.plan)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            CaptureMode.entries.forEach { mode ->
                PillButton(
                    label = mode.label,
                    selected = mode == state.mode,
                    enabled = !state.isCapturing && !state.isCountingDown,
                    onClick = { onSelectMode(mode) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LastShot(
                thumbnail = state.thumbnail,
                onClick = onOpenLastPhoto,
                modifier = Modifier.width(72.dp),
            )

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ShutterButton(state = state, onClick = onShutter)
            }

            Box(modifier = Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                TimerButton(
                    seconds = state.settings.timerSeconds,
                    enabled = !state.isCapturing && !state.isCountingDown,
                    onChange = onTimerChange,
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissMessage),
            )
        }
    }
}

/**
 * Shows exactly what the app decided to do, before it does it.
 *
 * The promise is that you pick a duration and the app works out the rest — but a
 * black box silently choosing your settings is not something to trust at two in
 * the morning when a picture comes out wrong. So the plan is on screen, in the
 * terms an astrophotographer would use.
 */
@Composable
private fun PlanSummary(plan: CapturePlan?) {
    if (plan == null) {
        Text(
            text = "Reading the sky…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val total = when {
            plan.mode == CaptureMode.SINGLE -> ""
            plan.mode.isIndefinite -> "  ·  until you stop"
            else -> String.format(
                java.util.Locale.US, "  ·  %.0f s total", plan.plannedIntegrationMs / 1000.0,
            )
        }

        Text(
            text = plan.summary() + total,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )

        Text(
            text = when (plan.limitedBy) {
                ExposureLimit.SCENE_BRIGHTNESS ->
                    "Too bright for a night exposure — shutter cut to " +
                        "${CapturePlan.formatSeconds(plan.subExposureNs)} so it does not blow out"
                ExposureLimit.STAR_TRAILING ->
                    "Held to ${CapturePlan.formatSeconds(plan.subExposureNs)} per frame " +
                        "so the stars stay points, not streaks"
                ExposureLimit.HARDWARE ->
                    if (plan.mode == CaptureMode.SINGLE) {
                        "One frame, no stacking"
                    } else {
                        "${CapturePlan.formatSeconds(plan.subExposureNs)} is the longest single " +
                            "frame this sensor allows — the rest comes from stacking"
                    }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TuningPanel(
    focus: Float,
    ev: Float,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
) {
    var focusValue by remember(focus) { mutableFloatStateOf(focus) }
    var evValue by remember(ev) { mutableFloatStateOf(ev) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Focus is the one thing no amount of stacking can rescue. Autofocus cannot
        // lock onto a star, so it is set by hand and starts at infinity — right for
        // the sky, but phone lenses vary enough to want a nudge.
        SliderRow(
            label = if (focusValue <= 0.01f) {
                "Focus  ∞"
            } else {
                String.format(java.util.Locale.US, "Focus  %.2f", focusValue)
            },
            value = focusValue,
            range = 0f..1.5f,
            onValueChange = { focusValue = it },
            onValueChangeFinished = { onFocusChange(focusValue) },
        )
        SliderRow(
            label = String.format(java.util.Locale.US, "Bright  %+.1f EV", evValue),
            value = evValue,
            range = -3f..3f,
            onValueChange = { evValue = it },
            onValueChangeFinished = { onEvChange(evValue) },
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LastShot(
    thumbnail: android.graphics.Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (thumbnail == null) return@Box

        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = "Open the last photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun TimerButton(seconds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    // Cycles rather than opening a menu: three values, and one thumb in the dark.
    val next = when (seconds) {
        0 -> 3
        3 -> 10
        else -> 0
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled) { onChange(next) }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = "Self timer",
            tint = if (seconds == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = if (seconds == 0) "off" else "${seconds}s",
            style = MaterialTheme.typography.labelMedium,
            color = if (seconds == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun ShutterButton(state: UiState, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (state.isCapturing) {
            val progress = state.progress
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(88.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    strokeWidth = 4.dp,
                )
            } else {
                // Indefinite capture has no end to show progress toward, so the ring
                // simply turns for as long as it runs.
                CircularProgressIndicator(
                    modifier = Modifier.size(88.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    strokeWidth = 4.dp,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    when {
                        state.isCapturing || state.isCountingDown -> MaterialTheme.colorScheme.primary
                        state.isBusy -> Color.White.copy(alpha = 0.2f)
                        else -> Color.White
                    }
                )
                .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                .clickable(enabled = state.selected != null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val label = when {
                state.isCapturing -> "STOP"
                state.isCountingDown -> "CANCEL"
                state.phase == Phase.Metering -> "…"
                state.phase == Phase.Saving -> "SAVING"
                else -> null
            }

            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = if (label.length > 4) 10.sp else 13.sp,
                    color = if (state.isCapturing || state.isCountingDown) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.Black
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
