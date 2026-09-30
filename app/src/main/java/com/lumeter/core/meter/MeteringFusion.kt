package com.lumeter.core.meter

import kotlin.math.abs

/**
 * Robustly fuses per-frame measurements into the value shown to the user.
 * Adapted from lightstop MeteringFusion.kt.
 *
 * Keeping fusion independent of the camera stack makes the adaptive frame policy easy to
 * test without a device. Median EV/luma reject a transient frame while clipped fraction
 * remains an average.
 */
object MeteringFusion {

    fun fuse(stats: List<MeteringFrameStat>, source: MeteringSource): MeterReading? {
        if (stats.isEmpty()) return null
        val representative = stats[stats.size / 2]
        return MeterReading(
            sceneEv100 = median(stats.map(MeteringFrameStat::ev100).sorted()),
            rawLuma = median(stats.map(MeteringFrameStat::luma).sorted()),
            clippedFraction = stats.map(MeteringFrameStat::clipped).average(),
            frameCount = stats.size,
            captureIso = representative.captureIso,
            exposureTimeNs = representative.exposureTimeNs,
            aperture = representative.aperture,
            source = source,
        )
    }

    /** True when every frame carried the same calibration state, so the fused EV is attributable. */
    fun calibrationIsConsistent(stats: List<MeteringFrameStat>): Boolean {
        if (stats.isEmpty()) return false
        val first = stats.first().appliedUserCorrectionEv
        return stats.all { abs(it.appliedUserCorrectionEv - first) <= CONSISTENCY_TOLERANCE_EV }
    }

    fun fusedEvBeforeCalibration(stats: List<MeteringFrameStat>): Double? =
        stats.takeIf { it.isNotEmpty() }
            ?.let { median(it.map(MeteringFrameStat::ev100BeforeUserCalibration).sorted()) }

    private const val CONSISTENCY_TOLERANCE_EV = 1e-3

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }
}
