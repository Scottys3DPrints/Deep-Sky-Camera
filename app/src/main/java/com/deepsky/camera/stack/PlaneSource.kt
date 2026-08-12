package com.deepsky.camera.stack

import java.nio.ByteBuffer

/**
 * One image plane, described in the only terms the stacker needs.
 *
 * Deliberately not an `android.media.Image.Plane`. That type cannot be
 * constructed in a JVM test, which meant the pixel-handling code — the part most
 * able to produce a corrupt photograph, and least able to announce that it had —
 * could only ever be checked by taking a picture and squinting at it. Reduced to
 * a buffer and two strides, the whole path becomes testable on a laptop.
 */
class PlaneSource(
    val buffer: ByteBuffer,
    /** Bytes from the start of one row to the start of the next. Often > width. */
    val rowStride: Int,
    /** Bytes between adjacent samples. 2 for the interleaved chroma of NV21/NV12. */
    val pixelStride: Int,
)
