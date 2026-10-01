package com.lumeter.core.exposure

/**
 * Layered exposure model (L2–L5) per the metering spec.
 *
 * Discipline: the metering layer answers only "how bright is the scene" ([MeterReading],
 * ev100 with calibration, never ISO/aperture/shutter aware); the parameter layer answers
 * only "can I deliver that EV" ([evaluate] in [ExposureEngine]). The two layers talk
 * through exactly one number: EV100.
 */

/** L2 output: what the metering layer knows about the scene. */
data class MeterReading(
    /** Scene EV100 including the user calibration offset; ND/accessories NOT included. */
    val ev100: Double,
    val valid: Boolean,
    val reason: ReadingInvalidReason? = null,
    val timestampMs: Long = 0L,
    /** Fraction of metered pixels clipped at sensor top (drives saturation invalidity). */
    val clippedFraction: Double = 0.0,
)

enum class ReadingInvalidReason {
    CLIPPED,
    TOO_DARK,
    NO_SIGNAL,
    UNSTABLE,
}

/** L3/L4 input: everything the user set. */
data class ExposureState(
    val mode: ExposureMode,
    val aperture: Double,
    val shutter: Double,
    val iso: Int,
    /** +1 = deliberately overexpose one stop. */
    val biasStops: Double = 0.0,
    /** Light lost AFTER the meter: ND, teleconverter, bellows magnification. */
    val ndStops: Int = 0,
    val teleStops: Double = 0.0,
    val magnification: Double = 0.0,
)

enum class ResultKind { OK, NO_READING, NO_SOLUTION }

/** L5: the deviation split by origin, so "user intent" never reads as "app bug". */
data class Causes(
    val clamped: Boolean,
    val quantized: Boolean,
    val bias: Double,
    val accessory: Double,
    /** Residual left purely by snapping to the discrete scale, in stops. */
    val quantizedResidual: Double,
)

enum class SuggestionKind { ISO, APERTURE, SHUTTER, ND, ACCEPT }

/** One actionable way out, ordered by cost (ISO first). */
data class Suggestion(
    val kind: SuggestionKind,
    /** Technical notation, locale-neutral (e.g. "ISO 400", "f/2.8", "-2 ND", "+1.3 EV"). */
    val detail: String,
)

data class ExposureResult(
    val kind: ResultKind,
    /** The snapped value the solver produced for the free parameter (null in M). */
    val solvedAperture: Double? = null,
    val solvedShutter: Double? = null,
    /** Main reading: >0 overexposed, <0 underexposed, 0 inside the dead zone. */
    val stops: Double? = null,
    val causes: Causes? = null,
    val suggestions: List<Suggestion> = emptyList(),
)
