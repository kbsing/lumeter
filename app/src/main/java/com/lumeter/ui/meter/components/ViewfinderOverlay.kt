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
import com.lumeter.core.exposure.ReadingInvalidReason
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
import kotlin.math.pow
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
                        if (vm.meteringMode == MeteringMode.MULTI) {
                            vm.addSpot(
                                (offset.x / size.width).coerceIn(0.02f, 0.98f),
                                (offset.y / size.height).coerceIn(0.02f, 0.98f),
                                vm.ev100 ?: 0.0,
                            )
                        }
                    }
                },
        )

        ModeReticle(vm)

        if (vm.meteringMode == MeteringMode.MULTI) {
            SpotLayer(vm, boxWidth, boxHeight)
            MultiSpotInfo(
                vm,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp),
            )
        }

        Text(
            "${vm.luxText} ${stringResource(R.string.lux)}",
            style = LabelTiny,
            color = ColorInk.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 10.dp),
        )

        ReadoutPanel(
            vm,
            Modifier.align(Alignment.BottomCenter),
            landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        )

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

/** Line color that stays legible on the given linear luma (auto-inverted). */
private fun contrastOn(luma: Double): Color =
    if (luma > 0.30) Color(0xD910100E) else Color(0xB3ECE8DF)

@Composable
private fun ModeReticle(vm: AppViewModel) {
    val mode = vm.meteringMode
    if (mode == MeteringMode.MULTI) return
    // SPOT's reading IS the center, so refine the local brightness estimate with it.
    val local = if (mode == MeteringMode.SPOT) {
        val spotEv = vm.ev100
        val base = vm.sceneEv
        if (spotEv != null && base != null && vm.engineRawLuma > 0.0) {
            vm.engineRawLuma * 2.0.pow((spotEv - base).coerceIn(-6.0, 6.0))
        } else {
            vm.engineRawLuma
        }
    } else {
        vm.engineRawLuma
    }
    val line = contrastOn(local)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shape = when (mode) {
            MeteringMode.SPOT -> Modifier
                .size(32.dp)
                .border(1.5.dp, line, CircleShape)
            MeteringMode.CENTER -> Modifier
                .size(96.dp)
                .border(1.5.dp, line, CircleShape)
            else -> Modifier
                .size(width = 160.dp, height = 112.dp)
                .border(1.5.dp, line, RoundedCornerShape(4.dp))
        }
        Box(shape) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(line),
            )
        }
    }
}

@Composable
private fun SpotLayer(vm: AppViewModel, boxWidth: Float, boxHeight: Float) {
    // Push spot positions to the engine whenever geometry or any spot moves.
    LaunchedEffect(
        vm.spots,
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
            vm.pushEngineSpots(
                vm.spots.map { (x, y) -> transform.screenToFrame(x * boxWidth, y * boxHeight) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        val engineEvs = vm.spotDisplayEvs
        val base = vm.sceneEv
        vm.spots.forEachIndexed { index, spot ->
            val spotKeyline = contrastOn(
                run {
                    val ev = engineEvs.getOrNull(index)
                    if (ev != null && ev.isFinite() && base != null && vm.engineRawLuma > 0.0) {
                        (vm.engineRawLuma * 2.0.pow((ev - base).coerceIn(-6.0, 6.0)))
                            .coerceIn(0.0, 1.0)
                    } else {
                        vm.engineRawLuma
                    }
                },
            )
                val ev = engineEvs.getOrNull(index)?.takeIf { it.isFinite() } ?: spot.ev100
                SpotHandle(
                    x = spot.x * boxWidth,
                    y = spot.y * boxHeight,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    label = "${index + 1} \u00b7 ${FormatUtils.evText(ev)}",
                    keyline = spotKeyline,
                    onDrag = { nx, ny -> vm.updateSpot(spot.id, nx / boxWidth, ny / boxHeight) },
                    onDelete = { vm.removeSpot(spot.id) },
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
    keyline: Color,
    onDrag: (Float, Float) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // Keep the gesture lambdas reading the latest position: the pointerInput(Unit)
    // block runs once, so closures would otherwise capture the initial coordinates.
    val currentX by androidx.compose.runtime.rememberUpdatedState(x)
    val currentY by androidx.compose.runtime.rememberUpdatedState(y)
    val currentOnDrag by androidx.compose.runtime.rememberUpdatedState(onDrag)
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
                        currentOnDrag(
                            (currentX + amount.x).coerceIn(8f, boxWidth - 8f),
                            (currentY + amount.y).coerceIn(8f, boxHeight - 8f),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onDelete?.invoke() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, keyline, CircleShape)
                    .padding(1.dp)
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
private fun ReadoutPanel(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val result = vm.exposureResult
    val reading = vm.activeReading

    val rightLabel = when (vm.exposureMode) {
        ExposureMode.APERTURE_PRIORITY -> stringResource(R.string.shutter).uppercase()
        ExposureMode.SHUTTER_PRIORITY -> stringResource(R.string.aperture).uppercase()
        ExposureMode.MANUAL -> stringResource(R.string.exposure).uppercase()
    }
    val rightValue = when (vm.exposureMode) {
        ExposureMode.APERTURE_PRIORITY ->
            result.solvedShutter?.let { FormatUtils.shutter(it) } ?: "--"
        ExposureMode.SHUTTER_PRIORITY ->
            result.solvedAperture?.let { FormatUtils.aperture(it) } ?: "--"
        ExposureMode.MANUAL ->
            result.stops?.let { FormatUtils.signed(it) } ?: "--"
    }
    val rightColor = when (vm.exposureMode) {
        ExposureMode.MANUAL -> if (vm.matched) ColorAccent else ColorInk
        else -> ColorAccent
    }
    // Reading-invalid override replaces the EV number with a diagnosis.
    val evText = when (reading?.reason) {
        ReadingInvalidReason.CLIPPED -> stringResource(R.string.too_bright).uppercase()
        ReadingInvalidReason.TOO_DARK -> stringResource(R.string.too_dark).uppercase()
        ReadingInvalidReason.NO_SIGNAL -> "--.-"
        else -> FormatUtils.evText(vm.ev100)
    }
    val evDim = reading?.valid == false

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(ColorBody.copy(alpha = 0f), ColorBody.copy(alpha = 0.9f)),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(
                    stringResource(R.string.ev_iso_100),
                    style = LabelTiny.copy(fontSize = 9.sp),
                    color = ColorDim,
                )
                Text(
                    evText,
                    style = if (evDim) LabelTiny.copy(fontSize = 20.sp) else DisplayLarge.copy(
                        fontSize = 58.sp,
                        lineHeight = 50.sp,
                    ),
                    color = if (evDim) ColorAccent else ColorInk,
                    modifier = if (evDim) Modifier.padding(top = 14.dp, bottom = 6.dp) else Modifier,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Text(rightLabel, style = LabelTiny, color = ColorDim)
                Text(rightValue, style = DisplayMedium.copy(fontSize = 34.sp, lineHeight = 34.sp), color = rightColor)
            }
        }
        if (!landscape) {
            ExposureScaleCanvas(
                needle = vm.needle,
                matched = vm.matched,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
        // Attribution + first suggestion, one quiet line under the scale.
        val causes = result.causes
        val hint = buildString {
            if (causes != null && causes.quantized && result.stops == 0.0) {
                append(
                    stringResource(R.string.scale_limit) +
                        " " + FormatUtils.signed(causes.quantizedResidual),
                )
            }
            result.suggestions.firstOrNull()?.let { append("  \u00b7  " + it.detail) }
        }
        if (hint.isNotBlank()) {
            Text(
                hint.trim(),
                style = LabelTiny.copy(fontSize = 9.sp),
                color = ColorDim,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
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
                .height(16.dp),
        ) {
            val tickCount = 25
            val step = size.width / (tickCount - 1)
            repeat(tickCount) { i ->
                val long = i % 4 == 0
                val h = if (long) 9.dp.toPx() else 6.dp.toPx()
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
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                color = accent,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            listOf("−3", "−2", "−1", "0", "+1", "+2", "+3").forEachIndexed { i, label ->
                Text(
                    label,
                    fontSize = 8.sp,
                    color = if (i == 3 && matched) ColorAccent else ColorDim,
                )
            }
        }
    }
}

/**
 * Landscape companion of the needle scale: a vertical rail beside the viewfinder,
 * positive EV up, negative down, needle sweeping horizontally.
 */
@Composable
fun VerticalExposureScale(
    needle: Double,
    matched: Boolean,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = needle.toFloat(),
        animationSpec = tween(150),
        label = "needleV",
    )
    val accent = ColorAccent
    Row(modifier) {
        Canvas(
            Modifier
                .padding(top = 8.dp, bottom = 8.dp)
                .height(220.dp)
                .width(18.dp),
        ) {
            val tickCount = 25
            val step = size.height / (tickCount - 1)
            repeat(tickCount) { i ->
                val long = i % 4 == 0
                val w = if (long) 12.dp.toPx() else 7.dp.toPx()
                // Top is +3: index 0 at the top.
                drawLine(
                    color = ColorInk.copy(alpha = if (long) 0.7f else 0.4f),
                    start = Offset(0f, i * step),
                    end = Offset(w, i * step),
                    strokeWidth = 1.5f,
                )
            }
            val y = ((3f - animated) / 6f) * size.height
            drawLine(
                color = accent.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 4.dp.toPx(),
            )
            drawLine(
                color = accent,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        Column(
            Modifier
                .padding(top = 8.dp, bottom = 8.dp)
                .height(220.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            listOf("+3", "+2", "+1", "0", "\u22121", "\u22122", "\u22123").forEachIndexed { i, label ->
                Text(
                    label,
                    fontSize = 8.sp,
                    color = if (i == 3 && matched) ColorAccent else ColorDim,
                )
            }
        }
    }
}
