package com.lumeter.ui

import android.app.Application
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.lumeter.camera.CameraManager
import com.lumeter.camera.MeterEngineState
import com.lumeter.camera.MeterSpot
import com.lumeter.core.exposure.ExposureEngine
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.exposure.ExposureResult
import com.lumeter.core.exposure.ExposureSolver
import com.lumeter.core.exposure.ExposureState
import com.lumeter.core.exposure.MeterReading
import com.lumeter.core.exposure.ReadingInvalidReason
import com.lumeter.core.exposure.ResultKind
import com.lumeter.core.meter.MeteringMode
import com.lumeter.data.ActiveField
import com.lumeter.data.FilmStock
import com.lumeter.data.PreferencesRepository
import com.lumeter.data.Reading
import com.lumeter.data.ReadingRepository
import com.lumeter.data.LumeterDatabase
import com.lumeter.data.SpotAggregation
import com.lumeter.data.SpotMeasurement
import com.lumeter.ui.theme.AccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main application state manager. Coordinates UI state across all screens and bridges
 * the metering engine, the pure exposure evaluator, and persistence.
 *
 * Operating model (per the metering spec):
 * - LIVE: the reading streams continuously.
 * - HELD (the primary handheld-meter workflow): the reading is frozen; the main action
 *   button takes exactly one fresh stable reading. Adjusting ISO/aperture/shutter
 *   re-evaluates against the frozen reading but never re-meters.
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

    // Engine-derived state
    var engineSceneEv by mutableStateOf<Double?>(null)
        private set
    var engineRawLuma by mutableStateOf(0.0)
        private set
    var engineClipped by mutableStateOf(0.0)
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

    // Session model: LIVE streams, HELD freezes + one-shot metering.
    var continuous by mutableStateOf(true)
        private set
    var measuring by mutableStateOf(false)
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

    /** Bottom parameter panel expansion (session state). */
    var controlsExpanded by mutableStateOf(true)
        private set

    var flashKey by mutableStateOf(0)
        private set

    // ---- Reading model ----

    /** Scene EV100 shown to the user: raw scene + calibration + K shift. Never ND. */
    val sceneEv: Double?
        get() = engineSceneEv?.let { it + calOffset + ExposureSolver.calibrationShift(kConstant) }

    /** Alias kept for the UI. */
    val ev100: Double? get() = sceneEv

    /** Spread between the brightest and darkest MULTI spot, in EV. */
    val spread: Double
        get() {
            val shifted = spotDisplayEvs.filter { it.isFinite() }
            return if (shifted.size > 1) shifted.max() - shifted.min() else 0.0
        }

    /** Per-spot EVs on the same pure chain as [sceneEv]. */
    val spotDisplayEvs: List<Double>
        get() = engineSpotEvs.map { it + calOffset + ExposureSolver.calibrationShift(kConstant) }

    private var engineSpotEvs: List<Double> = emptyList()

    private val multiActive: Boolean get() = meteringMode == MeteringMode.MULTI

    private val aggOffset: Double
        get() {
            if (!multiActive || engineSpotEvs.isEmpty()) return 0.0
            val shifted = spotDisplayEvs.filter { it.isFinite() }
            if (shifted.isEmpty()) return 0.0
            val base = sceneEv ?: return 0.0
            val value = when (spotAggregation) {
                SpotAggregation.AVG -> shifted.average()
                SpotAggregation.HIGH -> shifted.max()
                SpotAggregation.LOW -> shifted.min()
            }
            return value - base
        }

    /** The reading the evaluator sees: LIVE streams, HELD freezes. */
    private var liveReading: MeterReading? = null
    private var frozenReading: MeterReading? = null
    private var lastAdoptedEv: Double? = null

    val activeReading: MeterReading?
        get() = if (continuous) liveReading else frozenReading

    private fun currentState(): ExposureState {
        val multi = if (multiActive) aggOffset else 0.0
        return ExposureState(
            mode = exposureMode,
            aperture = userAperture,
            shutter = userShutter,
            iso = userIso,
            biasStops = expComp - multi,
            ndStops = ndFilter,
        )
    }

    val exposureResult: ExposureResult
        get() = ExposureEngine.evaluate(currentState(), activeReading)

    /** Equivalent (aperture, shutter) pairs around the current aperture. */
    val equivalentPairs: List<Pair<Double, Double>>
        get() = ExposureEngine.equivalentPairs(currentState(), activeReading, userAperture)

    /** Apply one equivalent pair explicitly: both become user values in M mode. */
    fun applyEquivalentPair(aperture: Double, shutter: Double) {
        _exposureMode = ExposureMode.MANUAL
        userAperture = aperture
        userShutter = shutter
        if (_activeField == ActiveField.SHUTTER) _activeField = ActiveField.APERTURE
        viewModelScope.launch { runCatching { prefs.setExposureMode(ExposureMode.MANUAL) } }
    }

    val needle: Double get() = exposureResult.stops ?: 0.0
    val matched: Boolean
        get() = exposureResult.kind == ResultKind.OK && exposureResult.stops == 0.0

    val luxText: String
        get() = sceneEv?.let { ExposureSolver.approxLux(it, 0).toInt().toString() } ?: "--"

    init {
        viewModelScope.launch {
            runCatching { prefs.preferences.collect { p -> applyPreferences(p) } }
        }
        viewModelScope.launch {
            runCatching { readingRepo.allReadings.collect { logEntries = it } }
        }
    }

    private fun applyPreferences(p: com.lumeter.data.UserPreferences) {
        if (preferencesLoaded) return
        preferencesLoaded = true
        userIso = p.iso
        userAperture = p.aperture
        userShutter = p.shutter
        _exposureMode = p.exposureMode
        _meteringMode = p.meteringMode
        liveMetering = p.isLiveMetering
        continuous = p.isLiveMetering
        thirdStopIncrements = p.useThirdStops
        _calOffset = p.calibrationOffset
        _kConstant = p.kConstant
        _hapticFeedback = p.hapticsEnabled
        _accentColor = AccentColor.entries.firstOrNull { it.name == p.accentName }
            ?: AccentColor.AMBER
    }

    private var preferencesLoaded = false

    // ---- Camera ----

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.start(lifecycleOwner, previewView, ::onEngineState)
        cameraManager.analyzer?.let { engine ->
            engine.meteringMode = meteringMode
            engine.live = continuous
        }
    }

    fun pushEngineSpots(frameSpots: List<Pair<Float, Float>>) {
        cameraManager.analyzer?.spots =
            frameSpots.map { MeterSpot(it.first, it.second) }
    }

    private var lastEmitNanos = 0L

    private fun onEngineState(state: MeterEngineState) {
        val now = System.nanoTime()
        if (now - lastEmitNanos < EMIT_INTERVAL_NS) return
        lastEmitNanos = now
        state.reading?.let {
            engineSceneEv = it.sceneEv100
            engineRawLuma = it.rawLuma
            engineClipped = it.clippedFraction
        }
        engineSpotEvs = state.spotEvs
        aeConverged = state.aeConverged
        analysisWidth = state.analysisWidth
        analysisHeight = state.analysisHeight
        rotationDegrees = state.rotationDegrees

        val ev = sceneEv
        if (continuous) {
            if (ev != null) {
                // Solve hysteresis: small jitters never move the adopted center.
                lastAdoptedEv = ExposureEngine.adoptEv(lastAdoptedEv, ev)
                liveReading = buildReading(lastAdoptedEv)
            } else {
                liveReading = buildReading(null)
            }
        } else if (measuring && ev != null && state.reading != null) {
            lastAdoptedEv = ev
            frozenReading = buildReading(ev)
            measuring = false
        }
    }

    private fun buildReading(ev: Double?): MeterReading {
        val reason = when {
            ev == null -> ReadingInvalidReason.NO_SIGNAL
            engineRawLuma > 0.97 || engineClipped > 0.5 -> ReadingInvalidReason.CLIPPED
            engineRawLuma < 1e-4 -> ReadingInvalidReason.TOO_DARK
            else -> null
        }
        return MeterReading(
            ev100 = ev ?: 0.0,
            valid = reason == null,
            reason = reason,
            timestampMs = System.currentTimeMillis(),
            clippedFraction = engineClipped,
        )
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
        val next = ExposureSolver.ND_FILTERS.firstOrNull { it > ndFilter }
            ?: ExposureSolver.ND_FILTERS.first()
        ndFilter = next
    }

    fun toggleContinuous() {
        if (continuous) {
            continuous = false
            frozenReading = liveReading
        } else {
            continuous = true
            measuring = false
        }
        cameraManager.analyzer?.live = continuous
    }

    /**
     * Main action button. HELD mode: take exactly one fresh stable reading.
     * LIVE mode: force the solve to re-center on the next incoming reading.
     */
    fun measureNow() {
        if (continuous) {
            // LIVE: force the solve to re-center on the very next reading.
            lastAdoptedEv = null
            return
        }
        measuring = true
        frozenReading = null
        cameraManager.analyzer?.oneShot = true
        viewModelScope.launch {
            delay(MEASURE_TIMEOUT_MS)
            if (measuring) {
                measuring = false
                if (frozenReading == null) {
                    frozenReading = buildReading(sceneEv)
                }
            }
        }
    }

    fun setExposureMode(mode: ExposureMode) {
        val carried = ExposureEngine.carryOver(currentState(), exposureResult, mode)
        _exposureMode = mode
        userAperture = carried.aperture
        userShutter = carried.shutter
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

    // The settings switch only decides the startup default.
    fun toggleLiveMetering() {
        liveMetering = !liveMetering
        continuous = liveMetering
        cameraManager.analyzer?.live = continuous
        viewModelScope.launch { runCatching { prefs.setLiveMetering(liveMetering) } }
    }

    fun toggleControls() {
        controlsExpanded = !controlsExpanded
    }

    // Multi-spot

    fun addSpot(x: Float, y: Float, ev100: Double) {
        if (spots.size >= MAX_SPOTS) return
        spots = spots + SpotMeasurement(id = nextSpotId(), x = x, y = y, ev100 = ev100)
    }

    fun removeSpot(id: Long) {
        spots = spots.filter { it.id != id }
    }

    fun updateSpot(id: Long, x: Float, y: Float) {
        spots = spots.map {
            if (it.id == id) {
                it.copy(x = x.coerceIn(0.02f, 0.98f), y = y.coerceIn(0.02f, 0.98f))
            } else {
                it
            }
        }
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
        const val MAX_SPOTS = 12
        private const val EMIT_INTERVAL_NS = 100_000_000L // 10 Hz
        private const val MEASURE_TIMEOUT_MS = 5_000L
        private val spotIdCounter = java.util.concurrent.atomic.AtomicLong(1)
        private fun nextSpotId(): Long = spotIdCounter.getAndIncrement()

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
