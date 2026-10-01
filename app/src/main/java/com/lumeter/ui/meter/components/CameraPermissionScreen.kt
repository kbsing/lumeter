package com.lumeter.ui.meter.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.ui.theme.*

@Composable
fun CameraPermissionScreen(
    onRequestPermission: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Text(
                text = stringResource(R.string.app_title),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = ColorInk,
                letterSpacing = 2.sp
            )
            
            Text(
                text = stringResource(R.string.camera_permission_message),
                fontSize = 16.sp,
                color = ColorDim,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                modifier = Modifier.widthIn(max = 280.dp)
            )
            
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorAccent,
                    contentColor = ColorBody
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .height(48.dp)
                    .widthIn(min = 160.dp)
            ) {
                Text(
                    text = stringResource(R.string.grant_permission),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
