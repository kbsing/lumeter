package com.lumeter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark professional photography palette: near-black surfaces, amber accent that stays
// readable in dim light, red reserved for clipping/alerts.
private val Amber = Color(0xFFFFB300)
private val AmberDim = Color(0xFF9C7A1A)
private val Danger = Color(0xFFFF5252)
private val Ink = Color(0xFF0A0A0C)
private val Panel = Color(0xF0101014)
private val Hairline = Color(0xFF2A2A30)
private val TextPrimary = Color(0xFFEDEDEF)
private val TextSecondary = Color(0xFF8E8E96)

val LumeterColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    secondary = AmberDim,
    onSecondary = Color(0xFFEDEDEF),
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = Panel,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    outline = Hairline,
)

@Composable
fun LumeterTheme(content: @Composable () -> Unit) {
    // The metering UI is deliberately dark-only; a light viewfinder ruins night adaptation.
    isSystemInDarkTheme() // tracked so recomposition on theme change doesn't crash previews
    MaterialTheme(
        colorScheme = LumeterColors,
        content = content,
    )
}
