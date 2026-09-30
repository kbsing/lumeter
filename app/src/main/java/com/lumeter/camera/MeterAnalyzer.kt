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

/** State the meter engine publishes to the UI. */
data class MeterEngineState(
    val reading: MeterReading?,
    val aeConverged: Boolean,
    val cameraIso: Int?,
    val cameraShutterNs: Long?,
    val cameraAperture: Float?,
    val histogram: IntArray?,
    val source: MeteringSource,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val rotationDegrees: Int = 0,
)

/**
 * CameraX ImageAnalysis analyzer that meters the preview stream.
 *
 * The YUV path mirrors lightstop's CompatibleLightMeter: per-frame robust luma over the
 * metering ROI, linearized through the reported tonemap, converted to EV100 with the
 * frame's own exposure metadata. Camera2ImageInfo gives each analysis frame its full
 * CaptureResult, so no manual timestamp pairing is needed.
 *
 * A reading is only accepted while the camera's exposure has been stable for
 * [STABLE_FRAME_WINDOW] frames (or AE explicitly reports CONVERGED); stable frames feed a
 * small ring whose median is published. This keeps flicker out of the readout without
 * freezing the live histogram.
 */
class MeterAnalyzer(
    private val onState: (MeterEngineState) -> Unit,
    @Volatile var defaultAperture: Float,
    private val resultStore: CaptureResultStore,
) : ImageAnalysis.Analyzer {

    @Volatile var meteringMode: MeteringMode = MeteringMode.CENTER_WEIGHTED
    @Volatile var spotX: Float = 0.5f
    @Volatile var spotY: Float = 0.5f
    @Volatile var calibrationEv: Double = 0.0

    private val statRing = ArrayDeque<MeteringFrameStat>()
    private val exposureHistory = ArrayDeque<FrameExposure>()
    private var lastPublishedReading: MeterReading? = null
    private var lastHistogram: IntArray? = null
    private var frameCounter = 0

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun analyze(image: ImageProxy) {
        try {
            val captureResult = resultStore.take(image.imageInfo.timestamp)
            if (captureResult == null) return

            val exposure = frameExposure(captureResult) ?: return
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
            val spotCenterX = if (mode == MeteringMode.SPOT) spotX else 0.5f
            val spotCenterY = if (mode == MeteringMode.SPOT) spotY else 0.5f
            val spot = analyzer.regionStat(
                spotCenterX,
                spotCenterY,
                EvMath.SPOT_ROI_FRACTION,
            )
            val region: RegionStat? = when (mode) {
                MeteringMode.CENTER_WEIGHTED -> {
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
                MeteringMode.SPOT -> spot
            }

            val converged = exposureStable(exposure) || aeStateConverged(captureResult)
            val stat = region?.let {
                EvMath.ev100(exposure, it.luma, calibrationEv)?.let { ev ->
                    MeteringFrameStat(
                        ev100 = ev,
                        luma = it.luma,
                        clipped = it.clippedFraction,
                        captureIso = exposure.sensitivity,
                        exposureTimeNs = exposure.exposureTimeNs,
                        aperture = exposure.aperture,
                        ev100BeforeUserCalibration = ev - calibrationEv,
                        appliedUserCorrectionEv = calibrationEv,
                    )
                }
            }
            if (converged && stat != null) {
                statRing.addLast(stat)
                while (statRing.size > STAT_RING_SIZE) statRing.removeFirst()
            } else if (!converged) {
                statRing.clear()
            }

            if (frameCounter % HISTOGRAM_INTERVAL_FRAMES == 0) {
                lastHistogram = analyzer.histogram()
            }
            frameCounter++
            if (frameCounter % LOG_INTERVAL_FRAMES == 0) {
                val last = statRing.lastOrNull()
                android.util.Log.i(
                    TAG,
                    "ev100=${last?.ev100} luma=${last?.luma} converged=$converged " +
                        "ring=${statRing.size} iso=${exposure.sensitivity} " +
                        "t_ns=${exposure.exposureTimeNs} aperture=${exposure.aperture} " +
                        "tonemap=${captureResult.get(CaptureResult.TONEMAP_MODE)} " +
                        "pairMissed=${resultStore.missedCount}",
                )
            }

            val fused = if (statRing.isNotEmpty()) {
                MeteringFusion.fuse(statRing.toList(), MeteringSource.YUV_PREVIEW)
            } else {
                null
            }
            // Suppress duplicate emissions: only publish when the median EV actually moved.
            val publishReading = fused?.takeIf { new ->
                val last = lastPublishedReading
                last == null || abs(new.sceneEv100 - last.sceneEv100) >= PUBLISH_DELTA_EV
            }
            if (publishReading != null) lastPublishedReading = publishReading

            onState(
                MeterEngineState(
                    reading = publishReading ?: lastPublishedReading,
                    aeConverged = converged,
                    cameraIso = exposure.sensitivity,
                    cameraShutterNs = exposure.exposureTimeNs,
                    cameraAperture = exposure.aperture,
                    histogram = lastHistogram,
                    source = MeteringSource.YUV_PREVIEW,
                    analysisWidth = image.width,
                    analysisHeight = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
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
        lastHistogram = null
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
        private const val HISTOGRAM_INTERVAL_FRAMES = 4
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
