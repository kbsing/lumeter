package com.lumeter.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsMathTest {

    // ---- Reciprocity ----

    @Test
    fun `short exposures pass through unchanged`() {
        val model = Reciprocity.fromStopAt(stops = 1.0, atSeconds = 10.0)
        assertEquals(0.5, model.correctedSeconds(0.5), 1e-9)
        assertEquals(1.0, model.correctedSeconds(1.0), 1e-9)
    }

    @Test
    fun `stop-at anchor reproduces the quoted correction`() {
        // +1 stop at 10s: corrected time must be 20s.
        val model = Reciprocity.fromStopAt(stops = 1.0, atSeconds = 10.0)
        assertEquals(20.0, model.correctedSeconds(10.0), 1e-9)
        assertEquals(1.0, model.correctionStops(10.0), 1e-9)
    }

    @Test
    fun `half stop at ten seconds`() {
        val model = Reciprocity.fromStopAt(stops = 0.5, atSeconds = 10.0)
        assertEquals(10.0 * Math.sqrt(2.0), model.correctedSeconds(10.0), 1e-9)
        assertEquals(0.5, model.correctionStops(10.0), 1e-6)
    }

    @Test
    fun `threshold model leaves short times alone and grows beyond it`() {
        val model = Reciprocity(thresholdSeconds = 30.0, exponent = 1.15)
        assertEquals(10.0, model.correctedSeconds(10.0), 1e-9)
        assertTrue(model.correctedSeconds(60.0) > 60.0)
        assertEquals(0.0, model.correctionStops(30.0), 1e-9)
    }

    @Test
    fun `zero correction model is identity`() {
        assertEquals(42.0, Reciprocity.NONE.correctedSeconds(42.0), 1e-9)
        assertEquals(0.0, Reciprocity.NONE.correctionStops(42.0), 1e-9)
    }

    // ---- Flash ----

    @Test
    fun `f number follows inverse square`() {
        // GN 32 (m, ISO100) at 4m → f/8.
        assertEquals(8.0, FlashMath.fNumber(32.0, 4.0, 100)!!, 1e-9)
    }

    @Test
    fun `iso scales the effective guide number by sqrt`() {
        // GN 32 at ISO 400 behaves like GN 64 → f/16 at 4m.
        assertEquals(16.0, FlashMath.fNumber(32.0, 4.0, 400)!!, 1e-9)
    }

    @Test
    fun `distance and guide number round-trip`() {
        val gn = FlashMath.guideNumberM(fNumber = 5.6, distanceM = 3.0, iso = 100)!!
        assertEquals(3.0, FlashMath.distanceM(gn, 5.6, 100)!!, 1e-9)
    }

    @Test
    fun `non-positive inputs are rejected`() {
        assertNull(FlashMath.fNumber(0.0, 1.0, 100))
        assertNull(FlashMath.distanceM(32.0, 0.0, 100))
        assertNull(FlashMath.guideNumberM(5.6, -1.0, 100))
    }

    // ---- Depth of field ----

    @Test
    fun `hyperfocal formula`() {
        // 50mm, f/8, c=0.03mm: H = 50²/(8·0.03) mm ≈ 10.417 m + 0.05.
        val dof = DepthOfField(50.0, 8.0, 10.0, 0.030)
        assertEquals(50.0 * 50.0 / (8 * 0.030) / 1000.0 + 0.05, dof.hyperfocalM, 1e-6)
    }

    @Test
    fun `distance beyond hyperfocal reaches infinity`() {
        val dof = DepthOfField(50.0, 8.0, 30.0, 0.030)
        assertTrue(dof.farInfinite)
        assertNull(dof.farM)
    }

    @Test
    fun `near and far bracket the focus distance`() {
        val dof = DepthOfField(50.0, 5.6, 5.0, 0.030)
        assertTrue(dof.nearM < 5.0)
        assertTrue(dof.farM!! > 5.0)
        assertEquals(dof.farM!! - dof.nearM, dof.totalM!!, 1e-9)
    }

    @Test
    fun `narrower aperture deepens the field`() {
        val wide = DepthOfField(50.0, 2.0, 5.0, 0.030)
        val narrow = DepthOfField(50.0, 11.0, 5.0, 0.030)
        assertTrue(narrow.totalM!! > wide.totalM!!)
    }

    // ---- Latitude ----

    @Test
    fun `scene inside latitude leaves positive headroom`() {
        // Latitude spans EV 9..14 at placed 11; scene occupies 9.5..13.5.
        val placement = LatitudeMath.evaluate(
            sceneLowEv = 9.5,
            sceneHighEv = 13.5,
            placedEv100 = 11.0,
            latitudeLowStops = 2.0,
            latitudeHighStops = 3.0,
        )
        assertEquals(0.5, placement.highlightHeadroomStops, 1e-9)
        assertEquals(0.5, placement.shadowHeadroomStops, 1e-9)
        assertFalse(placement.clipsHighlights)
        assertFalse(placement.blocksShadows)
    }

    @Test
    fun `bright scene clips highlights`() {
        val placement = LatitudeMath.evaluate(
            sceneLowEv = 8.0,
            sceneHighEv = 15.0,
            placedEv100 = 11.0,
            latitudeLowStops = 2.0,
            latitudeHighStops = 3.0,
        )
        assertTrue(placement.clipsHighlights)
        assertEquals(-1.0, placement.highlightHeadroomStops, 1e-9)
    }

    @Test
    fun `formatStops renders signed stops`() {
        assertEquals("0", LatitudeMath.formatStops(0.0))
        assertEquals("+1.5", LatitudeMath.formatStops(1.5))
        assertEquals("+1.33", LatitudeMath.formatStops(4.0 / 3.0))
        assertEquals("−0.33", LatitudeMath.formatStops(-1.0 / 3.0))
        assertEquals("+2", LatitudeMath.formatStops(2.0))
    }
}
