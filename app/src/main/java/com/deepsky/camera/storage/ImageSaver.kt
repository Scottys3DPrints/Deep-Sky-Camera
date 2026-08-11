package com.deepsky.camera.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a finished stack into the phone's gallery.
 *
 * Photos go to Pictures/DeepSkyCamera so they appear in Google Photos and the
 * Samsung gallery alongside everything else, rather than hiding in app-private
 * storage where they would be deleted with the app.
 */
object ImageSaver {

    private const val FOLDER = "DeepSkyCamera"

    data class Saved(val uri: Uri, val displayName: String)

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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val seconds = (integrationMs / 1000.0).toInt()
        // The filename carries the recipe, so a folder full of attempts can be
        // told apart months later without opening any of them.
        val name = "DSC_${stamp}_${seconds}s_${frames}f_ISO$iso.jpg"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, jpeg, name)
        } else {
            saveToPublicDirectory(context, jpeg, name)
        }

        writeExif(context, uri, sensorOrientation, exposureNs, iso, frames, integrationMs)
        Saved(uri, name)
    }

    private fun saveViaMediaStore(context: Context, jpeg: ByteArray, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            // Hides a half-written file from the gallery until it is complete.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("The gallery refused to create the file")

        resolver.openOutputStream(uri)?.use { it.write(jpeg) }
            ?: error("Could not write the photo")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDirectory(context: Context, jpeg: ByteArray, name: String): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        ).apply { mkdirs() }

        val file = File(directory, name)
        file.outputStream().use { it.write(jpeg) }

        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null,
        )
        return Uri.fromFile(file)
    }

    /**
     * Records how the photo was taken, and which way is up.
     *
     * The stack is encoded straight from sensor buffers, so it is landscape no
     * matter how the phone was held. Rather than rotating sixteen megapixels of
     * pixels, the orientation is written as a tag — lossless, instant, and what
     * every gallery app already reads.
     */
    private fun writeExif(
        context: Context,
        uri: Uri,
        sensorOrientation: Int,
        exposureNs: Long,
        iso: Int,
        frames: Int,
        integrationMs: Long,
    ) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)

                exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (sensorOrientation) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }.toString(),
                )

                // Per-frame exposure, which is the shutter speed that was really
                // used. The total is recorded separately in the description
                // because EXIF has no field that means "sum of many exposures".
                exif.setAttribute(
                    ExifInterface.TAG_EXPOSURE_TIME,
                    (exposureNs / 1_000_000_000.0).toString(),
                )
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Deep Sky Camera")
                exif.setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "Stack of $frames frames, ${"%.1f".format(integrationMs / 1000.0)} s total integration",
                )
                exif.saveAttributes()
            }
        }
    }
}
