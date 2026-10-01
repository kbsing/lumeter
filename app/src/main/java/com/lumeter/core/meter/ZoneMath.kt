package com.lumeter.core.meter

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zone System placement math.
 *
 * One rule: Zone V is what the meter indicates; every zone is one stop. Placing a
 * metered area at zone N shifts the indicated EV100 by (N − V). Conversely, with the
 * final EV chosen, any spot's zone is V plus its EV distance from that anchor.
 */
object ZoneMath {

    /** The meter-indicated mid zone. */
    const val MID_ZONE = 5

    /** Zones 0..X inclusive. */
    const val ZONE_COUNT = 11

    /** Roman labels, index == zone number. */
    val LABELS = listOf("0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    /**
     * EV100 shift that places the metered area at [targetZone]. Placing anything ABOVE
     * zone V needs MORE exposure than indicated (snow at VIII: −3 EV), so the shift is
     * V − N.
     */
    fun evShiftForPlacement(targetZone: Int): Double = (MID_ZONE - targetZone).toDouble()

    /**
     * The zone a spot with [spotEv] falls at, given the anchor EV the meter finally
     * recommends (the placed reading). Fractional zones are meaningful for display.
     */
    fun zoneForSpot(spotEv: Double, anchorEv: Double): Double =
        MID_ZONE + (spotEv - anchorEv)

    /** Nearest whole zone index clamped into 0..X. */
    fun zoneIndex(zoneValue: Double): Int = (zoneValue.roundToInt()).coerceIn(0, ZONE_COUNT - 1)

    /** True when two zone positions should render as the same mark. */
    fun sameZone(a: Double, b: Double, tolerance: Double = 0.34): Boolean =
        abs(a - b) <= tolerance
}
