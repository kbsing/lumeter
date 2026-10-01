package com.lumeter.core.meter

import kotlin.math.roundToInt

/**
 * EV-domain full-frame analysis shared by the histogram overlay and the automatic
 * scene range. One sparse sampling pass yields a 64-bin histogram over
 * [-RANGE_EV, 0] EV (0 EV = sensor saturation) plus p05/p95 linear-luma
 * percentiles, so the UI can show where the scene sits without manual MULTI spots.
 */
class LumaFrameStats(
    /** Per-bin sample counts, length [Bin.BIN_COUNT]. */
    val histogram: IntArray,
    /** 5th percentile of linear luma — the shadow representative. */
    val lowLuma: Double,
    /** 95th percentile of linear luma — the highlight representative. */
    val highLuma: Double,
    val clippedFraction: Double,
    val samples: Int,
) {
    /** EV100 displacement of a percentile luma from mid gray, before calibration. */
    fun evFromLuma(luma: Double, exposure: FrameExposure): Double? =
        EvMath.ev100(exposure, luma)

    object Bin {
        const val BIN_COUNT = 64

        /** Bins span this many EV below sensor saturation. */
        const val RANGE_EV = 10.0

        /** Bin index of a linear luma; 0 EV (saturation) lands in the last bin. */
        fun of(luma: Double): Int {
            if (luma <= 0.0) return 0
            val ev = EvMath.log2(luma.coerceAtMost(1.0))
            return ((ev + RANGE_EV) / RANGE_EV * (BIN_COUNT - 1)).roundToInt()
                .coerceIn(0, BIN_COUNT - 1)
        }
    }
}

object LumaHistogram {

    /**
     * Builds frame statistics from sampled linear luminances. [lumas] must be
     * sorted ascending; [count] is the usable prefix. Returns null on empty input.
     */
    fun analyze(lumas: DoubleArray, count: Int, clippedCount: Int): LumaFrameStats? {
        if (count <= 0) return null
        val histogram = IntArray(LumaFrameStats.Bin.BIN_COUNT)
        for (i in 0 until count) {
            histogram[LumaFrameStats.Bin.of(lumas[i])]++
        }
        fun percentile(fraction: Double): Double {
            val index = (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
            return lumas[index]
        }
        return LumaFrameStats(
            histogram = histogram,
            lowLuma = percentile(0.05),
            highLuma = percentile(0.95),
            clippedFraction = clippedCount.toDouble() / count,
            samples = count,
        )
    }
}
