package com.lumeter.core.meter

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneMathTest {

    @Test
    fun `placing at zone V shifts nothing`() {
        assertEquals(0.0, ZoneMath.evShiftForPlacement(5), 1e-9)
    }

    @Test
    fun `placing follows one stop per zone`() {
        // Shadows at zone III = overexpose two stops from the meter's EV.
        assertEquals(2.0, ZoneMath.evShiftForPlacement(3), 1e-9)
        // Snow at zone VIII = three stops more light than indicated.
        assertEquals(-3.0, ZoneMath.evShiftForPlacement(8), 1e-9)
        assertEquals(5.0, ZoneMath.evShiftForPlacement(0), 1e-9)
        assertEquals(-5.0, ZoneMath.evShiftForPlacement(10), 1e-9)
    }

    @Test
    fun `spot zones measure from the anchor`() {
        // Spot one stop brighter than the placed reading lands at zone VI.
        assertEquals(6.0, ZoneMath.zoneForSpot(spotEv = 9.0, anchorEv = 8.0), 1e-9)
        // Two stops darker → zone III.
        assertEquals(3.0, ZoneMath.zoneForSpot(spotEv = 6.0, anchorEv = 8.0), 1e-9)
    }

    @Test
    fun `placement and spot-zone are inverse views`() {
        val spot = 12.5
        val anchor = 11.0
        // Before placement the spot sits at zone VI (halfway to VII).
        assertEquals(6.5, ZoneMath.zoneForSpot(spot, anchor), 1e-9)
        // Placing the anchor area at zone VII lifts every other area two zones too.
        val placedAnchor = anchor + ZoneMath.evShiftForPlacement(7)
        assertEquals(8.5, ZoneMath.zoneForSpot(spot, placedAnchor), 1e-9)
    }

    @Test
    fun `zone index clamps into the scale`() {
        assertEquals(0, ZoneMath.zoneIndex(-4.0))
        assertEquals(10, ZoneMath.zoneIndex(11.5))
        assertEquals(6, ZoneMath.zoneIndex(6.2))
    }

    @Test
    fun `labels cover the full scale`() {
        assertEquals(ZoneMath.ZONE_COUNT, ZoneMath.LABELS.size)
        assertEquals("0", ZoneMath.LABELS.first())
        assertEquals("X", ZoneMath.LABELS.last())
        assertEquals("V", ZoneMath.LABELS[ZoneMath.MID_ZONE])
    }
}
