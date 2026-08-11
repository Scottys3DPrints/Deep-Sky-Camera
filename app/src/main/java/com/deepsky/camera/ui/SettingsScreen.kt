package com.deepsky.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Section("Stacking") {
            ToggleRow(
                title = "Align frames",
                subtitle = "Tracks the stars as the sky turns and shifts each frame back " +
                    "into place. Without it, a long capture blurs into short arcs.",
                checked = state.settings.alignFrames,
                onCheckedChange = onAlignChange,
            )
            ToggleRow(
                title = "Auto stretch",
                subtitle = "A stacked sky sits in a narrow dark band of the histogram and " +
                    "looks black until it is pulled apart. Turn off to keep the raw levels.",
                checked = state.settings.autoStretch,
                onCheckedChange = onStretchChange,
            )
        }

        Section("This phone's cameras") {
            Text(
                text = "Read from the hardware, not assumed. The longest single frame is " +
                    "the number that decides how many frames a stack needs.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            state.cameras.filter { !it.isFrontFacing }.forEach { camera ->
                CameraFacts(camera)
            }
        }

        Section("Updates") {
            Text(
                text = "Version ${UpdateChecker.currentVersion}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Updates install over the top of this app, so nothing is lost and " +
                    "you never reinstall.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            UpdatePanel(
                update = state.update,
                onCheck = onCheckForUpdates,
                onInstall = onInstallUpdate,
            )

            var url by remember(state.settings.updateUrl) {
                mutableStateOf(state.settings.updateUrl)
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Update manifest URL") },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            Button(
                onClick = { onUpdateUrlChange(url) },
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text("Save address")
            }
        }
    }
}

@Composable
private fun UpdatePanel(
    update: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateChecker.Manifest) -> Unit,
) {
    when (update) {
        UpdateState.Idle -> Button(onClick = onCheck) { Text("Check for updates") }

        UpdateState.Checking -> Text(
            text = "Checking…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        UpdateState.UpToDate -> Column {
            Text(
                text = "You are on the newest version.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Button(onClick = onCheck, modifier = Modifier.padding(top = 6.dp)) {
                Text("Check again")
            }
        }

        is UpdateState.Available -> Column {
            Text(
                text = "Version ${update.manifest.versionName} is available.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (update.manifest.notes.isNotBlank()) {
                Text(
                    text = update.manifest.notes,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Button(
                onClick = { onInstall(update.manifest) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Download and install")
            }
        }

        is UpdateState.Downloading -> Column {
            Text(
                text = "Downloading ${(update.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { update.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        UpdateState.Installing -> Text(
            text = "Installing…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is UpdateState.Failed -> Column {
            Text(
                text = update.reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onCheck, modifier = Modifier.padding(top = 6.dp)) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun CameraFacts(camera: AstroCamera) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = camera.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Fact("Longest single frame", CapturePlan.formatSeconds(camera.maxExposureNs))
        Fact("ISO range", "${camera.minIso} – ${camera.maxIso}")
        Fact("Lens", "%.2f mm f/%.1f".format(camera.focalLengthMm, camera.apertureF))
        Fact("Capture size", "${camera.captureSize.width} × ${camera.captureSize.height}")
        Fact("Pixel pitch", "%.2f µm".format(camera.pixelPitchUm))
        Fact("Manual control", if (camera.supportsManual) camera.hardwareLevelName else "not available")
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
        HorizontalDivider(
            modifier = Modifier.padding(top = 14.dp),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
