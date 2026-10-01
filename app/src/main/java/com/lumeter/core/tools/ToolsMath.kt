package com.lumeter.core.tools

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure math for the tool panel: reciprocity failure, flash guide numbers, depth of
 * field and film latitude. No Android types; every result is in stops or seconds so
 * the UI layer only formats.
 */

/**
 * Reciprocity failure as a threshold power model: exposure time [thresholdSeconds] and
 * beyond picks up a correction; below it the film behaves normally. Corrected time
 * `t' = threshold · (t / threshold)^exponent` — continuous at the threshold, `exponent`
 * 1.0 means no failure. Fit from published data (+x stop at Ns ⇒ exponent = log2 of the
 * corrected/metered ratio at that point); approximations, not datasheet replacements.
 */
data class Reciprocity(
    val thresholdSeconds: Double,
    val exponent: Double,
) {
    /** Corrected exposure time for [meteredSeconds]; identity for non-positive input. */
    fun correctedSeconds(meteredSeconds: Double): Double {
        if (meteredSeconds <= 0.0 || exponent <= 1.0) return meteredSeconds
        if (meteredSeconds <= thresholdSeconds) return meteredSeconds
        return thresholdSeconds * (meteredSeconds / thresholdSeconds).pow(exponent)
    }

    /** The correction in stops (positive = give more light than metered). */
    fun correctionStops(meteredSeconds: Double): Double {
        val corrected = correctedSeconds(meteredSeconds)
        if (corrected <= 0.0 || meteredSeconds <= 0.0) return 0.0
        return kotlin.math.ln(corrected / meteredSeconds) / kotlin.math.ln(2.0)
    }

    companion object {
        /** Convenience constructor for films quoted as "+N stop at T seconds". */
        fun fromStopAt(stops: Double, atSeconds: Double): Reciprocity {
            if (stops <= 0.0 || atSeconds <= 1.0) return Reciprocity(1.0, 1.0)
            val corrected = atSeconds * 2.0.pow(stops)
            // t^exponent == corrected at t == atSeconds, and 1s stays uncorrected.
            val exponent = kotlin.math.ln(corrected) / kotlin.math.ln(atSeconds)
            return Reciprocity(thresholdSeconds = 1.0, exponent = exponent)
        }

        val NONE = Reciprocity(thresholdSeconds = 1.0, exponent = 1.0)
    }
}

/** Flash guide numbers. GN is in meters, normalized to ISO 100. */
object FlashMath {

    /** f-number delivering full flash output at [distanceM] for [iso]. */
    fun fNumber(guideNumberM: Double, distanceM: Double, iso: Int): Double? {
        if (guideNumberM <= 0.0 || distanceM <= 0.0 || iso <= 0) return null
        return guideNumberM * isoScale(iso) / distanceM
    }

    /** Working distance for a chosen [fNumber]. */
    fun distanceM(guideNumberM: Double, fNumber: Double, iso: Int): Double? {
        if (guideNumberM <= 0.0 || fNumber <= 0.0 || iso <= 0) return null
        return guideNumberM * isoScale(iso) / fNumber
    }

    /** GN (ISO 100, meters) implied by a shot at [fNumber] and [distanceM]. */
    fun guideNumberM(fNumber: Double, distanceM: Double, iso: Int): Double? {
        if (fNumber <= 0.0 || distanceM <= 0.0 || iso <= 0) return null
        return fNumber * distanceM / isoScale(iso)
    }

    /**
     * Ambient-vs-flash balance: how many stops the ambient-required aperture sits
     * from the flash-required one. Positive = ambient demands far more stop-down, so
     * the flash output dwarfs it (flash dominant); negative = the flash barely
     * registers against the ambient light.
     */
    fun mixVerdict(ambientFNumber: Double, flashFNumber: Double): FlashMixVerdict? {
        if (ambientFNumber <= 0.0 || flashFNumber <= 0.0) return null
        val stops = kotlin.math.ln(ambientFNumber / flashFNumber) / kotlin.math.ln(2.0)
        val mix = when {
            stops >= 1.0 -> FlashMix.FLASH_DOMINANT
            stops <= -1.0 -> FlashMix.AMBIENT_DOMINANT
            else -> FlashMix.BALANCED
        }
        return FlashMixVerdict(stops, mix)
    }

    private fun isoScale(iso: Int): Double = sqrt(iso / 100.0)
}

enum class FlashMix { FLASH_DOMINANT, BALANCED, AMBIENT_DOMINANT }

data class FlashMixVerdict(val stops: Double, val mix: FlashMix)

/** Depth of field on the thin-lens model with circle of confusion. */
data class DepthOfField(
    val focalLengthMm: Double,
    val aperture: Double,
    val distanceM: Double,
    val circleOfConfusionMm: Double,
) {
    /** Hyperfocal distance in meters: H = f²/(N·c) + f. */
    val hyperfocalM: Double
        get() {
            val f = focalLengthMm
            val h = f * f / (aperture * circleOfConfusionMm) + f
            return h / 1000.0
        }

    val nearM: Double
        get() = limit(denominator = hyperfocalM + (distanceM - focalLengthMm / 1000.0))

    /** Infinity when the far limit denominator collapses (distance past hyperfocal). */
    val farInfinite: Boolean
        get() = hyperfocalM <= distanceM - focalLengthMm / 1000.0

    val farM: Double?
        get() = if (farInfinite) {
            null
        } else {
            limit(denominator = hyperfocalM - (distanceM - focalLengthMm / 1000.0))
        }

    val totalM: Double?
        get() = farM?.let { it - nearM }

    private fun limit(denominator: Double): Double {
        if (denominator <= 0.0) return Double.POSITIVE_INFINITY
        return hyperfocalM * distanceM / denominator
    }

    companion object {
        /** Common COC presets, mm. */
        val COC_PRESETS = linkedMapOf(
            "FF 35mm" to 0.030,
            "APS-C" to 0.019,
            "MFT" to 0.015,
            "6×4.5" to 0.051,
            "6×6" to 0.060,
            "6×7" to 0.072,
        )
    }
}

/**
 * Scene-range vs film latitude: given the darkest and brightest spot EVs and the EV the
 * meter finally recommends, how much highlight/shadow headroom is left inside the film's
 * usable range. Latitude spans [latitudeLowStops] below and [latitudeHighStops] above
 * the placed mid (zone V).
 */
data class LatitudePlacement(
    val highlightHeadroomStops: Double,
    val shadowHeadroomStops: Double,
    val clipsHighlights: Boolean,
    val blocksShadows: Boolean,
)

object LatitudeMath {
    fun evaluate(
        sceneLowEv: Double,
        sceneHighEv: Double,
        placedEv100: Double,
        latitudeLowStops: Double,
        latitudeHighStops: Double,
    ): LatitudePlacement {
        // Headroom = (limit − occupied), in stops. Highlight limit sits ABOVE the placed
        // EV; the brightest spot must stay under it.
        val highlightLimit = placedEv100 + latitudeHighStops
        val shadowLimit = placedEv100 - latitudeLowStops
        val highlightHeadroom = highlightLimit - sceneHighEv
        val shadowHeadroom = sceneLowEv - shadowLimit
        return LatitudePlacement(
            highlightHeadroomStops = highlightHeadroom,
            shadowHeadroomStops = shadowHeadroom,
            clipsHighlights = highlightHeadroom < 0,
            blocksShadows = shadowHeadroom < 0,
        )
    }

    /** Signed stops readout, two decimals with trailing zeros trimmed. */
    fun formatStops(stops: Double): String {
        val v = kotlin.math.round(stops * 100) / 100
        val text = if (v == kotlin.math.floor(v)) {
            v.toInt().toString()
        } else {
            v.toString()
        }
        return when {
            v > 0 -> "+$text"
            v < 0 -> "−${text.substring(1)}"
            else -> "0"
        }
    }
}
