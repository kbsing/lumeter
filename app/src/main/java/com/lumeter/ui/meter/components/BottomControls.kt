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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lumeter.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.exposure.ExposureSolver
import com.lumeter.data.ActiveField
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorLine
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.ColorPanel2
import com.lumeter.ui.theme.DisplayCell
import com.lumeter.ui.theme.LabelTiny

/**
 * Bottom control area from the reference design: A/S/M segmented selector, exposure
 * compensation, the three parameter cells, the snapping value ruler for the active
 * field, and the shutter ring / pause / log row.
 */
@Composable
fun BottomControls(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val vm = appViewModel
    val haptics = LocalHapticFeedback.current
    fun tick() {
        if (vm.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(ColorBody)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // A/S/M + exposure compensation
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ColorPanel)
                    .padding(2.dp),
            ) {
                listOf(
                    ExposureMode.APERTURE_PRIORITY to "A",
                    ExposureMode.SHUTTER_PRIORITY to "S",
                    ExposureMode.MANUAL to "M",
                ).forEach { (mode, label) ->
                    val on = vm.exposureMode == mode
                    Box(
                        Modifier
                            .padding(1.dp)
                            .size(width = 44.dp, height = 32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) ColorInk else Color.Transparent)
                            .clickable { tick(); vm.setExposureMode(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontFamily = com.lumeter.ui.theme.BarlowCondensed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = if (on) ColorBody else ColorDim,
                        )
                    }
                }
            }
            ExpCompControl(vm, ::tick)
        }

        Spacer(Modifier.height(10.dp))

        // Three parameter cells
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val solver = vm.solverResult
            val apertureLocked = vm.exposureMode == ExposureMode.SHUTTER_PRIORITY
            val shutterLocked = vm.exposureMode == ExposureMode.APERTURE_PRIORITY
            val apertureValue = if (apertureLocked && solver != null) {
                solver.apertureSnapped
            } else {
                vm.userAperture
            }
            val shutterValue = if (shutterLocked && solver != null) {
                solver.shutterSnapped
            } else {
                vm.userShutter
            }

            ParameterCell(
                label = stringResource(R.string.iso).uppercase(),
                value = vm.userIso.toString(),
                active = vm.activeField == ActiveField.ISO,
                locked = false,
                modifier = Modifier.weight(1f),
                onClick = { tick(); vm.setActiveField(ActiveField.ISO) },
            )
            ParameterCell(
                label = stringResource(R.string.aperture).uppercase(),
                value = if (apertureLocked) stringResource(R.string.auto).uppercase() else FormatUtils.aperture(apertureValue),
                active = vm.activeField == ActiveField.APERTURE,
                locked = apertureLocked,
                modifier = Modifier.weight(1f),
                onClick = { tick(); vm.setActiveField(ActiveField.APERTURE) },
            )
            ParameterCell(
                label = stringResource(R.string.shutter).uppercase(),
                value = if (shutterLocked) stringResource(R.string.auto).uppercase() else FormatUtils.shutter(shutterValue),
                active = vm.activeField == ActiveField.SHUTTER,
                locked = shutterLocked,
                modifier = Modifier.weight(1f),
                onClick = { tick(); vm.setActiveField(ActiveField.SHUTTER) },
            )
        }

        Spacer(Modifier.height(10.dp))

        // Ruler for the active field
        val rulerLocked = when (vm.activeField) {
            ActiveField.ISO -> false
            ActiveField.APERTURE -> vm.exposureMode == ExposureMode.SHUTTER_PRIORITY
            ActiveField.SHUTTER -> vm.exposureMode == ExposureMode.APERTURE_PRIORITY
        }
        if (rulerLocked) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ColorPanel.copy(alpha = 0.5f))
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.auto).uppercase(), style = LabelTiny, color = ColorDim)
            }
        } else {
            when (vm.activeField) {
                ActiveField.ISO -> ValueRuler(
                    entries = ExposureSolver.ISOS.map { it.toString() },
                    currentIndex = ExposureSolver.ISOS.indexOf(vm.userIso).coerceAtLeast(0),
                    onSelect = { tick(); vm.setIso(ExposureSolver.ISOS[it]) },
                    hapticEnabled = vm.hapticFeedback,
                )
                ActiveField.APERTURE -> ValueRuler(
                    entries = ExposureSolver.APERTURES.map { RulerLabels.aperture(it) },
                    currentIndex = nearestApertureIndex(vm.userAperture),
                    onSelect = { tick(); vm.setAperture(ExposureSolver.APERTURES[it]) },
                    hapticEnabled = vm.hapticFeedback,
                )
                ActiveField.SHUTTER -> ValueRuler(
                    entries = ExposureSolver.SHUTTERS.map { RulerLabels.shutter(it) },
                    currentIndex = nearestShutterIndex(vm.userShutter),
                    onSelect = { tick(); vm.setShutter(ExposureSolver.SHUTTERS[it]) },
                    hapticEnabled = vm.hapticFeedback,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Log reading (left) / pause-or-single-shot (center) / reading count (right)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "◉ " + stringResource(R.string.log_reading).uppercase(),
                style = LabelTiny,
                color = ColorInk,
                modifier = Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        tick()
                        val solver = vm.solverResult
                        val ev = vm.ev100 ?: return@clickable
                        val ap = when (vm.exposureMode) {
                            ExposureMode.SHUTTER_PRIORITY -> solver?.apertureSnapped ?: vm.userAperture
                            else -> vm.userAperture
                        }
                        val sh = when (vm.exposureMode) {
                            ExposureMode.APERTURE_PRIORITY -> solver?.shutterSnapped ?: vm.userShutter
                            else -> vm.userShutter
                        }
                        vm.logReading(ev, ap, sh)
                    }
                    .padding(vertical = 6.dp),
            )

            // Small action circle: pause when live, take one reading when paused.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ColorInk.copy(alpha = 0.08f))
                    .border(1.5.dp, ColorInk.copy(alpha = 0.8f), CircleShape)
                    .clickable {
                        tick()
                        if (vm.liveMetering) vm.toggleLiveMetering() else vm.singleShot()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.liveMetering) "❚❚" else "●",
                    color = ColorAccent,
                    fontSize = if (vm.liveMetering) 14.sp else 20.sp,
                )
            }

            // Passive reading count: history is reached from the top bar only.
            Text(
                "· ${vm.logEntries.size} ·",
                style = LabelTiny,
                color = ColorDim.copy(alpha = 0.7f),
                modifier = Modifier.width(96.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun ExpCompControl(vm: AppViewModel, tick: () -> Unit) {
    val step = if (vm.thirdStopIncrements) 1.0 / 3.0 else 0.5
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.exp_comp).uppercase(), style = LabelTiny, color = ColorDim)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorPanel)
                .clickable { tick(); vm.adjustExpComp(-step) },
            contentAlignment = Alignment.Center,
        ) {
            Text("−", color = ColorInk, fontWeight = FontWeight.Bold)
        }
        Text(
            FormatUtils.signed(vm.expComp),
            color = if (vm.expComp != 0.0) ColorAccent else ColorInk,
            fontSize = 14.sp,
            modifier = Modifier
                .width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorPanel)
                .clickable { tick(); vm.adjustExpComp(step) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = ColorInk, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ParameterCell(
    label: String,
    value: String,
    active: Boolean,
    locked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ColorPanel2 else ColorPanel)
            .clickable(enabled = !locked) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            if (locked) "$label · AUTO" else label,
            style = LabelTiny.copy(fontSize = 9.sp),
            color = if (active && !locked) ColorAccent else ColorDim,
        )
        Text(
            value,
            style = DisplayCell,
            color = if (locked) ColorInk.copy(alpha = 0.5f) else ColorInk,
        )
    }
}

private fun nearestApertureIndex(value: Double): Int =
    ExposureSolver.APERTURES.indices.minByOrNull {
        kotlin.math.abs(
            kotlin.math.log2(ExposureSolver.APERTURES[it] / value),
        )
    } ?: 0

private fun nearestShutterIndex(value: Double): Int =
    ExposureSolver.SHUTTERS.indices.minByOrNull {
        kotlin.math.abs(
            kotlin.math.log2(ExposureSolver.SHUTTERS[it] / value),
        )
    } ?: 0
