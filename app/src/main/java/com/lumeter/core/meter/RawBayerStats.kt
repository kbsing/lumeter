package com.lumeter.core.meter

/**
 * Bayer-domain statistics over a RAW sensor frame, adapted from lightstop's
 * raw_meter.cpp but in pure Kotlin: per-channel medians of black-level-subtracted,
 * white-normalized code values, plus a clipped fraction. Testable on the JVM — the
 * camera layer supplies a pixel reader, this file owns the CFA mapping, sampling
 * stride and statistics.
 */

/** Sensor CFA arrangement, matching CameraCharacteristics SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. */
enum class CfaPattern(val id: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3),
    ;

    companion object {
        fun fromId(id: Int): CfaPattern? = entries.firstOrNull { it.id == id }
    }
}

/** Per-channel medians [R, G1, G2, B] normalized 0..1, clipped fraction and sample count. */
data class RawRegionStat(
    val red: Double,
    val greenEven: Double,
    val greenOdd: Double,
    val blue: Double,
    val clippedFraction: Double,
    val samples: Int,
) {
    /** Scene luminance on sensor-linear RGB coefficients (Rec.709 by default). */
    fun luminance(
        redWeight: Double = 0.2126,
        greenWeight: Double = 0.7152,
        blueWeight: Double = 0.0722,
    ): Double {
        val green = (greenEven + greenOdd) * 0.5
        return redWeight * red + greenWeight * green + blueWeight * blue
    }

    val valid: Boolean get() = samples >= MIN_SAMPLES

    companion object {
        /** Below this the ROI says nothing usable (lightstop's threshold). */
        const val MIN_SAMPLES = 16
    }
}

object RawBayerStats {

    /** Pixel reader: absolute sensor-space (column, row) → raw code value, or null outside. */
    fun interface PixelReader {
        fun sample(column: Int, row: Int): Int?
    }

    /**
     * Statistics over [roi], aligned to whole 2×2 Bayer cells. Like lightstop, complete
     * cells keep R/G1/G2/B equally represented; the stride caps scratch memory at
     * [MAX_CELLS] cells per channel.
     */
    fun regionStat(
        reader: PixelReader,
        cfa: CfaPattern,
        blackLevels: FloatArray,
        whiteLevel: Int,
        roi: RoiGeometry.Rect,
        maxCells: Int = MAX_CELLS,
    ): RawRegionStat? {
        if (whiteLevel <= 0 || blackLevels.size < 4) return null
        for (level in blackLevels) {
            if (!level.isFinite() || level < 0f || level >= whiteLevel) return null
        }
        if (roi.width < 2 || roi.height < 2) return null

        // Even-align so every sampled 2x2 cell is complete.
        val firstX = roi.left + (roi.left and 1)
        val firstY = roi.top + (roi.top and 1)
        val cellColumns = (roi.right - firstX) / 2
        val cellRows = (roi.bottom - firstY) / 2
        if (cellColumns <= 0 || cellRows <= 0) return null

        val totalCells = cellColumns.toLong() * cellRows
        val stride = if (totalCells <= maxCells) {
            1
        } else {
            kotlin.math.ceil(kotlin.math.sqrt(totalCells.toDouble() / maxCells)).toInt()
                .coerceAtLeast(1)
        }

        val red = DoubleArray(MAX_CELLS)
        val greenEven = DoubleArray(MAX_CELLS)
        val greenOdd = DoubleArray(MAX_CELLS)
        val blue = DoubleArray(MAX_CELLS)
        // Counts per channel may differ by at most one row/column of cells.
        var redCount = 0
        var greenEvenCount = 0
        var greenOddCount = 0
        var blueCount = 0
        var clipped = 0
        var valid = 0

        val pixelStep = stride * 2
        var cellY = firstY
        while (cellY + 1 < roi.bottom) {
            var cellX = firstX
            while (cellX + 1 < roi.right) {
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = cellX + dx
                        val y = cellY + dy
                        val raw = reader.sample(x, y) ?: continue
                        val position = ((y and 1) shl 1) or (x and 1)
                        val black = blackLevels[position].toDouble()
                        val denominator = maxOf(1.0, whiteLevel.toDouble() - black)
                        val normalized = ((raw - black) / denominator).coerceIn(0.0, 1.0)
                        // Capacity is bounded by construction (stride ≈ sqrt(total/max));
                        // a ceil overshoot of one cell drops its pixels rather than
                        // corrupting the median slot.
                        val slot = when (channelFor(cfa, position)) {
                            0 -> if (redCount < red.size) redCount else -1
                            1 -> if (greenEvenCount < greenEven.size) greenEvenCount else -1
                            2 -> if (greenOddCount < greenOdd.size) greenOddCount else -1
                            else -> if (blueCount < blue.size) blueCount else -1
                        }
                        if (slot >= 0) {
                            when (channelFor(cfa, position)) {
                                0 -> { red[slot] = normalized; redCount++ }
                                1 -> { greenEven[slot] = normalized; greenEvenCount++ }
                                2 -> { greenOdd[slot] = normalized; greenOddCount++ }
                                else -> { blue[slot] = normalized; blueCount++ }
                            }
                        }
                        if (normalized >= 0.985) clipped++
                        valid++
                    }
                }
                cellX += pixelStep
            }
            cellY += pixelStep
        }

        fun boundedMedian(values: DoubleArray, count: Int): Double {
            if (count <= 0) return 0.0
            val n = minOf(count, values.size)
            val list = values.copyOf(n)
            list.sort()
            return ProcessedLumaMath.median(list, n) ?: 0.0
        }

        if (valid == 0) return null
        return RawRegionStat(
            red = boundedMedian(red, redCount),
            greenEven = boundedMedian(greenEven, greenEvenCount),
            greenOdd = boundedMedian(greenOdd, greenOddCount),
            blue = boundedMedian(blue, blueCount),
            clippedFraction = clipped.toDouble() / valid,
            samples = valid,
        )
    }

    /** Channel index [R=0, G1=1, G2=2, B=3] for a position in the 2x2 row-major layout. */
    fun channelFor(cfa: CfaPattern, position: Int): Int = when (cfa) {
        CfaPattern.RGGB -> when (position) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }
        CfaPattern.GRBG -> when (position) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
        CfaPattern.GBRG -> when (position) { 0 -> 1; 1 -> 3; 2 -> 0; else -> 2 }
        CfaPattern.BGGR -> when (position) { 0 -> 3; 1 -> 1; 2 -> 2; else -> 0 }
    }

    /**
     * Whole-frame EV-domain analysis over sampled 2x2 cells: each cell contributes one
     * cell-averaged Rec.709 luminance, so histogram and percentiles share the sampling
     * budget with [regionStat]. Cell stride follows the same max-samples rule.
     */
    fun frameAnalysis(
        reader: PixelReader,
        cfa: CfaPattern,
        blackLevels: FloatArray,
        whiteLevel: Int,
        frameWidth: Int,
        frameHeight: Int,
        maxCells: Int = MAX_CELLS,
    ): LumaFrameStats? {
        if (whiteLevel <= 0 || blackLevels.size < 4 || frameWidth < 2 || frameHeight < 2) return null
        for (level in blackLevels) {
            if (!level.isFinite() || level < 0f || level >= whiteLevel) return null
        }
        val cellColumns = frameWidth / 2
        val cellRows = frameHeight / 2
        val totalCells = cellColumns.toLong() * cellRows
        val stride = if (totalCells <= maxCells) {
            1
        } else {
            kotlin.math.ceil(kotlin.math.sqrt(totalCells.toDouble() / maxCells)).toInt()
                .coerceAtLeast(1)
        }

        val luminances = DoubleArray(maxCells)
        var count = 0
        var clipped = 0
        val pixelStep = stride * 2
        var y = 0
        while (y + 1 < frameHeight) {
            var x = 0
            while (x + 1 < frameWidth) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = x + dx
                        val py = y + dy
                        val raw = reader.sample(px, py) ?: continue
                        val position = ((py and 1) shl 1) or (px and 1)
                        val black = blackLevels[position].toDouble()
                        val denominator = maxOf(1.0, whiteLevel.toDouble() - black)
                        val normalized = ((raw - black) / denominator).coerceIn(0.0, 1.0)
                        when (channelFor(cfa, position)) {
                            0 -> r = normalized
                            1, 2 -> g += normalized * 0.5
                            else -> b = normalized
                        }
                        if (normalized >= 0.985) clipped++
                    }
                }
                if (count < luminances.size) {
                    luminances[count] = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    count++
                }
                x += pixelStep
            }
            y += pixelStep
        }
        if (count == 0) return null
        luminances.sort(0, count)
        return LumaHistogram.analyze(luminances, count, clipped)
    }

    private const val MAX_CELLS = 16_384
}
