package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.*

/**
 * Calibration screen for adjusting EV offset and K constant.
 */
@Composable
fun CalibrationPage(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody)
            .padding(horizontal = 20.dp)
    ) {
        // Header
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
            // Current scene reading
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
                        FormatUtils.formatEv(it + appViewModel.calOffset) 
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
            
            // EV Offset control
            CalibrationControl(
                title = stringResource(R.string.ev_offset),
                description = stringResource(R.string.ev_offset_desc),
                value = appViewModel.calOffset,
                formatValue = { FormatUtils.formatExpComp(it) },
                onIncrement = { appViewModel.setCalOffset(appViewModel.calOffset + 0.1) },
                onDecrement = { appViewModel.setCalOffset(appViewModel.calOffset - 0.1) }
            )
            
            // K Constant control
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
        }
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
