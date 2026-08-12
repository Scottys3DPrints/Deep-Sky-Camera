package com.deepsky.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepsky.camera.camera.AstroCamera
import com.deepsky.camera.camera.CaptureMode
import com.deepsky.camera.camera.CapturePlan
import com.deepsky.camera.camera.ExposureLimit

/**
 * The whole app on one screen, laid out for the conditions it is used in:
 * outdoors, in the dark, one-handed, by eyes that have spent twenty minutes
 * adapting.
 *
 * Three bands, in the order attention moves through them — what the camera is
 * pointed at, what it is about to do, and the control to do it. Everything
 * needed to take a photograph is here; everything else is in Settings.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .statusBarsPadding(),
    ) {
        LensBar(
            cameras = state.cameras,
            selected = state.selected,
            enabled = !state.isCapturing && !state.isCountingDown,
            tuningOpen = showTuning,
            onSelect = onSelectCamera,
            onToggleTuning = { showTuning = !showTuning },
            onOpenSettings = onOpenSettings,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Viewfinder(state = state, onSurfaceReady = onSurfaceReady)

            if (state.isCapturing) {
                CaptureHud(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp),
                )
            }

            state.countdown?.let { Countdown(it) }

            state.message?.let { message ->
                Toast(
                    message = message,
                    onDismiss = onDismissMessage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                )
            }
        }

        ControlDeck(
            state = state,
            showTuning = showTuning,
            onSelectMode = onSelectMode,
            onShutter = onShutter,
            onFocusChange = onFocusChange,
            onEvChange = onEvChange,
            onTimerChange = onTimerChange,
            onOpenLastPhoto = onOpenLastPhoto,
        )
    }
}

/**
 * The live view, inset and rounded rather than bleeding to the screen edge.
 *
 * A hairline border and a little breathing room make it read as a frame you are
 * composing inside, and stop a dark sky from dissolving into an equally dark
 * bezel with no visible boundary between them.
 */
@Composable
private fun Viewfinder(state: UiState, onSurfaceReady: (android.view.Surface) -> Unit) {
    val camera = state.selected
    if (camera == null) {
        Text(
            text = "Waking the camera",
            style = Type.Caption,
            color = Ink.TextTertiary,
        )
        return
    }

    Box(
        modifier = Modifier
            // Deliberately no fillMaxWidth: on its own, aspectRatio fits itself to
            // whichever incoming constraint binds. Forcing the width instead makes
            // the frame taller than its slot the moment the tuning panel opens, and
            // the bottom of the preview disappears under the controls.
            .aspectRatio(camera.previewSize.height.toFloat() / camera.previewSize.width)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .border(1.dp, Ink.Line, RoundedCornerShape(18.dp)),
    ) {
        CameraPreview(
            previewSize = camera.previewSize,
            sensorOrientation = camera.sensorOrientation,
            onSurfaceReady = onSurfaceReady,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LensBar(
    cameras: List<AstroCamera>,
    selected: AstroCamera?,
    enabled: Boolean,
    tuningOpen: Boolean,
    onSelect: (AstroCamera) -> Unit,
    onToggleTuning: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Only cameras that can be driven manually are offered. One that cannot
            // be told its exposure would meter the night sky as a black frame and
            // hand back exactly that.
            cameras.filter { it.supportsManual && !it.isFrontFacing }.forEach { camera ->
                LensTab(
                    label = camera.label.substringBefore(" ("),
                    active = camera.id == selected?.id,
                    enabled = enabled,
                    onClick = { onSelect(camera) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        GhostIcon(
            icon = Icons.Filled.Tune,
            description = "Focus and brightness",
            active = tuningOpen,
            onClick = onToggleTuning,
        )
        GhostIcon(
            icon = Icons.Filled.Settings,
            description = "Settings",
            active = false,
            onClick = onOpenSettings,
        )
    }
}

/**
 * A lens choice as a word with a dot under it, not a filled slab.
 *
 * Two or three of these sit at the top of the screen permanently; as solid pills
 * they dominated a view whose whole job is to show the sky.
 */
@Composable
private fun LensTab(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (active) Ink.Accent else Ink.TextSecondary,
        label = "lensColor",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = Type.Label, color = color)
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) Ink.Accent else Color.Transparent),
        )
    }
}

@Composable
private fun GhostIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        if (active) Ink.Accent else Ink.TextSecondary,
        label = "iconTint",
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** Live numbers during a capture, floating over the frame they describe. */
@Composable
private fun CaptureHud(state: UiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.Scrim)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val target = state.plan?.frameCount ?: 0
        Text(
            text = if (target == Int.MAX_VALUE) {
                "${state.framesDone} frames"
            } else {
                "${state.framesDone}/$target frames"
            },
            style = Type.Readout,
            color = Ink.TextPrimary,
        )
        Text(formatElapsed(state.elapsedMs), style = Type.ReadoutSmall, color = Ink.TextSecondary)

        // What the sensor actually did, as opposed to what it was asked for. A
        // phone that silently clamps the exposure shows up here and nowhere else.
        state.honouredExposureNs?.let {
            Text(
                text = CapturePlan.formatSeconds(it),
                style = Type.ReadoutSmall,
                color = Ink.TextTertiary,
            )
        }

        val (shiftX, shiftY) = state.alignShift
        if (shiftX != 0 || shiftY != 0) {
            // Watching this creep upward is watching the sky rotate.
            Text(
                text = "drift $shiftX,$shiftY",
                style = Type.ReadoutSmall,
                color = Ink.TextTertiary,
            )
        }
    }
}

@Composable
private fun Countdown(seconds: Int) {
    Box(
        modifier = Modifier
            .size(128.dp)
            .clip(CircleShape)
            .background(Ink.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Text(seconds.toString(), style = Type.Countdown, color = Ink.Accent)
    }
}

@Composable
private fun Toast(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = Type.Caption,
        color = Ink.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.Scrim)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun ControlDeck(
    state: UiState,
    showTuning: Boolean,
    onSelectMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onFocusChange: (Float) -> Unit,
    onEvChange: (Float) -> Unit,
    onTimerChange: (Int) -> Unit,
    onOpenLastPhoto: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Ink.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showTuning) {
            TuningPanel(
                focus = state.settings.focusDiopters,
                ev = state.settings.evOffset,
                onFocusChange = onFocusChange,
                onEvChange = onEvChange,
            )
        }

        PlanReadout(state.plan)

        ModeSelector(
            selected = state.mode,
            enabled = !state.isCapturing && !state.isCountingDown,
            onSelect = onSelectMode,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
                LastShot(state.thumbnail, onOpenLastPhoto)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ShutterButton(state = state, onClick = onShutter)
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                TimerButton(
                    seconds = state.settings.timerSeconds,
                    enabled = !state.isCapturing && !state.isCountingDown,
                    onChange = onTimerChange,
                )
            }
        }
    }
}

/**
 * What the app decided to do, before it does it.
 *
 * The promise is that you pick a duration and it works out the rest, but a black
 * box quietly choosing your settings is not something to trust at two in the
 * morning when a picture comes out wrong. The numbers are set in monospace and
 * given the most weight on the screen after the shutter; the reason sits under
 * them in a single quiet line, where it informs without competing.
 */
@Composable
private fun PlanReadout(plan: CapturePlan?) {
    if (plan == null) {
        Text("Reading the sky", style = Type.Caption, color = Ink.TextTertiary)
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Value(CapturePlan.formatSeconds(plan.subExposureNs).replace(" ", ""))
            Dot()
            Value("ISO ${plan.iso}")
            if (plan.mode != CaptureMode.SINGLE) {
                Dot()
                Value(if (plan.frameCount == Int.MAX_VALUE) "∞" else "×${plan.frameCount}")
                Dot()
                Value(
                    if (plan.mode.isIndefinite) {
                        "open"
                    } else {
                        String.format(java.util.Locale.US, "%.0fs", plan.plannedIntegrationMs / 1000.0)
                    }
                )
            }
        }

        Spacer(Modifier.height(5.dp))

        Text(
            text = when (plan.limitedBy) {
                ExposureLimit.SCENE_BRIGHTNESS -> "Too bright for a long frame — shutter shortened"
                ExposureLimit.STAR_TRAILING -> "Short enough to keep stars as points"
                ExposureLimit.HARDWARE ->
                    if (plan.mode == CaptureMode.SINGLE) {
                        "One frame, no stacking"
                    } else {
                        "The longest frame this sensor allows, stacked"
                    }
            },
            style = Type.Caption,
            color = Ink.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = Type.Readout, color = Ink.TextPrimary)
}

@Composable
private fun Dot() {
    Box(
        modifier = Modifier
            .padding(horizontal = 9.dp)
            .size(3.dp)
            .clip(CircleShape)
            .background(Ink.TextTertiary),
    )
}

/**
 * A segmented track rather than four separate buttons.
 *
 * These are four values of one setting, and a shared track says so at a glance;
 * as loose pills they read as four unrelated things and the row ran off the edge
 * of a narrow screen.
 */
@Composable
private fun ModeSelector(
    selected: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Ink.SurfaceHigh)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        CaptureMode.entries.forEach { mode ->
            val active = mode == selected
            val background by animateColorAsState(
                if (active) Ink.Accent else Color.Transparent,
                label = "segmentBg",
            )
            val textColor by animateColorAsState(
                if (active) Ink.Background else Ink.TextSecondary,
                label = "segmentFg",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable(
                        enabled = enabled,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(mode) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(mode.label, style = Type.Label, color = textColor)
            }
        }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // Focus is the one thing no amount of stacking can rescue. Autofocus cannot
        // lock onto a star, so it is set by hand and starts at infinity — right for
        // the sky, but phone lenses vary enough to want a nudge.
        TuningSlider(
            name = "Focus",
            value = if (focusValue <= 0.01f) "∞" else String.format(java.util.Locale.US, "%.2f", focusValue),
            position = focusValue,
            range = 0f..1.5f,
            onValueChange = { focusValue = it },
            onSettled = { onFocusChange(focusValue) },
        )
        TuningSlider(
            name = "Bright",
            value = String.format(java.util.Locale.US, "%+.1f EV", evValue),
            position = evValue,
            range = -3f..3f,
            onValueChange = { evValue = it },
            onSettled = { onEvChange(evValue) },
        )
    }
}

@Composable
private fun TuningSlider(
    name: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onSettled: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(name, style = Type.Caption, color = Ink.TextSecondary)
            Text(value, style = Type.ReadoutSmall, color = Ink.TextPrimary)
        }
        Slider(
            value = position,
            onValueChange = onValueChange,
            onValueChangeFinished = onSettled,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ink.Accent,
                activeTrackColor = Ink.Accent,
                inactiveTrackColor = Ink.Line,
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun LastShot(thumbnail: android.graphics.Bitmap?, onClick: () -> Unit) {
    if (thumbnail == null) return

    Image(
        bitmap = thumbnail.asImageBitmap(),
        contentDescription = "Open the last photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TimerButton(seconds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    // Cycles rather than opening a menu: three values, and one thumb in the dark.
    val next = when (seconds) {
        0 -> 3
        3 -> 10
        else -> 0
    }
    val tint = if (seconds == 0) Ink.TextSecondary else Ink.Accent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(next) }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = "Self timer",
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (seconds == 0) "off" else "${seconds}s",
            style = Type.ReadoutSmall,
            color = tint,
        )
    }
}

/**
 * The shutter: a fixed outer ring with a shape inside that changes state.
 *
 * A white disc means ready, an amber square means running and tapping it stops.
 * Progress is drawn as an arc on the ring itself rather than as a separate bar,
 * so the one thing worth watching during a two minute exposure is also the one
 * thing under your thumb.
 */
@Composable
private fun ShutterButton(state: UiState, onClick: () -> Unit) {
    val running = state.isCapturing
    val counting = state.isCountingDown

    val innerSize by animateDpAsState(if (running) 28.dp else 58.dp, label = "shutterSize")
    val innerCorner by animateDpAsState(if (running) 7.dp else 29.dp, label = "shutterCorner")
    val innerColor by animateColorAsState(
        when {
            running -> Ink.Danger
            counting -> Ink.Accent
            state.isBusy -> Ink.TextTertiary
            else -> Color.White
        },
        label = "shutterColor",
    )
    val progress by animateFloatAsState(state.progress ?: 0f, label = "shutterProgress")

    Box(
        modifier = Modifier
            .size(76.dp)
            .clickable(
                enabled = state.selected != null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f

            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = radius,
                style = Stroke(width = stroke),
            )

            if (running && state.progress != null) {
                drawArc(
                    color = Ink.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(innerColor),
        )
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
