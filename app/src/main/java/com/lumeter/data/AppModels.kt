package com.lumeter.data

import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.meter.MeteringMode
import com.lumeter.core.tools.Reciprocity

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
    /**
     * Threshold-power reciprocity model fit from the same published figures the hint
     * summarizes ([Reciprocity.NONE] when no correction applies on the quoted span).
     */
    val reciprocity: Reciprocity = Reciprocity.NONE,
    /** Usable latitude below/above the placed mid, in stops. */
    val latitudeLowStops: Double = 2.0,
    val latitudeHighStops: Double = 3.0,
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
    private val SLIDE_LATITUDE = 1.0 to 1.0 // ±1 stop is generous for slide film
    private val NEG_LATITUDE = 2.0 to 3.0

    val ALL = listOf(
        // Colour negative
        FilmStock(
            "Kodak Portra 400", 400, FilmType.COLOUR_NEGATIVE, "reciprocity_portra",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Portra 800", 800, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Gold 200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak ColorPlus 200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Ektar 100", 100, FilmType.COLOUR_NEGATIVE, "None up to 30s",
            Reciprocity(30.0, 1.2), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Ultramax 400", 400, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Fuji Superia X-TRA 400", 400, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Fuji C200", 200, FilmType.COLOUR_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        // B&W negative
        FilmStock(
            "Ilford HP5 Plus", 400, FilmType.BW_NEGATIVE, "reciprocity_hp5",
            Reciprocity.fromStopAt(1.0, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Kodak Tri-X 400", 400, FilmType.BW_NEGATIVE, "reciprocity_trix",
            Reciprocity.fromStopAt(1.0, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Kodak T-Max 100", 100, FilmType.BW_NEGATIVE, "+1/3 stop at 60s",
            Reciprocity.fromStopAt(1.0 / 3.0, 60.0), 2.5, 3.0,
        ),
        FilmStock(
            "Kodak T-Max 400", 400, FilmType.BW_NEGATIVE, "+1/3 stop at 60s",
            Reciprocity.fromStopAt(1.0 / 3.0, 60.0), 2.5, 3.0,
        ),
        FilmStock(
            "Kodak T-Max P3200", 3200, FilmType.BW_NEGATIVE, "+1/2 stop at 30s",
            Reciprocity.fromStopAt(0.5, 30.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford Delta 100", 100, FilmType.BW_NEGATIVE, "reciprocity_delta",
            Reciprocity.fromStopAt(0.5, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford Delta 400", 400, FilmType.BW_NEGATIVE, "+1 stop at 10s",
            Reciprocity.fromStopAt(1.0, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford Delta 3200", 3200, FilmType.BW_NEGATIVE, "+1/2 stop at 60s",
            Reciprocity.fromStopAt(0.5, 60.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford FP4 Plus", 125, FilmType.BW_NEGATIVE, "+1 stop at 10s",
            Reciprocity.fromStopAt(1.0, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford Pan 400", 400, FilmType.BW_NEGATIVE, "+1 stop at 10s",
            Reciprocity.fromStopAt(1.0, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Ilford XP2 Super 400", 400, FilmType.BW_NEGATIVE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), 2.5, 3.0,
        ),
        FilmStock(
            "Fuji Neopan Acros 100", 100, FilmType.BW_NEGATIVE, "None up to 120s",
            Reciprocity(120.0, 1.05), 2.5, 3.0,
        ),
        // Colour slide
        FilmStock(
            "Fuji Velvia 50", 50, FilmType.COLOUR_SLIDE, "reciprocity_velvia",
            Reciprocity.fromStopAt(1.0 / 3.0, 8.0), SLIDE_LATITUDE.first, SLIDE_LATITUDE.second,
        ),
        FilmStock(
            "Fuji Velvia 100", 100, FilmType.COLOUR_SLIDE, "+2/3 stop at 32s",
            Reciprocity.fromStopAt(2.0 / 3.0, 32.0), SLIDE_LATITUDE.first, SLIDE_LATITUDE.second,
        ),
        FilmStock(
            "Fuji Provia 100F", 100, FilmType.COLOUR_SLIDE, "None up to 128s",
            Reciprocity(128.0, 1.1), SLIDE_LATITUDE.first, SLIDE_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Ektachrome E100", 100, FilmType.COLOUR_SLIDE, "+1/2 stop at 10s",
            Reciprocity.fromStopAt(0.5, 10.0), SLIDE_LATITUDE.first, SLIDE_LATITUDE.second,
        ),
        // Tungsten / cinema
        FilmStock(
            "CineStill 800T", 800, FilmType.TUNGSTEN_NEGATIVE, "reciprocity_cinestill",
            Reciprocity.fromStopAt(0.5, 10.0), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "CineStill 50D", 50, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s",
            Reciprocity(2.0, 1.15), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Vision3 500T", 500, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s",
            Reciprocity(2.0, 1.15), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
        FilmStock(
            "Kodak Vision3 250D", 250, FilmType.TUNGSTEN_NEGATIVE, "None up to 2s",
            Reciprocity(2.0, 1.15), NEG_LATITUDE.first, NEG_LATITUDE.second,
        ),
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
    val calibrationOffsetYuv: Double = 0.0,
    val calibrationOffsetRaw: Double = 0.0,
    val kConstant: Double = 12.5,
    val accentName: String = "AMBER",
    val hapticsEnabled: Boolean = true,
    val rawMode: Boolean = false,
    val histogramEnabled: Boolean = false,
    val spotDraggable: Boolean = true,
    val currentFilmStock: String? = null,
)
