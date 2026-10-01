package com.lumeter.core.meter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class LumaHistogramTest {

    @Test
    fun `flat mid gray lands in one dominant bin`() {
        val lumas = DoubleArray(1000) { 0.18 }
        lumas.sort()
        val stats = LumaHistogram.analyze(lumas, lumas.size, 0)
        assertNotNull(stats)
        val dominant = stats!!.histogram.max()
        assertTrue("dominant bin should hold nearly all samples", dominant > 950)
        assertEquals(1000, stats.histogram.sum())
        assertEquals(0.18, stats.lowLuma, 1e-9)
        assertEquals(0.18, stats.highLuma, 1e-9)
    }

    @Test
    fun `saturation maps into the last bin`() {
        assertEquals(LumaFrameStats.Bin.BIN_COUNT - 1, LumaFrameStats.Bin.of(1.0))
        assertEquals(LumaFrameStats.Bin.BIN_COUNT - 1, LumaFrameStats.Bin.of(2.0))
    }

    @Test
    fun `black maps into the first bin`() {
        assertEquals(0, LumaFrameStats.Bin.of(0.0))
        assertEquals(0, LumaFrameStats.Bin.of(-1.0))
        // -10 EV below saturation is the bin floor.
        assertEquals(0, LumaFrameStats.Bin.of(2.0.pow(-10.0) * 0.5))
    }

    @Test
    fun `percentiles bracket the population`() {
        // 90 values at 0.2, 10 values at 0.8 → p05 = 0.2, p95 = 0.8.
        val lumas = DoubleArray(100) { if (it < 90) 0.2 else 0.8 }
        lumas.sort()
        val stats = LumaHistogram.analyze(lumas, lumas.size, 0)!!
        assertEquals(0.2, stats.lowLuma, 1e-9)
        assertEquals(0.8, stats.highLuma, 1e-9)
    }

    @Test
    fun `clipped fraction passes through`() {
        val lumas = DoubleArray(10) { 0.5 }
        val stats = LumaHistogram.analyze(lumas, 10, 3)
        assertEquals(0.3, stats!!.clippedFraction, 1e-9)
    }

    @Test
    fun `empty input rejected`() {
        assertNull(LumaHistogram.analyze(DoubleArray(4), 0, 0))
    }
}
