package com.lumeter.core.meter

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Absolute read of one code value from a YUV plane at luma-plane coordinates. */
fun interface PlaneSampler {
    fun sample(column: Int, row: Int): Int?
}

/**
 * ROI statistics over one YUV_420_888 frame, adapted from lightstop
 * MeteringAnalysis.analyzeYuvRegion / YuvPlaneReader.
 *
 * The frame's plane bytes stay behind a [PlaneSampler] supplied by the camera layer, so this
 * class is pure Kotlin and unit-testable on the JVM. Chroma samplers already address the
 * half-resolution chroma planes; pass them `column/2, row/2` style coordinates or wrap them
 * with [chroma] below.
 */
class YuvFrameAnalyzer(
    val width: Int,
    val height: Int,
    private val ySampler: PlaneSampler,
    private val uSampler: PlaneSampler,
    private val vSampler: PlaneSampler,
    private val decoder: ProcessedLumaDecoder,
    private val encoding: YuvColorEncoding,
) {
    /** Wraps a half-resolution chroma sampler so callers pass luma coordinates. */
    class ChromaAdapter(private val sampler: PlaneSampler) : PlaneSampler {
        override fun sample(column: Int, row: Int): Int? =
            sampler.sample(column / 2, row / 2)
    }

    fun regionStat(centerX: Float, centerY: Float, roiFraction: Float): RegionStat? {
        val roi = RoiGeometry.roiRect(width, height, centerX, centerY, roiFraction)
        return regionStat(roi)
    }

    fun regionStat(roi: RoiGeometry.Rect): RegionStat? {
        if (roi.width < 2 || roi.height < 2) return null
        // Cap per-region work so a continuous analysis stream stays cheap; a median is stable
        // far below every pixel.
        val pixels = roi.width.toLong() * roi.height.toLong()
        val step = max(1, (sqrt(pixels.toDouble() / MAX_SAMPLES)).roundToInt())
        val columns = (roi.width + step - 1) / step
        val rows = (roi.height + step - 1) / step
        val luminances = DoubleArray(columns * rows)
        var clipped = 0
        var count = 0
        var row = roi.top
        while (row < roi.bottom) {
            var column = roi.left
            while (column < roi.right) {
                val yCode = ySampler.sample(column, row)
                val uCode = uSampler.sample(column, row)
                val vCode = vSampler.sample(column, row)
                if (yCode != null && uCode != null && vCode != null) {
                    val rgb = ProcessedLumaMath.yuvToEncodedRgb(yCode, uCode, vCode, encoding)
                    if (count < luminances.size) {
                        luminances[count] = decoder.linearLuma(rgb.red, rgb.green, rgb.blue)
                    }
                    if (rgb.clipped) clipped += 1
                    count += 1
                }
                column += step
            }
            row += step
        }
        val usable = minOf(count, luminances.size)
        if (usable == 0) return null
        val luma = ProcessedLumaMath.median(luminances, usable) ?: return null
        return RegionStat(luma, clipped.toDouble() / count)
    }

    /**
     * Linear-luma histogram of the whole frame, sampled on a sparse grid. The bin index is
     * the linear luma scaled to [bins]; callers typically draw it against log/EV ticks.
     */
    fun histogram(bins: Int = 128, step: Int = 4): IntArray? {
        if (width <= step || height <= step) return null
        val result = IntArray(bins)
        var counted = 0
        var row = 0
        while (row < height) {
            var column = 0
            while (column < width) {
                val yCode = ySampler.sample(column, row)
                val uCode = uSampler.sample(column, row)
                val vCode = vSampler.sample(column, row)
                if (yCode != null && uCode != null && vCode != null) {
                    val rgb = ProcessedLumaMath.yuvToEncodedRgb(yCode, uCode, vCode, encoding)
                    val luma = decoder.linearLuma(rgb.red, rgb.green, rgb.blue)
                    val index = (luma * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
                    result[index] += 1
                    counted += 1
                }
                column += step
            }
            row += step
        }
        if (counted == 0) return null
        return result
    }

    private companion object {
        private const val MAX_SAMPLES = 4096
    }
}
