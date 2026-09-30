package com.lumeter.core.meter

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * EV100 computation shared by every metering source, adapted from lightstop
 * MeteringAnalysis.kt. All luma values are normalized 0..1 in the sensor-linear
 * domain (processed streams have been through [ProcessedLumaDecoder] first).
 */
object EvMath {
    /** 18% gray reference: the luma an ordinary reflected meter places at the indicated EV. */
    const val REFERENCE_LUMA = 0.18

    const val SPOT_ROI_FRACTION = 0.08f
    const val CENTER_WEIGHTED_ROI_FRACTION = 0.30f
    const val CENTER_SPOT_WEIGHT = 0.7
    const val CENTER_WIDE_WEIGHT = 0.3

    /**
     * EV100 of a scene region from the camera's own exposure settings and the region's
     * linear luma. Returns null when inputs are outside the valid domain.
     */
    fun ev100(exposure: FrameExposure, regionLuma: Double, calibrationEv: Double = 0.0): Double? {
        if (!regionLuma.isFinite() || regionLuma <= 0.00001) return null
        if (exposure.sensitivity <= 0 || exposure.seconds <= 0.0 || exposure.aperture <= 0f) {
            return null
        }
        val cameraEv = log2(
            exposure.aperture.toDouble() * exposure.aperture /
                exposure.seconds * 100.0 / exposure.sensitivity,
        )
        return cameraEv + log2(regionLuma / REFERENCE_LUMA) + calibrationEv
    }

    fun log2(value: Double): Double = ln(value) / ln(2.0)
}

/** Robust luma/clipping statistics over a rectangular ROI of a YUV frame. */
data class RegionStat(val luma: Double, val clippedFraction: Double)

/**
 * Center/spot ROI geometry shared by metering and multi-point placement.
 * All coordinates are normalized 0..1 in the analysis frame.
 */
object RoiGeometry {
    fun roiRect(
        frameWidth: Int,
        frameHeight: Int,
        centerX: Float,
        centerY: Float,
        roiFraction: Float,
    ): Rect {
        val roiWidth = (frameWidth * roiFraction).roundToInt().coerceIn(2, frameWidth)
        val roiHeight = (frameHeight * roiFraction).roundToInt().coerceIn(2, frameHeight)
        val left = (centerX * frameWidth - roiWidth / 2f).roundToInt().coerceIn(0, frameWidth - roiWidth)
        val top = (centerY * frameHeight - roiHeight / 2f).roundToInt().coerceIn(0, frameHeight - roiHeight)
        return Rect(left, top, left + roiWidth, top + roiHeight)
    }

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
}
