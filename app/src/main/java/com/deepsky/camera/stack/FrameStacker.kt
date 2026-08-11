package com.deepsky.camera.stack

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Adds many short exposures together into one deep one.
 *
 * The arithmetic is the entire reason this app can claim a thirty second
 * exposure on hardware that refuses to open the shutter for longer than half a
 * second. Summing N frames multiplies the star signal by N while the random
 * sensor noise, being random, only grows by sqrt(N). The picture therefore gets
 * cleaner by a factor of sqrt(N) — sixty-seven frames is a little over eight
 * times less noise — and every individual frame stays short enough that the
 * stars remain points.
 *
 * Accumulators are 32-bit per channel and full frame, which is why the app asks
 * for a large heap. At sixteen megapixels that is 96 MB held for the duration of
 * the capture, and it is the honest cost of not throwing away precision: summing
 * into 8-bit would clip after the very first few frames.
 *
 * Not thread-safe. One stacker belongs to one capture, driven from the camera's
 * background thread.
 */
class FrameStacker(
    val width: Int,
    val height: Int,
    /** Whether to compensate for the sky drifting across the sensor. */
    private val alignFrames: Boolean = true,
) {
    private val chromaWidth = width / 2
    private val chromaHeight = height / 2

    private val yAccumulator = IntArray(width * height)
    private val uAccumulator = IntArray(chromaWidth * chromaHeight)
    private val vAccumulator = IntArray(chromaWidth * chromaHeight)

    /** Scratch buffer reused for every frame, so the GC has nothing to do mid-capture. */
    private val yScratch = ByteArray(width * height)
    private val uScratch = ByteArray(chromaWidth * chromaHeight)
    private val vScratch = ByteArray(chromaWidth * chromaHeight)

    @Volatile
    var frameCount: Int = 0
        private set

    /** Where the reference frame's stars sat, in downsampled coordinates. */
    private var referenceX = 0f
    private var referenceY = 0f
    private var haveReference = false

    /** Last applied alignment shift, in full-resolution pixels. Surfaced for the UI. */
    @Volatile
    var lastShiftX: Int = 0
        private set

    @Volatile
    var lastShiftY: Int = 0
        private set

    /**
     * Reads one camera frame and folds it into the running total.
     *
     * The [Image] is only read, never closed here — the caller owns it.
     */
    fun add(image: Image) {
        extractPlane(image.planes[0], width, height, yScratch)

        var shiftX = 0
        var shiftY = 0
        if (alignFrames) {
            val centroid = starCentroid(yScratch, width, height)
            if (centroid != null) {
                if (!haveReference) {
                    referenceX = centroid.first
                    referenceY = centroid.second
                    haveReference = true
                } else {
                    // The sky has moved by (current - reference), so we sample that
                    // much further along to put it back where it started.
                    shiftX = ((centroid.first - referenceX) * DOWNSAMPLE).roundToInt()
                    shiftY = ((centroid.second - referenceY) * DOWNSAMPLE).roundToInt()

                    // A huge jump is a bumped tripod or a passing cloud, not sky
                    // rotation. Trusting it would smear the whole stack, so it is
                    // ignored and the frame stacked unshifted.
                    if (abs(shiftX) > MAX_SHIFT_PX || abs(shiftY) > MAX_SHIFT_PX) {
                        shiftX = 0
                        shiftY = 0
                    }
                }
            }
        }
        lastShiftX = shiftX
        lastShiftY = shiftY

        accumulate(yScratch, yAccumulator, width, height, shiftX, shiftY)

        extractPlane(image.planes[1], chromaWidth, chromaHeight, uScratch)
        extractPlane(image.planes[2], chromaWidth, chromaHeight, vScratch)
        accumulate(uScratch, uAccumulator, chromaWidth, chromaHeight, shiftX / 2, shiftY / 2)
        accumulate(vScratch, vAccumulator, chromaWidth, chromaHeight, shiftX / 2, shiftY / 2)

        frameCount++
    }

    /**
     * Averages everything gathered so far and encodes it as JPEG.
     *
     * Safe to call while more frames are still arriving — it is what produces the
     * live result of an indefinite capture when the user finally taps stop.
     */
    fun encodeJpeg(autoStretch: Boolean, quality: Int = 95): ByteArray? {
        val frames = frameCount
        if (frames == 0) return null

        val luma = ByteArray(width * height)
        for (i in yAccumulator.indices) {
            luma[i] = (yAccumulator[i] / frames).coerceIn(0, 255).toByte()
        }

        if (autoStretch) stretch(luma)

        // NV21 is Y followed by V and U interleaved, which is what YuvImage wants
        // and avoids ever materialising a full ARGB bitmap — that alone would be
        // another 64 MB on top of the accumulators.
        val nv21 = ByteArray(width * height + chromaWidth * chromaHeight * 2)
        System.arraycopy(luma, 0, nv21, 0, luma.size)

        var out = width * height
        for (i in 0 until chromaWidth * chromaHeight) {
            nv21[out++] = (vAccumulator[i] / frames).coerceIn(0, 255).toByte()
            nv21[out++] = (uAccumulator[i] / frames).coerceIn(0, 255).toByte()
        }

        val stream = ByteArrayOutputStream()
        val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, stream)
        return if (ok) stream.toByteArray() else null
    }

    /**
     * Pulls the faint stuff up out of the noise floor.
     *
     * A stacked night sky occupies a narrow, dark band of the histogram — almost
     * everything sits between about 8 and 40 out of 255, which looks like a black
     * rectangle until it is stretched. The black point is set just under the sky
     * background so the sky stays dark rather than turning grey, and the top of
     * the range is pulled up to near-white.
     */
    private fun stretch(luma: ByteArray) {
        val histogram = IntArray(256)
        for (b in luma) histogram[b.toInt() and 0xFF]++

        val total = luma.size
        val blackPoint = percentile(histogram, total, 0.001f)
        val whitePoint = percentile(histogram, total, 0.9995f)
        if (whitePoint <= blackPoint) return

        // A lookup table means the per-pixel cost is one array read, not a
        // division and a pow across sixteen million pixels.
        val scale = 245f / (whitePoint - blackPoint)
        val lut = ByteArray(256)
        for (v in 0..255) {
            val linear = (v - blackPoint) * scale
            // Mild gamma lift: brings up nebulosity and faint stars without
            // flattening the bright ones into discs.
            val gamma = 255f * Math.pow((linear / 255f).coerceIn(0f, 1f).toDouble(), 0.80).toFloat()
            lut[v] = gamma.roundToInt().coerceIn(0, 255).toByte()
        }

        for (i in luma.indices) luma[i] = lut[luma[i].toInt() and 0xFF]
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt().coerceIn(0, total)
        var running = 0
        for (v in 0..255) {
            running += histogram[v]
            if (running >= target) return v
        }
        return 255
    }

    /**
     * Copies one image plane into a tightly packed byte array.
     *
     * Camera planes arrive with a row stride that is usually wider than the image
     * and, for chroma, a pixel stride of two. Normalising once up front means the
     * hot accumulation loop is plain array indexing.
     */
    private fun extractPlane(plane: Image.Plane, planeWidth: Int, planeHeight: Int, into: ByteArray) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1 && rowStride == planeWidth) {
            buffer.position(0)
            buffer.get(into, 0, minOf(into.size, buffer.remaining()))
            return
        }

        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until planeHeight) {
            val start = y * rowStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            val length = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, length)

            if (pixelStride == 1) {
                System.arraycopy(row, 0, into, offset, minOf(planeWidth, length))
            } else {
                for (x in 0 until planeWidth) {
                    val index = x * pixelStride
                    if (index >= length) break
                    into[offset + x] = row[index]
                }
            }
            offset += planeWidth
        }
    }

    /**
     * Adds a frame into an accumulator, offset by the alignment shift.
     *
     * Source coordinates are clamped rather than skipped at the edges. A skipped
     * edge would darken by however many frames missed it, leaving a visible band;
     * clamping repeats the outermost pixel instead, which is invisible at the few
     * pixels of drift these shifts actually reach.
     */
    private fun accumulate(
        source: ByteArray,
        target: IntArray,
        planeWidth: Int,
        planeHeight: Int,
        shiftX: Int,
        shiftY: Int,
    ) {
        for (y in 0 until planeHeight) {
            val sourceY = (y + shiftY).coerceIn(0, planeHeight - 1)
            val sourceRow = sourceY * planeWidth
            val targetRow = y * planeWidth

            if (shiftX == 0) {
                for (x in 0 until planeWidth) {
                    target[targetRow + x] += source[sourceRow + x].toInt() and 0xFF
                }
            } else {
                for (x in 0 until planeWidth) {
                    val sourceX = (x + shiftX).coerceIn(0, planeWidth - 1)
                    target[targetRow + x] += source[sourceRow + sourceX].toInt() and 0xFF
                }
            }
        }
    }

    /**
     * Finds where the stars are, as a single intensity-weighted point.
     *
     * Tracking the centroid of everything bright is crude next to real plate
     * solving, but it costs one pass over a downsampled frame instead of a
     * search, and it corrects the drift that actually matters here. Over thirty
     * seconds the sky turns about seven pixels at this focal length; over several
     * minutes of indefinite capture it is enough to smear every star into a short
     * arc, and this is what prevents that.
     *
     * Honest limits: it measures translation only, so it cannot correct the field
     * rotation that appears over very long captures, and it needs a genuine star
     * field — a blank or cloud-covered frame returns null and stacks unshifted.
     */
    private fun starCentroid(luma: ByteArray, fullWidth: Int, fullHeight: Int): Pair<Float, Float>? {
        val smallWidth = fullWidth / DOWNSAMPLE
        val smallHeight = fullHeight / DOWNSAMPLE
        if (smallWidth < 8 || smallHeight < 8) return null

        var sum = 0.0
        var sumSquares = 0.0
        val samples = smallWidth * smallHeight

        val small = IntArray(samples)
        var index = 0
        for (y in 0 until smallHeight) {
            val row = y * DOWNSAMPLE * fullWidth
            for (x in 0 until smallWidth) {
                val value = luma[row + x * DOWNSAMPLE].toInt() and 0xFF
                small[index++] = value
                sum += value
                sumSquares += value.toDouble() * value
            }
        }

        val mean = sum / samples
        val variance = (sumSquares / samples) - mean * mean
        val deviation = sqrt(variance.coerceAtLeast(0.0))

        // Stars are the outliers against a flat sky. Three sigma keeps the sky
        // background itself out of the measurement.
        val threshold = mean + 3.0 * deviation
        if (deviation < 0.5) return null

        var weightTotal = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var bright = 0

        index = 0
        for (y in 0 until smallHeight) {
            for (x in 0 until smallWidth) {
                val value = small[index++]
                if (value > threshold) {
                    val weight = value - threshold
                    weightTotal += weight
                    weightedX += weight * x
                    weightedY += weight * y
                    bright++
                }
            }
        }

        if (bright < MIN_STARS || weightTotal <= 0.0) return null
        return (weightedX / weightTotal).toFloat() to (weightedY / weightTotal).toFloat()
    }

    private companion object {
        /** Alignment runs on a 1/8 scale frame: fast, and still sub-pixel accurate once scaled back. */
        const val DOWNSAMPLE = 8

        /** Beyond this, something moved that was not the sky. */
        const val MAX_SHIFT_PX = 256

        /** Fewer bright points than this is not a star field worth aligning to. */
        const val MIN_STARS = 6
    }
}
