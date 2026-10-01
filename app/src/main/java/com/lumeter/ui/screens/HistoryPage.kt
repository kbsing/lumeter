package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.lumeter.data.Reading
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.common.FormatUtils
import com.lumeter.ui.theme.*

/**
 * History screen showing logged meter readings.
 */
@Composable
fun HistoryPage(
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
                text = stringResource(R.string.history).uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ColorInk,
                letterSpacing = 2.sp
            )
            
            // Stats
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.readings),
                    fontSize = 9.sp,
                    color = ColorDim,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = appViewModel.logEntries.size.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorAccent
                )
            }
        }
        
        // Content
        if (appViewModel.logEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_readings),
                    fontSize = 14.sp,
                    color = ColorDim,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(appViewModel.logEntries, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onDelete = { appViewModel.deleteLogEntry(entry.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: Reading,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FormatUtils.formatEv(entry.ev100),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorAccent
                )
                Text(
                    text = "EV",
                    fontSize = 10.sp,
                    color = ColorDim,
                    letterSpacing = 1.sp
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "ISO ${entry.iso}",
                    fontSize = 11.sp,
                    color = ColorInk
                )
                Text(
                    text = FormatUtils.formatAperture(entry.aperture),
                    fontSize = 11.sp,
                    color = ColorInk
                )
                Text(
                    text = FormatUtils.formatShutter(entry.shutter),
                    fontSize = 11.sp,
                    color = ColorInk
                )
            }
            
            Text(
                text = com.lumeter.ui.AppViewModel.formatTimestamp(entry.timestamp),
                fontSize = 10.sp,
                color = ColorDim
            )
        }
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ColorPanel2)
                .clickable { onDelete() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.delete),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = ColorDanger,
                letterSpacing = 0.5.sp
            )
        }
    }
}
