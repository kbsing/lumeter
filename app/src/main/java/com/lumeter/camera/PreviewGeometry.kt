package com.lumeter.camera

import kotlin.math.max

/**
 * Maps screen touch coordinates to normalized analysis-frame coordinates under
 * PreviewView's default FILL_CENTER mode, including the sensor-orientation rotation
 * reported by ImageInfo.rotationDegrees.
 *
 * The inverse (frame → screen) is what the overlay uses to draw spot markers exactly on
 * the metered pixels, so both directions live here and share one contract. Front-camera
 * mirroring is NOT applied here: PreviewView mirrors the front preview itself, so the
 * mirrored x-flip must be applied by the caller for front lenses (x → 1−x before rotation).
 */
object PreviewGeometry {

    data class FillCenterTransform(
        val rotationDegrees: Int,
        val mirrored: Boolean,
        val frameWidth: Int,
        val frameHeight: Int,
        val viewWidth: Int,
        val viewHeight: Int,
    ) {
        /** Visible normalized span of the upright frame after FILL_CENTER cropping. */
        private val scale: Float =
            max(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)

        private val uprightWidth: Float =
            if (rotationDegrees == 90 || rotationDegrees == 270) frameHeight.toFloat()
            else frameWidth.toFloat()

        private val uprightHeight: Float =
            if (rotationDegrees == 90 || rotationDegrees == 270) frameWidth.toFloat()
            else frameHeight.toFloat()

        private val visibleUprightWidth: Float = viewWidth / scale
        private val visibleUprightHeight: Float = viewHeight / scale

        /** Screen px → normalized analysis-frame (sensor) coordinates. */
        fun screenToFrame(screenX: Float, screenY: Float): Pair<Float, Float> {
            val uprightU =
                (0.5f - visibleUprightWidth / (2f * uprightWidth)) +
                    screenX / viewWidth * (visibleUprightWidth / uprightWidth)
            val uprightV =
                (0.5f - visibleUprightHeight / (2f * uprightHeight)) +
                    screenY / viewHeight * (visibleUprightHeight / uprightHeight)
            val mirroredU = if (mirrored) 1f - uprightU else uprightU
            return uprightToFrame(mirroredU, uprightV)
        }

        /** Normalized analysis-frame coordinates → screen px (inverse of [screenToFrame]). */
        fun frameToScreen(frameU: Float, frameV: Float): Pair<Float, Float> {
            val (uprightU, uprightV) = frameToUpright(frameU, frameV)
            val mirroredU = if (mirrored) 1f - uprightU else uprightU
            val screenX = (mirroredU * uprightWidth - (uprightWidth - visibleUprightWidth) / 2f) *
                (viewWidth / visibleUprightWidth)
            val screenY = (uprightV * uprightHeight - (uprightHeight - visibleUprightHeight) / 2f) *
                (viewHeight / visibleUprightHeight)
            return screenX to screenY
        }

        /** Upright image is the sensor frame rotated clockwise by [rotationDegrees]. */
        private fun uprightToFrame(u: Float, v: Float): Pair<Float, Float> = when (rotationDegrees) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }

        private fun frameToUpright(u: Float, v: Float): Pair<Float, Float> = when (rotationDegrees) {
            90 -> 1f - v to u
            180 -> 1f - u to 1f - v
            270 -> v to 1f - u
            else -> u to v
        }
    }
}
