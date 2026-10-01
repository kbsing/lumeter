package com.lumeter.core.exposure

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureSolverTest {

    @Test
    fun `aperture priority solves shutter and snaps to scale`() {
        // EV100=10 at ISO 100 with f/5.6: t = 31.36/1024 ≈ 0.0306s → snaps to 1/30.
        val out = ExposureSolver.solve(
            ExposureMode.APERTURE_PRIORITY,
            aperture = 5.6,
            shutter = 1.0 / 250.0,
            evIso = 10.0,
        )
        assertEquals(1.0 / 30.0, out.shutterSnapped, 1e-9)
        assertEquals(5.6, out.apertureSnapped, 1e-9)
        assertEquals(kotlin.math.log2(0.030625 / (1.0 / 30.0)), out.diff, 1e-9)
        assertTrue(abs(out.diff) < 0.35)
    }

    @Test
    fun `shutter priority solves aperture and snaps to scale`() {
        // EV100=10 at ISO 100 with 1/125: N = sqrt(0.008·1024) ≈ 2.86 → snaps to 2.8.
        val out = ExposureSolver.solve(
            ExposureMode.SHUTTER_PRIORITY,
            aperture = 5.6,
            shutter = 1.0 / 125.0,
            evIso = 10.0,
        )
        assertEquals(2.8, out.apertureSnapped, 1e-9)
        assertEquals(1.0 / 125.0, out.shutterSnapped, 1e-9)
        assertEquals(-kotlin.math.log2(2.8622 * 2.8622 / (2.8 * 2.8)), out.diff, 0.01)
    }

    @Test
    fun `manual mode diff is scene minus set exposure`() {
        // set = log2(5.6²/(1/60)) = log2(31.36·60) ≈ 10.88; scene evIso = 10 → diff ≈ −0.88.
        val out = ExposureSolver.solve(
            ExposureMode.MANUAL,
            aperture = 5.6,
            shutter = 1.0 / 60.0,
            evIso = 10.0,
        )
        assertEquals(-0.88, out.diff, 0.01)
        assertFalse(ExposureSolver.isMatched(out.diff))
    }

    @Test
    fun `matched manual setting gives near-zero diff`() {
        // log2(5.6²/t) = 10 exactly.
        val t = 5.6 * 5.6 / 1024.0
        val out = ExposureSolver.solve(ExposureMode.MANUAL, 5.6, t, 10.0)
        assertEquals(0.0, out.diff, 1e-9)
        assertTrue(ExposureSolver.isMatched(out.diff))
    }

    @Test
    fun `needle clamps to the scale range`() {
        assertEquals(3.0, ExposureSolver.needle(7.0), 1e-9)
        assertEquals(-3.0, ExposureSolver.needle(-9.0), 1e-9)
        assertEquals(0.5, ExposureSolver.needle(0.5), 1e-9)
    }

    @Test
    fun `iso gain shifts EV by stop count`() {
        // evAtIso returns the working-ISO EV: scene EV plus the ISO gain in stops.
        assertEquals(12.0, ExposureSolver.evAtIso(10.0, 400), 1e-9)
        assertEquals(8.0, ExposureSolver.evAtIso(10.0, 25), 1e-9)
    }

    @Test
    fun `K constant shift is zero at the neutral point`() {
        assertEquals(0.0, ExposureSolver.calibrationShift(12.5), 1e-9)
        assertEquals(kotlin.math.log2(14.0 / 12.5), ExposureSolver.calibrationShift(14.0), 1e-9)
    }

    @Test
    fun `lux estimate matches the reference formula`() {
        assertEquals(2.5, ExposureSolver.approxLux(0.0, 0), 1e-9)
        assertEquals(10.0, ExposureSolver.approxLux(2.0, 0), 1e-9)
        // ND attenuation is added back for the illuminance display.
        assertEquals(10.0, ExposureSolver.approxLux(0.0, 2), 1e-9)
    }

    @Test
    fun `nearest picks the log-closest scale stop`() {
        assertEquals(1.0 / 125.0, ExposureSolver.nearest(ExposureSolver.SHUTTERS, 1.0 / 140.0), 1e-9)
        assertEquals(10.0, ExposureSolver.nearest(ExposureSolver.APERTURES, 9.5), 1e-9)
    }

    @Test
    fun `equivalent exposure invariant across modes`() {
        // A mode at EV 12 ISO 100 f/8: t = 64/4096 = 1/64 → snaps 1/60. The snapped pair
        // must reproduce the EV within a third stop.
        val out = ExposureSolver.solve(ExposureMode.APERTURE_PRIORITY, 8.0, 1.0 / 250.0, 12.0)
        val reproducedEv = kotlin.math.log2(
            out.apertureSnapped * out.apertureSnapped / out.shutterSnapped,
        )
        assertEquals(12.0, reproducedEv, 1.0 / 6.0)
    }
}
