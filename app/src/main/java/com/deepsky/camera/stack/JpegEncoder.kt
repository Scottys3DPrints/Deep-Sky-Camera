package com.deepsky.camera.stack

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Turns a finished NV21 stack into JPEG bytes.
 *
 * Split out from [FrameStacker] purely so the stacker stays free of Android
 * types and can be tested on a laptop. This half is a single platform call with
 * nothing to get wrong.
 */
object JpegEncoder {

    fun encode(nv21: ByteArray, width: Int, height: Int, quality: Int = 95): ByteArray? {
        val expected = width * height * 3 / 2
        require(nv21.size >= expected) {
            "NV21 buffer is ${nv21.size} bytes, need $expected for ${width}x$height"
        }

        // Sized to roughly a tenth of the raw frame: enough that a 16 MP photo does
        // not spend the encode repeatedly doubling and copying its own output.
        val stream = ByteArrayOutputStream(expected / 10)
        val encoded = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, stream)

        return if (encoded) stream.toByteArray() else null
    }
}
