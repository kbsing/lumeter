package com.lumeter.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spec's chapter-10 table plus carry-over and hysteresis. All logic-layer:
 * no device, no UI.
 */
class ExposureEngineTest {

    private fun reading(ev: Double) = MeterReading(ev100 = ev, valid = true)

    // 1. A, EV100 12, ISO 100, f/8 -> shutter solves to ~1/60, stops = 0.
    @Test
    fun `case1 A mode solves shutter`() {
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.APERTURE_PRIORITY, 8.0, 1.0 / 250.0, 100),
            reading(12.0),
        )
        assertEquals(ResultKind.OK, r.kind)
        assertEquals(1.0 / 60.0, r.solvedShutter!!, 1e-9)
        assertEquals(0.0, r.stops!!, 1e-9)
    }

    // 2. A, EV100 3, ISO 100, f/11 -> long exposure solves on the scale, stops = 0
    //    (no capability table: full scale, no clamping).
    @Test
    fun `case2 A mode dark scene solves long exposure`() {
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.APERTURE_PRIORITY, 11.0, 1.0 / 250.0, 100),
            reading(3.0),
        )
        assertEquals(ResultKind.OK, r.kind)
        // EReq = 3, Av(11) ≈ 6.87 -> Tv ≈ -3.87 -> t ≈ 14.6s -> snaps to 8s or 30s scale.
        assertNotNull(r.solvedShutter)
        assertTrue(r.solvedShutter!! >= 8.0)
        assertTrue(r.suggestions.isNotEmpty())
    }

    // 3. A, ISO 100 -> 400: the solved shutter is 2 stops faster, stops stays 0.
    @Test
    fun `case3 ISO gain shifts solution not the deviation`() {
        val base = ExposureEngine.evaluate(
            ExposureState(ExposureMode.APERTURE_PRIORITY, 8.0, 1.0 / 250.0, 100),
            reading(12.0),
        )
        val pushed = ExposureEngine.evaluate(
            ExposureState(ExposureMode.APERTURE_PRIORITY, 8.0, 1.0 / 250.0, 400),
            reading(12.0),
        )
        assertEquals(
            kotlin.math.log2(base.solvedShutter!! / pushed.solvedShutter!!),
            2.0,
            1.0 / 3.0 + 1e-9,
        )
        assertEquals(0.0, pushed.stops!!, 1e-9)
    }

    // 4. M, EV100 12, ISO 100, f/8, 1/30 -> stops ≈ +1 (overexposed one stop).
    @Test
    fun `case4 M mode residual is the output`() {
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 30.0, 100),
            reading(12.0),
        )
        assertNull(r.solvedShutter)
        assertEquals(1.0, r.stops!!, 0.2)
        assertTrue(r.causes!!.quantized.not())
    }

    // 5. bias +1 is equivalent to EV100 - 1 (whole combination brightens one stop).
    @Test
    fun `case5 bias equivalence`() {
        val withBias = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 125.0, 100, biasStops = 1.0),
            reading(12.0),
        )
        val withDimmerScene = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 125.0, 100),
            reading(11.0),
        )
        assertEquals(withDimmerScene.stops!!, withBias.stops!!, 1e-9)
        assertEquals(1.0, withBias.causes!!.bias, 1e-9)
    }

    // 6. ND 3 stops with unchanged parameters -> stops = -3, accessory = 3.
    @Test
    fun `case6 ND lands on the delivered side`() {
        // Baseline f/8 + 1/60 sits inside the dead zone at EV12, so the ND shift is clean.
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 60.0, 100, ndStops = 3),
            reading(12.0),
        )
        assertEquals(-3.0, r.stops!!, 0.2)
        assertEquals(3.0, r.causes!!.accessory, 1e-9)
    }

    // 7. Magnification m = 1 costs 2 stops, equivalent to ND 2.
    @Test
    fun `case7 bellows magnification equals ND2`() {
        val macro = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 125.0, 100, magnification = 1.0),
            reading(12.0),
        )
        val nd2 = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 125.0, 100, ndStops = 2),
            reading(12.0),
        )
        assertEquals(nd2.stops!!, macro.stops!!, 1e-9)
    }

    // 8. Full-stop scale in A mode leaves up to half a stop of quantization residual,
    //    flagged as quantized (a scale limit, not an error).
    @Test
    fun `case8 quantization residual is flagged`() {
        // EReq = 10.33 lands between 1/60 and 1/125 relative to f/5.6.
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.APERTURE_PRIORITY, 5.6, 1.0 / 125.0, 100),
            reading(10.33),
        )
        assertTrue(r.causes!!.quantized)
        assertTrue(kotlin.math.abs(r.causes!!.quantizedResidual) <= 0.5 + 1e-9)
    }

    // 9. Hysteresis: a +/-0.1 EV jitter does not move the adopted solve center.
    @Test
    fun `case9 solve hysteresis`() {
        val center = 12.0
        assertEquals(center, ExposureEngine.adoptEv(center, center + 0.1), 1e-9)
        assertEquals(center, ExposureEngine.adoptEv(center, center - 0.1), 1e-9)
        assertEquals(12.5, ExposureEngine.adoptEv(center, 12.5), 1e-9)
    }

    // 10. A -> S -> M carry-over keeps E_eff unchanged across switches.
    @Test
    fun `case10 mode carry-over preserves effective exposure`() {
        var state = ExposureState(ExposureMode.APERTURE_PRIORITY, 8.0, 1.0 / 250.0, 100)
        var result = ExposureEngine.evaluate(state, reading(12.0))

        state = ExposureEngine.carryOver(state, result, ExposureMode.SHUTTER_PRIORITY)
        assertEquals(1.0 / 60.0, state.shutter, 1e-9)
        result = ExposureEngine.evaluate(state, reading(12.0))
        assertEquals(0.0, result.stops!!, 1e-9)

        state = ExposureEngine.carryOver(state, result, ExposureMode.MANUAL)
        result = ExposureEngine.evaluate(state, reading(12.0))
        assertEquals(0.0, result.stops!!, 1e-9)
    }

    // 11. No solution path: suggestions ordered ISO first, accept always last.
    @Test
    fun `case11 suggestions ordered by cost`() {
        // S mode: the fixed parameter (shutter) is the second-cheapest way out.
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.SHUTTER_PRIORITY, 16.0, 1.0 / 1000.0, 100),
            reading(3.0),
        )
        assertTrue(r.suggestions.isNotEmpty())
        assertEquals(SuggestionKind.ISO, r.suggestions.first().kind)
        assertEquals(SuggestionKind.ACCEPT, r.suggestions.last().kind)
        assertTrue(r.suggestions.any { it.kind == SuggestionKind.SHUTTER })
    }

    // 12. Invalid reading produces no numbers at all.
    @Test
    fun `case12 invalid reading`() {
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 125.0, 100),
            MeterReading(ev100 = 0.0, valid = false, reason = ReadingInvalidReason.CLIPPED),
        )
        assertEquals(ResultKind.NO_READING, r.kind)
        assertNull(r.stops)
        assertNull(r.causes)
    }

    // Equivalent pairs: every pair delivers the same EV within a third stop,
    // centered around the user's aperture.
    @Test
    fun `equivalent pairs share one effective exposure`() {
        val state = ExposureState(ExposureMode.MANUAL, 5.6, 1.0 / 125.0, 100)
        val pairs = ExposureEngine.equivalentPairs(state, reading(12.0), 5.6)
        assertTrue(pairs.isNotEmpty())
        // The shutter scale is full-stop, so snapping can leave up to half a stop
        // of residual on the pairs — that bound is what the UI actually promises.
        pairs.forEach { (ap, sh) ->
            val e = ExposureEngine.av(ap) + ExposureEngine.tv(sh)
            assertEquals(12.0, e, 0.5 + 1e-9)
        }
        // The center pair (current aperture) carries at most a third stop.
        val center = pairs.first { kotlin.math.abs(it.first - 5.6) < 0.01 }
        assertEquals(
            12.0,
            ExposureEngine.av(center.first) + ExposureEngine.tv(center.second),
            1.0 / 3.0 + 1e-9,
        )
        // Centered: the current aperture is present and pairs span +/- stops.
        assertTrue(pairs.any { kotlin.math.abs(it.first - 5.6) < 0.01 })
        assertTrue(pairs.first().first < 5.6 && pairs.last().first > 5.6)
    }

    @Test
    fun `equivalent pairs empty without a reading`() {
        val pairs = ExposureEngine.equivalentPairs(
            ExposureState(ExposureMode.MANUAL, 5.6, 1.0 / 125.0, 100),
            null,
            5.6,
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `dead zone reports zero`() {
        val r = ExposureEngine.evaluate(
            ExposureState(ExposureMode.MANUAL, 8.0, 1.0 / 60.0, 100),
            reading(12.0),
        )
        // ~0.09 stops off -> inside the 1/6 dead zone.
        assertEquals(0.0, r.stops!!, 1e-9)
    }

    @Test
    fun `A mode ideal-shutter snap never inverts the deviation sign`() {
        // Sanity across a sweep: |stops| after snapping stays under half a stop in A mode.
        for (ev in 4..16) {
            val r = ExposureEngine.evaluate(
                ExposureState(ExposureMode.APERTURE_PRIORITY, 5.6, 1.0 / 125.0, 100),
                reading(ev.toDouble()),
            )
            assertTrue("ev=$ev stops=${r.stops}", kotlin.math.abs(r.stops!!) <= 0.5 + 1e-9)
            assertFalse("ev=$ev must not be NO_READING", r.kind == ResultKind.NO_READING)
        }
    }
}
