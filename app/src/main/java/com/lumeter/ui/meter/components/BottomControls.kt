package com.lumeter.ui.meter.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.core.exposure.ExposureMode
import com.lumeter.core.exposure.ExposureSolver
import com.lumeter.data.ActiveField
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.BarlowCondensed
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorBody
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorInk
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.ColorPanel2
import com.lumeter.ui.theme.DisplayCell
import com.lumeter.ui.theme.LabelTiny

/**
 * Bottom control area. Collapsed it is a single slim summary bar (the viewfinder gets
 * nearly the whole screen); tapping it — or any of its three value cells — expands the
 * full panel: A/S/M, exposure compensation, parameter cells, value ruler and the
 * action row. The handle bar collapses it again.
 */
@Composable
fun BottomControls(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val vm = appViewModel

    Column(
        modifier
            .fillMaxWidth()
            .background(ColorPanel)
            .navigationBarsPadding(),
    ) {
        AnimatedVisibility(
            visible = vm.controlsExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ExpandedPanel(vm)
        }
        AnimatedVisibility(
            visible = !vm.controlsExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CollapsedBar(vm)
        }
    }
}

@Composable
private fun ExpandedPanel(vm: AppViewModel) {
    val haptics = LocalHapticFeedback.current
    fun tick() {
        if (vm.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // Collapse handle: a labeled pill, obvious and easy to hit.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, ColorDim, RoundedCornerShape(50))
                .clickable { tick(); vm.toggleControls() }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "\u25BE " + stringResource(R.string.collapse).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = ColorInk,
            )
        }

        // A/S/M + exposure compensation
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ColorBody)
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
                            fontFamily = BarlowCondensed,
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
            val result = vm.exposureResult
            val apertureLocked = vm.exposureMode == ExposureMode.SHUTTER_PRIORITY
            val shutterLocked = vm.exposureMode == ExposureMode.APERTURE_PRIORITY
            val apertureValue = if (apertureLocked && result.solvedAperture != null) {
                result.solvedAperture
            } else {
                vm.userAperture
            }
            val shutterValue = if (shutterLocked && result.solvedShutter != null) {
                result.solvedShutter
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
                value = if (apertureLocked) {
                    stringResource(R.string.auto).uppercase()
                } else {
                    FormatUtils.aperture(apertureValue)
                },
                active = vm.activeField == ActiveField.APERTURE,
                locked = apertureLocked,
                modifier = Modifier.weight(1f),
                onClick = { tick(); vm.setActiveField(ActiveField.APERTURE) },
            )
            ParameterCell(
                label = stringResource(R.string.shutter).uppercase(),
                value = if (shutterLocked) {
                    stringResource(R.string.auto).uppercase()
                } else {
                    FormatUtils.shutter(shutterValue)
                },
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
                    .background(ColorBody.copy(alpha = 0.5f))
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

        // Log reading (left) / pause-resume (center) / reading count (right)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "\u25C9 " + stringResource(R.string.log_reading).uppercase(),
                style = LabelTiny,
                color = ColorInk,
                modifier = Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        tick()
                        val result = vm.exposureResult
                        val ev = vm.ev100 ?: return@clickable
                        val ap = when (vm.exposureMode) {
                            ExposureMode.SHUTTER_PRIORITY ->
                                result.solvedAperture ?: vm.userAperture
                            else -> vm.userAperture
                        }
                        val sh = when (vm.exposureMode) {
                            ExposureMode.APERTURE_PRIORITY ->
                                result.solvedShutter ?: vm.userShutter
                            else -> vm.userShutter
                        }
                        vm.logReading(ev, ap, sh)
                    }
                    .padding(vertical = 6.dp),
            )

            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ColorInk.copy(alpha = 0.08f))
                    .border(1.5.dp, ColorInk.copy(alpha = 0.8f), CircleShape)
                    .clickable {
                        tick()
                        vm.measureNow()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.measuring) "\u25CF" else "\u25C9",
                    color = if (vm.measuring) ColorAccent else ColorInk,
                    fontSize = 20.sp,
                )
            }

            Text(
                "\u00B7 ${vm.logEntries.size} \u00B7",
                style = LabelTiny,
                color = ColorDim.copy(alpha = 0.7f),
                modifier = Modifier.width(96.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun CollapsedBar(vm: AppViewModel) {
    val haptics = LocalHapticFeedback.current
    fun tick() {
        if (vm.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val result = vm.exposureResult
    val apertureLocked = vm.exposureMode == ExposureMode.SHUTTER_PRIORITY
    val shutterLocked = vm.exposureMode == ExposureMode.APERTURE_PRIORITY
    val apertureText = if (apertureLocked && result.solvedAperture != null) {
        FormatUtils.aperture(result.solvedAperture)
    } else if (apertureLocked) {
        stringResource(R.string.auto).uppercase()
    } else {
        FormatUtils.aperture(vm.userAperture)
    }
    val shutterText = if (shutterLocked && result.solvedShutter != null) {
        FormatUtils.shutter(result.solvedShutter)
    } else if (shutterLocked) {
        stringResource(R.string.auto).uppercase()
    } else {
        FormatUtils.shutter(vm.userShutter)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryCell(
            label = stringResource(R.string.iso),
            value = vm.userIso.toString(),
            active = vm.activeField == ActiveField.ISO,
            modifier = Modifier.weight(1f),
        ) {
            tick(); vm.setActiveField(ActiveField.ISO); vm.toggleControls()
        }
        SummaryCell(
            label = stringResource(R.string.aperture),
            value = apertureText,
            active = vm.activeField == ActiveField.APERTURE,
            modifier = Modifier.weight(1f),
        ) {
            tick(); vm.setActiveField(ActiveField.APERTURE); vm.toggleControls()
        }
        SummaryCell(
            label = stringResource(R.string.shutter),
            value = shutterText,
            active = vm.activeField == ActiveField.SHUTTER,
            modifier = Modifier.weight(1f),
        ) {
            tick(); vm.setActiveField(ActiveField.SHUTTER); vm.toggleControls()
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ColorBody)
                .clickable { tick(); vm.toggleControls() },
            contentAlignment = Alignment.Center,
        ) {
            Text("\u25B2", fontSize = 13.sp, color = ColorDim)
        }
    }
}

@Composable
private fun SummaryCell(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ColorPanel2 else ColorBody)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label.uppercase(),
            style = LabelTiny.copy(fontSize = 8.sp),
            color = if (active) ColorAccent else ColorDim,
        )
        Text(
            value,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 20.sp,
            color = ColorInk,
        )
    }
}

@Composable
private fun ExpCompControl(vm: AppViewModel, tick: () -> Unit) {
    val step = if (vm.thirdStopIncrements) 1.0 / 3.0 else 0.5
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.exp_comp).uppercase(),
            style = LabelTiny,
            color = ColorDim,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorBody)
                .clickable { tick(); vm.adjustExpComp(-step) },
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2212", color = ColorInk, fontWeight = FontWeight.Bold)
        }
        Text(
            FormatUtils.signed(vm.expComp),
            color = if (vm.expComp != 0.0) ColorAccent else ColorInk,
            fontSize = 14.sp,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorBody)
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
            .background(if (active) ColorPanel2 else ColorBody)
            .clickable(enabled = !locked) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            if (locked) "$label \u00b7 AUTO" else label,
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
