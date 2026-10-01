package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.ui.theme.AccentColor
import com.lumeter.ui.AppPage
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.theme.*

/**
 * Settings screen.
 */
@Composable
fun SettingsPage(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
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
                text = stringResource(R.string.settings).uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ColorInk,
                letterSpacing = 2.sp
            )
            
            Spacer(Modifier.width(80.dp))
        }
        
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live metering toggle
            SettingToggle(
                title = stringResource(R.string.live_metering),
                description = stringResource(R.string.live_metering_desc),
                checked = appViewModel.liveMetering,
                onCheckedChange = { appViewModel.toggleLiveMetering() }
            )
            
            // Third stop increments
            SettingToggle(
                title = stringResource(R.string.third_stop),
                description = stringResource(R.string.third_stop_desc),
                checked = appViewModel.thirdStopIncrements,
                onCheckedChange = { appViewModel.setThirdStop(!appViewModel.thirdStopIncrements) }
            )
            
            // Haptic feedback
            SettingToggle(
                title = stringResource(R.string.haptic_feedback),
                description = stringResource(R.string.haptic_feedback_desc),
                checked = appViewModel.hapticFeedback,
                onCheckedChange = { appViewModel.setHapticFeedback(!appViewModel.hapticFeedback) }
            )
            
            Spacer(Modifier.height(8.dp))
            
            // Accent color picker
            AccentColorPicker(
                currentColor = appViewModel.accentColor,
                onColorChange = { appViewModel.setAccentColor(it) }
            )
            
            Spacer(Modifier.height(8.dp))
            
            // Calibration menu link
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ColorPanel)
                    .clickable { appViewModel.navigateTo(AppPage.CALIBRATION) }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.calibration_menu),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ColorInk
                    )
                    Text(
                        text = stringResource(R.string.calibration_desc),
                        fontSize = 12.sp,
                        color = ColorDim,
                        lineHeight = 18.sp
                    )
                }
                Text(
                    text = "→",
                    fontSize = 18.sp,
                    color = ColorDim
                )
            }
            
            Spacer(Modifier.weight(1f))
            
            // Version footer
            Text(
                text = stringResource(R.string.version),
                fontSize = 10.sp,
                color = ColorDim,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
        
        Spacer(Modifier.width(16.dp))
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ColorAccent,
                checkedTrackColor = ColorAccent.copy(alpha = 0.3f),
                uncheckedThumbColor = ColorDim,
                uncheckedTrackColor = ColorPanel2
            )
        )
    }
}

@Composable
private fun AccentColorPicker(
    currentColor: AccentColor,
    onColorChange: (AccentColor) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.accent_colour),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ColorInk
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AccentColor.entries.forEach { color ->
                val isSelected = color == currentColor
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(color.color)
                            .clickable { onColorChange(color) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Text(
                                text = "✓",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorBody
                            )
                        }
                    }
                    Text(
                        text = color.displayName,
                        fontSize = 10.sp,
                        color = if (isSelected) ColorInk else ColorDim
                    )
                }
            }
        }
    }
}
