package com.lumeter.camera

import android.annotation.SuppressLint
import android.hardware.DataSpace
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.os.Build
import com.lumeter.core.meter.ProcessedLumaDecoder
import com.lumeter.core.meter.ProcessedToneCurve
import com.lumeter.core.meter.ProcessedTransfer
import com.lumeter.core.meter.YuvCodeRange
import com.lumeter.core.meter.YuvColorEncoding

/**
 * Converts Android camera metadata into the pure processed-luma model.
 * Adapted from lightstop ProcessedLumaMetadata.kt.
 */
object ProcessedLumaMetadata {
    /** Caches the built decoder while the reported tonemap object/mode stays the same. */
    private var cachedCurve: TonemapCurve? = null
    private var cachedTransferToken: Any? = null
    private var cachedDecoder: ProcessedLumaDecoder? = null

    @Synchronized
    fun decoder(result: CaptureResult): ProcessedLumaDecoder {
        val curve = result.get(CaptureResult.TONEMAP_CURVE)
        val mode = result.get(CaptureResult.TONEMAP_MODE)
        val token = if (curve != null) curve else ("mode" to (mode ?: -1))
        if (curve === cachedCurve && token == cachedTransferToken && cachedDecoder != null) {
            return cachedDecoder!!
        }
        val decoder = ProcessedLumaDecoder(transfer(result))
        cachedCurve = curve
        cachedTransferToken = token
        cachedDecoder = decoder
        return decoder
    }

    /** YUV encoding of an [Image]; Camera2 defaults to full-range JFIF BT.601. */
    @SuppressLint("WrongConstant")
    fun yuvEncodingFromDataSpace(dataSpace: Int): YuvColorEncoding {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return YuvColorEncoding()
        return runCatching {
            val range = when (DataSpace.getRange(dataSpace)) {
                DataSpace.RANGE_LIMITED -> YuvCodeRange.LIMITED
                else -> YuvCodeRange.FULL
            }
            val coefficients = when (DataSpace.getStandard(dataSpace)) {
                DataSpace.STANDARD_BT709 -> 0.2126 to 0.0722
                DataSpace.STANDARD_BT2020,
                DataSpace.STANDARD_BT2020_CONSTANT_LUMINANCE,
                -> 0.2627 to 0.0593
                else -> 0.299 to 0.114
            }
            YuvColorEncoding(
                range = range,
                redLumaCoefficient = coefficients.first,
                blueLumaCoefficient = coefficients.second,
            )
        }.getOrDefault(YuvColorEncoding())
    }

    private fun transfer(result: CaptureResult): ProcessedTransfer {
        result.get(CaptureResult.TONEMAP_CURVE)?.let(::reportedCurves)?.let { return it }
        return when (result.get(CaptureResult.TONEMAP_MODE)) {
            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> result
                .get(CaptureResult.TONEMAP_GAMMA)
                ?.toDouble()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let(ProcessedTransfer::GAMMA)
                ?: ProcessedTransfer.SRGB
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> when (
                result.get(CaptureResult.TONEMAP_PRESET_CURVE)
            ) {
                CameraMetadata.TONEMAP_PRESET_CURVE_REC709 -> ProcessedTransfer.REC709
                else -> ProcessedTransfer.SRGB
            }
            else -> ProcessedTransfer.SRGB
        }
    }

    private fun reportedCurves(curve: TonemapCurve): ProcessedTransfer.CURVES? = runCatching {
        ProcessedTransfer.CURVES(
            red = ProcessedToneCurve(curvePoints(curve, TonemapCurve.CHANNEL_RED)),
            green = ProcessedToneCurve(curvePoints(curve, TonemapCurve.CHANNEL_GREEN)),
            blue = ProcessedToneCurve(curvePoints(curve, TonemapCurve.CHANNEL_BLUE)),
        )
    }.getOrNull()

    private fun curvePoints(curve: TonemapCurve, channel: Int): DoubleArray {
        val points = FloatArray(curve.getPointCount(channel) * TonemapCurve.POINT_SIZE)
        curve.copyColorCurve(channel, points, 0)
        return DoubleArray(points.size) { points[it].toDouble() }
    }
}
