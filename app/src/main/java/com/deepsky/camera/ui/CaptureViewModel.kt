package com.deepsky.camera.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepsky.camera.camera.AstroCamera
import com.deepsky.camera.camera.CameraCapabilities
import com.deepsky.camera.camera.CameraController
import com.deepsky.camera.camera.CaptureMode
import com.deepsky.camera.camera.CapturePlan
import com.deepsky.camera.camera.CapturePlanner
import com.deepsky.camera.camera.SceneMetering
import com.deepsky.camera.settings.Settings
import com.deepsky.camera.settings.SettingsStore
import com.deepsky.camera.storage.ImageSaver
import com.deepsky.camera.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { Idle, Opening, Ready, Metering, Capturing, Saving }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateChecker.Manifest) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val reason: String) : UpdateState
}

data class UiState(
    val cameras: List<AstroCamera> = emptyList(),
    val selected: AstroCamera? = null,
    val mode: CaptureMode = CaptureMode.TEN_SECONDS,
    val plan: CapturePlan? = null,
    val phase: Phase = Phase.Idle,
    val framesDone: Int = 0,
    val elapsedMs: Long = 0L,
    val alignShift: Pair<Int, Int> = 0 to 0,
    val lastSavedName: String? = null,
    val message: String? = null,
    val settings: Settings = Settings(),
    val update: UpdateState = UpdateState.Idle,
) {
    val isCapturing: Boolean get() = phase == Phase.Capturing

    /** 0..1, or null when the capture has no fixed end. */
    val progress: Float?
        get() {
            val target = plan?.frameCount ?: return null
            if (target == Int.MAX_VALUE || target <= 0) return null
            return (framesDone.toFloat() / target).coerceIn(0f, 1f)
        }
}

class CaptureViewModel(application: Application) : AndroidViewModel(application),
    CameraController.Listener {

    private val controller = CameraController(application)
    private val settingsStore = SettingsStore(application)
    private val updateChecker = UpdateChecker(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Last reading from the camera's own meter, reused when the mode changes. */
    private var metering: SceneMetering = SceneMetering.DARK_SKY_FALLBACK
    private var timerJob: Job? = null

    init {
        controller.setListener(this)
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                controller.focusDiopters = settings.focusDiopters
                _state.value = _state.value.copy(settings = settings)
                replan()
            }
        }
    }

    // ------------------------------------------------------------------ camera

    /**
     * Reads the hardware before any surface exists.
     *
     * Order matters here: a preview surface has to be created at a size the
     * camera actually offers, or the capture session fails to configure. So the
     * camera list is discovered first, the UI sizes its preview from it, and only
     * then is [onSurfaceReady] called back.
     */
    fun discoverCameras() {
        if (_state.value.cameras.isNotEmpty()) return

        viewModelScope.launch {
            val cameras = withContext(Dispatchers.IO) {
                CameraCapabilities.discover(getApplication())
            }
            if (cameras.isEmpty()) {
                _state.value = _state.value.copy(
                    message = "No camera on this phone can be reached.",
                )
                return@launch
            }

            val remembered = _state.value.settings.cameraId
            val chosen = cameras.firstOrNull { it.id == remembered }
                ?: cameras.firstOrNull { it.supportsManual }
                ?: cameras.first()

            _state.value = _state.value.copy(cameras = cameras, selected = chosen)
            replan()
        }
    }

    fun onSurfaceReady(target: Surface) {
        if (_state.value.phase != Phase.Idle) return
        val camera = _state.value.selected ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(phase = Phase.Opening)
            openAndMeter(camera, target)
        }
    }

    private var surface: Surface? = null

    private suspend fun openAndMeter(camera: AstroCamera, target: Surface) {
        surface = target
        val opened = runCatching { controller.open(camera, target) }
        if (opened.isFailure) {
            _state.value = _state.value.copy(
                phase = Phase.Idle,
                message = opened.exceptionOrNull()?.message ?: "Could not open the camera",
            )
            return
        }

        _state.value = _state.value.copy(phase = Phase.Metering)
        metering = controller.meter()
        _state.value = _state.value.copy(phase = Phase.Ready)
        replan()
    }

    fun selectCamera(camera: AstroCamera) {
        val target = surface ?: return
        if (_state.value.isCapturing) return

        viewModelScope.launch {
            settingsStore.setCameraId(camera.id)
            _state.value = _state.value.copy(selected = camera, phase = Phase.Opening)
            openAndMeter(camera, target)
        }
    }

    fun selectMode(mode: CaptureMode) {
        if (_state.value.isCapturing) return
        _state.value = _state.value.copy(mode = mode)
        replan()
    }

    /** Recomputes the plan whenever anything it depends on changes. */
    private fun replan() {
        val camera = _state.value.selected ?: return
        val plan = CapturePlanner.plan(
            camera = camera,
            mode = _state.value.mode,
            metering = metering,
            evOffset = _state.value.settings.evOffset,
        )
        _state.value = _state.value.copy(plan = plan)
    }

    // ----------------------------------------------------------------- shutter

    fun onShutter() {
        val current = _state.value
        if (current.isCapturing) {
            controller.stopCapture()
            return
        }
        val camera = current.selected ?: return
        if (!camera.supportsManual) {
            _state.value = current.copy(
                message = "${camera.label} cannot be driven manually. Pick another camera.",
            )
            return
        }

        viewModelScope.launch {
            // Re-meter immediately before the exposure. The sky the app measured
            // when it opened may be minutes old by now, and clouds move.
            _state.value = _state.value.copy(phase = Phase.Metering, message = null)
            metering = controller.meter()
            replan()

            val plan = _state.value.plan ?: return@launch
            _state.value = _state.value.copy(
                phase = Phase.Capturing,
                framesDone = 0,
                elapsedMs = 0L,
                lastSavedName = null,
            )
            startTimer()
            controller.startCapture(
                plan = plan,
                alignFrames = _state.value.settings.alignFrames,
                autoStretch = _state.value.settings.autoStretch,
            )
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        val startedAt = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (_state.value.isCapturing) {
                _state.value = _state.value.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                delay(200)
            }
        }
    }

    override fun onFrameStacked(framesDone: Int, framesTarget: Int, shiftX: Int, shiftY: Int) {
        _state.value = _state.value.copy(
            framesDone = framesDone,
            alignShift = shiftX to shiftY,
        )
    }

    override fun onCaptureComplete(jpeg: ByteArray?, frames: Int, integrationMs: Long) {
        timerJob?.cancel()
        val camera = _state.value.selected
        val plan = _state.value.plan

        if (jpeg == null || frames == 0 || camera == null || plan == null) {
            _state.value = _state.value.copy(
                phase = Phase.Ready,
                message = "The capture produced no frames.",
            )
            return
        }

        _state.value = _state.value.copy(phase = Phase.Saving)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                ImageSaver.saveJpeg(
                    context = getApplication(),
                    jpeg = jpeg,
                    sensorOrientation = camera.sensorOrientation,
                    frames = frames,
                    integrationMs = integrationMs,
                    exposureNs = plan.subExposureNs,
                    iso = plan.iso,
                )
            }

            _state.value = saved.fold(
                onSuccess = {
                    _state.value.copy(
                        phase = Phase.Ready,
                        lastSavedName = it.displayName,
                        message = "Saved $frames frames — " +
                            "${"%.1f".format(integrationMs / 1000.0)} s of light",
                    )
                },
                onFailure = {
                    _state.value.copy(
                        phase = Phase.Ready,
                        message = it.message ?: "Could not save the photo",
                    )
                },
            )
        }
    }

    override fun onError(message: String) {
        timerJob?.cancel()
        _state.value = _state.value.copy(phase = Phase.Ready, message = message)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---------------------------------------------------------------- settings

    fun setAlignFrames(value: Boolean) = viewModelScope.launch { settingsStore.setAlignFrames(value) }
    fun setAutoStretch(value: Boolean) = viewModelScope.launch { settingsStore.setAutoStretch(value) }
    fun setEvOffset(value: Float) = viewModelScope.launch { settingsStore.setEvOffset(value) }
    fun setUpdateUrl(value: String) = viewModelScope.launch { settingsStore.setUpdateUrl(value) }

    fun setFocusDiopters(value: Float) = viewModelScope.launch {
        settingsStore.setFocusDiopters(value)
    }

    // ----------------------------------------------------------------- updates

    fun checkForUpdates() {
        viewModelScope.launch {
            _state.value = _state.value.copy(update = UpdateState.Checking)
            val url = settingsStore.settings.first().updateUrl
            _state.value = _state.value.copy(
                update = when (val result = updateChecker.check(url)) {
                    is UpdateChecker.Result.Available -> UpdateState.Available(result.manifest)
                    UpdateChecker.Result.UpToDate -> UpdateState.UpToDate
                    UpdateChecker.Result.NotConfigured ->
                        UpdateState.Failed("No update address is set.")
                    is UpdateChecker.Result.Failed -> UpdateState.Failed(result.reason)
                },
            )
        }
    }

    fun downloadAndInstall(manifest: UpdateChecker.Manifest) {
        if (!updateChecker.canInstall()) {
            updateChecker.openInstallPermissionSettings()
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(update = UpdateState.Downloading(0f))
            val file = updateChecker.download(manifest) { progress ->
                _state.value = _state.value.copy(update = UpdateState.Downloading(progress))
            }

            file.fold(
                onSuccess = {
                    _state.value = _state.value.copy(update = UpdateState.Installing)
                    updateChecker.install(it)
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        update = UpdateState.Failed(it.message ?: "Download failed"),
                    )
                },
            )
        }
    }

    fun releaseCamera() {
        controller.stopCapture()
        controller.close()
        surface = null
        _state.value = _state.value.copy(phase = Phase.Idle)
    }

    override fun onCleared() {
        controller.setListener(null)
        controller.close()
        super.onCleared()
    }
}
