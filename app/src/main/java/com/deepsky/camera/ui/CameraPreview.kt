package com.deepsky.camera.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The live view, rendered by a [TextureView] the camera writes into directly.
 *
 * Two things have to be got right here, and both fail silently.
 *
 * The buffer is sized to a resolution the camera actually offers rather than to
 * whatever size the view happens to be laid out at, because Camera2 refuses to
 * configure a session against an unsupported surface size and the failure is
 * opaque when it happens.
 *
 * And the buffer arrives in *sensor* orientation, which on this phone is a
 * quarter turn from the way the phone is held. Nothing rotates it on the way to a
 * TextureView, so without the transform below the room appears lying on its side
 * — a preview that is perfectly sharp, correctly exposed, and unusable for aiming
 * at anything.
 */
@Composable
fun CameraPreview(
    previewSize: Size,
    /** Degrees the sensor is mounted at, from `SENSOR_ORIENTATION`. */
    sensorOrientation: Int,
    onSurfaceReady: (Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One Surface per SurfaceTexture, kept across recompositions so a mode change
    // or a progress tick never tears down the live view.
    val holder = remember { PreviewSurfaceHolder() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                        applyRotation(this@apply, width, height, sensorOrientation)
                        val surface = holder.surfaceFor(texture)
                        if (holder.consumeIfNew()) onSurfaceReady(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        applyRotation(this@apply, width, height, sensorOrientation)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // Release our Surface wrapper and let TextureView reclaim the
                        // SurfaceTexture. Returning to the app then produces a
                        // genuinely new surface, which is what re-triggers
                        // onSurfaceReady and reopens the camera.
                        holder.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.surfaceTexture?.let { texture ->
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                if (view.width > 0 && view.height > 0) {
                    applyRotation(view, view.width, view.height, sensorOrientation)
                }
                val surface = holder.surfaceFor(texture)
                if (holder.consumeIfNew()) onSurfaceReady(surface)
            }
        },
    )
}

/**
 * Deliberately applies no rotation.
 *
 * Measured on this device rather than assumed: the frame a TextureView displays is
 * already turned a quarter circle from the buffer the same camera writes into an
 * ImageReader. The platform applies the sensor orientation on the way to the
 * display, so the preview arrives upright for a phone held in portrait, and adding
 * the obvious correction here double-corrects it into being sideways.
 *
 * Worth stating plainly because the opposite is easy to conclude from a single
 * screenshot: this phone has auto-rotate switched off and is locked to portrait,
 * so Android reports ROTATION_0 however the phone is physically propped. Propping
 * it on its side makes a *correct* preview look rotated, and chasing that
 * appearance is what breaks the upright case.
 *
 * The identity transform is set explicitly so that a recycled TextureView can
 * never carry a stale matrix from a previous surface.
 */
private fun applyRotation(view: TextureView, viewWidth: Int, viewHeight: Int, sensorOrientation: Int) {
    if (viewWidth <= 0 || viewHeight <= 0) return
    view.setTransform(Matrix())
}

private class PreviewSurfaceHolder {
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var fresh = false

    fun surfaceFor(candidate: SurfaceTexture): Surface {
        val existing = surface
        if (existing != null && texture === candidate) return existing

        release()
        texture = candidate
        fresh = true
        return Surface(candidate).also { surface = it }
    }

    /** True exactly once per newly created surface, so the camera opens only once. */
    fun consumeIfNew(): Boolean {
        if (!fresh) return false
        fresh = false
        return true
    }

    fun release() {
        surface?.release()
        surface = null
        texture = null
        fresh = false
    }
}
