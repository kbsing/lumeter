package com.lumeter.core.meter

/**
 * Metering data models, adapted from lightstop MeterModels.kt / MeteringAnalysis.kt.
 */

/** Which camera stream produced a reading. RAW is reserved for the future C++ path. */
enum class MeteringSource {
    RAW,
    YUV_PREVIEW,
    ISP_PREVIEW,
}

enum class MeteringMode {
    MATRIX,
    CENTER,
    SPOT,
    MULTI,
}

/** Exposure metadata of the frame a measurement was taken from. */
data class FrameExposure(
    val exposureTimeNs: Long,
    val sensitivity: Int,
    val aperture: Float,
) {
    val seconds: Double get() = exposureTimeNs / 1_000_000_000.0
}

data class MeteringFrameStat(
    val ev100: Double,
    val luma: Double,
    val clipped: Double,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val aperture: Float,
    /** EV before the user calibration offset was applied; the re-baselining anchor. */
    val ev100BeforeUserCalibration: Double = ev100,
    val appliedUserCorrectionEv: Double = 0.0,
)

/**
 * Fused output of the metering chain (median over the stable-frame ring). Named apart
 * from core.exposure's L2 [com.lumeter.core.exposure.MeterReading] on purpose: the
 * layers talk through one number (EV100), not through shared types.
 */
data class FusedReading(
    val sceneEv100: Double,
    val rawLuma: Double,
    val clippedFraction: Double,
    val frameCount: Int,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val aperture: Float,
    val source: MeteringSource = MeteringSource.YUV_PREVIEW,
)
