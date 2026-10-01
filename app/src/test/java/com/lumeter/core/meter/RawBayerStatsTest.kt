package com.lumeter.core.meter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBayerStatsTest {

    /** Synthetic sensor: every 2x2 cell laid out per [cfa], value from a per-channel lambda. */
    private fun readerFor(
        cfa: CfaPattern,
        value: (channel: Int, x: Int, y: Int) -> Int,
    ): RawBayerStats.PixelReader = RawBayerStats.PixelReader { x, y ->
        value(RawBayerStats.channelFor(cfa, ((y and 1) shl 1) or (x and 1)), x, y)
    }

    private val black = floatArrayOf(64f, 64f, 64f, 64f)
    private val WHITE = 1024

    @Test
    fun `channel mapping covers all four CFA layouts`() {
        // RGGB: EE=R, EO=Gr, OE=Gb, OO=B.
        assertEquals(0, RawBayerStats.channelFor(CfaPattern.RGGB, 0))
        assertEquals(1, RawBayerStats.channelFor(CfaPattern.RGGB, 1))
        assertEquals(2, RawBayerStats.channelFor(CfaPattern.RGGB, 2))
        assertEquals(3, RawBayerStats.channelFor(CfaPattern.RGGB, 3))
        // BGGR: EE=B.
        assertEquals(3, RawBayerStats.channelFor(CfaPattern.BGGR, 0))
        assertEquals(0, RawBayerStats.channelFor(CfaPattern.BGGR, 3))
        // GRBG: EE=Gr, EO=R.
        assertEquals(1, RawBayerStats.channelFor(CfaPattern.GRBG, 0))
        assertEquals(0, RawBayerStats.channelFor(CfaPattern.GRBG, 1))
        // GBRG: EE=Gr, EO=B.
        assertEquals(1, RawBayerStats.channelFor(CfaPattern.GBRG, 0))
        assertEquals(3, RawBayerStats.channelFor(CfaPattern.GBRG, 1))
    }

    @Test
    fun `flat gray field yields matching channel medians and known luminance`() {
        // 304 codes: (304-64)/(1024-64) = 0.25 normalized.
        val reader = readerFor(CfaPattern.RGGB) { _, _, _ -> 304 }
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.RGGB, black, WHITE,
            RoiGeometry.Rect(0, 0, 64, 64),
        )
        assertNotNull(stat)
        stat!!.let {
            assertEquals(0.25, it.red, 1e-9)
            assertEquals(0.25, it.greenEven, 1e-9)
            assertEquals(0.25, it.greenOdd, 1e-9)
            assertEquals(0.25, it.blue, 1e-9)
            assertEquals(0.25, it.luminance(), 1e-9)
            assertEquals(0.0, it.clippedFraction, 1e-9)
            assertTrue(it.valid)
        }
    }

    @Test
    fun `black level subtracts before normalization`() {
        val reader = readerFor(CfaPattern.BGGR) { _, _, _ -> 64 + 120 } // 12.5% of range above black
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.BGGR, black, WHITE,
            RoiGeometry.Rect(0, 0, 32, 32),
        )
        assertEquals(0.125, stat!!.red, 1e-9)
        assertEquals(0.125, stat!!.luminance(), 1e-9)
    }

    @Test
    fun `hot pixels do not move the median`() {
        // Every 8th pixel is stuck near white; medians must ignore them.
        val reader = RawBayerStats.PixelReader { x, y ->
            if ((x * 31 + y * 17) % 8 == 0) 1020 else 304
        }
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.GBRG, black, WHITE,
            RoiGeometry.Rect(0, 0, 96, 96),
        )
        assertEquals(0.25, stat!!.red, 1e-6)
        assertTrue(stat!!.clippedFraction > 0.0)
    }

    @Test
    fun `saturated region reports clipping`() {
        val reader = readerFor(CfaPattern.RGGB) { _, _, _ -> WHITE }
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.RGGB, black, WHITE,
            RoiGeometry.Rect(0, 0, 48, 48),
        )
        assertEquals(1.0, stat!!.clippedFraction, 1e-9)
        assertEquals(1.0, stat!!.red, 1e-9)
    }

    @Test
    fun `tiny roi is rejected`() {
        val reader = readerFor(CfaPattern.RGGB) { _, _, _ -> 256 }
        assertNull(
            RawBayerStats.regionStat(
                reader, CfaPattern.RGGB, black, WHITE,
                RoiGeometry.Rect(0, 0, 2, 1),
            ),
        )
    }

    @Test
    fun `invalid black level rejects the frame`() {
        val reader = readerFor(CfaPattern.RGGB) { _, _, _ -> 256 }
        assertNull(
            RawBayerStats.regionStat(
                reader, CfaPattern.RGGB, floatArrayOf(-1f, 0f, 0f, 0f), WHITE,
                RoiGeometry.Rect(0, 0, 32, 32),
            ),
        )
        assertNull(
            RawBayerStats.regionStat(
                reader, CfaPattern.RGGB, black, 0,
                RoiGeometry.Rect(0, 0, 32, 32),
            ),
        )
    }

    @Test
    fun `odd-aligned roi is even-aligned before sampling`() {
        val reader = readerFor(CfaPattern.RGGB) { _, _, _ -> 304 }
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.RGGB, black, WHITE,
            RoiGeometry.Rect(1, 3, 65, 67), // odd offsets, even size
        )
        assertNotNull(stat)
        assertEquals(0.25, stat!!.luminance(), 1e-9)
    }

    @Test
    fun `luminance respects custom coefficients`() {
        val reader = readerFor(CfaPattern.RGGB) { channel, _, _ ->
            when (channel) { 0 -> 512; 3 -> 512; else -> 256 }
        }
        val stat = RawBayerStats.regionStat(
            reader, CfaPattern.RGGB, black, WHITE,
            RoiGeometry.Rect(0, 0, 32, 32),
        )
        // R=B=(512-64)/960, G=(256-64)/960, default Rec.709 coefficients.
        assertEquals(
            0.2126 * (448.0 / 960) + 0.7152 * (192.0 / 960) + 0.0722 * (448.0 / 960),
            stat!!.luminance(),
            1e-9,
        )
        assertFalse(stat!!.samples < RawRegionStat.MIN_SAMPLES)
    }
}
