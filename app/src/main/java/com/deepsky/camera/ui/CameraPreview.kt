package com.deepsky.camera.ui

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
 * The buffer is sized to a resolution the camera actually offers rather than to
 * whatever size the view happens to be laid out at. Camera2 will refuse to
 * configure a session against an unsupported surface size, and the failure is
 * opaque when it happens, so the size is pinned here deliberately.
 */
@Composable
fun CameraPreview(
    previewSize: Size,
    onSurfaceReady: (Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One Surface per SurfaceTexture, kept across recompositions so that a mode
    // change or a progress tick never tears down the live view.
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
                        val surface = holder.surfaceFor(texture)
                        if (holder.consumeIfNew()) onSurfaceReady(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        holder.release()
                        // Returning false keeps the SurfaceTexture alive for us to
                        // release ourselves, after the camera has let go of it.
                        return false
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.surfaceTexture?.let { texture ->
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val surface = holder.surfaceFor(texture)
                if (holder.consumeIfNew()) onSurfaceReady(surface)
            }
        },
    )
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
