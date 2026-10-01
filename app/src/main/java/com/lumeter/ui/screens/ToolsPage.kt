package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.core.tools.DepthOfField
import com.lumeter.core.tools.FlashMath
import com.lumeter.core.tools.FlashMix
import com.lumeter.core.tools.LatitudeMath
import com.lumeter.core.tools.LatitudePlacement
import com.lumeter.core.tools.Reciprocity
import com.lumeter.data.FilmStock
import com.lumeter.data.FilmStocks
import com.lumeter.data.SpotAggregation
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.BarlowCondensed
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDanger
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.ColorPanel2
import com.lumeter.ui.theme.DisplaySmall
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tool panel: reciprocity correction, flash guide numbers, depth of field and film
 * latitude. Each card is a self-contained calculator; the film picker at the top feeds
 * the reciprocity and latitude cards.
 */
@Composable
fun ToolsPage(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var film by remember { mutableStateOf(FilmStocks.ALL.first()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        ToolsHeader(onBack)

        FilmPicker(film, onPick = { film = it })

        ReciprocityCard(film)
        Spacer(Modifier.height(12.dp))
        FlashCard(appViewModel)
        Spacer(Modifier.height(12.dp))
        DepthOfFieldCard()
        Spacer(Modifier.height(12.dp))
        LatitudeCard(appViewModel, film)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ToolsHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorPanel)
                .clickable { onBack() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "← ${stringResource(R.string.back)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ColorDim,
                letterSpacing = 0.5.sp,
            )
        }
        Text(
            text = stringResource(R.string.tools).uppercase(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ColorInk,
            letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun FilmPicker(selected: FilmStock, onPick: (FilmStock) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.tool_film),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ColorInk,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilmStocks.ALL.forEach { stock ->
                val on = stock.name == selected.name
                Text(
                    stock.name,
                    fontSize = 11.sp,
                    color = if (on) ColorBody else ColorInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (on) ColorAccent else ColorPanel2)
                        .clickable { onPick(stock) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ReciprocityCard(film: FilmStock) {
    val times = listOf(1.0, 2.0, 4.0, 8.0, 15.0, 30.0, 60.0, 120.0, 240.0, 480.0)
    var metered by remember { mutableStateOf(10.0) }
    val model = film.reciprocity
    val corrected = model.correctedSeconds(metered)
    val stops = model.correctionStops(metered)

    ToolCard(title = stringResource(R.string.tool_reciprocity)) {
        ChipRow(
            values = times,
            selected = metered,
            format = { formatSeconds(it) },
            onSelect = { metered = it },
            label = stringResource(R.string.tool_metered_time),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    stringResource(R.string.tool_corrected).uppercase(),
                    style = com.lumeter.ui.theme.LabelTiny.copy(fontSize = 9.sp),
                    color = ColorDim,
                )
                Text(
                    formatSeconds(corrected),
                    style = DisplaySmall.copy(fontSize = 26.sp),
                    color = ColorInk,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.tool_correction).uppercase(),
                    style = com.lumeter.ui.theme.LabelTiny.copy(fontSize = 9.sp),
                    color = ColorDim,
                )
                Text(
                    if (stops > 0.01) "+${LatitudeMath.formatStops(stops).drop(1)} EV" else "±0",
                    style = DisplaySmall.copy(fontSize = 26.sp),
                    color = if (stops > 0.01) ColorAccent else ColorInk,
                )
            }
        }
        Text(
            film.reciprocityHint,
            fontSize = 11.sp,
            color = ColorDim,
        )
    }
}

@Composable
private fun FlashCard(appViewModel: AppViewModel) {
    var guideNumber by remember { mutableStateOf(32.0) }
    val gns = listOf(12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0, 45.0, 58.0)
    val distances = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 7.0, 10.0)
    var distance by remember { mutableStateOf(3.0) }
    var useAmbient by remember { mutableStateOf(false) }
    val iso = appViewModel.userIso
    val fNumber = FlashMath.fNumber(guideNumber, distance, iso)

    ToolCard(title = stringResource(R.string.tool_flash)) {
        ChipRow(
            values = gns,
            selected = guideNumber,
            format = { String.format(Locale.US, "GN %.0f", it) },
            onSelect = { guideNumber = it },
            label = stringResource(R.string.tool_gn),
        )
        ChipRow(
            values = distances,
            selected = distance,
            format = { String.format(Locale.US, "%.1f m", it) },
            onSelect = { distance = it },
            label = stringResource(R.string.tool_distance),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    stringResource(R.string.aperture).uppercase(),
                    style = com.lumeter.ui.theme.LabelTiny.copy(fontSize = 9.sp),
                    color = ColorDim,
                )
                Text(
                    fNumber?.let { FormatUtils.aperture(snapAperture(it)) } ?: "--",
                    style = DisplaySmall.copy(fontSize = 26.sp),
                    color = ColorAccent,
                )
            }
            Text(
                "ISO $iso",
                style = DisplaySmall.copy(fontSize = 18.sp),
                color = ColorDim,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            stringResource(R.string.flash_use_current),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (useAmbient) ColorBody else ColorInk,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (useAmbient) ColorAccent else ColorBody)
                .clickable { useAmbient = !useAmbient }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (useAmbient) {
            val result = appViewModel.exposureResult
            val ambient = result.solvedAperture ?: appViewModel.userAperture
            val verdict = fNumber?.let { FlashMath.mixVerdict(ambient, it) }
            if (verdict != null) {
                Text(
                    buildString {
                        append(stringResource(R.string.flash_needs))
                        append(" ")
                        append(FormatUtils.aperture(fNumber))
                        append("  ·  ")
                        append(stringResource(R.string.flash_ambient))
                        append(" ")
                        append(FormatUtils.aperture(ambient))
                    },
                    fontSize = 11.sp,
                    color = ColorInk,
                )
                Text(
                    buildString {
                        append(
                            when (verdict.mix) {
                                FlashMix.FLASH_DOMINANT -> stringResource(R.string.flash_mix_flash)
                                FlashMix.BALANCED -> stringResource(R.string.flash_mix_balanced)
                                FlashMix.AMBIENT_DOMINANT -> stringResource(R.string.flash_mix_ambient)
                            },
                        )
                        append("  ")
                        append(LatitudeMath.formatStops(verdict.stops))
                        append(" EV")
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorAccent,
                )
            }
        }
    }
}

@Composable
private fun DepthOfFieldCard() {
    val focals = listOf(14.0, 24.0, 35.0, 50.0, 85.0, 135.0, 200.0)
    var focal by remember { mutableStateOf(50.0) }
    var aperture by remember { mutableStateOf(5.6) }
    var distance by remember { mutableStateOf(5.0) }
    var coc by remember { mutableStateOf(0.030) }
    val dof = DepthOfField(focal, aperture, distance, coc)

    ToolCard(title = stringResource(R.string.tool_dof)) {
        ChipRow(
            values = focals,
            selected = focal,
            format = { String.format(Locale.US, "%.0f mm", it) },
            onSelect = { focal = it },
            label = stringResource(R.string.tool_focal),
        )
        ChipRow(
            values = listOf(1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0),
            selected = aperture,
            format = { FormatUtils.aperture(it) },
            onSelect = { aperture = it },
            label = stringResource(R.string.aperture),
        )
        ChipRow(
            values = listOf(1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0),
            selected = distance,
            format = { String.format(Locale.US, "%.1f m", it) },
            onSelect = { distance = it },
            label = stringResource(R.string.tool_distance),
        )
        ChipRow(
            values = DepthOfField.COC_PRESETS.values.toList(),
            selected = coc,
            format = { String.format(Locale.US, "%.3f mm", it) },
            onSelect = { coc = it },
            label = stringResource(R.string.tool_coc),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DofValue(stringResource(R.string.tool_near), formatMeters(dof.nearM))
            DofValue(stringResource(R.string.tool_far), dof.farM?.let(::formatMeters) ?: "∞")
            DofValue(stringResource(R.string.tool_hyperfocal), formatMeters(dof.hyperfocalM))
        }
    }
}

@Composable
private fun LatitudeCard(appViewModel: AppViewModel, film: FilmStock) {
    // MULTI spots win when present; otherwise fall back to the automatic frame range.
    val spotEvs = appViewModel.spotDisplayEvs.filter { it.isFinite() }
    val autoRange = appViewModel.sceneRangeEvs
    val sceneLow: Double?
    val sceneHigh: Double?
    if (spotEvs.size >= 2) {
        sceneLow = spotEvs.min()
        sceneHigh = spotEvs.max()
    } else if (autoRange != null) {
        sceneLow = autoRange.first
        sceneHigh = autoRange.second
    } else {
        sceneLow = null
        sceneHigh = null
    }
    val placed = appViewModel.sceneEv
    val placement: LatitudePlacement? = if (sceneLow != null && sceneHigh != null && placed != null) {
        LatitudeMath.evaluate(
            sceneLowEv = sceneLow,
            sceneHighEv = sceneHigh,
            placedEv100 = placed,
            latitudeLowStops = film.latitudeLowStops,
            latitudeHighStops = film.latitudeHighStops,
        )
    } else {
        null
    }

    ToolCard(title = stringResource(R.string.tool_latitude)) {
        if (placement == null) {
            Text(
                stringResource(R.string.tool_need_spots),
                fontSize = 12.sp,
                color = ColorDim,
                lineHeight = 18.sp,
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DofValue(
                    stringResource(R.string.tool_highlight_headroom),
                    LatitudeMath.formatStops(placement.highlightHeadroomStops),
                    danger = placement.clipsHighlights,
                )
                DofValue(
                    stringResource(R.string.tool_shadow_headroom),
                    LatitudeMath.formatStops(placement.shadowHeadroomStops),
                    danger = placement.blocksShadows,
                )
                DofValue(
                    stringResource(R.string.tool_scene_range),
                    String.format(Locale.US, "%.1f EV", sceneHigh!! - sceneLow!!),
                )
            }
            Text(
                when {
                    placement.clipsHighlights && placement.blocksShadows ->
                        stringResource(R.string.tool_range_exceeds)
                    placement.clipsHighlights -> stringResource(R.string.tool_clips)
                    placement.blocksShadows -> stringResource(R.string.tool_blocks)
                    else -> stringResource(R.string.tool_fits)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    placement.clipsHighlights || placement.blocksShadows -> ColorDanger
                    else -> ColorAccent
                },
            )
        }
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ColorInk,
        )
        content()
    }
}

@Composable
private fun ChipRow(
    values: List<Double>,
    selected: Double,
    format: (Double) -> String,
    onSelect: (Double) -> Unit,
    label: String? = null,
) {
    Column {
        if (label != null) {
            Text(
                label.uppercase(),
                style = com.lumeter.ui.theme.LabelTiny.copy(fontSize = 8.sp),
                color = ColorDim,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { value ->
                val on = value == selected
                Text(
                    format(value),
                    fontSize = 11.sp,
                    fontFamily = BarlowCondensed,
                    color = if (on) ColorBody else ColorInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (on) ColorAccent else ColorBody)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DofValue(label: String, value: String, danger: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = com.lumeter.ui.theme.LabelTiny.copy(fontSize = 8.sp),
            color = ColorDim,
        )
        Text(
            value,
            style = DisplaySmall.copy(fontSize = 20.sp),
            color = if (danger) ColorDanger else ColorInk,
        )
    }
}

/** Nearest standard aperture to a computed f-number (log2 distance). */
private fun snapAperture(value: Double): Double {
    val scale = listOf(1.0, 1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
    return scale.minByOrNull { std ->
        kotlin.math.abs(kotlin.math.ln(std / value) / kotlin.math.ln(2.0))
    } ?: value
}

/** Seconds as "8s" or "2m" style compact text. */
private fun formatSeconds(seconds: Double): String = when {
    seconds < 60.0 -> "${trim(seconds)}s"
    else -> "${trim(seconds / 60.0)}m"
}

private fun trim(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

private fun formatMeters(m: Double): String = when {
    m >= 100.0 -> "∞"
    else -> String.format(Locale.US, "%.2f m", m)
}
