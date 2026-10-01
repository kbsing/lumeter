package com.lumeter.core.exposure

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * L3 constraint + L4 solve + L5 attribution, as one pure function. No UI, no time
 * behavior — hysteresis and mode carry-over live beside it as separate pure helpers.
 *
 * One formula (Av+Tv grows → less light reaches the film):
 *   EReq = ev100 + log2(ISO/100) − bias − reciprocity
 *   EEff = Av + Tv + ND + tele + 2·log2(1+m)
 *   stops = EReq − EEff        (>0 overexposed, <0 underexposed)
 */
object ExposureEngine {

    /** Display/solve dead zone: |stops| below this shows OK. */
    const val DEAD_ZONE_STOPS = 1.0 / 6.0

    /** Solve hysteresis: a new EV within this of the last adopted center re-solves nothing. */
    const val HYSTERESIS_STOPS = 1.0 / 6.0

    /** Suggestions appear beyond this deviation. */
    const val SUGGESTION_THRESHOLD_STOPS = 1.0 / 3.0

    private const val QUANTIZED_FLAG_THRESHOLD = 1.0 / 12.0

    fun av(aperture: Double): Double = 2 * log2(aperture)
    fun tv(shutterSeconds: Double): Double = -log2(shutterSeconds)

    fun evaluate(state: ExposureState, reading: MeterReading?): ExposureResult {
        if (reading == null || !reading.valid) {
            return ExposureResult(kind = ResultKind.NO_READING)
        }

        val accessory = state.ndStops + state.teleStops + 2 * log2(1 + state.magnification)
        val eReq = reading.ev100 + log2(state.iso / 100.0) - state.biasStops

        var aperture = state.aperture
        var shutter = state.shutter
        var quantized = false
        var quantizedResidual = 0.0
        // Reciprocity failure: the film needs MORE time (more light) than the meter's
        // answer once exposure passes the model's threshold. In Av+Tv terms the
        // requirement DROPS by the correction (lower Av+Tv = more light). In A the
        // extra time rides on the solved shutter; in S/M the lower requirement makes
        // the solved aperture open / the needle report the missing light.
        var reciprocityStops = 0.0
        val model = state.reciprocity

        when (state.mode) {
            ExposureMode.APERTURE_PRIORITY -> {
                val tvNeed = eReq - av(aperture) - accessory
                val idealShutter = 2.0.pow(-tvNeed)
                val corrected = model?.correctedSeconds(idealShutter) ?: idealShutter
                reciprocityStops = if (corrected > 0.0) log2(corrected / idealShutter) else 0.0
                val snapped = nearest(ExposureSolver.SHUTTERS, corrected)
                quantizedResidual = log2(snapped / corrected) // >0: slower than ideal
                shutter = snapped
            }
            ExposureMode.SHUTTER_PRIORITY -> {
                reciprocityStops = model?.correctionStops(shutter) ?: 0.0
                val avNeed = eReq - reciprocityStops - tv(shutter) - accessory
                val idealAperture = 2.0.pow(avNeed / 2)
                val snapped = nearest(ExposureSolver.APERTURES, idealAperture)
                quantizedResidual = log2(snapped / idealAperture) * 2 // in stops
                aperture = snapped
            }
            ExposureMode.MANUAL -> {
                reciprocityStops = model?.correctionStops(shutter) ?: 0.0
                // Both fixed; the residual is the whole output.
            }
        }
        quantized = state.mode != ExposureMode.MANUAL &&
            abs(quantizedResidual) > QUANTIZED_FLAG_THRESHOLD

        val eEff = av(aperture) + tv(shutter) + accessory
        val raw = (eReq - reciprocityStops) - eEff
        val stops = if (abs(raw) < DEAD_ZONE_STOPS) 0.0 else raw

        val suggestions = if (abs(stops) > SUGGESTION_THRESHOLD_STOPS) {
            buildSuggestions(state, stops)
        } else {
            emptyList()
        }

        return ExposureResult(
            kind = ResultKind.OK,
            solvedAperture = if (state.mode == ExposureMode.SHUTTER_PRIORITY) aperture else null,
            solvedShutter = if (state.mode == ExposureMode.APERTURE_PRIORITY) shutter else null,
            stops = stops,
            causes = Causes(
                clamped = false, // no equipment capability table yet: full scale assumed
                quantized = quantized,
                bias = state.biasStops,
                accessory = accessory,
                quantizedResidual = quantizedResidual,
                reciprocity = reciprocityStops,
            ),
            suggestions = suggestions,
        )
    }

    /**
     * Solve hysteresis: only adopt `candidate` as the new solving center when it moved
     * beyond [HYSTERESIS_STOPS] from the last adopted value. Prevents stop-boundary
     * chatter when EV sits between two scale marks.
     */
    fun adoptEv(lastAdopted: Double?, candidate: Double): Double =
        if (lastAdopted != null && abs(candidate - lastAdopted) < HYSTERESIS_STOPS) {
            lastAdopted
        } else {
            candidate
        }

    /**
     * Mode carry-over: switching modes keeps E_eff unchanged by turning the solved value
     * into the newly-fixed user input (A→S hands over the solved shutter, S→A the
     * aperture, →M keeps both as-is).
     */
    fun carryOver(
        current: ExposureState,
        lastResult: ExposureResult?,
        newMode: ExposureMode,
    ): ExposureState {
        if (newMode == current.mode || lastResult == null) {
            return current.copy(mode = newMode)
        }
        return when (newMode) {
            ExposureMode.SHUTTER_PRIORITY ->
                current.copy(
                    mode = newMode,
                    shutter = lastResult.solvedShutter ?: current.shutter,
                )
            ExposureMode.APERTURE_PRIORITY ->
                current.copy(
                    mode = newMode,
                    aperture = lastResult.solvedAperture ?: current.aperture,
                )
            ExposureMode.MANUAL ->
                current.copy(
                    mode = newMode,
                    shutter = lastResult.solvedShutter ?: current.shutter,
                    aperture = lastResult.solvedAperture ?: current.aperture,
                )
        }
    }

    /** Ways out, ordered by cost: ISO, then the fixed parameter, then ND, then accept. */
    private fun buildSuggestions(state: ExposureState, stops: Double): List<Suggestion> {
        val list = mutableListOf<Suggestion>()
        // ISO that cancels the deviation (rounded to a real ISO stop).
        val targetIsoLog = log2(state.iso / 100.0) - stops
        val idealIso = 100 * 2.0.pow(targetIsoLog)
        val isoCandidate = ExposureSolver.nearestIso(idealIso.roundToInt())
        if (isoCandidate != state.iso) {
            list += Suggestion(SuggestionKind.ISO, "ISO $isoCandidate")
        }
        when (state.mode) {
            ExposureMode.APERTURE_PRIORITY -> {
                // Underexposed → open up; overexposed → stop down.
                val ideal = state.aperture * 2.0.pow(-stops / 2)
                val snapped = nearest(ExposureSolver.APERTURES, ideal)
                if (snapped != state.aperture) {
                    list += Suggestion(
                        SuggestionKind.APERTURE,
                        "f/${formatAperture(snapped)}",
                    )
                }
            }
            ExposureMode.SHUTTER_PRIORITY -> {
                val ideal = state.shutter * 2.0.pow(-stops)
                val snapped = nearest(ExposureSolver.SHUTTERS, ideal)
                if (snapped != state.shutter) {
                    list += Suggestion(SuggestionKind.SHUTTER, formatShutter(snapped))
                }
            }
            ExposureMode.MANUAL -> {}
        }
        if (state.ndStops > 0 && stops < 0) {
            val keep = state.ndStops - kotlin.math.round(stops).toInt().coerceAtLeast(0)
            if (keep < state.ndStops) {
                list += Suggestion(SuggestionKind.ND, if (keep == 0) "ND off" else "-${state.ndStops - keep} ND")
            }
        }
        list += Suggestion(
            SuggestionKind.ACCEPT,
            (if (stops > 0) "+" else "\u2212") +
                String.format(java.util.Locale.US, "%.1f", abs(stops)) + " EV",
        )
        return list
    }

    /**
     * Equivalent exposure pairs around [centerAperture]: for every aperture within
     * +/- [spanStops] stops, the shutter that delivers the same EV (snapped to the
     * scale). For trading depth of field against hand-hold limits.
     */
    fun equivalentPairs(
        state: ExposureState,
        reading: MeterReading?,
        centerAperture: Double,
        spanStops: Int = 4,
    ): List<Pair<Double, Double>> {
        if (reading == null || !reading.valid) return emptyList()
        val eReq = reading.ev100 + log2(state.iso / 100.0) - state.biasStops
        val acc = accessoryOf(state)
        val model = state.reciprocity
        val center = ExposureSolver.APERTURES.indices.minByOrNull {
            abs(log2(ExposureSolver.APERTURES[it] / centerAperture))
        } ?: return emptyList()
        val half = spanStops * 3 // third-stop indices
        return ((center - half).coerceAtLeast(0)..(center + half)
            .coerceAtMost(ExposureSolver.APERTURES.lastIndex))
            .mapNotNull { i ->
                val aperture = ExposureSolver.APERTURES[i]
                val t = 2.0.pow(-(eReq - av(aperture) - acc))
                val corrected = model?.correctedSeconds(t) ?: t
                aperture to nearest(ExposureSolver.SHUTTERS, corrected)
            }
    }

    private fun accessoryOf(state: ExposureState): Double =
        state.ndStops + state.teleStops + 2 * log2(1 + state.magnification)

    fun nearest(items: List<Double>, target: Double): Double =
        items.minByOrNull { abs(log2(it / target)) } ?: items.first()

    fun formatAperture(value: Double): String =
        if (value < 0.99) {
            "0.95"
        } else if (value >= 10.0 || abs(value - value.toInt()) < 0.01) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }

    fun formatShutter(seconds: Double): String =
        if (seconds >= 1.0) {
            if (seconds.toInt().toDouble() == seconds) "${seconds.toInt()}s" else "$seconds s"
        } else {
            "1/${kotlin.math.round(1.0 / seconds).toInt()}"
        }
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
