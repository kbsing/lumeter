package com.lumeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.lumeter.ui.meter.MeterScreen
import com.lumeter.ui.theme.LumeterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumeterApp()
        }
    }
}

@Composable
private fun LumeterApp() {
    LumeterTheme {
        MeterScreen()
    }
}
