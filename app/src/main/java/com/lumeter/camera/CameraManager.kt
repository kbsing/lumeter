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
 *
 * The Viewfinder rebuilds its PreviewView whenever the meter page re-enters
 * composition (returning from a sub page), and re-attaching a surface provider to the
 * existing Preview proved unreliable on this device (session stays, surface never
 * becomes ready → black viewfinder). Instead every start() rebinds the session with a
 * fresh Preview while the analysis use case and analyzer keep their state.
 */
class CameraManager(private val context: Context) {

    private val resultStore = CaptureResultStore()
    private var provider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var analysis: ImageAnalysis? = null

    var analyzer: MeterAnalyzer? = null
        private set

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onState: (MeterEngineState) -> Unit,
    ) {
        if (analyzer == null) {
            executor = Executors.newSingleThreadExecutor()
            analyzer = MeterAnalyzer(
                onState = onState,
                defaultAperture = 2.0f,
                resultStore = resultStore,
            )
        }
        val meter = analyzer ?: return
        val exec = executor ?: return

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()

            // Keep the analysis use case across rebinds (same builder config), so the
            // metering stream and the analyzer's state survive page switches.
            val useCase = analysis ?: ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .let { builder ->
                    Camera2Interop.Extender(builder).setSessionCaptureCallback(
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
                    builder.build().also { built ->
                        built.setAnalyzer(exec, meter)
                        analysis = built
                    }
                }

            val freshPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            runCatching {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    freshPreview,
                    useCase,
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
        analysis = null
        provider = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun backCameraAperture(camera: androidx.camera.core.Camera): Float =
        runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 2.0f
        }.getOrDefault(2.0f)
}
