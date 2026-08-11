package com.deepsky.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepsky.camera.ui.CameraScreen
import com.deepsky.camera.ui.CaptureViewModel
import com.deepsky.camera.ui.DeepSkyTheme
import com.deepsky.camera.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A stack runs for minutes with no touch input. If the screen slept the
        // capture would be cut short, so it is held awake for as long as the app
        // is in front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            DeepSkyTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val viewModel: CaptureViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showSettings by remember { mutableStateOf(false) }

    val requestPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) {
            requestPermission.launch(Manifest.permission.CAMERA)
        } else {
            // Cameras are enumerated before any preview exists, because the
            // preview surface has to be created at a size the camera supports.
            viewModel.discoverCameras()
        }
    }

    when {
        !hasCameraPermission -> PermissionWall(
            onGrant = { requestPermission.launch(Manifest.permission.CAMERA) },
        )

        showSettings -> SettingsScreen(
            state = state,
            onBack = { showSettings = false },
            onAlignChange = viewModel::setAlignFrames,
            onStretchChange = viewModel::setAutoStretch,
            onUpdateUrlChange = viewModel::setUpdateUrl,
            onCheckForUpdates = viewModel::checkForUpdates,
            onInstallUpdate = viewModel::downloadAndInstall,
        )

        else -> CameraScreen(
            state = state,
            onSurfaceReady = viewModel::onSurfaceReady,
            onSelectCamera = viewModel::selectCamera,
            onSelectMode = viewModel::selectMode,
            onShutter = viewModel::onShutter,
            onFocusChange = viewModel::setFocusDiopters,
            onEvChange = viewModel::setEvOffset,
            onOpenSettings = { showSettings = true },
            onDismissMessage = viewModel::dismissMessage,
        )
    }
}

@Composable
private fun PermissionWall(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Deep Sky Camera needs the camera",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "It is used only while the app is open, to take the photographs you ask " +
                "for. Nothing is uploaded anywhere.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
        )
        Button(onClick = onGrant) { Text("Allow camera") }
    }
}
