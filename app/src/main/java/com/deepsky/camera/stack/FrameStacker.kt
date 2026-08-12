package com.deepsky.camera.stack

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Adds many short exposures together into one deep one.
 *
 * The arithmetic is the reason this app can offer a thirty second exposure on
 * hardware that will not open the shutter for longer than half a second. Summing
 * N frames multiplies the star signal by N while the random sensor noise, being
 * random, only grows by sqrt(N). The picture gets cleaner by a factor of sqrt(N)
 * — sixty-seven frames is a little over eight times less noise — and every frame
 * stays short enough that the stars remain points.
 *
 * Contains no Android types on purpose, so every pixel decision in it is covered
 * by tests that run without a phone.
 *
 * Not thread-safe. One stacker belongs to one capture, driven from the camera's
 * background thread.
 */
class FrameStacker(
    val width: Int,
    val height: Int,
    /** Whether to compensate for the sky drifting across the sensor. */
    private val alignFrames: Boolean = true,
    /**
     * Pixels trimmed from every edge of the finished image.
     *
     * Measured on this phone, the outermost rows and columns read roughly 30%
     * brighter than the frame's interior — sensor border pixels and amplifier glow,
     * invisible in a daylight photograph and glaring once a stacked night sky is
     * stretched. Alignment adds to it: shifted frames clamp at the edge, so the
     * outermost pixels get repeated rather than averaged. Cropping is what every
     * astrophotography workflow does with these, and a few dozen pixels off a
     * twelve megapixel frame costs nothing.
     */
    private val margin: Int = 0,
) {
    init {
        require(width > 0 && height > 0) { "Frame size must be positive" }
        require(width % 2 == 0 && height % 2 == 0) {
            "4:2:0 chroma needs even dimensions; got ${width}x$height"
        }
        require(margin >= 0 && margin % 2 == 0) { "Margin must be even and non-negative" }
        require(width - 2 * margin > 0 && height - 2 * margin > 0) {
            "Margin of $margin leaves nothing of a ${width}x$height frame"
        }
    }

    val chromaWidth = width / 2
    val chromaHeight = height / 2

    /** Size of the image this stacker will actually hand back. */
    val outputWidth = width - 2 * margin
    val outputHeight = height - 2 * margin

    // 32-bit accumulators, full frame. At sixteen megapixels this is 96 MB held
    // for the whole capture, which is why the app asks for a large heap. Summing
    // into 8 bits would clip after the first few frames; this is the honest cost
    // of keeping the precision that stacking exists to gain.
    private val yAccumulator = IntArray(width * height)
    private val uAccumulator = IntArray(chromaWidth * chromaHeight)
    private val vAccumulator = IntArray(chromaWidth * chromaHeight)

    /** Reused every frame, so the collector has nothing to do mid-capture. */
    private val yScratch = ByteArray(width * height)
    private val uScratch = ByteArray(chromaWidth * chromaHeight)
    private val vScratch = ByteArray(chromaWidth * chromaHeight)

    @Volatile
    var frameCount: Int = 0
        private set

    private var referenceX = 0f
    private var referenceY = 0f
    private var haveReference = false

    @Volatile
    var lastShiftX: Int = 0
        private set

    @Volatile
    var lastShiftY: Int = 0
        private set

    /**
     * Reads one camera frame and folds it into the running total.
     */
    fun add(y: PlaneSource, u: PlaneSource, v: PlaneSource) {
        extractPlane(y, width, height, yScratch)

        var shiftX = 0
        var shiftY = 0
        if (alignFrames) {
            val centroid = starCentroid(yScratch)
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

                    // A large jump is a knocked tripod or a passing cloud, not the
                    // sky turning. Trusting it would smear the whole stack.
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

        extractPlane(u, chromaWidth, chromaHeight, uScratch)
        extractPlane(v, chromaWidth, chromaHeight, vScratch)
        // Chroma is half resolution, so it moves half as far.
        accumulate(uScratch, uAccumulator, chromaWidth, chromaHeight, shiftX / 2, shiftY / 2)
        accumulate(vScratch, vAccumulator, chromaWidth, chromaHeight, shiftX / 2, shiftY / 2)

        frameCount++
    }

    /**
     * Averages everything gathered so far into an NV21 buffer.
     *
     * NV21 is the plane order `YuvImage` expects: the full luma plane, then V and
     * U interleaved at quarter resolution. Producing it directly avoids ever
     * materialising a full ARGB bitmap, which at sixteen megapixels would be
     * another 64 MB on top of the accumulators.
     *
     * Safe to call while frames are still arriving — it is what produces the
     * result of an indefinite capture when the user finally taps stop.
     */
    fun averageToNv21(autoStretch: Boolean): ByteArray? {
        val frames = frameCount
        if (frames == 0) return null

        val outputChromaWidth = outputWidth / 2
        val outputChromaHeight = outputHeight / 2
        val lumaSize = outputWidth * outputHeight
        val nv21 = ByteArray(lumaSize + outputChromaWidth * outputChromaHeight * 2)

        for (y in 0 until outputHeight) {
            val sourceRow = (y + margin) * width + margin
            val targetRow = y * outputWidth
            for (x in 0 until outputWidth) {
                nv21[targetRow + x] = (yAccumulator[sourceRow + x] / frames).coerceIn(0, 255).toByte()
            }
        }

        // Stretched after cropping, deliberately. The bright border would otherwise
        // set the white point and hold the rest of the picture down.
        if (autoStretch) stretchLuma(nv21, lumaSize)

        val chromaMargin = margin / 2
        var out = lumaSize
        for (y in 0 until outputChromaHeight) {
            val sourceRow = (y + chromaMargin) * chromaWidth + chromaMargin
            for (x in 0 until outputChromaWidth) {
                nv21[out++] = (vAccumulator[sourceRow + x] / frames).coerceIn(0, 255).toByte()
                nv21[out++] = (uAccumulator[sourceRow + x] / frames).coerceIn(0, 255).toByte()
            }
        }

        return nv21
    }

    /**
     * Pulls the faint detail up out of the noise floor.
     *
     * A stacked night sky occupies a narrow, dark band of the histogram — almost
     * everything between about 8 and 40 out of 255 — which looks like a black
     * rectangle until it is stretched. The black point is set just under the sky
     * background so the sky stays dark rather than turning grey, and the top of
     * the range is pulled up to near white.
     */
    private fun stretchLuma(buffer: ByteArray, lumaSize: Int) {
        val histogram = IntArray(256)
        for (i in 0 until lumaSize) histogram[buffer[i].toInt() and 0xFF]++

        val blackPoint = percentile(histogram, lumaSize, 0.001f)
        val whitePoint = percentile(histogram, lumaSize, 0.9995f)
        if (whitePoint <= blackPoint) return

        // A lookup table keeps the per-pixel cost to one array read rather than a
        // division and a power across sixteen million pixels.
        //
        // The gain is capped. Without a ceiling, a frame containing nothing but
        // sensor noise — a covered lens, a completely overcast sky — has a tiny
        // histogram spread, and the stretch dutifully amplifies that noise across
        // the whole range and hands back grey mush that looks like a fault. Eight
        // stops of lift is far more than a real sky needs and keeps an empty frame
        // looking empty.
        val scale = (245f / (whitePoint - blackPoint)).coerceAtMost(MAX_STRETCH_GAIN)
        val lut = ByteArray(256)
        for (value in 0..255) {
            val linear = ((value - blackPoint) * scale).coerceIn(0f, 255f)
            // A mild gamma lift brings up nebulosity and faint stars without
            // flattening the bright ones into featureless discs.
            val lifted = 255.0 * Math.pow((linear / 255f).toDouble(), 0.80)
            lut[value] = lifted.roundToInt().coerceIn(0, 255).toByte()
        }

        for (i in 0 until lumaSize) buffer[i] = lut[buffer[i].toInt() and 0xFF]
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt().coerceIn(0, total)
        var running = 0
        for (value in 0..255) {
            running += histogram[value]
            if (running >= target) return value
        }
        return 255
    }

    /**
     * Copies one plane into a tightly packed array, honouring both strides.
     *
     * Camera planes arrive with a row stride usually wider than the image and,
     * for chroma, a pixel stride of two. Ignoring either is the classic way to
     * produce a picture that is skewed diagonally or tinted green, so the strides
     * are obeyed here once and the accumulation loop downstream is plain indexing.
     *
     * Reads are absolute, never moving the buffer's position, because the caller
     * may hand us two views onto the same interleaved chroma memory.
     */
    private fun extractPlane(
        plane: PlaneSource,
        planeWidth: Int,
        planeHeight: Int,
        into: ByteArray,
    ) {
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = plane.buffer.limit()

        // A duplicate shares the bytes but carries its own position, so bulk reads
        // are available without disturbing the caller — which matters because the
        // two chroma planes are views onto one buffer, and moving one's position
        // would corrupt the other.
        val cursor = plane.buffer.duplicate()

        var offset = 0
        for (row in 0 until planeHeight) {
            val rowStart = row * rowStride
            if (rowStart >= limit) break

            if (pixelStride == 1) {
                // Bulk copy: sixteen million single-byte reads from a direct buffer
                // costs more per frame than the exposure itself, and the stack would
                // fall behind the shutter.
                //
                // The final row is often short of a full stride because the buffer
                // ends at the last real sample. Clamping keeps that row valid rather
                // than throwing, and the missing tail stays as the previous frame
                // left it rather than becoming noise.
                val available = (limit - rowStart).coerceAtMost(planeWidth)
                cursor.position(rowStart)
                cursor.get(into, offset, available)
            } else {
                for (x in 0 until planeWidth) {
                    val index = rowStart + x * pixelStride
                    if (index >= limit) break
                    into[offset + x] = cursor.get(index)
                }
            }
            offset += planeWidth
        }
    }

    /**
     * Adds a frame into an accumulator, offset by the alignment shift.
     *
     * Source coordinates are clamped rather than skipped at the edges. Skipping
     * would darken an edge band by however many frames missed it, which is
     * plainly visible; clamping repeats the outermost pixel, which is not, at the
     * few pixels of drift these shifts actually reach.
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
            val sourceRow = (y + shiftY).coerceIn(0, planeHeight - 1) * planeWidth
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
     * Tracking the centroid of everything bright is crude next to plate solving,
     * but it costs one pass over a downsampled frame rather than a search, and it
     * corrects the drift that actually matters. Over thirty seconds the sky turns
     * about seven pixels at this focal length; over several minutes it is enough
     * to draw every star into a short arc, and this is what prevents that.
     *
     * Honest limits: it measures translation only, so it cannot correct the field
     * rotation that appears over very long captures, and it needs a real star
     * field — a blank or clouded frame returns null and is stacked unshifted.
     */
    internal fun starCentroid(luma: ByteArray): Pair<Float, Float>? {
        val smallWidth = width / DOWNSAMPLE
        val smallHeight = height / DOWNSAMPLE
        if (smallWidth < 8 || smallHeight < 8) return null

        val samples = smallWidth * smallHeight
        val small = IntArray(samples)
        var sum = 0.0
        var sumSquares = 0.0

        var index = 0
        for (y in 0 until smallHeight) {
            val row = y * DOWNSAMPLE * width
            for (x in 0 until smallWidth) {
                val value = luma[row + x * DOWNSAMPLE].toInt() and 0xFF
                small[index++] = value
                sum += value
                sumSquares += value.toDouble() * value
            }
        }

        val mean = sum / samples
        val deviation = sqrt(((sumSquares / samples) - mean * mean).coerceAtLeast(0.0))
        // A flat frame has nothing to lock onto; three sigma keeps the sky
        // background itself out of the measurement.
        if (deviation < 0.5) return null
        val threshold = mean + 3.0 * deviation

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

    internal companion object {
        /** Alignment runs on a 1/8 scale frame: fast, and accurate once scaled back. */
        const val DOWNSAMPLE = 8

        /** Beyond this, something moved that was not the sky. */
        const val MAX_SHIFT_PX = 256

        /** Fewer bright points than this is not a star field worth aligning to. */
        const val MIN_STARS = 6

        /** Ceiling on auto-stretch, so an empty frame is not amplified into mush. */
        const val MAX_STRETCH_GAIN = 8f
    }
}
