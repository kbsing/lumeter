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

/** Built-in film stocks */
object FilmStocks {
    val ALL = listOf(
        FilmStock("Kodak Portra 400", 400, FilmType.COLOUR_NEGATIVE, "reciprocity_portra"),
        FilmStock("Ilford HP5 Plus", 400, FilmType.BW_NEGATIVE, "reciprocity_hp5"),
        FilmStock("Fuji Velvia 50", 50, FilmType.COLOUR_SLIDE, "reciprocity_velvia"),
        FilmStock("Kodak Tri-X 400", 400, FilmType.BW_NEGATIVE, "reciprocity_trix"),
        FilmStock("CineStill 800T", 800, FilmType.TUNGSTEN_NEGATIVE, "reciprocity_cinestill"),
        FilmStock("Ilford Delta 100", 100, FilmType.BW_NEGATIVE, "reciprocity_delta"),
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
