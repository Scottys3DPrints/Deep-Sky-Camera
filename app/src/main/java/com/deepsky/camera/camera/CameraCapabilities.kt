package com.deepsky.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size

/**
 * What one physical camera on this phone is actually willing to do.
 *
 * Every number here is read from [CameraCharacteristics] at runtime rather than
 * assumed, because the ceiling that matters most — how long a single exposure
 * may be — varies by more than an order of magnitude between cameras on the
 * *same* device. On the Galaxy A52s the main camera stops at 0.45 s while the
 * ultra-wide reaches 0.67 s, and neither comes close to the 10–30 s that the
 * stock camera app achieves through Samsung's private vendor tags. Discovering
 * that at runtime is what lets the app plan an honest exposure instead of
 * promising one it cannot take.
 */
data class AstroCamera(
    val id: String,
    val label: String,
    val facing: Int,
    val focalLengthMm: Float,
    /** f-number. Smaller gathers more light, and widens the star-trail budget. */
    val apertureF: Float,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val hardwareLevel: Int,
    val supportsManual: Boolean,
    val supportsRaw: Boolean,
    /** Sensor pixel pitch in micrometres — the NPF trail rule needs it. */
    val pixelPitchUm: Float,
    val captureSize: Size,
    val previewSize: Size,
    val sensorOrientation: Int,
) {
    val maxExposureMs: Long get() = maxExposureNs / 1_000_000L

    /**
     * 35 mm equivalent focal length, derived from the sensor diagonal. Only used
     * for display: the trail budget is computed from the real focal length and
     * pixel pitch, which is strictly better than any equivalence shorthand.
     */
    val isFrontFacing: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL"
        }
}

object CameraCapabilities {

    /**
     * Stacking keeps a 32-bit accumulator per channel for the whole exposure, so
     * capture resolution is bounded rather than simply "the largest on offer".
     * 16.8 MP is comfortably above this phone's 16 MP binned output and still
     * leaves room on the heap for the frame being read and the JPEG being built.
     */
    private const val MAX_CAPTURE_PIXELS = 16_800_000L

    /**
     * Enumerates every camera worth offering, best first.
     *
     * Cameras without manual sensor control are kept rather than hidden: the UI
     * needs something to say when a phone has no manual camera at all, and
     * saying "your ultra-wide cannot be driven manually" is more useful than
     * silently offering nothing.
     */
    fun discover(context: Context): List<AstroCamera> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())

        val found = cameras.mapNotNull { id ->
            runCatching { describe(manager, id) }.getOrNull()
        }

        // Rear cameras first, manual-capable first, then widest exposure ceiling:
        // that ordering puts the camera most likely to produce a usable frame of
        // the night sky under the user's thumb by default.
        val rear = found.filter { !it.isFrontFacing }
        val front = found.filter { it.isFrontFacing }

        val sortedRear = rear.sortedWith(
            compareByDescending<AstroCamera> { it.supportsManual }
                .thenByDescending { it.maxExposureNs }
        )
        return sortedRear + front.sortedByDescending { it.supportsManual }
    }

    private fun describe(manager: CameraManager, id: String): AstroCamera? {
        val chars = manager.getCameraCharacteristics(id)

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val supportsManual = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )
        val supportsRaw = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
        )

        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        if (yuvSizes.isEmpty()) return null

        val captureSize = yuvSizes
            .filter { it.width.toLong() * it.height <= MAX_CAPTURE_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: yuvSizes.minByOrNull { it.width.toLong() * it.height }
            ?: return null

        // A preview near 1080p: large enough to focus and frame by, small enough
        // that it never becomes the bottleneck in a long capture.
        val previewSize = yuvSizes
            .filter { it.width <= 1920 && it.height <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: captureSize

        // Pitch is deliberately measured against the size we actually record, not
        // against the raw photosite array. This sensor bins four 0.8 µm photosites
        // into one 1.6 µm output pixel, and it is the output pixel a star trails
        // across — using the array figure would halve the trail budget for no
        // reason.
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelPitchUm = if (physical != null && captureSize.width > 0) {
            physical.width / captureSize.width * 1000f
        } else {
            1.0f
        }

        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 4.0f
        val aperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull() ?: 2.0f

        val facing = chars.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK

        return AstroCamera(
            id = id,
            label = labelFor(id, facing, focal),
            facing = facing,
            focalLengthMm = focal,
            apertureF = aperture,
            minExposureNs = exposureRange?.lower ?: 100_000L,
            maxExposureNs = exposureRange?.upper ?: 100_000_000L,
            minIso = isoRange?.lower ?: 50,
            maxIso = isoRange?.upper ?: 800,
            hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            supportsManual = supportsManual,
            supportsRaw = supportsRaw,
            pixelPitchUm = pixelPitchUm,
            captureSize = captureSize,
            previewSize = previewSize,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
        )
    }

    /**
     * Names a camera the way its owner thinks of it.
     *
     * Focal length is the only reliable signal here — Android exposes no "this is
     * the ultra-wide" flag, and the id numbering is vendor-specific — so the
     * short lens becomes "Ultra-wide" and the long one "Telephoto".
     */
    private fun labelFor(id: String, facing: Int, focalMm: Float): String {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "Front"
        return when {
            focalMm < 3.0f -> "Ultra-wide"
            focalMm > 7.0f -> "Telephoto"
            else -> "Main"
        } + " ($id)"
    }
}
