package com.lumeter.core.exposure

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A/S/M exposure solving, mirroring the LUMEN MK·I reference model.
 *
 * The camera-style modes drive which parameter is locked and which is solved:
 * - APERTURE_PRIORITY: aperture locked, shutter computed then snapped to the scale; the
 *   needle shows how far the ideal shutter is from the snapped one.
 * - SHUTTER_PRIORITY: shutter locked, aperture computed then snapped.
 * - MANUAL: both manual; the needle shows scene EV (at the working ISO) minus the set exposure.
 */
enum class ExposureMode {
    APERTURE_PRIORITY,
    SHUTTER_PRIORITY,
    MANUAL,
}

data class SolverResult(
    val shutter: Double,
    val aperture: Double,
    val shutterSnapped: Double,
    val apertureSnapped: Double,
    /** EV deviation driving the ±3 needle; positive = scene needs more exposure than set. */
    val diff: Double,
)

object ExposureSolver {

    /** Reference scale stops (full-stop camera conventions from the design). */
    val ISOS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    val APERTURES = listOf(
        0.95, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8,
        3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0,
        11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0,
    )
    val SHUTTERS = listOf(
        1.0 / 2000.0, 1.0 / 1000.0, 1.0 / 500.0, 1.0 / 250.0, 1.0 / 125.0,
        1.0 / 60.0, 1.0 / 30.0, 1.0 / 15.0, 1.0 / 8.0, 1.0 / 4.0,
        1.0 / 2.0, 1.0, 2.0, 4.0, 8.0,
        // Long-exposure tail (reciprocity territory) in ~third-stop steps: full stops
        // past 30s span too much once corrections land between the marks.
        15.0, 30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 125.0,
        160.0, 200.0, 250.0, 320.0, 400.0, 480.0,
    )
    val ND_FILTERS = listOf(0, 3, 6, 10)

    const val NEEDLE_RANGE_EV = 3.0
    const val MATCH_TOLERANCE_EV = 0.35

    fun solve(mode: ExposureMode, aperture: Double, shutter: Double, evIso: Double): SolverResult {
        val shutters = SHUTTERS
        val apertures = APERTURES
        return when (mode) {
            ExposureMode.APERTURE_PRIORITY -> {
                val t = aperture * aperture / 2.0.pow(evIso)
                val snapped = nearest(shutters, t)
                SolverResult(t, aperture, snapped, aperture, log2(t / snapped))
            }
            ExposureMode.SHUTTER_PRIORITY -> {
                val n = sqrt(shutter * 2.0.pow(evIso))
                val snapped = nearest(apertures, n)
                SolverResult(shutter, n, shutter, snapped, -log2(n * n / (snapped * snapped)))
            }
            ExposureMode.MANUAL -> {
                val set = log2(aperture * aperture / shutter)
                SolverResult(shutter, aperture, shutter, aperture, evIso - set)
            }
        }
    }

    /** Needle position clamped to the ±3 scale. */
    fun needle(diff: Double): Double = diff.coerceIn(-NEEDLE_RANGE_EV, NEEDLE_RANGE_EV)

    fun isMatched(diff: Double): Boolean = abs(diff) < MATCH_TOLERANCE_EV

    /** EV of the working ISO: evEff (compensated scene EV) shifted by the ISO gain. */
    fun evAtIso(evEff: Double, iso: Int): Double = evEff + log2(iso / 100.0)

    /** Calibration-constant shift for reflected metering: K=12.5 is the neutral point. */
    fun calibrationShift(kConstant: Double): Double = log2(kConstant / 12.5)

    /** Rough scene illuminance display value, as on the reference viewfinder. */
    fun approxLux(ev100: Double, ndStops: Int): Double =
        2.0.pow(ev100 + ndStops) * 2.5

    fun nearest(items: List<Double>, target: Double): Double =
        items.minByOrNull { abs(log2(it / target)) } ?: items.first()

    fun nearestIso(iso: Int): Int =
        ISOS.minByOrNull { abs(log2(it.toDouble() / iso)) } ?: ISOS.first()
}
