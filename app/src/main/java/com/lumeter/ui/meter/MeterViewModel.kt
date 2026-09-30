package com.lumeter.ui.meter

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.lumeter.camera.CaptureResultStore
import com.lumeter.camera.MeterAnalyzer
import com.lumeter.camera.MeterEngineState
import com.lumeter.core.meter.MeteringMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class MeterUiState(
    val ev100: Double? = null,
    val aeConverged: Boolean = false,
    val cameraIso: Int? = null,
    val cameraShutterNs: Long? = null,
    val cameraAperture: Float? = null,
    val histogram: IntArray? = null,
    val meteringMode: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val targetIso: Int = 400,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val cameraReady: Boolean = false,
)

class MeterViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MeterUiState())
    val uiState: StateFlow<MeterUiState> = _uiState.asStateFlow()

    private var analysisExecutor: ExecutorService? = null
    private val resultStore = CaptureResultStore()
    private var boundProvider: ProcessCameraProvider? = null

    /** Spot position in normalized analysis-frame coordinates. */
    @Volatile var spotFrameU: Float = 0.5f
        private set
    @Volatile var spotFrameV: Float = 0.5f
        private set

    lateinit var analyzer: MeterAnalyzer
        private set

    val analyzerReady: Boolean get() = ::analyzer.isInitialized

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (analyzerReady) return
        val appContext = getApplication<Application>()
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor

        analyzer = MeterAnalyzer(
            onState = ::onEngineState,
            defaultAperture = 2.0f,
            resultStore = resultStore,
        )
        analyzer.meteringMode = _uiState.value.meteringMode

        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
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
                        request: android.hardware.camera2.CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        resultStore.offer(result)
                    }
                },
            )
            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(executor, analyzer) }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            boundProvider = provider
            analyzer.defaultAperture = backCameraDefaultAperture(camera.cameraInfo.cameraSelector, appContext)
            _uiState.value = _uiState.value.copy(cameraReady = true)
        }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
    }

    fun setMode(mode: MeteringMode) {
        if (analyzerReady) analyzer.meteringMode = mode
        _uiState.value = _uiState.value.copy(meteringMode = mode)
    }

    fun setSpotFramePosition(u: Float, v: Float) {
        spotFrameU = u.coerceIn(0.02f, 0.98f)
        spotFrameV = v.coerceIn(0.02f, 0.98f)
        if (analyzerReady) {
            analyzer.spotX = spotFrameU
            analyzer.spotY = spotFrameV
        }
    }

    fun cycleTargetIso() {
        val next = TARGET_ISO_CYCLE.firstOrNull { it > _uiState.value.targetIso } ?: TARGET_ISO_CYCLE.first()
        _uiState.value = _uiState.value.copy(targetIso = next)
    }

    private fun onEngineState(state: MeterEngineState) {
        val now = System.nanoTime()
        if (now - lastEmitNanos < EMIT_INTERVAL_NS) return
        lastEmitNanos = now
        _uiState.value = _uiState.value.copy(
            ev100 = state.reading?.sceneEv100 ?: _uiState.value.ev100,
            aeConverged = state.aeConverged,
            cameraIso = state.cameraIso,
            cameraShutterNs = state.cameraShutterNs,
            cameraAperture = state.cameraAperture,
            histogram = state.histogram ?: _uiState.value.histogram,
            analysisWidth = state.analysisWidth,
            analysisHeight = state.analysisHeight,
            rotationDegrees = state.rotationDegrees,
        )
    }

    private var lastEmitNanos = 0L

    @OptIn(ExperimentalCamera2Interop::class)
    private fun backCameraDefaultAperture(selector: CameraSelector, context: Context): Float =
        runCatching {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val cameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?.let { Camera2CameraInfo.from(it) }
            cameraInfo?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 2.0f
        }.getOrDefault(2.0f)

    override fun onCleared() {
        runCatching { boundProvider?.unbindAll() }
        resultStore.clear()
        analysisExecutor?.shutdown()
        super.onCleared()
    }

    private companion object {
        val TARGET_ISO_CYCLE = listOf(100, 200, 400, 800, 1600, 3200)
        private const val EMIT_INTERVAL_NS = 100_000_000L // 10 Hz readout, histogram included
    }
}
