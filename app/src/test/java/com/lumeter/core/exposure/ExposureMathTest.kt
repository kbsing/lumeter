package com.lumeter.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMathTest {

    @Test
    fun `primary pair reproduces the metered EV at the selected ISO`() {
        // The pair snaps to the discrete scale (worst case half of a third-stop ≈ 12.5%).
        val ev = 10.0
        val iso = 100
        val pair = ExposureMath.primaryPair(ev, iso, null, null)
        val reproduced = pair.aperture * pair.aperture / pair.shutterSeconds
        assertEquals(Math.pow(2.0, ev), reproduced, Math.pow(2.0, ev) * 0.13)
    }

    @Test
    fun `locked aperture keeps the aperture and solves the shutter`() {
        val apertureIndex = ExposureMath.apertures.indexOfFirst { kotlin.math.abs(it - 5.6) < 0.01 }
        val pair = ExposureMath.primaryPair(12.0, 100, apertureIndex, null)
        assertEquals(apertureIndex, pair.apertureIndex)
        val reproduced = pair.aperture * pair.aperture / pair.shutterSeconds
        assertEquals(4096.0, reproduced, 4096.0 * 0.08)
    }

    @Test
    fun `locked shutter keeps the shutter and solves the aperture`() {
        val shutterIndex = ExposureMath.shutters.indexOfFirst { kotlin.math.abs(it - 1.0 / 250.0) < 1e-6 }
        val pair = ExposureMath.primaryPair(12.0, 100, null, shutterIndex)
        assertEquals(shutterIndex, pair.shutterIndex)
        val reproduced = pair.aperture * pair.aperture / pair.shutterSeconds
        assertEquals(4096.0, reproduced, 4096.0 * 0.08)
    }

    @Test
    fun `higher ISO shortens the shutter`() {
        val base = ExposureMath.primaryPair(10.0, 100, null, null)
        val pushed = ExposureMath.primaryPair(10.0, 800, null, null)
        assertTrue(pushed.shutterSeconds <= base.shutterSeconds)
    }

    @Test
    fun `shutter formatting follows photographic conventions`() {
        assertEquals("1/125", ExposureMath.formatShutter(1.0 / 125.0))
        assertEquals("2″", ExposureMath.formatShutter(2.0))
        assertEquals("0.5″", ExposureMath.formatShutter(0.5))
    }

    @Test
    fun `aperture formatting drops trailing zero`() {
        assertEquals("f/8", ExposureMath.formatAperture(8.0))
        assertEquals("f/1.4", ExposureMath.formatAperture(1.4))
    }
}
