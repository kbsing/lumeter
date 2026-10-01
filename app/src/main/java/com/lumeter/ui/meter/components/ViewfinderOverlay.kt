package com.lumeter.ui.meter.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.lumeter.core.meter.ZoneMath
import com.lumeter.data.ActiveField
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.camera.PreviewGeometry
import com.lumeter.ui.theme.BarlowCondensed
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorLine
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

        if (vm.rawMode) {
            // RAW mode renders through a plain TextureView owned by the Camera2 session.
            RawCameraView(vm, Modifier.fillMaxSize())
        } else {
            LaunchedEffect(previewView, lifecycleOwner) {
                vm.startCamera(lifecycleOwner, previewView)
            }

            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Tap layer: MULTI adds a spot, SPOT moves the single metering point.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(vm.meteringMode, vm.spotDraggable) {
                    detectTapGestures { offset ->
                        when (vm.meteringMode) {
                            MeteringMode.MULTI -> vm.addSpot(
                                (offset.x / size.width).coerceIn(0.02f, 0.98f),
                                (offset.y / size.height).coerceIn(0.02f, 0.98f),
                                vm.ev100 ?: 0.0,
                            )
                            MeteringMode.SPOT ->
                                if (vm.spotDraggable) {
                                    vm.setSpotPosition(
                                        offset.x / size.width,
                                        offset.y / size.height,
                                    )
                                }
                            else -> Unit
                        }
                    }
                },
        )

        ModeReticle(vm, boxWidth, boxHeight)

        if (vm.meteringMode == MeteringMode.SPOT) {
            // Keep the engine's spot ROI under the viewfinder reticle (or center).
            LaunchedEffect(
                vm.spotPosition,
                vm.spotDraggable,
                vm.analysisWidth,
                vm.analysisHeight,
                vm.rotationDegrees,
                boxWidth,
                boxHeight,
            ) {
                if (vm.analysisWidth > 0 && vm.analysisHeight > 0) {
                    val src = if (vm.spotDraggable) {
                        vm.spotPosition
                    } else {
                        0.5f to 0.5f
                    }
                    val transform = PreviewGeometry.FillCenterTransform(
                        rotationDegrees = vm.rotationDegrees,
                        mirrored = false,
                        frameWidth = vm.analysisWidth,
                        frameHeight = vm.analysisHeight,
                        viewWidth = boxWidth.toInt(),
                        viewHeight = boxHeight.toInt(),
                    )
                    val (fu, fv) = transform.screenToFrame(src.first * boxWidth, src.second * boxHeight)
                    vm.pushEngineSpotPosition(fu, fv)
                }
            }
        }

        Text(
            "${vm.luxText} ${stringResource(R.string.lux)}",
            style = LabelTiny,
            color = ColorInk.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 10.dp),
        )

        if (vm.rawActive) {
            RawBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 28.dp),
            )
        }

        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // The histogram owns the finder's top-left corner (pro-camera placement);
        // whatever else lives there shifts below it.
        val topStartPad = if (vm.histogramEnabled) 68.dp else 8.dp
        if (vm.meteringMode == MeteringMode.MULTI) {
            MultiSpotInfo(
                vm,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = topStartPad),
            )
        } else if (landscape) {
            LiveChip(
                vm,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = topStartPad),
            )
        }

        if (landscape) {
            TopParamsStrip(
                vm,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = if (vm.meteringMode == MeteringMode.MULTI) 58.dp else 8.dp,
                    ),
            )
        }

        if (vm.histogramEnabled) {
            HistogramOverlay(
                vm,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp),
            )
        }

        // Reading panel first, spot handles after: spots stay visible and touchable
        // where they overlap the readout (the panel itself takes no input).
        ReadoutPanel(
            vm,
            Modifier.align(Alignment.BottomCenter),
        )

        if (vm.meteringMode == MeteringMode.MULTI) {
            SpotLayer(vm, boxWidth, boxHeight)
        }

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

/** "RAW" chip marking the Bayer-domain metering source. */
@Composable
private fun RawBadge(modifier: Modifier = Modifier) {
    Text(
        "RAW",
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = ColorAccent,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ColorBody.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Line color that stays legible on the given linear luma (auto-inverted). */
private fun contrastOn(luma: Double): Color =
    if (luma > 0.30) Color(0xD910100E) else Color(0xB3ECE8DF)

@Composable
private fun ModeReticle(vm: AppViewModel, boxWidth: Float, boxHeight: Float) {
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
    if (mode == MeteringMode.SPOT && vm.spotDraggable) {
        DraggableSpotReticle(
            x = vm.spotPosition.first * boxWidth,
            y = vm.spotPosition.second * boxHeight,
            line = line,
            vm = vm,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
        )
        return
    }
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

/** SPOT reticle at the movable point: 48dp hit area, drag retargets the metering ROI. */
@Composable
private fun DraggableSpotReticle(
    x: Float,
    y: Float,
    line: Color,
    vm: AppViewModel,
    boxWidth: Float,
    boxHeight: Float,
) {
    val currentX by androidx.compose.runtime.rememberUpdatedState(x)
    val currentY by androidx.compose.runtime.rememberUpdatedState(y)
    Box(
        Modifier
            .offset {
                val half = 24.dp.roundToPx()
                androidx.compose.ui.unit.IntOffset(
                    (x - half).roundToInt(),
                    (y - half).roundToInt(),
                )
            }
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    vm.setSpotPosition(
                        (currentX + amount.x) / boxWidth,
                        (currentY + amount.y) / boxHeight,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .border(1.5.dp, line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(line),
            )
        }
    }
}

/**
 * Frame histogram in the pro-camera style: a compact dark corner box with faint
 * quartile grid ticks and a solid white luminance silhouette (dark left → bright
 * right). Not themed beyond the app's ink color — instrument standard.
 */
@Composable
private fun HistogramOverlay(vm: AppViewModel, modifier: Modifier = Modifier) {
    val histogram = vm.engineHistogram ?: return
    if (histogram.size < 2) return
    // Resolve theme colors before the draw scope.
    val fill = ColorInk
    val grid = ColorInk.copy(alpha = 0.25f)
    Box(
        modifier
            .size(width = 132.dp, height = 52.dp)
            .background(ColorBody.copy(alpha = 0.7f))
            .border(1.dp, ColorLine)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val max = histogram.max()
            if (max <= 0) return@Canvas
            // Quartile ticks across the brightness axis.
            for (q in 1..3) {
                val gx = size.width * q / 4f
                drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
            }
            val bar = size.width / histogram.size
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(0f, size.height)
            for (i in histogram.indices) {
                val h = (histogram[i].toFloat() / max) * size.height
                val x = i * bar
                path.lineTo(x, size.height - h)
                path.lineTo(x + bar, size.height - h)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, fill)
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
        val zones = vm.spotZones
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
                    label = spotLabel(index, ev, zones.getOrNull(index), vm.zoneEnabled),
                    keyline = spotKeyline,
                    onDrag = { nx, ny -> vm.updateSpot(spot.id, nx / boxWidth, ny / boxHeight) },
                    onDelete = { vm.removeSpot(spot.id) },
                )
        }
    }
}

/** Spot chip text: "1 · 12.4", gaining " · VII" once Zone placement is armed. */
private fun spotLabel(index: Int, ev: Double, zone: Double?, zoneEnabled: Boolean): String {
    val text = "${index + 1} \u00b7 ${FormatUtils.evText(ev)}"
    if (!zoneEnabled || zone == null || !zone.isFinite()) return text
    return "$text \u00b7 ${ZoneMath.LABELS[ZoneMath.zoneIndex(zone)]}"
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
        ZoneStrip(
            vm,
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp),
        )
        ExposureScaleCanvas(
            needle = vm.needle,
            matched = vm.matched,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
        // Attribution + first suggestion, one quiet line under the scale.
        val causes = result.causes
        val hint = buildString {
            if (causes != null && causes.quantized && result.stops == 0.0) {
                append(
                    stringResource(R.string.scale_limit) +
                        " " + FormatUtils.signed(causes.quantizedResidual),
                )
            }
            if (causes != null && causes.reciprocity > 0.01) {
                if (isNotBlank()) append("  \u00b7  ")
                append(
                    stringResource(R.string.reciprocity_short) + " " +
                        FormatUtils.signed(causes.reciprocity),
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

/**
 * Zone System strip above the ±3 scale: a ZONE arming chip, and when armed, the 0–X
 * placement cells (tap to place the metered area) plus the placed zone's description.
 */
@Composable
private fun ZoneStrip(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.zone).uppercase(),
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = if (vm.zoneEnabled) ColorBody else ColorDim,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (vm.zoneEnabled) ColorAccent else ColorBody.copy(alpha = 0.6f))
                .clickable { vm.toggleZone() }
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
        if (vm.zoneEnabled) {
            ZoneMath.LABELS.forEachIndexed { zone, label ->
                val selected = vm.placedZone == zone
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) ColorAccent else ColorBody.copy(alpha = 0.6f))
                        .clickable { vm.setPlacedZone(zone) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) ColorBody else ColorInk,
                    )
                }
            }
            Text(
                zoneDescription(vm.placedZone),
                fontSize = 9.sp,
                color = ColorDim,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun zoneDescription(zone: Int): String = when (zone) {
    0 -> stringResource(R.string.zone_d0)
    1 -> stringResource(R.string.zone_d1)
    2 -> stringResource(R.string.zone_d2)
    3 -> stringResource(R.string.zone_d3)
    4 -> stringResource(R.string.zone_d4)
    5 -> stringResource(R.string.zone_d5)
    6 -> stringResource(R.string.zone_d6)
    7 -> stringResource(R.string.zone_d7)
    8 -> stringResource(R.string.zone_d8)
    9 -> stringResource(R.string.zone_d9)
    else -> stringResource(R.string.zone_d10)
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

/**
 * Landscape top strip over the finder: the current exposure triple with the dial's
 * active field highlighted, per the reference layout. In A mode the shutter shown is
 * the solver's value, in S mode the aperture is.
 */
@Composable
private fun TopParamsStrip(vm: AppViewModel, modifier: Modifier = Modifier) {
    val result = vm.exposureResult
    val shutterLocked = vm.exposureMode == ExposureMode.APERTURE_PRIORITY
    val apertureLocked = vm.exposureMode == ExposureMode.SHUTTER_PRIORITY
    val shutterText = if (shutterLocked) {
        result.solvedShutter?.let { FormatUtils.shutter(it) } ?: "--"
    } else {
        FormatUtils.shutter(vm.userShutter)
    }
    val apertureText = if (apertureLocked) {
        result.solvedAperture?.let { FormatUtils.aperture(it) } ?: "--"
    } else {
        FormatUtils.aperture(vm.userAperture)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ColorBody.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        StripSeg(shutterText, vm.activeField == ActiveField.SHUTTER)
        StripDot()
        StripSeg(apertureText, vm.activeField == ActiveField.APERTURE)
        StripDot()
        StripSeg("ISO ${vm.userIso}", vm.activeField == ActiveField.ISO)
    }
}

@Composable
private fun StripSeg(text: String, active: Boolean) {
    Text(
        text,
        fontFamily = BarlowCondensed,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp,
        color = if (active) ColorAccent else ColorInk,
    )
}

@Composable
private fun StripDot() {
    Box(
        Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(ColorDim.copy(alpha = 0.6f)),
    )
}

/** LIVE/HELD state chip at the finder's top-left in landscape. */
@Composable
private fun LiveChip(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ColorBody.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (vm.continuous) Color(0xFFE5484D) else ColorDim),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (vm.continuous) "LIVE" else "HELD",
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = ColorInk.copy(alpha = 0.85f),
        )
    }
}
