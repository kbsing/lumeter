package com.lumeter.data

import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.meter.MeteringMode

/**
 * Application-level data models for UI features beyond the core metering engine.
 * Enum authority: metering/exposure enums live in `core`, accent colors in `ui.theme`.
 */

/** Which parameter is currently being adjusted */
enum class ActiveField {
    ISO,
    APERTURE,
    SHUTTER,
}

/** Multi-spot aggregation method */
enum class SpotAggregation {
    AVG,   // Average all spots
    HIGH,  // Use brightest spot (highlight protection)
    LOW,   // Use darkest spot (shadow retention)
}

/** A single spot measurement in multi-spot mode */
data class SpotMeasurement(
    val id: Long,
    val x: Float,       // Normalized [0,1] position in viewfinder
    val y: Float,
    val ev100: Double,  // Measured EV at this spot
)

/** Film stock information; type drives section grouping, reciprocity is a string-resource key. */
data class FilmStock(
    val name: String,
    val iso: Int,
    val type: FilmType,
    val reciprocityHint: String,
)

enum class FilmType {
    COLOUR_NEGATIVE,
    BW_NEGATIVE,
    COLOUR_SLIDE,
    TUNGSTEN_NEGATIVE,
}

/**
 * Built-in film stocks. The original six carry localized reciprocity resource keys
 * ("reciprocity_*"); later additions use common technical notation directly
 * ("+1 stop at 10s" style), which reads the same in any locale.
 */
object FilmStocks {
    val ALL = listOf(
        // Colour negative
        FilmStock("Kodak Portra 400", 400, FilmType.COLOUR_NEGATIVE, "reciprocity_portra"),
        FilmStock("Kodak Portra 800", 800, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Kodak Gold 200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Kodak ColorPlus 200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Kodak Ektar 100", 100, FilmType.COLOUR_NEGATIVE, "None up to 30s"),
        FilmStock("Kodak Ultramax 400", 400, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Fuji Superia X-TRA 400", 400, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Fuji C200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s"),
        // B&W negative
        FilmStock("Ilford HP5 Plus", 400, FilmType.BW_NEGATIVE, "reciprocity_hp5"),
        FilmStock("Kodak Tri-X 400", 400, FilmType.BW_NEGATIVE, "reciprocity_trix"),
        FilmStock("Kodak T-Max 100", 100, FilmType.BW_NEGATIVE, "+1/3 stop at 60s"),
        FilmStock("Kodak T-Max 400", 400, FilmType.BW_NEGATIVE, "+1/3 stop at 60s"),
        FilmStock("Kodak T-Max P3200", 3200, FilmType.BW_NEGATIVE, "+1/2 stop at 30s"),
        FilmStock("Ilford Delta 100", 100, FilmType.BW_NEGATIVE, "reciprocity_delta"),
        FilmStock("Ilford Delta 400", 400, FilmType.BW_NEGATIVE, "+1 stop at 10s"),
        FilmStock("Ilford Delta 3200", 3200, FilmType.BW_NEGATIVE, "+1/2 stop at 60s"),
        FilmStock("Ilford FP4 Plus", 125, FilmType.BW_NEGATIVE, "+1 stop at 10s"),
        FilmStock("Ilford Pan 400", 400, FilmType.BW_NEGATIVE, "+1 stop at 10s"),
        FilmStock("Ilford XP2 Super 400", 400, FilmType.BW_NEGATIVE, "+1/2 stop at 10s"),
        FilmStock("Fuji Neopan Acros 100", 100, FilmType.BW_NEGATIVE, "None up to 120s"),
        // Colour slide
        FilmStock("Fuji Velvia 50", 50, FilmType.COLOUR_SLIDE, "reciprocity_velvia"),
        FilmStock("Fuji Velvia 100", 100, FilmType.COLOUR_SLIDE, "+2/3 stop at 32s"),
        FilmStock("Fuji Provia 100F", 100, FilmType.COLOUR_SLIDE, "None up to 128s"),
        FilmStock("Kodak Ektachrome E100", 100, FilmType.COLOUR_SLIDE, "+1/2 stop at 10s"),
        // Tungsten / cinema
        FilmStock("CineStill 800T", 800, FilmType.TUNGSTEN_NEGATIVE, "reciprocity_cinestill"),
        FilmStock("CineStill 50D", 50, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s"),
        FilmStock("Kodak Vision3 500T", 500, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s"),
        FilmStock("Kodak Vision3 250D", 250, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s"),
    )
}

/** Snapshot of all persisted user preferences. */
data class UserPreferences(
    val iso: Int = 400,
    val aperture: Double = 5.6,
    val shutter: Double = 1.0 / 250.0,
    val exposureMode: ExposureMode = ExposureMode.APERTURE_PRIORITY,
    val meteringMode: MeteringMode = MeteringMode.MATRIX,
    val isLiveMetering: Boolean = true,
    val useThirdStops: Boolean = false,
    val calibrationOffset: Double = 0.0,
    val kConstant: Double = 12.5,
    val accentName: String = "AMBER",
    val hapticsEnabled: Boolean = true,
)
