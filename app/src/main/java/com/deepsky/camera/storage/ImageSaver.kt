package com.deepsky.camera.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a finished stack into the phone's gallery.
 *
 * The order of operations here is the whole point, and the previous order was
 * wrong. It used to insert the JPEG into MediaStore and *then* reopen the
 * resulting content URI to write EXIF through its file descriptor. ExifInterface
 * has to rewrite a JPEG in place to do that — read the whole file, splice in a new
 * header, write it back over itself — and doing that down a MediaStore descriptor
 * is not reliable: interrupt it, or hand it a descriptor that does not support the
 * seeks it wants, and what is left in the gallery is a truncated file. A photograph
 * that took two minutes of standing in a field to gather came back corrupt.
 *
 * Now the file is finished completely before the gallery ever sees it: written to
 * cache, tagged there by path, checked that it still decodes, and only then
 * streamed into MediaStore in one pass.
 */
object ImageSaver {

    private const val TAG = "DeepSky"
    private const val FOLDER = "DeepSkyCamera"

    data class Saved(val uri: Uri, val displayName: String, val bytes: Long)

    /**
     * @param sensorOrientation degrees the sensor is mounted at, from
     * [android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION].
     */
    fun saveJpeg(
        context: Context,
        jpeg: ByteArray,
        sensorOrientation: Int,
        frames: Int,
        integrationMs: Long,
        exposureNs: Long,
        iso: Int,
    ): Result<Saved> = runCatching {
        require(jpeg.isNotEmpty()) { "The encoder produced no image data" }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val seconds = (integrationMs / 1000.0).toInt()
        // The filename carries the recipe, so a folder full of attempts can be told
        // apart months later without opening any of them.
        val name = "DSC_${stamp}_${seconds}s_${frames}f_ISO$iso.jpg"

        val staged = File(context.cacheDir, "staging.jpg")
        try {
            staged.outputStream().use { it.write(jpeg) }

            writeExif(staged, sensorOrientation, exposureNs, iso, frames, integrationMs)
            verifyDecodable(staged)

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, staged, name, sensorOrientation)
            } else {
                publishToPublicDirectory(context, staged, name)
            }

            Saved(uri, name, staged.length())
        } finally {
            staged.delete()
        }
    }

    /**
     * Refuses to hand the gallery something it cannot open.
     *
     * Decoding just the header is enough to prove the JPEG has a valid structure
     * and the dimensions we meant to write. It costs milliseconds, and it converts
     * the worst failure this app can have — a corrupt file discovered days later,
     * with the sky it came from long gone — into an error message while the phone
     * is still pointed at the sky.
     */
    private fun verifyDecodable(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            error("The encoded photo could not be read back — not saving a corrupt file")
        }
        Log.i(TAG, "Verified ${options.outWidth}x${options.outHeight}, ${file.length()} bytes")
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        name: String,
        sensorOrientation: Int,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.SIZE, source.length())
            // Belt and braces alongside the EXIF tag: some galleries read this
            // column and ignore the header entirely.
            put(MediaStore.Images.Media.ORIENTATION, sensorOrientation)
            // Hides a half-written file from the gallery until it is complete.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("The gallery refused to create the file")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("Could not open the gallery file for writing")
        } catch (error: Throwable) {
            // A partially written entry is worse than none: it shows up as a broken
            // thumbnail forever. Take it back out.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishToPublicDirectory(context: Context, source: File, name: String): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        ).apply { mkdirs() }

        val file = File(directory, name)
        source.inputStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null,
        )
        return Uri.fromFile(file)
    }

    /**
     * Records how the photo was taken, and which way is up.
     *
     * Written against a plain file path, which is the well-trodden ExifInterface
     * path — it can rewrite the file freely because nothing else is holding it.
     * The stack is encoded straight from sensor buffers, so it is landscape however
     * the phone was held; tagging the orientation is lossless and instant where
     * rotating sixteen megapixels of pixels would be neither.
     */
    private fun writeExif(
        file: File,
        sensorOrientation: Int,
        exposureNs: Long,
        iso: Int,
        frames: Int,
        integrationMs: Long,
    ) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)

            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                when (sensorOrientation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }.toString(),
            )

            // Per-frame exposure, which is the shutter speed really used. The total
            // goes in the description because EXIF has no field meaning "sum of many
            // exposures".
            exif.setAttribute(
                ExifInterface.TAG_EXPOSURE_TIME,
                (exposureNs / 1_000_000_000.0).toString(),
            )
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Deep Sky Camera")
            exif.setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                "Stack of $frames frames, " + String.format(
                    Locale.US, "%.1f s total integration", integrationMs / 1000.0,
                ),
            )
            exif.saveAttributes()
        }.onFailure {
            // Metadata is worth having but never worth losing the photograph over.
            Log.w(TAG, "Could not write EXIF; keeping the image anyway", it)
        }
    }
}
