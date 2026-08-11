package com.deepsky.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepsky.camera.camera.AstroCamera
import com.deepsky.camera.camera.CaptureMode
import com.deepsky.camera.camera.CapturePlan
import com.deepsky.camera.camera.ExposureLimit

@Composable
fun CameraScreen(
    state: UiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSelectCamera: (AstroCamera) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraChooser(
            cameras = state.cameras,
            selected = state.selected,
            enabled = !state.isCapturing,
            onSelect = onSelectCamera,
            onOpenSettings = onOpenSettings,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val camera = state.selected
            if (camera != null) {
                CameraPreview(
                    previewSize = camera.previewSize,
                    onSurfaceReady = onSurfaceReady,
                    modifier = Modifier
                        // The sensor is mounted sideways, so a landscape preview
                        // buffer becomes a portrait frame on screen.
                        .aspectRatio(
                            camera.previewSize.height.toFloat() / camera.previewSize.width,
                        ),
                )
            } else {
                Text(
                    text = "Waking the camera…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (state.isCapturing) {
                CaptureOverlay(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
            }
        }

        ControlPanel(
            state = state,
            onSelectMode = onSelectMode,
            onShutter = onShutter,
            onFocusChange = onFocusChange,
            onEvChange = onEvChange,
            onDismissMessage = onDismissMessage,
        )
    }
}

@Composable
private fun CameraChooser(
    cameras: List<AstroCamera>,
    selected: AstroCamera?,
    enabled: Boolean,
    onSelect: (AstroCamera) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Only cameras that can be driven manually are offered. A camera that
            // cannot be told its exposure is useless here — it would meter the
            // night sky as a black frame and hand back exactly that.
            cameras.filter { it.supportsManual && !it.isFrontFacing }.forEach { camera ->
                FilterChip(
                    selected = camera.id == selected?.id,
                    onClick = { if (enabled) onSelect(camera) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = camera.label.substringBefore(" ("),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
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

@Composable
private fun CaptureOverlay(state: UiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val target = state.plan?.frameCount ?: 0
        val frames = if (target == Int.MAX_VALUE) {
            "${state.framesDone} frames"
        } else {
            "${state.framesDone} / $target frames"
        }

        Readout(frames)
        Readout(formatElapsed(state.elapsedMs) + " elapsed")

        val (shiftX, shiftY) = state.alignShift
        if (shiftX != 0 || shiftY != 0) {
            // Proof that alignment is doing something. Watching this creep upward
            // is watching the sky rotate.
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
private fun ControlPanel(
    state: UiState,
    onSelectMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlanSummary(state.plan)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            CaptureMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == state.mode,
                    onClick = { onSelectMode(mode) },
                    enabled = !state.isCapturing,
                    label = {
                        Text(mode.label, style = MaterialTheme.typography.labelMedium)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        if (!state.isCapturing) {
            FocusAndBrightness(
                focus = state.settings.focusDiopters,
                ev = state.settings.evOffset,
                onFocusChange = onFocusChange,
                onEvChange = onEvChange,
            )
        }

        ShutterButton(state = state, onClick = onShutter)

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissMessage)
                    .padding(top = 2.dp),
            )
        }
    }
}

/**
 * Shows exactly what the app decided to do, before it does it.
 *
 * The whole promise here is that you pick a duration and the app works out the
 * rest — but a black box that silently chooses your settings is not trustworthy
 * at two in the morning when the picture comes out wrong. So the plan is on
 * screen, in the same terms an astrophotographer would use.
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
        Text(
            text = plan.summary(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        val explanation = when {
            plan.mode == CaptureMode.SINGLE ->
                "One frame, no stacking"
            plan.limitedBy == ExposureLimit.HARDWARE ->
                "${CapturePlan.formatSeconds(plan.subExposureNs)} is the longest single " +
                    "frame this sensor allows — the rest comes from stacking"
            else ->
                "Held to ${CapturePlan.formatSeconds(plan.subExposureNs)} per frame " +
                    "so the stars stay points, not streaks"
        }

        Text(
            text = explanation,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FocusAndBrightness(
    focus: Float,
    ev: Float,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
) {
    var focusValue by remember(focus) { mutableFloatStateOf(focus) }
    var evValue by remember(ev) { mutableFloatStateOf(ev) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Focus is the one thing no amount of stacking can rescue. Autofocus
        // cannot lock onto a star, so it is set by hand and starts at infinity —
        // which is right for the sky, but phone lenses vary enough to want a nudge.
        SliderRow(
            label = if (focusValue <= 0.01f) "Focus ∞" else "Focus %.2f".format(focusValue),
            value = focusValue,
            range = 0f..1.5f,
            onValueChange = { focusValue = it },
            onValueChangeFinished = { onFocusChange(focusValue) },
        )

        SliderRow(
            label = "Brightness %+.1f EV".format(evValue),
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
            modifier = Modifier.width(120.dp),
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
private fun ShutterButton(state: UiState, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (state.isCapturing) {
            val progress = state.progress
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(82.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp,
                )
            } else {
                // Indefinite capture has no end to show progress toward, so the
                // ring simply turns for as long as it runs.
                CircularProgressIndicator(
                    modifier = Modifier.size(82.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp,
                )
            }
        }

        val busy = state.phase == Phase.Metering || state.phase == Phase.Saving
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    if (state.isCapturing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(enabled = !busy && state.selected != null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    state.isCapturing -> "STOP"
                    state.phase == Phase.Metering -> "…"
                    state.phase == Phase.Saving -> "SAVE"
                    else -> "SHOOT"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state.isCapturing) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
