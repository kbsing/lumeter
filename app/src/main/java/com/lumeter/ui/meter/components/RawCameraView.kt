package com.lumeter.ui.meter.components

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lumeter.ui.AppViewModel
import kotlin.math.max

/**
 * RAW mode's preview surface: a plain TextureView driven by the Camera2 session in
 * [com.lumeter.camera.RawMeterSource]. CameraX's PreviewView cannot render a raw Camera2
 * stream, so this view reproduces its FILL_CENTER behavior with a view transform: uniform
 * scale to cover, centered, rotated upright by the sensor orientation.
 */
@Composable
fun RawCameraView(vm: AppViewModel, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                view.surfaceTextureListener = RawSurfaceListener(vm)
            }
        },
        update = { view ->
            // Recompute on every state-driven recomposition; layout size may also have
            // changed (orientation flip), and post() defers until it is measurable.
            view.post { applyFillCenter(view, vm) }
        },
        modifier = modifier,
    )
}

private class RawSurfaceListener(private val vm: AppViewModel) : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
        vm.startRawCamera(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
        // Returning true releases the texture; the RAW session is torn down separately.
        return true
    }

    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
}

/** Cover-and-center transform of the preview buffer into the view, rotated upright. */
private fun applyFillCenter(view: TextureView, vm: AppViewModel) {
    val bufW = vm.previewWidth
    val bufH = vm.previewHeight
    val viewW = view.width
    val viewH = view.height
    if (bufW <= 0 || bufH <= 0 || viewW == 0 || viewH == 0) return
    val rotation = vm.rotationDegrees
    val uprightW = if (rotation == 90 || rotation == 270) bufH else bufW
    val uprightH = if (rotation == 90 || rotation == 270) bufW else bufH
    val scale = max(viewW.toFloat() / uprightW, viewH.toFloat() / uprightH)
    val matrix = Matrix()
    matrix.setScale(scale, scale, viewW / 2f, viewH / 2f)
    matrix.postRotate(rotation.toFloat(), viewW / 2f, viewH / 2f)
    view.setTransform(matrix)
}
