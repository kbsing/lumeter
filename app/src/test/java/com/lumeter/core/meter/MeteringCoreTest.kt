package com.lumeter.core.meter

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvMathTest {

    @Test
    fun `18 percent gray at camera exposure reports the camera's own EV100`() {
        val exposure = FrameExposure(
            exposureTimeNs = 10_000_000L, // 1/100 s
            sensitivity = 100,
            aperture = 8f,
        )
        val cameraEv = log2(8.0 * 8.0 / 0.01 * 100.0 / 100.0)
        val sceneEv = EvMath.ev100(exposure, EvMath.REFERENCE_LUMA)
        assertNotNull(sceneEv)
        assertEquals(cameraEv, sceneEv!!, 1e-9)
    }

    @Test
    fun `one stop brighter luma is one EV higher`() {
        val exposure = FrameExposure(exposureTimeNs = 10_000_000L, sensitivity = 100, aperture = 8f)
        val base = EvMath.ev100(exposure, 0.18)!!
        val brighter = EvMath.ev100(exposure, 0.36)!!
        assertEquals(1.0, brighter - base, 1e-9)
    }

    @Test
    fun `invalid exposure or luma is rejected`() {
        val exposure = FrameExposure(exposureTimeNs = 10_000_000L, sensitivity = 100, aperture = 8f)
        assertNull(EvMath.ev100(exposure, 0.0))
        assertNull(
            EvMath.ev100(
                FrameExposure(exposureTimeNs = 0L, sensitivity = 100, aperture = 8f),
                0.18,
            ),
        )
        assertNull(
            EvMath.ev100(
                FrameExposure(exposureTimeNs = 10_000_000L, sensitivity = 0, aperture = 8f),
                0.18,
            ),
        )
    }
}

class ProcessedLumaMathTest {

    @Test
    fun `neutral YUV decodes to gray and its linear value`() {
        val encoding = YuvColorEncoding() // full-range BT.601
        val rgb = ProcessedLumaMath.yuvToEncodedRgb(128, 128, 128, encoding)
        assertEquals(rgb.red, rgb.green, 1e-12)
        assertEquals(rgb.green, rgb.blue, 1e-12)
        val decoder = ProcessedLumaDecoder(ProcessedTransfer.SRGB)
        val luma = decoder.linearLuma(rgb.red, rgb.green, rgb.blue)
        val expected = if (rgb.red <= 0.04045) rgb.red / 12.92 else ((rgb.red + 0.055) / 1.055).pow(2.4)
        assertEquals(expected, luma, 1e-7)
    }

    @Test
    fun `limited range black maps to zero`() {
        val encoding = YuvColorEncoding(range = YuvCodeRange.LIMITED)
        val rgb = ProcessedLumaMath.yuvToEncodedRgb(16, 128, 128, encoding)
        assertEquals(0.0, rgb.red, 1e-12)
        assertEquals(0.0, rgb.green, 1e-12)
        assertEquals(0.0, rgb.blue, 1e-12)
    }

    @Test
    fun `monotonic identity curve lookup is exact`() {
        val decoder = ProcessedLumaDecoder(ProcessedTransfer.LINEAR)
        assertEquals(0.25, decoder.linearLuma(0.25, 0.25, 0.25), 0.002)
        assertEquals(0.75, decoder.linearLuma(0.75, 0.75, 0.75), 0.002)
    }

    @Test
    fun `reported tonemap curve is inverted around its midpoint`() {
        // A simple gamma-2.0 curve reported as sample points: y = x².
        val points = DoubleArray(10) { index ->
            if (index % 2 == 0) index / 8.0 else ((index - 1) / 8.0) * ((index - 1) / 8.0)
        }
        val transfer = ProcessedTransfer.CURVES(
            red = ProcessedToneCurve(points),
            green = ProcessedToneCurve(points),
            blue = ProcessedToneCurve(points),
        )
        val decoder = ProcessedLumaDecoder(transfer)
        // Encoded 0.25 came from linear 0.5 (0.5^2). Inversion should recover ~0.5.
        assertEquals(0.5, decoder.linearLuma(0.25, 0.25, 0.25), 0.01)
        // Encoded 0.64 came from linear 0.8.
        assertEquals(0.8, decoder.linearLuma(0.64, 0.64, 0.64), 0.01)
    }
}

class MeteringFusionTest {

    private fun stat(ev: Double) = MeteringFrameStat(
        ev100 = ev,
        luma = 0.18,
        clipped = 0.0,
        captureIso = 100,
        exposureTimeNs = 10_000_000L,
        aperture = 8f,
    )

    @Test
    fun `median rejects a transient frame`() {
        val reading = MeteringFusion.fuse(
            listOf(stat(10.0), stat(10.1), stat(10.0), stat(10.1), stat(16.0)),
            MeteringSource.YUV_PREVIEW,
        )
        assertNotNull(reading)
        assertEquals(10.1, reading!!.sceneEv100, 1e-9)
    }

    @Test
    fun `empty input yields no reading`() {
        assertNull(MeteringFusion.fuse(emptyList(), MeteringSource.YUV_PREVIEW))
    }
}

class YuvFrameAnalyzerTest {

    private open class GridPlane(
        private val bytes: ByteArray,
        private val stride: Int,
    ) : PlaneSampler {
        override fun sample(column: Int, row: Int): Int = bytes[row * stride + column].toInt() and 0xff
    }

    private class LumaPlane(bytes: ByteArray) : GridPlane(bytes, 64)
    private class ChromaPlane(bytes: ByteArray) : GridPlane(bytes, 32)

    private fun grayAnalyzer(
        yCode: Int,
        transfer: ProcessedTransfer = ProcessedTransfer.SRGB,
    ): YuvFrameAnalyzer {
        val y = ByteArray(64 * 64) { yCode.toByte() }
        val chroma = ByteArray(32 * 32) { 128.toByte() }
        return YuvFrameAnalyzer(
            width = 64,
            height = 64,
            ySampler = LumaPlane(y),
            uSampler = YuvFrameAnalyzer.ChromaAdapter(ChromaPlane(chroma)),
            vSampler = YuvFrameAnalyzer.ChromaAdapter(ChromaPlane(chroma)),
            decoder = ProcessedLumaDecoder(transfer),
            encoding = YuvColorEncoding(),
        )
    }

    @Test
    fun `uniform gray frame meters to the gray's linear luma`() {
        val analyzer = grayAnalyzer(128)
        val stat = analyzer.regionStat(0.5f, 0.5f, 0.2f)
        assertNotNull(stat)
        val encoded = 128.0 / 255.0
        val expected = srgbLinear(encoded)
        assertEquals(expected, stat!!.luma, 0.01)
        assertEquals(0.0, stat.clippedFraction, 1e-9)
    }

    @Test
    fun `saturated frame is flagged clipped`() {
        val analyzer = grayAnalyzer(255)
        val stat = analyzer.regionStat(0.5f, 0.5f, 0.2f)
        assertNotNull(stat)
        assertTrue(stat!!.clippedFraction > 0.99)
    }

    @Test
    fun `off-center ROI reads only its own pixels`() {
        // Left half bright, right half dark in the luma plane.
        val y = ByteArray(64 * 64)
        for (row in 0 until 64) {
            for (column in 0 until 64) {
                y[row * 64 + column] = if (column < 32) 200.toByte() else 40.toByte()
            }
        }
        val chroma = ByteArray(32 * 32) { 128.toByte() }
        val analyzer = YuvFrameAnalyzer(
            width = 64,
            height = 64,
            ySampler = LumaPlane(y),
            uSampler = YuvFrameAnalyzer.ChromaAdapter(ChromaPlane(chroma)),
            vSampler = YuvFrameAnalyzer.ChromaAdapter(ChromaPlane(chroma)),
            decoder = ProcessedLumaDecoder(ProcessedTransfer.SRGB),
            encoding = YuvColorEncoding(),
        )
        val bright = analyzer.regionStat(0.25f, 0.5f, 0.2f)!!
        val dark = analyzer.regionStat(0.75f, 0.5f, 0.2f)!!
        val encodedBright = 200.0 / 255.0
        val encodedDark = 40.0 / 255.0
        assertEquals(srgbLinear(encodedBright), bright.luma, 0.01)
        assertEquals(srgbLinear(encodedDark), dark.luma, 0.01)
    }

    @Test
    fun `histogram covers the full frame`() {
        val analyzer = grayAnalyzer(64)
        val histogram = analyzer.histogram(bins = 16, step = 4)
        assertNotNull(histogram)
        assertEquals(16, histogram!!.size)
        assertTrue(histogram.sum() > 0)
        // All mass in the bin that contains the linear value of 64/255.
        val expectedBin = kotlin.math.round(srgbLinear(64.0 / 255.0) * 15).toInt().coerceIn(0, 15)
        assertEquals(histogram.sum().toLong(), histogram[expectedBin].toLong())
    }
}

private fun srgbLinear(value: Double): Double =
    if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
