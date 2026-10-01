package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.*

/**
 * Calibration screen: per-source EV offset (YUV and RAW read different system
 * constants), the shared K constant, and a one-step reference wizard that derives
 * the offset from a known EV (gray card or a trusted meter).
 */
@Composable
fun CalibrationPage(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var selectedRaw by remember { mutableStateOf(appViewModel.rawMode) }
    var referenceInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // Header: back pinned left, title centered on the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorPanel)
                    .clickable { onBack() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "← ${stringResource(R.string.back)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorDim,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = stringResource(R.string.calibration).uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ColorInk,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Source tabs: offsets are independent per metering path.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTab(
                    label = stringResource(R.string.calibration_source_yuv),
                    selected = !selectedRaw,
                    modifier = Modifier.weight(1f),
                ) { selectedRaw = false }
                SourceTab(
                    label = stringResource(R.string.calibration_source_raw),
                    selected = selectedRaw,
                    modifier = Modifier.weight(1f),
                ) { selectedRaw = true }
            }

            // Current scene reading (live, includes the ACTIVE source's offset)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorPanel, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.current_scene_reading),
                    fontSize = 10.sp,
                    color = ColorDim,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = appViewModel.ev100?.let {
                        FormatUtils.formatEv(it)
                    } ?: "—.—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorAccent,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "EV @ ISO 100",
                    fontSize = 11.sp,
                    color = ColorDim
                )
            }

            // EV Offset control for the selected source
            val offset = if (selectedRaw) {
                appViewModel.calOffsetRaw
            } else {
                appViewModel.calOffsetYuv
            }
            val setOffset: (Double) -> Unit = { value ->
                if (selectedRaw) appViewModel.setCalOffsetRaw(value) else appViewModel.setCalOffsetYuv(value)
            }
            CalibrationControl(
                title = stringResource(R.string.ev_offset) +
                    " · " + if (selectedRaw) {
                    stringResource(R.string.calibration_source_raw)
                } else {
                    stringResource(R.string.calibration_source_yuv)
                },
                description = stringResource(R.string.ev_offset_desc),
                value = offset,
                formatValue = { FormatUtils.formatExpComp(it) },
                onIncrement = { setOffset(offset + 0.1) },
                onDecrement = { setOffset(offset - 0.1) }
            )

            // Reference wizard: derive the offset from a known true EV.
            val measured = appViewModel.ev100?.let { scene ->
                // Strip the selected source's offset: the wizard compares against the
                // meter's RAW indication, not the already-corrected display value.
                scene - offset
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorPanel, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.reference_ev),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorInk
                )
                Text(
                    text = stringResource(R.string.reference_hint),
                    fontSize = 12.sp,
                    color = ColorDim,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = referenceInput,
                        onValueChange = { referenceInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = measured != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        placeholder = {
                            Text(
                                "12.5",
                                fontSize = 14.sp,
                                color = ColorDim,
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = ColorPanel2,
                            unfocusedContainerColor = ColorPanel2,
                            focusedTextColor = ColorInk,
                            unfocusedTextColor = ColorInk,
                            focusedIndicatorColor = ColorAccent,
                            unfocusedIndicatorColor = ColorLine,
                        ),
                    )
                    val reference = referenceInput.replace(',', '.').toDoubleOrNull()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    reference == null || measured == null -> ColorPanel2
                                    else -> ColorAccent
                                },
                            )
                            .clickable(enabled = reference != null && measured != null) {
                                if (reference != null && measured != null) {
                                    val updated = com.lumeter.core.calibration.CalibrationMath
                                        .updatedUserCorrection(offset, reference, measured)
                                    setOffset(updated)
                                    referenceInput = ""
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reference_apply),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (reference != null && measured != null) ColorBody else ColorDim,
                        )
                    }
                }
            }

            // K Constant control (shared by both sources)
            CalibrationControl(
                title = stringResource(R.string.calibration_constant),
                description = stringResource(R.string.calibration_constant_desc),
                value = appViewModel.kConstant,
                formatValue = { String.format("%.1f", it) },
                onIncrement = { appViewModel.setKConstant(appViewModel.kConstant + 0.1) },
                onDecrement = { appViewModel.setKConstant(appViewModel.kConstant - 0.1) }
            )

            Spacer(Modifier.height(8.dp))

            // Reset button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ColorPanel2)
                    .clickable { appViewModel.resetCalibration() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.reset_to_factory),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorDanger,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SourceTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ColorAccent else ColorPanel)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) ColorBody else ColorInk,
        )
    }
}

@Composable
private fun CalibrationControl(
    title: String,
    description: String,
    value: Double,
    formatValue: (Double) -> String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ColorInk
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = ColorDim,
                lineHeight = 18.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ColorPanel2)
                    .clickable { onDecrement() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "−",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorInk
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(ColorPanel2, RoundedCornerShape(8.dp))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatValue(value),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorAccent
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ColorPanel2)
                    .clickable { onIncrement() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorInk
                )
            }
        }
    }
}
