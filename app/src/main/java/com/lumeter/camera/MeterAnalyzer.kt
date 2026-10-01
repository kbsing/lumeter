package com.lumeter.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lumeter.core.meter.EvMath
import com.lumeter.core.meter.FrameExposure
import com.lumeter.core.meter.MeteringFusion
import com.lumeter.core.meter.MeteringFrameStat
import com.lumeter.core.meter.MeteringMode
import com.lumeter.core.meter.MeteringSource
import com.lumeter.core.meter.MeterReading
import com.lumeter.core.meter.PlaneSampler
import com.lumeter.core.meter.RegionStat
import com.lumeter.core.meter.YuvColorEncoding
import com.lumeter.core.meter.YuvFrameAnalyzer
import kotlin.math.abs

/** State the meter engine publishes to the UI. Spot EVs are raw (uncalibrated). */
data class MeterEngineState(
    val reading: MeterReading?,
    /** Per-spot EV100 for MULTI mode, ordered to match the spot list set via [MeterAnalyzer.spots]. */
    val spotEvs: List<Double> = emptyList(),
    val aeConverged: Boolean = false,
    val cameraIso: Int? = null,
    val cameraShutterNs: Long? = null,
    val cameraAperture: Float? = null,
    val source: MeteringSource = MeteringSource.YUV_PREVIEW,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val rotationDegrees: Int = 0,
)

/** A MULTI-mode metering spot in normalized analysis-frame coordinates. */
data class MeterSpot(val frameU: Float, val frameV: Float)

/**
 * CameraX ImageAnalysis analyzer that meters the preview stream for all four modes.
 *
 * Per-frame robust luma over the mode's ROI(s), linearized through the reported tonemap,
 * converted to EV100 with the frame's own exposure metadata (Camera2Interop session
 * callback + SENSOR_TIMESTAMP pairing via [CaptureResultStore]). A reading is accepted
 * only while the camera's exposure has been stable for [STABLE_FRAME_WINDOW] frames
 * within a small tolerance; stable frames feed a ring whose median is published.
 *
 * HOLD (AE-L) and PAUSE freeze the published state at the last live reading; the analyzer
 * keeps draining the result store so pairing does not back up behind stale entries.
 */
class MeterAnalyzer(
    private val onState: (MeterEngineState) -> Unit,
    @Volatile var defaultAperture: Float,
    private val resultStore: CaptureResultStore,
) : ImageAnalysis.Analyzer {

    @Volatile var meteringMode: MeteringMode = MeteringMode.CENTER
    @Volatile var spots: List<MeterSpot> = emptyList()
    @Volatile var hold: Boolean = false
    @Volatile var live: Boolean = true
    /** When paused, take exactly one more stable reading, then freeze again. */
    @Volatile var oneShot = false

    private val statRing = ArrayDeque<MeteringFrameStat>()
    private val exposureHistory = ArrayDeque<FrameExposure>()
    private var lastPublishedReading: MeterReading? = null
    private var lastSpotEvs: List<Double> = emptyList()
    private var lastConverged = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRotation = 0
    private var frameCounter = 0

    override fun analyze(image: ImageProxy) {
        try {
            val captureResult = resultStore.take(image.imageInfo.timestamp)
            if (captureResult == null) return

            val exposure = frameExposure(captureResult) ?: return
            if (hold || (!live && !oneShot)) {
                publishFrozen()
                return
            }

            val decoder = ProcessedLumaMetadata.decoder(captureResult)
            // YUV_420_888 defaults to the JFIF dataspace (full-range BT.601); CameraX does
            // not expose the per-frame dataspace, so the default encoding is used.
            val encoding = YuvColorEncoding()
            val analyzer = YuvFrameAnalyzer(
                width = image.width,
                height = image.height,
                ySampler = planeSampler(image.planes[0]),
                uSampler = YuvFrameAnalyzer.ChromaAdapter(planeSampler(image.planes[1])),
                vSampler = YuvFrameAnalyzer.ChromaAdapter(planeSampler(image.planes[2])),
                decoder = decoder,
                encoding = encoding,
            )

            val mode = meteringMode
            val region: RegionStat? = when (mode) {
                MeteringMode.MATRIX -> analyzer.regionStat(0.5f, 0.5f, 1.0f)
                MeteringMode.CENTER -> {
                    val spot = analyzer.regionStat(0.5f, 0.5f, EvMath.SPOT_ROI_FRACTION)
                    val wide = analyzer.regionStat(
                        0.5f,
                        0.5f,
                        EvMath.CENTER_WEIGHTED_ROI_FRACTION,
                    )
                    if (spot != null && wide != null) {
                        RegionStat(
                            luma = spot.luma * EvMath.CENTER_SPOT_WEIGHT +
                                wide.luma * EvMath.CENTER_WIDE_WEIGHT,
                            clippedFraction = spot.clippedFraction * EvMath.CENTER_SPOT_WEIGHT +
                                wide.clippedFraction * EvMath.CENTER_WIDE_WEIGHT,
                        )
                    } else {
                        spot ?: wide
                    }
                }
                MeteringMode.SPOT -> {
                    val point = spots.firstOrNull()
                    analyzer.regionStat(
                        point?.frameU ?: 0.5f,
                        point?.frameV ?: 0.5f,
                        EvMath.SPOT_ROI_FRACTION,
                    )
                }
                MeteringMode.MULTI -> analyzer.regionStat(0.5f, 0.5f, 1.0f)
            }

            val spotEvs = if (mode == MeteringMode.MULTI) {
                spots.map { spot ->
                    analyzer.regionStat(spot.frameU, spot.frameV, EvMath.SPOT_ROI_FRACTION)
                        ?.let { EvMath.ev100(exposure, it.luma) }
                        ?: Double.NaN
                }
            } else {
                emptyList()
            }

            val converged = exposureStable(exposure) || aeStateConverged(captureResult)
            val stat = region?.let { reg ->
                EvMath.ev100(exposure, reg.luma)?.let { ev ->
                    MeteringFrameStat(
                        ev100 = ev,
                        luma = reg.luma,
                        clipped = reg.clippedFraction,
                        captureIso = exposure.sensitivity,
                        exposureTimeNs = exposure.exposureTimeNs,
                        aperture = exposure.aperture,
                    )
                }
            }
            if (converged && stat != null) {
                statRing.addLast(stat)
                while (statRing.size > STAT_RING_SIZE) statRing.removeFirst()
            } else if (!converged) {
                statRing.clear()
            }

            frameCounter++
            if (frameCounter % LOG_INTERVAL_FRAMES == 0) {
                val last = statRing.lastOrNull()
                android.util.Log.i(
                    TAG,
                    "ev100=${last?.ev100} luma=${last?.luma} converged=$converged " +
                        "mode=$mode ring=${statRing.size} iso=${exposure.sensitivity} " +
                        "t_ns=${exposure.exposureTimeNs} aperture=${exposure.aperture} " +
                        "tonemap=${captureResult.get(CaptureResult.TONEMAP_MODE)} " +
                        "pairMissed=${resultStore.missedCount}",
                )
            }

            if (oneShot && converged && statRing.size >= STAT_RING_SIZE) {
                oneShot = false
            }
            val fused = if (statRing.isNotEmpty()) {
                MeteringFusion.fuse(statRing.toList(), MeteringSource.YUV_PREVIEW)
            } else {
                null
            }
            val publishReading = fused?.takeIf { new ->
                val last = lastPublishedReading
                last == null || abs(new.sceneEv100 - last.sceneEv100) >= PUBLISH_DELTA_EV
            }
            if (publishReading != null) lastPublishedReading = publishReading
            if (mode == MeteringMode.MULTI) lastSpotEvs = spotEvs
            lastConverged = converged
            lastWidth = image.width
            lastHeight = image.height
            lastRotation = image.imageInfo.rotationDegrees

            onState(
                MeterEngineState(
                    reading = publishReading ?: lastPublishedReading,
                    spotEvs = if (mode == MeteringMode.MULTI) lastSpotEvs else emptyList(),
                    aeConverged = converged,
                    cameraIso = exposure.sensitivity,
                    cameraShutterNs = exposure.exposureTimeNs,
                    cameraAperture = exposure.aperture,
                    source = MeteringSource.YUV_PREVIEW,
                    analysisWidth = lastWidth,
                    analysisHeight = lastHeight,
                    rotationDegrees = lastRotation,
                ),
            )
        } finally {
            image.close()
        }
    }

    fun reset() {
        statRing.clear()
        exposureHistory.clear()
        lastPublishedReading = null
        lastSpotEvs = emptyList()
    }

    private fun publishFrozen() {
        onState(
            MeterEngineState(
                reading = lastPublishedReading,
                spotEvs = lastSpotEvs,
                aeConverged = lastConverged,
                analysisWidth = lastWidth,
                analysisHeight = lastHeight,
                rotationDegrees = lastRotation,
            ),
        )
    }

    private fun frameExposure(result: CaptureResult): FrameExposure? {
        val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: defaultAperture
        if (time <= 0 || sensitivity <= 0 || aperture <= 0f) return null
        return FrameExposure(time, sensitivity, aperture)
    }

    /**
     * HAL-reported exposure jitters by tiny amounts frame to frame (±1 ISO step, a few
     * hundred ns), so exact equality never holds on real devices. Treat the exposure as
     * stable while every frame in the window sits within [STABLE_TOLERANCE_EV] of the first.
     */
    private fun exposureStable(exposure: FrameExposure): Boolean {
        exposureHistory.addLast(exposure)
        while (exposureHistory.size > STABLE_FRAME_WINDOW) exposureHistory.removeFirst()
        if (exposureHistory.size < STABLE_FRAME_WINDOW) return false
        val first = exposureHistory.first()
        return exposureHistory.all { frame ->
            abs(EvMath.log2(frame.exposureTimeNs.toDouble() / first.exposureTimeNs)) < STABLE_TOLERANCE_EV &&
                abs(EvMath.log2(frame.sensitivity.toDouble() / first.sensitivity.toDouble())) < STABLE_TOLERANCE_EV
        }
    }

    private fun aeStateConverged(result: CaptureResult): Boolean =
        result.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_CONVERGED

    private companion object {
        private const val TAG = "LumeterMeter"
        private const val STABLE_FRAME_WINDOW = 4
        private const val STABLE_TOLERANCE_EV = 1.0 / 24.0 // ignore ±1/24 EV HAL jitter
        private const val STAT_RING_SIZE = 5
        private const val PUBLISH_DELTA_EV = 1.0 / 12.0
        private const val LOG_INTERVAL_FRAMES = 30
    }
}

/** Absolute reads preserve each vendor plane's initial buffer offset and row/pixel padding. */
private fun planeSampler(plane: ImageProxy.PlaneProxy): PlaneSampler {
    val buffer = plane.buffer.duplicate()
    val start = buffer.position()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()
    return PlaneSampler { column, row ->
        if (column < 0 || row < 0 || rowStride <= 0 || pixelStride <= 0) {
            null
        } else {
            val offset = start.toLong() + row.toLong() * rowStride + column.toLong() * pixelStride
            if (offset < start || offset >= limit) {
                null
            } else {
                buffer.get(offset.toInt()).toInt() and 0xff
            }
        }
    }
}
