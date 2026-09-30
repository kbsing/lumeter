package com.lumeter.ui.meter

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumeter.camera.PreviewGeometry
import com.lumeter.core.exposure.ExposureMath
import com.lumeter.core.meter.EvMath
import com.lumeter.core.meter.MeteringMode

@Composable
fun MeterScreen(viewModel: MeterViewModel = viewModel()) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCameraPermission) {
            CameraPreviewLayer(viewModel)
            MeterOverlay(viewModel)
        } else {
            PermissionPrompt(
                Modifier.align(Alignment.Center),
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

@Composable
private fun PermissionPrompt(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "光度计需要相机权限进行测光",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("授予权限") }
    }
}

@Composable
private fun CameraPreviewLayer(viewModel: MeterViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.camera.view.PreviewView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                viewModel.bindCamera(this, lifecycleOwner)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun MeterOverlay(viewModel: MeterViewModel) {
    val state by viewModel.uiState.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()

        TopStatusRow(state)

        if (state.meteringMode == MeteringMode.SPOT &&
            state.analysisWidth > 0 && state.analysisHeight > 0 &&
            viewWidth > 0 && viewHeight > 0
        ) {
            SpotInteractionLayer(
                viewModel = viewModel,
                analysisWidth = state.analysisWidth,
                analysisHeight = state.analysisHeight,
                rotationDegrees = state.rotationDegrees,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                spotFrameU = viewModel.spotFrameU,
                spotFrameV = viewModel.spotFrameV,
            )
        }

        BottomPanel(
            viewModel = viewModel,
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun TopStatusRow(state: MeterUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC000000), Color(0x00000000)),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LUMETER",
            color = Color(0xFFFFB300),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "YUV",
            color = Color(0x99FFB300),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .background(Color(0x22FFB300), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (state.aeConverged) "AE ✓" else "AE…",
            color = if (state.aeConverged) Color(0xFF9CCC65) else Color(0xFF8E8E96),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .background(Color(0x22000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SpotInteractionLayer(
    viewModel: MeterViewModel,
    analysisWidth: Int,
    analysisHeight: Int,
    rotationDegrees: Int,
    viewWidth: Float,
    viewHeight: Float,
    spotFrameU: Float,
    spotFrameV: Float,
) {
    val transform = remember(analysisWidth, analysisHeight, rotationDegrees, viewWidth, viewHeight) {
        PreviewGeometry.FillCenterTransform(
            rotationDegrees = rotationDegrees,
            mirrored = false, // back camera
            frameWidth = analysisWidth,
            frameHeight = analysisHeight,
            viewWidth = viewWidth.toInt(),
            viewHeight = viewHeight.toInt(),
        )
    }
    val (markerX, markerY) = transform.frameToScreen(spotFrameU, spotFrameV)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val (u, v) = transform.screenToFrame(offset.x, offset.y)
                    viewModel.setSpotFramePosition(u, v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val (u, v) = transform.screenToFrame(change.position.x, change.position.y)
                    viewModel.setSpotFramePosition(u, v)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = 22.dp.toPx()
            val inner = 3.dp.toPx()
            drawCircle(
                color = Color(0xFFFFB300),
                radius = radius,
                center = Offset(markerX, markerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()),
            )
            drawCircle(
                color = Color(0xFFFFB300),
                radius = inner,
                center = Offset(markerX, markerY),
            )
            // Crosshair ticks
            val tick = radius + 8.dp.toPx()
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(markerX - tick, markerY),
                end = Offset(markerX - radius - 2.dp.toPx(), markerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(markerX + radius + 2.dp.toPx(), markerY),
                end = Offset(markerX + tick, markerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(markerX, markerY - tick),
                end = Offset(markerX, markerY - radius - 2.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(markerX, markerY + radius + 2.dp.toPx()),
                end = Offset(markerX, markerY + tick),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BottomPanel(
    viewModel: MeterViewModel,
    state: MeterUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x00000000), Color(0xE6000000), Color(0xFF000000)),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        HistogramCanvas(
            histogram = state.histogram,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )

        Spacer(Modifier.height(10.dp))

        // Main EV readout with the camera's own exposure underneath.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.ev100?.let { "%.1f".format(it) } ?: "--.-",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "EV100",
                color = Color(0xFF8E8E96),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                CameraExposureLine(state)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Recommended exposure triangle at the user's selected ISO.
        val pair = ExposureMath.primaryPair(state.ev100, state.targetIso, null, null)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RecommendCell(
                label = "光圈",
                value = ExposureMath.formatAperture(pair.aperture),
            )
            RecommendCell(
                label = "快门",
                value = ExposureMath.formatShutter(pair.shutterSeconds),
            )
            RecommendCell(
                label = "ISO",
                value = state.targetIso.toString(),
                onClick = { viewModel.cycleTargetIso() },
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            ModePill(
                label = "中央重点",
                selected = state.meteringMode == MeteringMode.CENTER_WEIGHTED,
                onClick = { viewModel.setMode(MeteringMode.CENTER_WEIGHTED) },
            )
            ModePill(
                label = "点测",
                selected = state.meteringMode == MeteringMode.SPOT,
                onClick = { viewModel.setMode(MeteringMode.SPOT) },
            )
        }
    }
}

@Composable
private fun CameraExposureLine(state: MeterUiState) {
    val text = buildString {
        append("相机 ")
        state.cameraAperture?.let { append("f/${"%.1f".format(it)} ") }
        state.cameraShutterNs?.let { append("${ExposureMath.formatShutter(it / 1_000_000_000.0)} ") }
        state.cameraIso?.let { append("ISO $it") }
    }
    Text(
        text,
        color = Color(0xFF8E8E96),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

@Composable
private fun RecommendCell(label: String, value: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) {
        Modifier
            .background(Color(0x14FFB300), RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    } else {
        Modifier
            .background(Color(0x11FFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
        )
        Text(
            label,
            color = Color(0xFF8E8E96),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Color(0xFFFFB300) else Color(0x22FFFFFF)
    val content = if (selected) Color(0xFF1A1200) else Color(0xFFCFCFD4)
    Box(
        Modifier
            .background(background, RoundedCornerShape(50))
            .pointerInput(selected) { detectTapGestures { onClick() } }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistogramCanvas(histogram: IntArray?, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0x11000000))) {
        val bins = histogram ?: return@Canvas
        if (bins.isEmpty()) return@Canvas
        val maxCount = bins.max()
        if (maxCount <= 0) return@Canvas
        val barWidth = size.width / bins.size
        // 18% gray reference line, where a correctly-exposed midtone sits.
        val referenceX = size.width * EvMath.REFERENCE_LUMA.toFloat()
        drawLine(
            color = Color(0x66FFB300),
            start = Offset(referenceX, 0f),
            end = Offset(referenceX, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        bins.forEachIndexed { index, count ->
            val barHeight = size.height * (count.toFloat() / maxCount)
            drawRect(
                color = Color(0xFFEDEDEF),
                topLeft = Offset(index * barWidth, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth + 0.5f, barHeight),
            )
        }
    }
}
