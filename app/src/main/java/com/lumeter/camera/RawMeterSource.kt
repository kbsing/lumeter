package com.lumeter.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.lumeter.core.meter.CfaPattern
import com.lumeter.core.meter.EvMath
import com.lumeter.core.meter.FrameExposure
import com.lumeter.core.meter.MeteringMode
import com.lumeter.core.meter.MeteringSource
import com.lumeter.core.meter.RawBayerStats
import com.lumeter.core.meter.RawRegionStat
import com.lumeter.core.meter.RoiGeometry
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * RAW_SENSOR metering source: a plain Camera2 session (preview SurfaceTexture + a small
 * RAW ImageReader) metered on Bayer statistics instead of the ISP's tonemapped YUV.
 *
 * This bypasses tonemap inversion entirely, which is the point: devices whose HAL
 * reports no tonemap curve (mode FAST with an sRGB fallback) get a systematic EV bias
 * on the YUV path that RAW reads straight past. The same stability gate, fusion and
 * publishing contract as the YUV analyzer are shared through [ReadingAccumulator].
 *
 * Owns its camera device, session, reader and handler thread; [stop] releases all of it.
 * Opening retries a few times because CameraX's unbind of the same camera device
 * completes asynchronously and can briefly still hold it.
 */
class RawMeterSource(
    private val context: Context,
    private val onState: (MeterEngineState) -> Unit,
    private val onError: (reason: String) -> Unit,
) {

    @Volatile var meteringMode: MeteringMode = MeteringMode.CENTER
    @Volatile var spots: List<MeterSpot> = emptyList()
    @Volatile var hold: Boolean = false
    @Volatile var live: Boolean = true
    /** When paused, take exactly one more stable reading, then freeze again. */
    @Volatile var oneShot = false

    @Volatile var active: Boolean = false
        private set

    private val resultStore = CaptureResultStore()
    private val accumulator = ReadingAccumulator(MeteringSource.RAW)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var rawSize = Size(0, 0)
    private var previewSize = Size(0, 0)
    private var sensorOrientation = 90
    private var cfa: CfaPattern? = null
    private var fixedBlackLevels: FloatArray? = null
    private var whiteLevel = 0
    private var defaultAperture = 2.0f
    private var openRetries = 0
    private var frameCounter = 0

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Back camera id with RAW_SENSOR output, or null when the device has none. */
    fun findRawCameraId(): String? = runCatching {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) {
                continue
            }
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) continue
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            if (!map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()) return id
        }
        null
    }.getOrNull()

    /**
     * Opens the camera and starts the repeating capture on [surfaceTexture] (the caller
     * keeps rendering it). [onReady] fires on the main thread once frames flow; every
     * terminal failure calls [onError] exactly once.
     */
    @SuppressLint("MissingPermission")
    fun start(surfaceTexture: SurfaceTexture, onReady: () -> Unit) {
        val id = findRawCameraId() ?: run {
            onError("no RAW back camera")
            return
        }
        previewSurfaceTexture = surfaceTexture
        cameraId = id
        val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: run {
            onError("characteristics unavailable")
            return
        }
        characteristics = chars
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.let(CfaPattern::fromId)
        whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
        fixedBlackLevels = fixedBlackLevels(chars)
        defaultAperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull() ?: 2.0f
        if (cfa == null || whiteLevel <= 0 || fixedBlackLevels == null) {
            onError("RAW sensor metadata incomplete")
            return
        }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
            onError("stream configuration unavailable")
            return
        }
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().toList()
        val raw = pickRawSize(rawSizes)
        if (raw == null) {
            onError("no usable RAW size")
            return
        }
        rawSize = raw
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList()
        val preview = pickPreviewSize(previewSizes, raw)
        previewSize = preview
        surfaceTexture.setDefaultBufferSize(preview.width, preview.height)

        thread = HandlerThread("RawMeterSource").also { it.start() }
        handler = Handler(thread!!.looper)

        openWithRetry(onReady)
    }

    private fun openWithRetry(onReady: () -> Unit) {
        val id = cameraId ?: return
        runCatching {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    if (device === camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    if (device === camera) device = null
                    reportError("camera open failed (code $error)")
                }
            }, handler)
        }.onFailure { error ->
            if (openRetries < MAX_OPEN_RETRIES) {
                openRetries++
                handler?.postDelayed({ openWithRetry(onReady) }, OPEN_RETRY_DELAY_MS)
            } else {
                reportError("camera busy after retries: ${error.message}")
            }
        }
    }

    private fun createSession(camera: CameraDevice, onReady: () -> Unit) {
        val surfaceTexture = previewSurfaceTexture ?: return
        val newReader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            MAX_IN_FLIGHT_IMAGES,
        )
        newReader.setOnImageAvailableListener({ onRawImage(it) }, handler)
        reader = newReader

        val previewSurface = android.view.Surface(surfaceTexture)
        val readerSurface = newReader.surface
        val request = runCatching {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }.build()
        }.getOrElse {
            reportError("capture request failed: ${it.message}")
            return
        }

        runCatching {
            camera.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (device !== camera) {
                            runCatching { configured.close() }
                            return
                        }
                        session = configured
                        try {
                            configured.setRepeatingRequest(
                                request,
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult,
                                    ) {
                                        resultStore.offer(result)
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: CaptureFailure,
                                    ) {
                                        Log.w(TAG, "capture failed: reason=${failure.reason}")
                                    }
                                },
                                handler,
                            )
                            active = true
                            openRetries = 0
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onReady()
                            }
                        } catch (e: Exception) {
                            reportError("repeating request failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        reportError("session configuration failed")
                    }
                },
                handler,
            )
        }.getOrElse {
            reportError("session creation failed: ${it.message}")
        }
    }

    /** Releases the camera, session, reader and handler thread. */
    fun stop() {
        active = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        previewSurfaceTexture = null
        resultStore.clear()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun onRawImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val result = resultStore.take(image.timestamp) ?: return
            processFrame(image, result)
        } finally {
            image.close()
        }
    }

    private fun processFrame(image: Image, result: CaptureResult) {
        if (hold || (!live && !oneShot)) {
            onState(frozenState())
            return
        }
        val exposure = frameExposure(result) ?: return
        val stat = meterFrame(image, result, exposure)

        val converged = accumulator.exposureStable(exposure) ||
            result.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_CONVERGED
        if (oneShot && converged && stat != null) {
            oneShot = false
        }
        val snapshot = accumulator.offer(exposure, converged, stat)

        val mode = meteringMode
        val spotEvs = if (mode == MeteringMode.MULTI) {
            spots.map { spot ->
                meterSpot(image, result, exposure, spot.frameU, spot.frameV)
            }
        } else {
            emptyList()
        }
        frameCounter++
        if (frameCounter % LOG_INTERVAL_FRAMES == 1) {
            Log.i(
                TAG,
                "RAW ev100=${stat?.ev100} luma=${stat?.luma} converged=$converged " +
                    "mode=$mode raw=${rawSize.width}x${rawSize.height} " +
                    "iso=${exposure.sensitivity} t_ns=${exposure.exposureTimeNs}",
            )
        }

        onState(
            MeterEngineState(
                reading = snapshot.display,
                spotEvs = if (mode == MeteringMode.MULTI) spotEvs else emptyList(),
                aeConverged = converged,
                cameraIso = exposure.sensitivity,
                cameraShutterNs = exposure.exposureTimeNs,
                cameraAperture = exposure.aperture,
                source = MeteringSource.RAW,
                analysisWidth = rawSize.width,
                analysisHeight = rawSize.height,
                rotationDegrees = sensorOrientation,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
            ),
        )
    }

    private fun frozenState(): MeterEngineState = MeterEngineState(
        reading = accumulator.frozen(false).display,
        source = MeteringSource.RAW,
        analysisWidth = rawSize.width,
        analysisHeight = rawSize.height,
        rotationDegrees = sensorOrientation,
        previewWidth = previewSize.width,
        previewHeight = previewSize.height,
    )

    /** Mode ROI statistics over one RAW frame, or null when the ROI says nothing. */
    private fun meterFrame(
        image: Image,
        result: CaptureResult,
        exposure: FrameExposure,
    ): com.lumeter.core.meter.MeteringFrameStat? {
        val region = when (meteringMode) {
            MeteringMode.MATRIX -> rawRegionStat(image, result, 0.5f, 0.5f, 1.0f)
            MeteringMode.CENTER -> {
                val spot = rawRegionStat(image, result, 0.5f, 0.5f, EvMath.SPOT_ROI_FRACTION)
                val wide = rawRegionStat(image, result, 0.5f, 0.5f, EvMath.CENTER_WEIGHTED_ROI_FRACTION)
                if (spot != null && wide != null) {
                    combineCenter(spot, wide)
                } else {
                    spot ?: wide
                }
            }
            MeteringMode.SPOT -> rawRegionStat(image, result, 0.5f, 0.5f, EvMath.SPOT_ROI_FRACTION)
            MeteringMode.MULTI -> rawRegionStat(image, result, 0.5f, 0.5f, 1.0f)
        } ?: return null
        val luma = regionLuma(region, result)
        val ev = EvMath.ev100(exposure, luma) ?: return null
        return com.lumeter.core.meter.MeteringFrameStat(
            ev100 = ev,
            luma = luma,
            clipped = region.clippedFraction,
            captureIso = exposure.sensitivity,
            exposureTimeNs = exposure.exposureTimeNs,
            aperture = exposure.aperture,
        )
    }

    private fun meterSpot(
        image: Image,
        result: CaptureResult,
        exposure: FrameExposure,
        u: Float,
        v: Float,
    ): Double {
        val stat = rawRegionStat(image, result, u, v, EvMath.SPOT_ROI_FRACTION) ?: return Double.NaN
        val luma = regionLuma(stat, result)
        return EvMath.ev100(exposure, luma) ?: Double.NaN
    }

    private fun rawRegionStat(
        image: Image,
        result: CaptureResult,
        u: Float,
        v: Float,
        roiFraction: Float,
    ): RawRegionStat? {
        val cfa = cfa ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) ?: fixedBlackLevels ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: whiteLevel
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 2) return null
        val byteOrder = buffer.order()
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val roi = RoiGeometry.roiRect(image.width, image.height, u, v, roiFraction)
            return RawBayerStats.regionStat(
                reader = { x, y ->
                    val offset = y * rowStride + x * pixelStride
                    if (offset < 0 || offset + 1 >= buffer.capacity()) {
                        null
                    } else {
                        buffer.getShort(offset).toInt() and 0xFFFF
                    }
                },
                cfa = cfa,
                blackLevels = black,
                whiteLevel = white,
                roi = roi,
            )
        } finally {
            buffer.order(byteOrder)
        }
    }

    /** Luminance of a RAW region: color-corrected when the HAL reports gains + matrix. */
    private fun regionLuma(region: RawRegionStat, result: CaptureResult): Double {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (gains != null && transform != null) {
            val r = region.red * gains.red
            val g = (region.greenEven * gains.greenEven + region.greenOdd * gains.greenOdd) * 0.5
            val b = region.blue * gains.blue
            val linear = Triple(
                element(transform, 0, 0) * r + element(transform, 1, 0) * g + element(transform, 2, 0) * b,
                element(transform, 0, 1) * r + element(transform, 1, 1) * g + element(transform, 2, 1) * b,
                element(transform, 0, 2) * r + element(transform, 1, 2) * g + element(transform, 2, 2) * b,
            )
            return 0.2126 * linear.first + 0.7152 * linear.second + 0.0722 * linear.third
        }
        return region.luminance()
    }

    private fun element(transform: ColorSpaceTransform, column: Int, row: Int): Double =
        transform.getElement(column, row).toDouble()

    private fun combineCenter(
        spot: RawRegionStat,
        wide: RawRegionStat,
    ): RawRegionStat = RawRegionStat(
        red = spot.red * EvMath.CENTER_SPOT_WEIGHT + wide.red * EvMath.CENTER_WIDE_WEIGHT,
        greenEven = spot.greenEven * EvMath.CENTER_SPOT_WEIGHT + wide.greenEven * EvMath.CENTER_WIDE_WEIGHT,
        greenOdd = spot.greenOdd * EvMath.CENTER_SPOT_WEIGHT + wide.greenOdd * EvMath.CENTER_WIDE_WEIGHT,
        blue = spot.blue * EvMath.CENTER_SPOT_WEIGHT + wide.blue * EvMath.CENTER_WIDE_WEIGHT,
        clippedFraction = spot.clippedFraction * EvMath.CENTER_SPOT_WEIGHT +
            wide.clippedFraction * EvMath.CENTER_WIDE_WEIGHT,
        samples = spot.samples + wide.samples,
    )

    private fun frameExposure(result: CaptureResult): FrameExposure? {
        val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: defaultAperture
        if (time <= 0 || sensitivity <= 0 || aperture <= 0f) return null
        return FrameExposure(time, sensitivity, aperture)
    }

    private fun reportError(reason: String) {
        Log.i(TAG, "RAW metering unavailable: $reason")
        active = false
        onError(reason)
    }

    private fun fixedBlackLevels(chars: CameraCharacteristics): FloatArray? {
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return null
        val levels = FloatArray(4)
        for (position in 0..3) {
            val column = position and 1
            val row = position shr 1
            levels[position] = runCatching { pattern.getOffsetForIndex(column, row) }
                .getOrElse { return null }.toFloat()
        }
        return levels
    }

    /** Smallest RAW size that still covers the metering ROIs comfortably. */
    private fun pickRawSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        return sizes.filter { it.width >= MIN_RAW_WIDTH && it.height >= MIN_RAW_HEIGHT }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    /** Preview size closest to [targetWidth] px on the RAW stream's aspect. */
    private fun pickPreviewSize(sizes: List<Size>, raw: Size): Size {
        val aspect = raw.width.toDouble() / raw.height
        val target = PREVIEW_TARGET_WIDTH
        return sizes.minByOrNull { size ->
            val sizeAspect = size.width.toDouble() / size.height
            val aspectPenalty = abs(ln(sizeAspect / aspect)) * 1000
            val widthPenalty = abs(size.width - target).toDouble()
            aspectPenalty + widthPenalty
        } ?: Size(PREVIEW_TARGET_WIDTH, PREVIEW_TARGET_WIDTH)
    }

    private companion object {
        private const val TAG = "LumeterRaw"
        private const val MAX_OPEN_RETRIES = 3
        private const val OPEN_RETRY_DELAY_MS = 300L
        private const val MAX_IN_FLIGHT_IMAGES = 3
        private const val MIN_RAW_WIDTH = 640
        private const val MIN_RAW_HEIGHT = 480
        private const val PREVIEW_TARGET_WIDTH = 1024
        private const val LOG_INTERVAL_FRAMES = 30
    }
}
