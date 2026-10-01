package com.lumeter.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.meter.components.BottomControls
import com.lumeter.ui.meter.components.CameraPermissionScreen
import com.lumeter.ui.meter.components.TopBar
import com.lumeter.ui.meter.components.Viewfinder
import com.lumeter.ui.theme.ColorBody

/** The main metering screen: top bar, viewfinder, bottom controls. */
@Composable
fun MeterPage(appViewModel: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        // Landscape per the reference: left tool rail | finder | control panel | action rail.
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxSize()
                .background(ColorBody),
        ) {
            if (hasPermission) {
                com.lumeter.ui.meter.components.LeftRail(appViewModel)
                Viewfinder(appViewModel, Modifier.weight(1f))
                BottomControls(
                    appViewModel,
                    Modifier.width(310.dp),
                    landscape = true,
                )
                com.lumeter.ui.meter.components.ActionRail(
                    appViewModel,
                    Modifier.width(56.dp),
                )
            } else {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    CameraPermissionScreen(
                        onRequestPermission = {
                            launcher.launch(Manifest.permission.CAMERA)
                        },
                    )
                }
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(ColorBody),
        ) {
            TopBar(appViewModel)
            if (hasPermission) {
                Viewfinder(
                    appViewModel,
                    Modifier.weight(1f),
                )
                BottomControls(appViewModel)
            } else {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) {
                    CameraPermissionScreen(
                        onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        }
    }
}
