package com.lumeter.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX binding for metering: preview + YUV analysis, with per-frame
 * CaptureResult delivered through a Camera2Interop session callback paired by
 * SENSOR_TIMESTAMP. All measurement happens in [MeterAnalyzer].
 */
class CameraManager(private val context: Context) {

    private val resultStore = CaptureResultStore()
    private var provider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null

    var analyzer: MeterAnalyzer? = null
        private set

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onState: (MeterEngineState) -> Unit,
    ) {
        if (analyzer != null) return
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        val meter = MeterAnalyzer(
            onState = onState,
            defaultAperture = 2.0f,
            resultStore = resultStore,
        )
        analyzer = meter

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        resultStore.offer(result)
                    }
                },
            )
            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(exec, meter) }

            runCatching {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                meter.defaultAperture = backCameraAperture(camera)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        resultStore.clear()
        executor?.shutdown()
        analyzer = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun backCameraAperture(camera: androidx.camera.core.Camera): Float =
        runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 2.0f
        }.getOrDefault(2.0f)
}
