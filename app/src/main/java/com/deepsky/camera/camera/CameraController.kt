package com.deepsky.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.deepsky.camera.stack.FrameStacker
import com.deepsky.camera.stack.JpegEncoder
import com.deepsky.camera.stack.PlaneSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives one camera through a stacked exposure.
 *
 * Everything the sensor is told is set explicitly and identically for every frame
 * of a capture. That uniformity is not fussiness: the stacker adds frames pixel
 * for pixel, so if the camera were free to re-meter, re-focus or re-balance
 * colour between them, the result would be a blurred average of several different
 * photographs rather than one deep one.
 */
class CameraController(private val context: Context) {

    interface Listener {
        /** A frame has been folded into the stack. */
        fun onFrameStacked(framesDone: Int, framesTarget: Int, shiftX: Int, shiftY: Int)

        /**
         * What the HAL actually did with the first frame, which is not always what
         * it was asked for. Surfaced rather than logged because a silently clamped
         * exposure changes what the photograph can possibly be.
         */
        fun onExposureConfirmed(exposureNs: Long, iso: Int)

        /** The capture finished or was stopped; [jpeg] is the finished image. */
        fun onCaptureComplete(jpeg: ByteArray?, frames: Int, integrationMs: Long)

        fun onError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var camera: AstroCamera? = null
    private var characteristics: CameraCharacteristics? = null
    private var requestableKeys: Set<CaptureRequest.Key<*>> = emptySet()

    private var stacker: FrameStacker? = null

    @Volatile private var capturing = false
    @Volatile private var framesTarget = 0
    @Volatile private var framesSeen = 0
    @Volatile private var captureStartedAt = 0L
    @Volatile private var autoStretch = true

    private var listener: Listener? = null

    /**
     * Manual focus position in dioptres. 0 is infinity, which is where the stars
     * are — but phone lenses do not always agree, so the UI can nudge it.
     */
    @Volatile var focusDiopters: Float = 0f

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("MissingPermission")
    suspend fun open(target: AstroCamera, preview: Surface) {
        close()
        camera = target
        previewSurface = preview
        characteristics = runCatching { manager.getCameraCharacteristics(target.id) }.getOrNull()
        requestableKeys = characteristics
            ?.let { runCatching { it.availableCaptureRequestKeys.toSet() }.getOrNull() }
            ?: emptySet()

        val thread = HandlerThread("deepsky-camera").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        device = suspendCancellableCoroutine { continuation ->
            manager.openCamera(target.id, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    if (continuation.isActive) continuation.resume(opened) else opened.close()
                }

                override fun onDisconnected(opened: CameraDevice) {
                    opened.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                }

                override fun onError(opened: CameraDevice, error: Int) {
                    opened.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Camera failed to open (error $error)")
                        )
                    }
                }
            }, handler)
        }

        val reader = ImageReader.newInstance(
            target.captureSize.width,
            target.captureSize.height,
            android.graphics.ImageFormat.YUV_420_888,
            // Three buffers: one being written, one being stacked, one spare. More
            // only adds latency between the shutter closing and the stack catching
            // up, and each costs 24 MB at this resolution.
            3,
        )
        this.reader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)

        session = createSession(listOf(preview, reader.surface))
        startPreview()
    }

    private suspend fun createSession(surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            val device = device ?: run {
                continuation.resumeWithException(IllegalStateException("Camera is not open"))
                return@suspendCancellableCoroutine
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(configured)
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Could not configure the camera for capture")
                            )
                        }
                    }
                },
                handler,
            )
        }

    fun close() {
        capturing = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        stacker = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------------ preview

    /**
     * Live view with the camera's own metering running.
     *
     * Auto exposure is deliberately left on here — it produces the reading that
     * [meter] turns into a manual plan, and it gives a bright enough preview to
     * aim and focus by even though the real capture will be far darker per frame.
     */
    private fun startPreview() {
        val device = device ?: return
        val preview = previewSurface ?: return
        val session = session ?: return

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            applyFocus(this)
        }.build()

        runCatching { session.setRepeatingRequest(request, null, handler) }
            .onFailure { listener?.onError(it.message ?: "Preview failed") }
    }

    /**
     * Asks the camera what it makes of the sky in front of it.
     *
     * On a dark sky the AE almost always pins itself to its own ceiling, which is
     * exactly the answer we want: the planner then starts from the most light this
     * camera believes it can gather, and stacking takes it from there.
     */
    suspend fun meter(): SceneMetering {
        val device = device ?: return SceneMetering.DARK_SKY_FALLBACK
        val preview = previewSurface ?: return SceneMetering.DARK_SKY_FALLBACK
        val session = session ?: return SceneMetering.DARK_SKY_FALLBACK

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            applyFocus(this)
        }.build()

        // AE needs a few frames to converge in the dark. If it never reports at all
        // — some HALs withhold sensor values in auto mode — the capture still goes
        // ahead on a sane dark-sky guess rather than refusing to shoot.
        val metered = withTimeoutOrNull(METERING_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var seen = 0
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        captureSession: CameraCaptureSession,
                        captureRequest: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        seen++
                        if (seen < METERING_FRAMES || !continuation.isActive) return

                        val exposure = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
                        val iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY)
                        if (exposure != null && iso != null && exposure > 0 && iso > 0) {
                            continuation.resume(SceneMetering(exposure, iso))
                        } else {
                            continuation.resume(SceneMetering.DARK_SKY_FALLBACK)
                        }
                    }
                }
                runCatching { session.setRepeatingRequest(request, callback, handler) }
                    .onFailure {
                        if (continuation.isActive) continuation.resume(SceneMetering.DARK_SKY_FALLBACK)
                    }
            }
        } ?: SceneMetering.DARK_SKY_FALLBACK

        Log.i(TAG, "Metered ${metered.exposureNs} ns at ISO ${metered.iso}")
        return metered
    }

    // ------------------------------------------------------------------ capture

    /**
     * Begins a stacked exposure and returns immediately; progress arrives on the
     * [Listener].
     */
    fun startCapture(plan: CapturePlan, alignFrames: Boolean, autoStretch: Boolean) {
        val device = device ?: return
        val session = session ?: return
        val reader = reader ?: return
        val camera = camera ?: return
        val preview = previewSurface

        this.autoStretch = autoStretch
        stacker = FrameStacker(
            width = camera.captureSize.width,
            height = camera.captureSize.height,
            alignFrames = alignFrames,
            margin = EDGE_CROP_PX,
        )
        framesTarget = plan.frameCount
        framesSeen = 0
        captureStartedAt = System.currentTimeMillis()
        capturing = true

        // TEMPLATE_MANUAL, not TEMPLATE_STILL_CAPTURE.
        //
        // The still-capture template asks the device for its *best photograph*,
        // which on this phone means the vendor's multi-frame pipeline — scene
        // optimiser, multi-frame noise reduction, its own idea of tone. Driven as a
        // repeating request with manual exposure underneath it, that pipeline
        // produces frames that are neither what was asked for nor consistent with
        // each other, which is ruinous for something that adds frames together.
        // TEMPLATE_MANUAL is the one template defined to hand back the sensor's
        // output with the processing left alone.
        val template = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
        }.getOrElse {
            Log.w(TAG, "TEMPLATE_MANUAL unavailable, falling back", it)
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        }

        val request = template.apply {
            addTarget(reader.surface)
            // The preview keeps updating from the same frames, so what is on screen
            // during a capture really is what is being stacked.
            preview?.let { addTarget(it) }

            // Manual exposure and focus, with white balance merely *locked*.
            //
            // CONTROL_MODE_OFF would switch off auto exposure, focus and white
            // balance in one line — and the HAL's colour correction with them,
            // leaving frames with a heavy green cast unless the app supplies its own
            // transform matrix. Locking AWB gets the part that matters, which is
            // that colour cannot drift between frames about to be summed.
            setIfSupported(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            setIfSupported(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            setIfSupported(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            setIfSupported(CaptureRequest.CONTROL_AWB_LOCK, true)
            setIfSupported(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)

            setIfSupported(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.subExposureNs)
            setIfSupported(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
            // Frame duration must be at least the exposure, or the HAL shortens the
            // exposure to fit the frame rate it was asked for.
            setIfSupported(CaptureRequest.SENSOR_FRAME_DURATION, plan.subExposureNs)

            applyFocus(this)

            // Stabilisation works against a tripod: it hunts, and every correction
            // shifts the field between otherwise identical frames.
            setIfSupported(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
            setIfSupported(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )

            // On-sensor noise reduction and edge sharpening both destroy faint
            // stars — they look exactly like the noise these filters exist to
            // remove. Stacking is the better noise reduction and needs the
            // untouched frame to work on.
            setIfSupported(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            setIfSupported(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)

            // Locks the sensor's black reference so the floor cannot shift under a
            // stack that is summing it sixty times over.
            setIfSupported(CaptureRequest.BLACK_LEVEL_LOCK, true)
        }.build()

        runCatching { session.setRepeatingRequest(request, captureCallback, handler) }
            .onFailure {
                capturing = false
                listener?.onError(it.message ?: "Could not start the exposure")
            }
    }

    /**
     * Sets a key only if this camera admits to accepting it.
     *
     * Silently ignoring an unknown key is the documented behaviour, but not the
     * universal one: some vendor HALs reject the entire request instead, which
     * fails the whole capture over a setting that was only ever a preference.
     */
    private fun <T> CaptureRequest.Builder.setIfSupported(key: CaptureRequest.Key<T>, value: T) {
        if (requestableKeys.isEmpty() || key in requestableKeys) {
            runCatching { set(key, value) }
                .onFailure { Log.w(TAG, "Camera refused ${key.name}", it) }
        } else {
            Log.i(TAG, "Skipping unsupported key ${key.name}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private var reported = false

        override fun onCaptureCompleted(
            captureSession: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (reported || !capturing) return
            reported = true

            val exposure = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: return
            val asked = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
            Log.i(TAG, "Asked for $asked ns, HAL honoured $exposure ns at ISO $iso")
            listener?.onExposureConfirmed(exposure, iso)
        }
    }

    /**
     * Ends an exposure and produces the finished image.
     *
     * Also the normal end of an indefinite capture — the frames already stacked
     * are a complete photograph, so stopping never discards anything.
     */
    fun stopCapture() {
        if (!capturing) return
        capturing = false
        runCatching { session?.stopRepeating() }

        val stacker = stacker
        val elapsed = System.currentTimeMillis() - captureStartedAt

        handler?.post {
            val jpeg = runCatching {
                stacker?.averageToNv21(autoStretch)?.let {
                    JpegEncoder.encode(it, stacker.outputWidth, stacker.outputHeight)
                }
            }.onFailure { Log.e(TAG, "Encoding the stack failed", it) }.getOrNull()

            listener?.onCaptureComplete(jpeg, stacker?.frameCount ?: 0, elapsed)
            this.stacker = null
            startPreview()
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        try {
            if (!capturing) return
            val stacker = stacker ?: return

            framesSeen++
            // The first frame after switching into manual is delivered while the
            // sensor is still ramping to the requested exposure, and it is also the
            // one most likely to carry the shake from the tap that started the
            // capture. It is thrown away rather than stacked.
            if (framesSeen <= WARMUP_FRAMES) return

            stacker.add(
                image.planes[0].toSource(),
                image.planes[1].toSource(),
                image.planes[2].toSource(),
            )
            val done = stacker.frameCount
            listener?.onFrameStacked(done, framesTarget, stacker.lastShiftX, stacker.lastShiftY)

            if (framesTarget != Int.MAX_VALUE && done >= framesTarget) {
                stopCapture()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Stacking failed", error)
            capturing = false
            listener?.onError(error.message ?: "Stacking failed")
        } finally {
            image.close()
        }
    }

    private fun Image.Plane.toSource() = PlaneSource(buffer, rowStride, pixelStride)

    /**
     * Points the lens at infinity, or wherever the user has nudged it.
     *
     * Autofocus cannot focus on stars: there is nothing for it to lock onto, and it
     * hunts until it settles on whatever is nearest, ruining the frame. So it is
     * switched off and the distance set by hand. Fixed-focus cameras ignore this
     * entirely, which is the correct outcome for them.
     */
    private fun applyFocus(builder: CaptureRequest.Builder) {
        val minimumFocusDistance =
            characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minimumFocusDistance == 0f) return // Fixed focus: already at infinity.

        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            focusDiopters.coerceIn(0f, minimumFocusDistance),
        )
    }

    private companion object {
        const val TAG = "DeepSky"
        const val METERING_FRAMES = 8
        const val METERING_TIMEOUT_MS = 4_000L
        const val WARMUP_FRAMES = 1

        /**
         * Trimmed from every edge of the finished photograph. Measured on this
         * phone: the outer ~25 rows and ~20 columns read about 30% brighter than
         * the interior once a stack is stretched.
         */
        const val EDGE_CROP_PX = 32
    }
}
