package com.lumeter.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumeter.camera.CameraManager
import com.lumeter.camera.MeterEngineState
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.exposure.ExposureSolver
import com.lumeter.core.exposure.SolverResult
import com.lumeter.core.meter.MeteringMode
import com.lumeter.data.ActiveField
import com.lumeter.data.FilmStock
import com.lumeter.data.FilmStocks
import com.lumeter.data.PreferencesRepository
import com.lumeter.data.Reading
import com.lumeter.data.ReadingRepository
import com.lumeter.data.LumeterDatabase
import com.lumeter.data.SpotAggregation
import com.lumeter.data.SpotMeasurement
import com.lumeter.ui.theme.AccentColor
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log2

/**
 * Main application state manager.
 * Coordinates UI state across all screens: meter, history, film, settings, calibration,
 * and bridges the metering engine + persistence.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application)
    private val readingRepo = ReadingRepository(
        LumeterDatabase.getDatabase(application).readingDao(),
    )
    private val cameraManager = CameraManager(application)

    // Navigation
    var currentPage by mutableStateOf(AppPage.METER)
        private set

    // Engine-derived state (published by CameraManager, throttled here)
    var engineSceneEv by mutableStateOf<Double?>(null)
        private set
    var engineSpotEvs by mutableStateOf<List<Double>>(emptyList())
        private set
    var aeConverged by mutableStateOf(false)
        private set
    var analysisWidth by mutableStateOf(0)
        private set
    var analysisHeight by mutableStateOf(0)
        private set
    var rotationDegrees by mutableStateOf(0)
        private set

    // Metering control
    private var _meteringMode by mutableStateOf(MeteringMode.MATRIX)
    val meteringMode: MeteringMode get() = _meteringMode
    var ndFilter by mutableStateOf(0)
        private set
    var aeHold by mutableStateOf(false)
        private set

    // Exposure settings
    private var _exposureMode by mutableStateOf(ExposureMode.APERTURE_PRIORITY)
    val exposureMode: ExposureMode get() = _exposureMode
    private var _activeField by mutableStateOf(ActiveField.APERTURE)
    val activeField: ActiveField get() = _activeField
    var userIso by mutableStateOf(400)
        private set
    var userAperture by mutableStateOf(5.6)
        private set
    var userShutter by mutableStateOf(1.0 / 250.0)
        private set
    var expComp by mutableStateOf(0.0)
        private set

    // Multi-spot metering (normalized viewfinder coordinates)
    var spots by mutableStateOf<List<SpotMeasurement>>(emptyList())
        private set
    private var _spotAggregation by mutableStateOf(SpotAggregation.AVG)
    val spotAggregation: SpotAggregation get() = _spotAggregation

    // History
    var logEntries by mutableStateOf<List<Reading>>(emptyList())
        private set

    // Settings
    var liveMetering by mutableStateOf(true)
        private set
    var thirdStopIncrements by mutableStateOf(false)
        private set
    private var _hapticFeedback by mutableStateOf(true)
    val hapticFeedback: Boolean get() = _hapticFeedback
    private var _accentColor by mutableStateOf(AccentColor.AMBER)
    val accentColor: AccentColor get() = _accentColor

    // Calibration
    private var _calOffset by mutableStateOf(0.0)
    val calOffset: Double get() = _calOffset
    private var _kConstant by mutableStateOf(12.5)
    val kConstant: Double get() = _kConstant

    var flashKey by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            runCatching { prefs.preferences.collect { p -> applyPreferences(p) } }
        }
        viewModelScope.launch {
            runCatching { readingRepo.allReadings.collect { logEntries = it } }
        }
    }

    private fun applyPreferences(p: com.lumeter.data.UserPreferences) {
        if (preferencesLoaded) {
            // Only the very first emission initializes; later writes came from this VM.
            return
        }
        preferencesLoaded = true
        userIso = p.iso
        userAperture = p.aperture
        userShutter = p.shutter
        _exposureMode = p.exposureMode
        _meteringMode = p.meteringMode
        liveMetering = p.isLiveMetering
        thirdStopIncrements = p.useThirdStops
        _calOffset = p.calibrationOffset
        _kConstant = p.kConstant
        _hapticFeedback = p.hapticsEnabled
        _accentColor = AccentColor.entries.firstOrNull { it.name == p.accentName } ?: AccentColor.AMBER
    }

    private var preferencesLoaded = false

    // ---- Derived exposure chain (mirrors the reference model) ----

    private val kShift: Double get() = ExposureSolver.calibrationShift(kConstant)

    /** Scene EV with calibration, ND and K-constant applied (before MULTI aggregation). */
    val baseEv: Double?
        get() = engineSceneEv?.let { it + calOffset - ndFilter + kShift }

    /** Per-spot display EVs (engine raw EVs shifted by the same chain as [baseEv]). */
    val spotDisplayEvs: List<Double>
        get() = engineSpotEvs.map { it + calOffset - ndFilter + kShift }

    val multiActive: Boolean get() = meteringMode == MeteringMode.MULTI

    private val aggOffset: Double
        get() {
            if (!multiActive || engineSpotEvs.isEmpty()) return 0.0
            val shifted = spotDisplayEvs.filter { it.isFinite() }
            if (shifted.isEmpty()) return 0.0
            val value = when (spotAggregation) {
                SpotAggregation.AVG -> shifted.average()
                SpotAggregation.HIGH -> shifted.max()
                SpotAggregation.LOW -> shifted.min()
            }
            return value - (baseEv ?: return 0.0)
        }

    val ev100: Double?
        get() = baseEv?.plus(aggOffset)

    val spread: Double
        get() {
            val shifted = spotDisplayEvs.filter { it.isFinite() }
            return if (shifted.size > 1) shifted.max() - shifted.min() else 0.0
        }

    private val evEff: Double? get() = ev100?.minus(expComp)

    private val evIso: Double? get() = evEff?.let { ExposureSolver.evAtIso(it, userIso) }

    val solverResult: SolverResult?
        get() = evIso?.let { ExposureSolver.solve(exposureMode, userAperture, userShutter, it) }

    val needle: Double get() = solverResult?.let { ExposureSolver.needle(it.diff) } ?: 0.0

    val matched: Boolean get() = solverResult?.let { ExposureSolver.isMatched(it.diff) } == true

    val luxText: String
        get() = ev100?.let { ExposureSolver.approxLux(it, ndFilter).toInt().toString() } ?: "--"

    // ---- Camera ----

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.start(lifecycleOwner, previewView, ::onEngineState)
        // The analyzer exists synchronously; push the current state so the first frames
        // already meter in the persisted mode instead of the analyzer default.
        cameraManager.analyzer?.let { engine ->
            engine.meteringMode = meteringMode
            engine.hold = aeHold
            engine.live = liveMetering
        }
    }

    /** Spot positions in analysis-frame coordinates, pushed by the view layer. */
    fun pushEngineSpots(frameSpots: List<Pair<Float, Float>>) {
        cameraManager.analyzer?.spots = frameSpots.map { com.lumeter.camera.MeterSpot(it.first, it.second) }
    }

    private var lastEmitNanos = 0L

    private fun onEngineState(state: MeterEngineState) {
        val now = System.nanoTime()
        if (now - lastEmitNanos < EMIT_INTERVAL_NS) return
        lastEmitNanos = now
        engineSceneEv = state.reading?.sceneEv100 ?: engineSceneEv
        engineSpotEvs = state.spotEvs
        aeConverged = state.aeConverged
        analysisWidth = state.analysisWidth
        analysisHeight = state.analysisHeight
        rotationDegrees = state.rotationDegrees
    }

    // ---- Actions ----

    fun navigateTo(page: AppPage) {
        currentPage = page
    }

    fun setMeteringMode(mode: MeteringMode) {
        _meteringMode = mode
        cameraManager.analyzer?.meteringMode = mode
        if (mode != MeteringMode.MULTI) {
            spots = emptyList()
            pushEngineSpots(emptyList())
        }
        viewModelScope.launch { runCatching { prefs.setMeteringMode(mode) } }
    }

    fun cycleNdFilter() {
        val next = ExposureSolver.ND_FILTERS
            .firstOrNull { it > ndFilter } ?: ExposureSolver.ND_FILTERS.first()
        ndFilter = next
    }

    /** One stable reading while paused, then freeze again. */
    fun singleShot() {
        cameraManager.analyzer?.oneShot = true
    }

    fun toggleAeHold() {
        aeHold = !aeHold
        cameraManager.analyzer?.hold = aeHold
    }

    fun setExposureMode(mode: ExposureMode) {
        _exposureMode = mode
        when (mode) {
            ExposureMode.APERTURE_PRIORITY ->
                if (_activeField == ActiveField.SHUTTER) _activeField = ActiveField.APERTURE
            ExposureMode.SHUTTER_PRIORITY ->
                if (_activeField == ActiveField.APERTURE) _activeField = ActiveField.SHUTTER
            ExposureMode.MANUAL -> {}
        }
        viewModelScope.launch { runCatching { prefs.setExposureMode(mode) } }
    }

    fun setActiveField(field: ActiveField) {
        val locked = when (field) {
            ActiveField.APERTURE -> exposureMode == ExposureMode.SHUTTER_PRIORITY
            ActiveField.SHUTTER -> exposureMode == ExposureMode.APERTURE_PRIORITY
            ActiveField.ISO -> false
        }
        if (!locked) _activeField = field
    }

    fun setIso(iso: Int) {
        userIso = iso
        viewModelScope.launch { runCatching { prefs.setIso(iso) } }
    }

    fun setAperture(aperture: Double) {
        userAperture = aperture
        viewModelScope.launch { runCatching { prefs.setAperture(aperture) } }
    }

    fun setShutter(shutter: Double) {
        userShutter = shutter
        viewModelScope.launch { runCatching { prefs.setShutter(shutter) } }
    }

    fun adjustExpComp(delta: Double) {
        expComp = (expComp + delta).coerceIn(-3.0, 3.0)
    }

    fun toggleLiveMetering() {
        liveMetering = !liveMetering
        cameraManager.analyzer?.live = liveMetering
        viewModelScope.launch { runCatching { prefs.setLiveMetering(liveMetering) } }
    }

    // Multi-spot

    fun addSpot(x: Float, y: Float, ev100: Double) {
        if (spots.size >= MAX_SPOTS) return
        spots = spots + SpotMeasurement(
            id = System.currentTimeMillis(),
            x = x,
            y = y,
            ev100 = ev100,
        )
    }

    fun removeSpot(id: Long) {
        spots = spots.filter { it.id != id }
    }

    fun clearSpots() {
        spots = emptyList()
    }

    fun setSpotAggregation(agg: SpotAggregation) {
        _spotAggregation = agg
    }

    // History

    fun logReading(ev100: Double, aperture: Double, shutter: Double) {
        flashKey++
        viewModelScope.launch {
            runCatching {
                readingRepo.insertReading(
                    Reading(
                        timestamp = System.currentTimeMillis(),
                        ev100 = ev100,
                        iso = userIso,
                        aperture = aperture,
                        shutter = shutter,
                        meteringMode = meteringMode.name,
                    ),
                )
            }
        }
    }

    fun deleteLogEntry(id: Long) {
        viewModelScope.launch { runCatching { readingRepo.deleteReadingById(id) } }
    }

    // Settings

    fun setThirdStop(enabled: Boolean) {
        thirdStopIncrements = enabled
        viewModelScope.launch { runCatching { prefs.setUseThirdStops(enabled) } }
    }

    fun setHapticFeedback(enabled: Boolean) {
        _hapticFeedback = enabled
        viewModelScope.launch { runCatching { prefs.setHapticsEnabled(enabled) } }
    }

    fun setAccentColor(color: AccentColor) {
        _accentColor = color
        viewModelScope.launch { runCatching { prefs.setAccentColor(color.name) } }
    }


    // Calibration

    fun setCalOffset(offset: Double) {
        _calOffset = offset.coerceIn(-2.0, 2.0)
        viewModelScope.launch { runCatching { prefs.setCalibrationOffset(calOffset) } }
    }

    fun setKConstant(k: Double) {
        _kConstant = k.coerceIn(10.6, 14.0)
        viewModelScope.launch { runCatching { prefs.setKConstant(kConstant) } }
    }

    fun resetCalibration() {
        _calOffset = 0.0
        _kConstant = 12.5
        viewModelScope.launch { runCatching { prefs.resetCalibration() } }
    }

    // Film

    fun applyFilmStock(film: FilmStock) {
        setIso(film.iso)
        navigateTo(AppPage.METER)
    }

    override fun onCleared() {
        cameraManager.stop()
        super.onCleared()
    }

    companion object {
        const val MAX_SPOTS = 6
        private const val EMIT_INTERVAL_NS = 100_000_000L // 10 Hz readout

        fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return if (now - timestamp < 60_000) "Just now" else fmt.format(Date(timestamp))
        }
    }
}

enum class AppPage {
    METER,
    HISTORY,
    FILM,
    SETTINGS,
    CALIBRATION,
}
