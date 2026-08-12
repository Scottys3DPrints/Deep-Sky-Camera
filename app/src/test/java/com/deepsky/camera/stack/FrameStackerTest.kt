package com.deepsky.camera.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Covers the pixel path end to end without a phone.
 *
 * This is the layer that shipped broken: a stride mishandled here does not throw
 * or log, it silently produces a photograph that is skewed, green, or garbage,
 * and the only previous way to find out was to take a picture and look at it.
 * Every frame the camera delivers goes through [FrameStacker.add], so every
 * awkward shape a real camera plane can take is reproduced here deliberately —
 * padded row strides, interleaved chroma sharing one buffer, and a final row that
 * stops short of a full stride.
 */
class FrameStackerTest {

    private val width = 64
    private val height = 32

    // ------------------------------------------------------------------ helpers

    /**
     * Builds planes the way a camera really delivers YUV_420_888 on this phone:
     * semi-planar, with U and V as two interleaved views onto *one* buffer.
     *
     * In NV21 order V sits first in memory, so the V plane starts at offset 0 and
     * the U plane at offset 1, both with a pixel stride of two. Getting this wrong
     * swaps the colour channels, which is why it is built faithfully rather than
     * as two tidy separate arrays.
     */
    private fun planes(
        luma: ByteArray,
        chromaU: ByteArray,
        chromaV: ByteArray,
        lumaRowPadding: Int = 0,
        truncateLastRow: Boolean = false,
    ): Triple<PlaneSource, PlaneSource, PlaneSource> {
        val lumaStride = width + lumaRowPadding
        val lumaBytes = ByteArray(lumaStride * height)
        for (y in 0 until height) {
            System.arraycopy(luma, y * width, lumaBytes, y * lumaStride, width)
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val interleaved = ByteArray(chromaWidth * chromaHeight * 2)
        for (i in 0 until chromaWidth * chromaHeight) {
            interleaved[i * 2] = chromaV[i]
            interleaved[i * 2 + 1] = chromaU[i]
        }

        val lumaBuffer = ByteBuffer.wrap(lumaBytes)
        // A real V plane's buffer ends after the last V sample, one byte short of
        // a full final row. Reproduced so the short-row handling is exercised.
        val uvLimit = if (truncateLastRow) interleaved.size - 1 else interleaved.size

        val vBuffer = ByteBuffer.wrap(interleaved, 0, uvLimit).slice()
        val uBuffer = ByteBuffer.wrap(interleaved, 1, uvLimit - 1).slice()

        return Triple(
            PlaneSource(lumaBuffer, lumaStride, 1),
            PlaneSource(uBuffer, chromaWidth * 2, 2),
            PlaneSource(vBuffer, chromaWidth * 2, 2),
        )
    }

    private fun flat(size: Int, value: Int) = ByteArray(size) { value.toByte() }

    private fun FrameStacker.addFlat(y: Int, u: Int, v: Int, padding: Int = 0) {
        val (luma, chromaU, chromaV) = planes(
            flat(width * height, y),
            flat(width * height / 4, u),
            flat(width * height / 4, v),
            lumaRowPadding = padding,
        )
        add(luma, chromaU, chromaV)
    }

    private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF

    // -------------------------------------------------------------------- tests

    @Test
    fun `a single flat frame comes back out unchanged`() {
        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.addFlat(y = 100, u = 110, v = 120)

        val nv21 = stacker.averageToNv21(autoStretch = false)!!

        assertEquals("NV21 is exactly 1.5 bytes per pixel", width * height * 3 / 2, nv21.size)
        for (i in 0 until width * height) {
            assertEquals("luma at $i", 100, nv21.at(i))
        }
        // NV21 interleaves V then U after the luma plane.
        assertEquals(120, nv21.at(width * height))
        assertEquals(110, nv21.at(width * height + 1))
    }

    @Test
    fun `a padded row stride does not skew the image`() {
        // A horizontal ramp is the shape that makes a stride bug obvious: read with
        // the wrong stride and every row slides sideways, tilting the ramp.
        val luma = ByteArray(width * height) { (it % width).toByte() }
        val (y, u, v) = planes(luma, flat(width * height / 4, 128), flat(width * height / 4, 128), lumaRowPadding = 48)

        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.add(y, u, v)
        val nv21 = stacker.averageToNv21(autoStretch = false)!!

        for (row in 0 until height) {
            for (column in 0 until width) {
                assertEquals(
                    "row $row column $column slid sideways",
                    column,
                    nv21.at(row * width + column),
                )
            }
        }
    }

    @Test
    fun `interleaved chroma planes are not swapped or mixed`() {
        val chromaSize = width * height / 4
        // Distinct, position-dependent patterns: if U and V were read from the same
        // offset, or their pixel stride ignored, these would not survive.
        val chromaU = ByteArray(chromaSize) { (it % 200).toByte() }
        val chromaV = ByteArray(chromaSize) { (255 - (it % 200)).toByte() }

        val (y, u, v) = planes(flat(width * height, 64), chromaU, chromaV)
        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.add(y, u, v)
        val nv21 = stacker.averageToNv21(autoStretch = false)!!

        for (i in 0 until chromaSize) {
            assertEquals("V at $i", 255 - (i % 200), nv21.at(width * height + i * 2))
            assertEquals("U at $i", i % 200, nv21.at(width * height + i * 2 + 1))
        }
    }

    @Test
    fun `a chroma buffer that stops short of a full last row does not throw`() {
        val chromaSize = width * height / 4
        val (y, u, v) = planes(
            flat(width * height, 40),
            flat(chromaSize, 90),
            flat(chromaSize, 150),
            truncateLastRow = true,
        )

        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.add(y, u, v)

        val nv21 = stacker.averageToNv21(autoStretch = false)
        assertNotNull("a short final row must not lose the whole frame", nv21)
        assertEquals(40, nv21!!.at(0))
    }

    @Test
    fun `frames are averaged, not summed`() {
        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.addFlat(y = 100, u = 100, v = 100)
        stacker.addFlat(y = 200, u = 200, v = 200)

        val nv21 = stacker.averageToNv21(autoStretch = false)!!

        assertEquals(2, stacker.frameCount)
        assertEquals("two frames must average, or a stack would blow out to white", 150, nv21.at(0))
        assertEquals(150, nv21.at(width * height))
    }

    @Test
    fun `many frames do not overflow or clip`() {
        val stacker = FrameStacker(width, height, alignFrames = false)
        repeat(500) { stacker.addFlat(y = 200, u = 128, v = 128) }

        val nv21 = stacker.averageToNv21(autoStretch = false)!!
        assertEquals(500, stacker.frameCount)
        assertEquals("500 frames of 200 must still average to 200", 200, nv21.at(0))
    }

    @Test
    fun `nothing is produced before a frame arrives`() {
        assertNull(FrameStacker(width, height).averageToNv21(autoStretch = false))
    }

    @Test
    fun `alignment recovers a known drift`() {
        val big = FrameStacker(256, 256, alignFrames = true)
        val chromaSize = 256 * 256 / 4

        fun starField(offsetX: Int, offsetY: Int): ByteArray {
            val luma = ByteArray(256 * 256) { 10 }
            // Eight stars, drawn as 8x8 blocks so the 1/8 scale alignment pass
            // cannot miss them between its samples.
            val positions = listOf(
                32 to 32, 64 to 96, 96 to 48, 128 to 160,
                160 to 80, 176 to 192, 200 to 112, 96 to 208,
            )
            positions.forEach { (starX, starY) ->
                for (dy in 0 until 8) {
                    for (dx in 0 until 8) {
                        val x = starX + offsetX + dx
                        val y = starY + offsetY + dy
                        if (x in 0 until 256 && y in 0 until 256) luma[y * 256 + x] = 240.toByte()
                    }
                }
            }
            return luma
        }

        fun addAt(offsetX: Int, offsetY: Int) {
            val lumaStride = 256
            val lumaBuffer = ByteBuffer.wrap(starField(offsetX, offsetY))
            val interleaved = ByteArray(chromaSize * 2) { 128.toByte() }
            val vBuffer = ByteBuffer.wrap(interleaved, 0, interleaved.size).slice()
            val uBuffer = ByteBuffer.wrap(interleaved, 1, interleaved.size - 1).slice()
            big.add(
                PlaneSource(lumaBuffer, lumaStride, 1),
                PlaneSource(uBuffer, 256, 2),
                PlaneSource(vBuffer, 256, 2),
            )
        }

        addAt(0, 0)
        assertEquals("the first frame is the reference and never shifts", 0, big.lastShiftX)

        addAt(16, 8)
        assertEquals("drift in x was not recovered", 16, big.lastShiftX)
        assertEquals("drift in y was not recovered", 8, big.lastShiftY)
    }

    @Test
    fun `a frame with nothing bright in it is stacked unshifted`() {
        val stacker = FrameStacker(width, height, alignFrames = true)
        stacker.addFlat(y = 30, u = 128, v = 128)

        assertEquals(0, stacker.lastShiftX)
        assertEquals(0, stacker.lastShiftY)
        assertNull(
            "a flat frame has no star field to lock onto",
            stacker.starCentroid(ByteArray(width * height) { 30 }),
        )
    }

    @Test
    fun `auto stretch opens up a narrow dark band without inverting it`() {
        // A real stacked sky: everything crushed between 8 and 40 out of 255.
        val luma = ByteArray(width * height) { (8 + (it % 33)).toByte() }
        val (y, u, v) = planes(luma, flat(width * height / 4, 128), flat(width * height / 4, 128))

        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.add(y, u, v)

        val raw = stacker.averageToNv21(autoStretch = false)!!.copyOf(width * height)
        val stretched = stacker.averageToNv21(autoStretch = true)!!.copyOf(width * height)

        val rawSpread = (raw.maxOf { it.toInt() and 0xFF } - raw.minOf { it.toInt() and 0xFF })
        val stretchedSpread =
            (stretched.maxOf { it.toInt() and 0xFF } - stretched.minOf { it.toInt() and 0xFF })

        assertTrue(
            "stretching must widen the histogram, got $rawSpread -> $stretchedSpread",
            stretchedSpread > rawSpread * 3,
        )

        // Brighter in must stay brighter out, or stars and sky would trade places.
        for (i in 1 until width * height) {
            if (raw.at(i) > raw.at(i - 1)) {
                assertTrue(
                    "stretch inverted the image at $i",
                    stretched.at(i) >= stretched.at(i - 1),
                )
            }
        }
    }

    @Test
    fun `stacking does not disturb the caller's buffer positions`() {
        // Two views onto one chroma buffer are read concurrently in the real
        // pipeline. Any position-based read would leave the other view pointing
        // somewhere unexpected and corrupt the next frame.
        val (y, u, v) = planes(
            flat(width * height, 70),
            flat(width * height / 4, 100),
            flat(width * height / 4, 200),
        )
        val positions = listOf(y.buffer.position(), u.buffer.position(), v.buffer.position())

        val stacker = FrameStacker(width, height, alignFrames = false)
        stacker.add(y, u, v)
        stacker.add(y, u, v)

        assertEquals(positions, listOf(y.buffer.position(), u.buffer.position(), v.buffer.position()))
        assertEquals(70, stacker.averageToNv21(autoStretch = false)!!.at(0))
    }
}
