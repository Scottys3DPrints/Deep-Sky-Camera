package com.deepsky.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.deepsky.camera.camera.AstroCamera
import com.deepsky.camera.camera.CapturePlan
import com.deepsky.camera.update.UpdateChecker

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onAlignChange: (Boolean) -> Unit,
    onStretchChange: (Boolean) -> Unit,
    onUpdateUrlChange: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: (UpdateChecker.Manifest) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink.TextPrimary,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text("Settings", style = Type.Title, color = Ink.TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Stacking")
            Card {
                SwitchRow(
                    title = "Align frames",
                    subtitle = "Tracks the stars as the sky turns and shifts each frame back " +
                        "into place. Without it a long capture blurs into short arcs.",
                    checked = state.settings.alignFrames,
                    onCheckedChange = onAlignChange,
                )
                Divider()
                SwitchRow(
                    title = "Auto stretch",
                    subtitle = "A stacked sky sits in a narrow dark band of the histogram and " +
                        "looks black until it is pulled apart.",
                    checked = state.settings.autoStretch,
                    onCheckedChange = onStretchChange,
                )
            }

            SectionLabel("Cameras")
            Text(
                text = "Read from the hardware, not assumed. The longest single frame is the " +
                    "number that decides how many frames a stack needs.",
                style = Type.Caption,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
            state.cameras.filter { !it.isFrontFacing }.forEach { camera ->
                Card { CameraFacts(camera) }
            }

            SectionLabel("Updates")
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Version ${UpdateChecker.currentVersion}",
                        style = Type.Readout,
                        color = Ink.TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Updates install over the top of this app, so nothing is lost " +
                            "and you never reinstall.",
                        style = Type.Caption,
                        color = Ink.TextTertiary,
                    )
                    Spacer(Modifier.height(14.dp))
                    UpdatePanel(
                        update = state.update,
                        onCheck = onCheckForUpdates,
                        onInstall = onInstallUpdate,
                    )
                }
            }

            SectionLabel("Update address")
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    var url by remember(state.settings.updateUrl) {
                        mutableStateOf(state.settings.updateUrl)
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Manifest URL", style = Type.Caption) },
                        singleLine = true,
                        textStyle = Type.ReadoutSmall.copy(color = Ink.TextPrimary),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Ink.Accent,
                            unfocusedBorderColor = Ink.Line,
                            focusedLabelColor = Ink.Accent,
                            unfocusedLabelColor = Ink.TextTertiary,
                            cursorColor = Ink.Accent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButton("Save address", filled = false) { onUpdateUrlChange(url) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = Type.Overline,
        color = Ink.Accent,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Ink.Line),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = Type.Label, color = Ink.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = Type.Caption, color = Ink.TextTertiary)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink.Background,
                checkedTrackColor = Ink.Accent,
                uncheckedThumbColor = Ink.TextTertiary,
                uncheckedTrackColor = Ink.SurfaceHigh,
                uncheckedBorderColor = Ink.Line,
            ),
        )
    }
}

@Composable
private fun CameraFacts(camera: AstroCamera) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = camera.label.substringBefore(" ("),
                style = Type.Label,
                color = Ink.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (camera.supportsManual) camera.hardwareLevelName else "no manual control",
                style = Type.ReadoutSmall,
                color = if (camera.supportsManual) Ink.Accent else Ink.TextTertiary,
            )
        }
        Spacer(Modifier.height(10.dp))

        FactRow("Longest frame", CapturePlan.formatSeconds(camera.maxExposureNs))
        FactRow("ISO range", "${camera.minIso}–${camera.maxIso}")
        FactRow(
            "Lens",
            String.format(java.util.Locale.US, "%.2f mm  f/%.1f", camera.focalLengthMm, camera.apertureF),
        )
        FactRow("Capture", "${camera.captureSize.width}×${camera.captureSize.height}")
        FactRow("Pixel pitch", String.format(java.util.Locale.US, "%.2f µm", camera.pixelPitchUm))
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, style = Type.Caption, color = Ink.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = Type.ReadoutSmall, color = Ink.TextPrimary)
    }
}

@Composable
private fun UpdatePanel(
    update: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateChecker.Manifest) -> Unit,
) {
    when (update) {
        UpdateState.Idle -> ActionButton("Check for updates") { onCheck() }

        UpdateState.Checking -> Text("Checking…", style = Type.Caption, color = Ink.TextSecondary)

        UpdateState.UpToDate -> Column {
            Text("You are on the newest version.", style = Type.Caption, color = Ink.Accent)
            Spacer(Modifier.height(12.dp))
            ActionButton("Check again", filled = false) { onCheck() }
        }

        is UpdateState.Available -> Column {
            Text(
                text = "Version ${update.manifest.versionName} is available",
                style = Type.Label,
                color = Ink.TextPrimary,
            )
            if (update.manifest.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(update.manifest.notes, style = Type.Caption, color = Ink.TextTertiary)
            }
            Spacer(Modifier.height(12.dp))
            ActionButton("Download and install") { onInstall(update.manifest) }
        }

        is UpdateState.Downloading -> Column {
            Text(
                text = "Downloading ${(update.progress * 100).toInt()}%",
                style = Type.ReadoutSmall,
                color = Ink.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { update.progress },
                color = Ink.Accent,
                trackColor = Ink.SurfaceHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }

        UpdateState.Installing -> Text("Installing…", style = Type.Caption, color = Ink.TextPrimary)

        is UpdateState.Failed -> Column {
            Text(update.reason, style = Type.Caption, color = Ink.Danger)
            Spacer(Modifier.height(12.dp))
            ActionButton("Try again", filled = false) { onCheck() }
        }
    }
}

@Composable
private fun ActionButton(label: String, filled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (filled) Ink.Accent else Ink.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            style = Type.Label,
            color = if (filled) Ink.Background else Ink.TextPrimary,
        )
    }
}
