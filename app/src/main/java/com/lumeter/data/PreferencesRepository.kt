package com.lumeter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.meter.MeteringMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lumeter_preferences")

class PreferencesRepository(private val context: Context) {

    companion object {
        // Exposure settings
        private val ISO = intPreferencesKey("iso")
        private val APERTURE = doublePreferencesKey("aperture")
        private val SHUTTER = doublePreferencesKey("shutter")
        private val EXPOSURE_MODE = stringPreferencesKey("exposure_mode")

        // Metering settings
        private val METERING_MODE = stringPreferencesKey("metering_mode")
        private val IS_LIVE_METERING = booleanPreferencesKey("is_live_metering")
        private val USE_THIRD_STOPS = booleanPreferencesKey("use_third_stops")

        // Calibration settings. The EV offset is per metering source (YUV and RAW
        // read different system constants); the legacy key feeds both on migration.
        private val CALIBRATION_OFFSET_YUV = doublePreferencesKey("calibration_offset_yuv")
        private val CALIBRATION_OFFSET_RAW = doublePreferencesKey("calibration_offset_raw")
        private val CALIBRATION_OFFSET_LEGACY = doublePreferencesKey("calibration_offset")
        private val K_CONSTANT = doublePreferencesKey("k_constant")

        // UI settings
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")

        // Metering source
        private val RAW_MODE = booleanPreferencesKey("raw_mode")
        private val HISTOGRAM_ENABLED = booleanPreferencesKey("histogram_enabled")
        private val SPOT_DRAGGABLE = booleanPreferencesKey("spot_draggable")
        private val CURRENT_FILM = stringPreferencesKey("current_film_stock")
        private val CURRENT_ROLL_ID = longPreferencesKey("current_roll_id")
    }

    // Read preferences
    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            iso = prefs[ISO] ?: 400,
            aperture = prefs[APERTURE] ?: 5.6,
            shutter = prefs[SHUTTER] ?: (1.0 / 250.0),
            exposureMode = prefs[EXPOSURE_MODE]?.let { runCatching { ExposureMode.valueOf(it) }.getOrNull() }
                ?: ExposureMode.APERTURE_PRIORITY,
            meteringMode = prefs[METERING_MODE]?.let { runCatching { MeteringMode.valueOf(it) }.getOrNull() }
                ?: MeteringMode.MATRIX,
            isLiveMetering = prefs[IS_LIVE_METERING] ?: true,
            useThirdStops = prefs[USE_THIRD_STOPS] ?: false,
            calibrationOffsetYuv = prefs[CALIBRATION_OFFSET_YUV]
                ?: prefs[CALIBRATION_OFFSET_LEGACY] ?: 0.0,
            calibrationOffsetRaw = prefs[CALIBRATION_OFFSET_RAW]
                ?: prefs[CALIBRATION_OFFSET_LEGACY] ?: 0.0,
            kConstant = prefs[K_CONSTANT] ?: 12.5,
            accentName = prefs[ACCENT_COLOR] ?: "AMBER",
            hapticsEnabled = prefs[HAPTICS_ENABLED] ?: true,
            rawMode = prefs[RAW_MODE] ?: false,
            histogramEnabled = prefs[HISTOGRAM_ENABLED] ?: false,
            spotDraggable = prefs[SPOT_DRAGGABLE] ?: true,
            currentFilmStock = prefs[CURRENT_FILM],
            currentRollId = prefs[CURRENT_ROLL_ID],
        )
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    suspend fun setIso(value: Int) = edit { it[ISO] = value }

    suspend fun setAperture(value: Double) = edit { it[APERTURE] = value }

    suspend fun setShutter(value: Double) = edit { it[SHUTTER] = value }

    suspend fun setExposureMode(mode: ExposureMode) = edit { it[EXPOSURE_MODE] = mode.name }

    suspend fun setMeteringMode(mode: MeteringMode) = edit { it[METERING_MODE] = mode.name }

    suspend fun setLiveMetering(enabled: Boolean) = edit { it[IS_LIVE_METERING] = enabled }

    suspend fun setUseThirdStops(enabled: Boolean) = edit { it[USE_THIRD_STOPS] = enabled }

    suspend fun setCalibrationOffsetYuv(value: Double) =
        edit { it[CALIBRATION_OFFSET_YUV] = value }

    suspend fun setCalibrationOffsetRaw(value: Double) =
        edit { it[CALIBRATION_OFFSET_RAW] = value }

    suspend fun setHistogramEnabled(enabled: Boolean) = edit { it[HISTOGRAM_ENABLED] = enabled }

    suspend fun setSpotDraggable(enabled: Boolean) = edit { it[SPOT_DRAGGABLE] = enabled }

    suspend fun setCurrentFilmStock(name: String?) = edit { prefs ->
        if (name == null) prefs.remove(CURRENT_FILM) else prefs[CURRENT_FILM] = name
    }

    suspend fun setCurrentRollId(id: Long?) = edit { prefs ->
        if (id == null) prefs.remove(CURRENT_ROLL_ID) else prefs[CURRENT_ROLL_ID] = id
    }

    suspend fun setKConstant(value: Double) = edit { it[K_CONSTANT] = value }

    suspend fun setAccentColor(name: String) = edit { it[ACCENT_COLOR] = name }

    suspend fun setHapticsEnabled(enabled: Boolean) = edit { it[HAPTICS_ENABLED] = enabled }

    suspend fun setRawMode(enabled: Boolean) = edit { it[RAW_MODE] = enabled }

    suspend fun resetCalibration() = edit {
        it[CALIBRATION_OFFSET_YUV] = 0.0
        it[CALIBRATION_OFFSET_RAW] = 0.0
        it[K_CONSTANT] = 12.5
    }
}
