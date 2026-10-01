package com.lumeter.ui.meter.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lumeter.R
import com.lumeter.core.meter.MeteringMode
import com.lumeter.data.SpotAggregation
import com.lumeter.ui.AppPage
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDanger
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.LabelTiny

/**
 * Top bar: metering-mode cycle, ND cycle, AE-L hold, then history/film/settings entries.
 */
@Composable
fun TopBar(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val vm = appViewModel
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ColorBody)
            .padding(top = 6.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactToggle(
            label = when (vm.meteringMode) {
                MeteringMode.MATRIX -> stringResource(R.string.metering_matrix).uppercase()
                MeteringMode.CENTER -> stringResource(R.string.metering_center).uppercase()
                MeteringMode.SPOT -> stringResource(R.string.metering_spot).uppercase()
                MeteringMode.MULTI -> stringResource(R.string.metering_multi).uppercase()
            },
            sub = "METER",
            on = true,
            onClick = {
                val order = listOf(
                    MeteringMode.MATRIX,
                    MeteringMode.CENTER,
                    MeteringMode.SPOT,
                    MeteringMode.MULTI,
                )
                val next = order[(order.indexOf(vm.meteringMode) + 1) % order.size]
                vm.setMeteringMode(next)
            },
        )
        CompactToggle(
            label = if (vm.ndFilter == 0) "OFF" else "−${vm.ndFilter}",
            sub = "ND",
            on = vm.ndFilter > 0,
            onClick = vm::cycleNdFilter,
        )
        CompactToggle(
            label = "HOLD",
            sub = "AE-L",
            on = vm.aeHold,
            onClick = vm::toggleAeHold,
        )
        Spacer(Modifier.weight(1f))
        IconEntry(Icons.Outlined.History, "history") { vm.navigateTo(AppPage.HISTORY) }
        IconEntry(Icons.Outlined.Movie, "film") { vm.navigateTo(AppPage.FILM) }
        IconEntry(Icons.Outlined.Settings, "settings") { vm.navigateTo(AppPage.SETTINGS) }
    }
}

@Composable
private fun CompactToggle(
    label: String,
    sub: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) ColorAccent else ColorPanel)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            color = if (on) ColorBody else ColorInk,
        )
        Text(
            sub,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            color = if (on) ColorBody.copy(alpha = 0.7f) else ColorDim,
        )
    }
}

@Composable
private fun IconEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ColorDim, modifier = Modifier.size(20.dp))
    }
}

/**
 * MULTI-mode control strip over the viewfinder: AVG/HIGH/LOW, clear, spot count and spread.
 */
@Composable
fun MultiSpotInfo(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val vm = appViewModel
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpotAggregation.entries.forEach { agg ->
                val on = vm.spotAggregation == agg
                Text(
                    agg.name,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = if (on) ColorBody else ColorInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) ColorAccent else ColorBody.copy(alpha = 0.6f))
                        .clickable { vm.setSpotAggregation(agg) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // One-tap clear, visually louder than the aggregation chips.
            Text(
                stringResource(R.string.clear).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = ColorDanger,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ColorBody.copy(alpha = 0.75f))
                    .border(1.dp, ColorDanger, RoundedCornerShape(4.dp))
                    .clickable { vm.clearSpots() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        val spread = vm.spread
        Text(
            buildString {
                append("${vm.spots.size}/${AppViewModel.MAX_SPOTS} ${stringResource(R.string.spots).uppercase()}")
                if (vm.spots.size >= AppViewModel.MAX_SPOTS) append(" · MAX")
                if (vm.spots.size > 1) {
                    append(" · ${stringResource(R.string.range).uppercase()} ${FormatUtils.evText(spread)} EV")
                }
                if (vm.spots.isEmpty()) append(" · ${stringResource(R.string.tap_to_add).uppercase()}")
            },
            style = LabelTiny,
            color = ColorInk.copy(alpha = 0.8f),
        )
    }
}
