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
import com.lumeter.core.meter.ZoneMath
import com.lumeter.data.FilmStocks
import com.lumeter.data.ActiveField
import com.lumeter.data.FilmRoll
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
        LumeterDatabase.getDatabase(application),
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
    /** RAW mode preview buffer dims for the TextureView fill-center matrix. */
    var previewWidth by mutableStateOf(0)
        private set
    var previewHeight by mutableStateOf(0)
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

    // Zone System
    private var _zoneEnabled by mutableStateOf(false)
    val zoneEnabled: Boolean get() = _zoneEnabled
    private var _placedZone by mutableStateOf(ZoneMath.MID_ZONE)
    val placedZone: Int get() = _placedZone

    // History
    var logEntries by mutableStateOf<List<Reading>>(emptyList())
        private set

    // Film rolls
    var rolls by mutableStateOf<List<FilmRoll>>(emptyList())
        private set
    var currentRollId by mutableStateOf<Long?>(null)
        private set
    private var _historyRollFilter by mutableStateOf<Long?>(null)
    val historyRollFilter: Long? get() = _historyRollFilter

    val activeRolls: List<FilmRoll>
        get() = rolls.filter { !it.isArchived }

    val currentRoll: FilmRoll?
        get() = activeRolls.firstOrNull { it.id == currentRollId }

    /** History entries narrowed by the roll filter (null = all). */
    val filteredEntries: List<Reading>
        get() = _historyRollFilter?.let { id -> logEntries.filter { it.rollId == id } }
            ?: logEntries

    // Settings
    var liveMetering by mutableStateOf(true)
        private set
    var thirdStopIncrements by mutableStateOf(false)
        private set
    private var _hapticFeedback by mutableStateOf(true)
    val hapticFeedback: Boolean get() = _hapticFeedback
    private var _accentColor by mutableStateOf(AccentColor.AMBER)
    val accentColor: AccentColor get() = _accentColor

    // Calibration, one EV offset per metering source (YUV vs RAW read different
    // system constants); the displayed/active one follows the live source.
    private var _calOffsetYuv by mutableStateOf(0.0)
    val calOffsetYuv: Double get() = _calOffsetYuv
    private var _calOffsetRaw by mutableStateOf(0.0)
    val calOffsetRaw: Double get() = _calOffsetRaw
    val calOffset: Double get() = if (rawActive) _calOffsetRaw else _calOffsetYuv
    private var _kConstant by mutableStateOf(12.5)
    val kConstant: Double get() = _kConstant

    // Frame analysis (histogram overlay + automatic scene range)
    var histogramEnabled by mutableStateOf(false)
        private set
    var engineHistogram by mutableStateOf<IntArray?>(null)
        private set
    var engineSceneLowEv by mutableStateOf<Double?>(null)
        private set
    var engineSceneHighEv by mutableStateOf<Double?>(null)
        private set

    /** Whether the SPOT reticle follows taps/drags (else fixed center). */
    private var _spotDraggable by mutableStateOf(true)
    val spotDraggable: Boolean get() = _spotDraggable
    /** SPOT metering point in normalized viewfinder coordinates. */
    var spotPosition by mutableStateOf(0.5f to 0.5f)
        private set

    // Current film: drives ISO plus reciprocity-linked solving.
    private var _currentFilmStock by mutableStateOf<String?>(null)
    val currentFilmStock: String? get() = _currentFilmStock
    val currentFilm: com.lumeter.data.FilmStock?
        get() = _currentFilmStock?.let { name ->
            FilmStocks.ALL.firstOrNull { it.name == name }
        }

    // RAW metering source
    /** User intent (settings switch); the session may fail to open on unsupported devices. */
    var rawMode by mutableStateOf(false)
        private set
    /** True once the RAW Camera2 session is actually delivering frames. */
    var rawActive by mutableStateOf(false)
        private set
    /** Last RAW failure reason; non-null implies the UI fell back to YUV. */
    var rawError by mutableStateOf<String?>(null)
        private set

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

    /** Automatic scene range on the same pure chain; null until the engine converged. */
    val sceneRangeEvs: Pair<Double, Double>?
        get() {
            val low = engineSceneLowEv ?: return null
            val high = engineSceneHighEv ?: return null
            val shift = calOffset + ExposureSolver.calibrationShift(kConstant)
            return (low + shift) to (high + shift)
        }

    private val multiActive: Boolean get() = meteringMode == MeteringMode.MULTI

    /** Zone placement shift: placing above V gives MORE light (EV100 recommendation drops). */
    private val zoneOffset: Double
        get() = if (_zoneEnabled) ZoneMath.evShiftForPlacement(_placedZone) else 0.0

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
            biasStops = expComp - multi + zoneOffset,
            ndStops = ndFilter,
            reciprocity = currentFilm?.reciprocity,
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
        viewModelScope.launch {
            runCatching { readingRepo.allRolls.collect { rolls = it } }
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
        _calOffsetYuv = p.calibrationOffsetYuv
        _calOffsetRaw = p.calibrationOffsetRaw
        _kConstant = p.kConstant
        _hapticFeedback = p.hapticsEnabled
        _accentColor = AccentColor.entries.firstOrNull { it.name == p.accentName }
            ?: AccentColor.AMBER
        rawMode = p.rawMode
        histogramEnabled = p.histogramEnabled
        _spotDraggable = p.spotDraggable
        _currentFilmStock = p.currentFilmStock
        currentRollId = p.currentRollId
    }

    private var preferencesLoaded = false

    // ---- Camera ----

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.start(lifecycleOwner, previewView, ::onEngineState)
        cameraManager.analyzer?.let { engine ->
            engine.meteringMode = meteringMode
            engine.live = continuous
            engine.frameAnalysisEnabled = histogramEnabled
            engine.spotPosition = MeterSpot(spotPosition.first, spotPosition.second)
        }
    }

    /**
     * RAW handover: called by the viewfinder's TextureView once its surface is ready.
     * Success flips [rawActive]; any failure tears the session down and falls back to
     * the YUV path, persisting the off state so the next launch does not retry blindly.
     */
    fun startRawCamera(surfaceTexture: android.graphics.SurfaceTexture) {
        if (!rawMode) return
        if (rawActive) {
            // Returning to the meter page: the old TextureView died with the page and
            // the orphaned session is writing to a released surface. Rebuild on this one
            // instead of ignoring the new surface (which shows a black viewfinder).
            cameraManager.stopRaw()
            rawActive = false
        }
        cameraManager.startRaw(
            surfaceTexture = surfaceTexture,
            meteringMode = meteringMode,
            live = continuous,
            onState = ::onEngineState,
            onReady = {
                rawActive = true
                rawError = null
            },
            onFallback = { reason ->
                rawActive = false
                rawError = reason
                if (rawMode) {
                    rawMode = false
                    viewModelScope.launch { runCatching { prefs.setRawMode(false) } }
                }
            },
        )
    }

    fun toggleRawMode() {
        rawError = null
        if (!rawMode) {
            rawMode = true
        } else {
            rawMode = false
            cameraManager.stopRaw()
            rawActive = false
        }
        viewModelScope.launch { runCatching { prefs.setRawMode(rawMode) } }
    }

    fun pushEngineSpots(frameSpots: List<Pair<Float, Float>>) {
        val mapped = frameSpots.map { MeterSpot(it.first, it.second) }
        cameraManager.analyzer?.spots = mapped
        cameraManager.rawMeterSource?.spots = mapped
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
        previewWidth = state.previewWidth
        previewHeight = state.previewHeight
        engineHistogram = state.histogram
        engineSceneLowEv = state.sceneLowEv
        engineSceneHighEv = state.sceneHighEv

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

    // Zone System

    fun toggleZone() {
        _zoneEnabled = !_zoneEnabled
    }

    fun setPlacedZone(zone: Int) {
        _placedZone = zone.coerceIn(0, ZoneMath.ZONE_COUNT - 1)
    }

    /**
     * Where each MULTI spot lands on the zone scale once the placed EV is shot: zone V
     * is whatever the final recommended EV renders mid-gray.
     */
    val spotZones: List<Double>
        get() {
            val base = sceneEv ?: return emptyList()
            val anchor = base + zoneOffset
            return spotDisplayEvs.map { ZoneMath.zoneForSpot(it, anchor) }
        }

    fun setMeteringMode(mode: MeteringMode) {
        _meteringMode = mode
        cameraManager.analyzer?.meteringMode = mode
        cameraManager.rawMeterSource?.meteringMode = mode
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
        cameraManager.rawMeterSource?.live = continuous
    }

    /**
     * Long-press on the main button while paused: take exactly one fresh stable
     * reading into the frozen display, without resuming the stream. A plain tap on
     * the button is pause/resume and goes through [toggleContinuous] directly.
     */
    fun measureNow() {
        if (continuous) return
        measuring = true
        frozenReading = null
        cameraManager.analyzer?.oneShot = true
        cameraManager.rawMeterSource?.oneShot = true
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
        val roll = currentRoll
        viewModelScope.launch {
            runCatching {
                readingRepo.logReading(
                    Reading(
                        timestamp = System.currentTimeMillis(),
                        ev100 = ev100,
                        iso = userIso,
                        aperture = aperture,
                        shutter = shutter,
                        meteringMode = meteringMode.name,
                        rollId = roll?.id,
                        filmStock = currentFilmStock,
                        source = if (rawActive) "RAW" else "YUV",
                        zone = if (_zoneEnabled) _placedZone else null,
                    ),
                    rollId = roll?.id,
                )
            }
        }
    }

    fun deleteLogEntry(id: Long) {
        viewModelScope.launch { runCatching { readingRepo.deleteReadingById(id) } }
    }

    /** Writes the currently filtered history (plus the rolls) to a SAF document. */
    fun exportHistory(context: android.content.Context, uri: android.net.Uri, format: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val entries = filteredEntries
                val text = if (format == "json") {
                    val json = kotlinx.serialization.json.Json { prettyPrint = true }
                    json.encodeToString(
                        com.lumeter.data.ExportPayload.serializer(),
                        com.lumeter.data.ExportPayload(rolls = rolls, readings = entries),
                    )
                } else {
                    exportCsv(entries)
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun exportCsv(entries: List<Reading>): String = buildString {
        append("timestamp,ev100,iso,aperture,shutter_s,metering_mode,film_stock,source,zone,roll_id\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
        entries.forEach { r ->
            append(fmt.format(java.util.Date(r.timestamp))).append(',')
            append(r.ev100).append(',')
            append(r.iso).append(',')
            append(r.aperture).append(',')
            append(r.shutter).append(',')
            append(r.meteringMode).append(',')
            append(csvEscape(r.filmStock)).append(',')
            append(r.source ?: "").append(',')
            append(r.zone?.toString() ?: "").append(',')
            append(r.rollId?.toString() ?: "").append('\n')
        }
    }

    private fun csvEscape(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return if (value.contains(',') || value.contains('"')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    // Settings

    fun toggleHistogram() {
        histogramEnabled = !histogramEnabled
        cameraManager.analyzer?.frameAnalysisEnabled = histogramEnabled
        cameraManager.rawMeterSource?.frameAnalysisEnabled = histogramEnabled
        viewModelScope.launch { runCatching { prefs.setHistogramEnabled(histogramEnabled) } }
    }

    fun setSpotDraggable(enabled: Boolean) {
        _spotDraggable = enabled
        viewModelScope.launch { runCatching { prefs.setSpotDraggable(enabled) } }
    }

    /** SPOT metering point in normalized VIEWFINDER coordinates (UI state only). */
    fun setSpotPosition(x: Float, y: Float) {
        spotPosition = x.coerceIn(0.02f, 0.98f) to y.coerceIn(0.02f, 0.98f)
    }

    /** Forwards the SPOT point in FRAME coordinates (the viewfinder maps screen->frame). */
    fun pushEngineSpotPosition(frameU: Float, frameV: Float) {
        val spot = MeterSpot(frameU, frameV)
        cameraManager.analyzer?.spotPosition = spot
        cameraManager.rawMeterSource?.spotPosition = spot
    }

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

    fun setCalOffsetYuv(offset: Double) {
        _calOffsetYuv = offset.coerceIn(-2.0, 2.0)
        viewModelScope.launch { runCatching { prefs.setCalibrationOffsetYuv(calOffsetYuv) } }
    }

    fun setCalOffsetRaw(offset: Double) {
        _calOffsetRaw = offset.coerceIn(-2.0, 2.0)
        viewModelScope.launch { runCatching { prefs.setCalibrationOffsetRaw(calOffsetRaw) } }
    }

    fun setKConstant(k: Double) {
        _kConstant = k.coerceIn(10.6, 14.0)
        viewModelScope.launch { runCatching { prefs.setKConstant(kConstant) } }
    }

    fun resetCalibration() {
        _calOffsetYuv = 0.0
        _calOffsetRaw = 0.0
        _kConstant = 12.5
        viewModelScope.launch { runCatching { prefs.resetCalibration() } }
    }

    // Film

    fun applyFilmStock(film: FilmStock) {
        _currentFilmStock = film.name
        setIso(film.iso)
        navigateTo(AppPage.METER)
    }

    fun clearFilmStock() {
        _currentFilmStock = null
        viewModelScope.launch { runCatching { prefs.setCurrentFilmStock(null) } }
    }

    // Film rolls

    fun createRoll(stockName: String, iso: Int, frameCount: Int, notes: String) {
        viewModelScope.launch {
            runCatching {
                val id = readingRepo.createRoll(
                    FilmRoll(
                        stockName = stockName,
                        iso = iso,
                        frameCount = frameCount,
                        loadedAt = System.currentTimeMillis(),
                        notes = notes,
                    ),
                )
                currentRollId = id
                prefs.setCurrentRollId(id)
            }
        }
    }

    /** Tap a roll card to make it the current one. */
    fun setCurrentRoll(id: Long) {
        currentRollId = if (currentRollId == id) null else id
        viewModelScope.launch { runCatching { prefs.setCurrentRollId(currentRollId) } }
    }

    fun archiveRoll(id: Long, archived: Boolean) {
        viewModelScope.launch {
            runCatching {
                readingRepo.setRollArchived(id, archived)
                if (archived && currentRollId == id) {
                    currentRollId = null
                    prefs.setCurrentRollId(null)
                }
            }
        }
    }

    fun deleteRoll(id: Long) {
        viewModelScope.launch {
            runCatching {
                readingRepo.deleteRollById(id)
                if (currentRollId == id) {
                    currentRollId = null
                    prefs.setCurrentRollId(null)
                }
                if (_historyRollFilter == id) _historyRollFilter = null
            }
        }
    }

    fun setHistoryRollFilter(id: Long?) {
        _historyRollFilter = id
    }

    override fun onCleared() {
        cameraManager.stopRaw()
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
    TOOLS,
    SETTINGS,
    CALIBRATION,
}
