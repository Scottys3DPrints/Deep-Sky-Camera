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
    /** What the sensor actually delivered, which is not always what was asked. */
    val honouredExposureNs: Long? = null,
    val honouredIso: Int? = null,
    val lastSavedName: String? = null,
    val lastSavedUri: android.net.Uri? = null,
    val thumbnail: android.graphics.Bitmap? = null,
    /** Seconds left on the self-timer, or null when it is not running. */
    val countdown: Int? = null,
    val message: String? = null,
    val settings: Settings = Settings(),
    val update: UpdateState = UpdateState.Idle,
) {
    val isCapturing: Boolean get() = phase == Phase.Capturing
    val isCountingDown: Boolean get() = countdown != null
    val isBusy: Boolean get() = phase == Phase.Metering || phase == Phase.Saving

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

    private var shutterJob: Job? = null

    fun onShutter() {
        val current = _state.value

        // One button, three meanings, in the order you reach for them: stop a
        // running capture, abandon a countdown, or begin.
        if (current.isCapturing) {
            controller.stopCapture()
            return
        }
        if (current.isCountingDown) {
            shutterJob?.cancel()
            _state.value = _state.value.copy(countdown = null, message = null)
            return
        }
        if (current.isBusy) return

        val camera = current.selected ?: return
        if (!camera.supportsManual) {
            _state.value = current.copy(
                message = "${camera.label} cannot be driven manually. Pick another camera.",
            )
            return
        }

        shutterJob = viewModelScope.launch {
            _state.value = _state.value.copy(message = null, honouredExposureNs = null)

            // Hands off the phone before the sensor opens. The wobble from tapping
            // a phone propped against a wall lasts well over a second, and it would
            // otherwise land in the first frames of the stack.
            val timer = _state.value.settings.timerSeconds
            for (remaining in timer downTo 1) {
                _state.value = _state.value.copy(countdown = remaining)
                delay(1000)
            }
            _state.value = _state.value.copy(countdown = null)

            // Re-meter immediately before the exposure. The sky measured when the
            // app opened may be minutes old by now, and clouds move.
            _state.value = _state.value.copy(phase = Phase.Metering)
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

    override fun onExposureConfirmed(exposureNs: Long, iso: Int) {
        _state.value = _state.value.copy(honouredExposureNs = exposureNs, honouredIso = iso)
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
                        lastSavedUri = it.uri,
                        message = "Saved — $frames frames, " + String.format(
                            java.util.Locale.US, "%.1f s of light", integrationMs / 1000.0,
                        ),
                    )
                },
                onFailure = {
                    _state.value.copy(
                        phase = Phase.Ready,
                        message = it.message ?: "Could not save the photo",
                    )
                },
            )

            // A thumbnail of what was actually written to the gallery, not of what
            // we hoped we wrote. If the file is unreadable this comes back null and
            // the absence of a preview is itself the warning.
            saved.getOrNull()?.let { result ->
                val thumbnail = withContext(Dispatchers.IO) { decodeThumbnail(jpeg) }
                _state.value = _state.value.copy(thumbnail = thumbnail)
            }
        }
    }

    /** Small enough to hold onto between shots without a second thought. */
    private fun decodeThumbnail(jpeg: ByteArray): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 256) sample *= 2

        android.graphics.BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

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

    fun setTimerSeconds(value: Int) = viewModelScope.launch {
        settingsStore.setTimerSeconds(value)
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
