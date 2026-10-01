package com.lumeter.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lumeter.core.meter.EvMath
import com.lumeter.core.meter.FusedReading
import com.lumeter.core.meter.FrameExposure
import com.lumeter.core.meter.MeteringFrameStat
import com.lumeter.core.meter.MeteringMode
import com.lumeter.core.meter.MeteringSource
import com.lumeter.core.meter.PlaneSampler
import com.lumeter.core.meter.RegionStat
import com.lumeter.core.meter.YuvColorEncoding
import com.lumeter.core.meter.YuvFrameAnalyzer

/** State the meter engine publishes to the UI. Spot EVs are raw (uncalibrated). */
data class MeterEngineState(
    val reading: FusedReading?,
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
    /** RAW mode: the PREVIEW buffer dims (differs from the RAW stream; drives the
     *  TextureView fill-center matrix). YUV path leaves these at 0. */
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
)

/** A MULTI-mode metering spot in normalized analysis-frame coordinates. */
data class MeterSpot(val frameU: Float, val frameV: Float)

/**
 * CameraX ImageAnalysis analyzer that meters the preview stream for all four modes.
 *
 * Per-frame robust luma over the mode's ROI(s), linearized through the reported tonemap,
 * converted to EV100 with the frame's own exposure metadata (Camera2Interop session
 * callback + SENSOR_TIMESTAMP pairing via [CaptureResultStore]). Stability gating and
 * median fusion live in [ReadingAccumulator], shared with the RAW source.
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

    private val accumulator = ReadingAccumulator(MeteringSource.YUV_PREVIEW)
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
                MeteringMode.SPOT -> analyzer.regionStat(0.5f, 0.5f, EvMath.SPOT_ROI_FRACTION)
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

            val converged = accumulator.exposureStable(exposure) ||
                aeStateConverged(captureResult)
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

            frameCounter++
            if (frameCounter % LOG_INTERVAL_FRAMES == 0) {
                android.util.Log.i(
                    TAG,
                    "ev100=${stat?.ev100} luma=${stat?.luma} converged=$converged " +
                        "mode=$mode iso=${exposure.sensitivity} " +
                        "t_ns=${exposure.exposureTimeNs} aperture=${exposure.aperture} " +
                        "tonemap=${captureResult.get(CaptureResult.TONEMAP_MODE)} " +
                        "pairMissed=${resultStore.missedCount}",
                )
            }

            if (oneShot && converged && stat != null) {
                oneShot = false
            }
            val snapshot = accumulator.offer(exposure, converged, stat)
            if (mode == MeteringMode.MULTI) lastSpotEvs = spotEvs
            lastConverged = converged
            lastWidth = image.width
            lastHeight = image.height
            lastRotation = image.imageInfo.rotationDegrees

            onState(
                MeterEngineState(
                    reading = snapshot.display,
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
        accumulator.reset()
        lastSpotEvs = emptyList()
    }

    private fun publishFrozen() {
        onState(
            MeterEngineState(
                reading = accumulator.frozen(lastConverged).display,
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

    private fun aeStateConverged(result: CaptureResult): Boolean =
        result.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_CONVERGED

    private companion object {
        private const val TAG = "LumeterMeter"
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
