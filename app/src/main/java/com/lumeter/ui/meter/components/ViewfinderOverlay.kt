package com.lumeter.ui.meter.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.meter.MeteringMode
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.camera.PreviewGeometry
import com.lumeter.ui.theme.BarlowCondensed
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.DisplayLarge
import com.lumeter.ui.theme.DisplayMedium
import com.lumeter.ui.theme.LabelTiny
import androidx.compose.ui.res.stringResource
import com.lumeter.R
import com.lumeter.ui.theme.LumenPalette
import kotlin.math.roundToInt

/**
 * Viewfinder container: hosts the camera preview plus every overlay from the reference
 * design — corner marks, mode reticle, MULTI spots, status line, LUX, the big EV readout
 * and the ±3 exposure scale.
 */
@Composable
fun Viewfinder(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val vm = appViewModel
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    BoxWithConstraints(
        modifier
            .background(ColorPanel)
            .clip(RoundedCornerShape(0.dp)),
    ) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()

        LaunchedEffect(previewView, lifecycleOwner) {
            vm.startCamera(lifecycleOwner, previewView)
        }

        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap layer: MULTI adds a spot, SPOT moves the single metering point.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val x = (offset.x / size.width).coerceIn(0.02f, 0.98f)
                        val y = (offset.y / size.height).coerceIn(0.02f, 0.98f)
                        when (vm.meteringMode) {
                            MeteringMode.MULTI -> vm.addSpot(x, y, vm.ev100 ?: 0.0)
                            MeteringMode.SPOT -> vm.setSpotPos(x, y)
                            else -> {}
                        }
                    }
                },
        )

        CornerMarks()
        ModeReticle(vm.meteringMode)

        if (vm.meteringMode == MeteringMode.MULTI || vm.meteringMode == MeteringMode.SPOT) {
            SpotLayer(vm, boxWidth, boxHeight)
            MultiSpotInfo(
                vm,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 44.dp),
            )
        }

        StatusLine(
            vm,
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp),
        )
        Text(
            "${vm.luxText} ${stringResource(R.string.lux)}",
            style = LabelTiny,
            color = ColorInk.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 16.dp),
        )

        ReadoutPanel(vm, Modifier.align(Alignment.BottomCenter))

        if (vm.flashKey > 0) {
            val alpha by animateFloatAsState(
                targetValue = 0f,
                animationSpec = tween(400),
                label = "flash",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.55f * maxOf(alpha, 0.0001f))),
            )
        }
    }
}

@Composable
private fun CornerMarks() {
    // True L-shaped viewfinder corners (two strokes each), not square outlines.
    val stroke = ColorInk.copy(alpha = 0.7f)
    Canvas(Modifier.fillMaxSize()) {
        val len = 20.dp.toPx()
        val w = 2.dp.toPx()
        val m = 14.dp.toPx()
        val corners = listOf(
            Offset(m, m) to listOf(Offset(1f, 0f), Offset(0f, 1f)),
            Offset(size.width - m, m) to listOf(Offset(-1f, 0f), Offset(0f, 1f)),
            Offset(m, size.height - m) to listOf(Offset(1f, 0f), Offset(0f, -1f)),
            Offset(size.width - m, size.height - m) to listOf(Offset(-1f, 0f), Offset(0f, -1f)),
        )
        corners.forEach { (corner, dirs) ->
            dirs.forEach { dir ->
                drawLine(
                    color = stroke,
                    start = corner,
                    end = Offset(corner.x + dir.x * len, corner.y + dir.y * len),
                    strokeWidth = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ModeReticle(mode: MeteringMode) {
    if (mode == MeteringMode.MULTI || mode == MeteringMode.SPOT) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shape = when (mode) {
            MeteringMode.SPOT -> Modifier
                .size(32.dp)
                .border(1.5.dp, ColorInk.copy(alpha = 0.7f), CircleShape)
            MeteringMode.CENTER -> Modifier
                .size(96.dp)
                .border(1.5.dp, ColorInk.copy(alpha = 0.7f), CircleShape)
            else -> Modifier
                .size(width = 160.dp, height = 112.dp)
                .border(1.5.dp, ColorInk.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
        }
        Box(shape) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ColorAccent),
            )
        }
    }
}

@Composable
private fun SpotLayer(vm: AppViewModel, boxWidth: Float, boxHeight: Float) {
    val isMulti = vm.meteringMode == MeteringMode.MULTI
    // Push spot positions to the engine whenever geometry or any spot moves.
    LaunchedEffect(
        vm.spots,
        vm.spotPos,
        vm.meteringMode,
        vm.analysisWidth,
        vm.analysisHeight,
        vm.rotationDegrees,
        boxWidth,
        boxHeight,
    ) {
        if (vm.analysisWidth > 0 && vm.analysisHeight > 0) {
            val transform = PreviewGeometry.FillCenterTransform(
                rotationDegrees = vm.rotationDegrees,
                mirrored = false,
                frameWidth = vm.analysisWidth,
                frameHeight = vm.analysisHeight,
                viewWidth = boxWidth.toInt(),
                viewHeight = boxHeight.toInt(),
            )
            val viewPoints = if (isMulti) {
                vm.spots.map { it.x to it.y }
            } else {
                listOf(vm.spotPos)
            }
            vm.pushEngineSpots(
                viewPoints.map { (x, y) -> transform.screenToFrame(x * boxWidth, y * boxHeight) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        val engineEvs = vm.spotDisplayEvs
        if (isMulti) {
            vm.spots.forEachIndexed { index, spot ->
                val ev = engineEvs.getOrNull(index)?.takeIf { it.isFinite() } ?: spot.ev100
                SpotHandle(
                    x = spot.x * boxWidth,
                    y = spot.y * boxHeight,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    label = "${index + 1} \u00b7 ${FormatUtils.evText(ev)}",
                    onDrag = { nx, ny -> vm.updateSpot(spot.id, nx / boxWidth, ny / boxHeight) },
                    onTap = { vm.removeSpot(spot.id) },
                )
            }
        } else {
            val ev = engineEvs.firstOrNull()?.takeIf { it.isFinite() } ?: vm.ev100 ?: 0.0
            SpotHandle(
                x = vm.spotPos.first * boxWidth,
                y = vm.spotPos.second * boxHeight,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                label = FormatUtils.evText(ev),
                onDrag = { nx, ny -> vm.setSpotPos(nx / boxWidth, ny / boxHeight) },
                onTap = null,
            )
        }
    }
}

/**
 * A small draggable metering spot: 24dp ring, wide invisible hit area, and an EV chip
 * that flips to the left when the spot is near the right screen edge.
 */
@Composable
private fun SpotHandle(
    x: Float,
    y: Float,
    boxWidth: Float,
    boxHeight: Float,
    label: String,
    onDrag: (Float, Float) -> Unit,
    onTap: (() -> Unit)?,
) {
    Box(
        Modifier
            .offset {
                val half = 24.dp.roundToPx() // half of the 48dp hit container
                androidx.compose.ui.unit.IntOffset(
                    (x - half).roundToInt(),
                    (y - half).roundToInt(),
                )
            }
            .size(48.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onDrag(
                            (x + amount.x).coerceIn(8f, boxWidth - 8f),
                            (y + amount.y).coerceIn(8f, boxHeight - 8f),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { onTap?.invoke() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, ColorAccent, CircleShape)
                    .background(ColorBody.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(ColorAccent),
                )
            }
        }
        // EV chip; flip to the left side when the spot hugs the right edge.
        val flipLeft = with(androidx.compose.ui.platform.LocalDensity.current) {
            x > boxWidth - 140.dp.toPx()
        }
        Text(
            label,
            fontSize = 10.sp,
            color = ColorInk,
            modifier = Modifier
                .align(if (flipLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .offset(x = if (flipLeft) (-46).dp else 30.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ColorBody.copy(alpha = 0.8f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusLine(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val dotColor = when {
            vm.aeHold -> ColorAccent
            vm.liveMetering -> LumenPalette.LiveRed
            else -> ColorDim
        }
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                vm.aeHold -> stringResource(R.string.locked)
                vm.liveMetering -> stringResource(R.string.live)
                else -> stringResource(R.string.paused)
            } + (if (vm.ndFilter > 0) " · ND${vm.ndFilter}" else ""),
            style = LabelTiny,
            color = ColorInk.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun ReadoutPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val solver = vm.solverResult
    val rightLabel = when (vm.exposureMode) {
        ExposureMode.APERTURE_PRIORITY -> stringResource(R.string.shutter).uppercase()
        ExposureMode.SHUTTER_PRIORITY -> stringResource(R.string.aperture).uppercase()
        ExposureMode.MANUAL -> stringResource(R.string.exposure).uppercase()
    }
    val rightValue = when (vm.exposureMode) {
        ExposureMode.APERTURE_PRIORITY ->
            solver?.let { FormatUtils.shutter(it.shutterSnapped) } ?: "--"
        ExposureMode.SHUTTER_PRIORITY ->
            solver?.let { FormatUtils.aperture(it.apertureSnapped) } ?: "--"
        ExposureMode.MANUAL ->
            solver?.let { FormatUtils.signed(it.diff) } ?: "±0.0"
    }
    val rightColor = when (vm.exposureMode) {
        ExposureMode.MANUAL -> if (vm.matched) ColorAccent else ColorInk
        else -> ColorAccent
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(ColorBody.copy(alpha = 0f), ColorBody.copy(alpha = 0.9f)),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(stringResource(R.string.ev_iso_100), style = LabelTiny, color = ColorDim)
                Text(
                    FormatUtils.evText(vm.ev100),
                    style = DisplayLarge,
                    color = ColorInk,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(rightLabel, style = LabelTiny, color = ColorDim)
                Text(rightValue, style = DisplayMedium, color = rightColor)
            }
        }
        ExposureScaleCanvas(
            needle = vm.needle,
            matched = vm.matched,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/** The ±3 EV needle scale at the bottom of the viewfinder. */
@Composable
fun ExposureScaleCanvas(needle: Double, matched: Boolean, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = needle.toFloat(),
        animationSpec = tween(150),
        label = "needle",
    )
    // Resolve the themed accent here: Canvas's draw scope is not composable.
    val accent = ColorAccent
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp),
        ) {
            val tickCount = 25
            val step = size.width / (tickCount - 1)
            repeat(tickCount) { i ->
                val long = i % 4 == 0
                val h = if (long) 14.dp.toPx() else 9.dp.toPx()
                drawLine(
                    color = ColorInk.copy(alpha = if (long) 0.7f else 0.4f),
                    start = Offset(i * step, 0f),
                    end = Offset(i * step, h),
                    strokeWidth = 1.5f,
                )
            }
            val x = ((animated + 3f) / 6f) * size.width
            // Soft glow under the needle, then the crisp needle.
            drawLine(
                color = accent.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 5.dp.toPx(),
            )
            drawLine(
                color = accent,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            listOf("−3", "−2", "−1", "0", "+1", "+2", "+3").forEachIndexed { i, label ->
                Text(
                    label,
                    fontSize = 9.sp,
                    color = if (i == 3 && matched) ColorAccent else ColorDim,
                )
            }
        }
    }
}
