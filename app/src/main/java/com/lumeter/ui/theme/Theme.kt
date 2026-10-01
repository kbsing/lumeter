package com.lumeter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lumeter.R

// Design tokens translated from the LUMEN MK·I reference (Tailwind theme):
// deep warm blacks, high-contrast cream text, selectable accent.
object LumenPalette {
    val Body = Color(0xFF0B0B0A) // deep warm black background
    val Panel = Color(0xFF141412) // slightly lighter panel surface
    val Panel2 = Color(0xFF1C1C19) // secondary panel for nested surfaces
    val Line = Color(0xFF2A2A26) // hairline borders
    val Ink = Color(0xFFECE8DF) // high-contrast cream text
    val Dim = Color(0xFF8A867B) // dimmed secondary text
    val LiveRed = Color(0xFFEF4444) // LIVE pulsing dot
    val Danger = Color(0xFFFF5252) // error/clipping red
}

enum class AccentColor(val displayName: String, val color: Color) {
    AMBER("Amber", Color(0xFFFF9F1C)),
    SIGNAL("Signal", Color(0xFFFF4D2E)),
    PHOSPHOR("Phosphor", Color(0xFFB8F24A)),
}

// Top-level token aliases used by the screen components. The accent follows the
// user-selected theme; everything else is static.
val ColorBody = LumenPalette.Body
val ColorPanel = LumenPalette.Panel
val ColorPanel2 = LumenPalette.Panel2
val ColorLine = LumenPalette.Line
val ColorInk = LumenPalette.Ink
val ColorDim = LumenPalette.Dim

val ColorAccent: Color
    @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary

val ColorDanger = LumenPalette.Danger
val ColorAccentPhosphor = AccentColor.PHOSPHOR.color
val ColorAccentSignal = AccentColor.SIGNAL.color

val BarlowCondensed = FontFamily(
    Font(R.font.barlowcondensed_medium, FontWeight.Medium),
    Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlowcondensed_bold, FontWeight.Bold),
)

val DmMono = FontFamily(
    Font(R.font.dmmono_regular, FontWeight.Normal),
    Font(R.font.dmmono_medium, FontWeight.Medium),
)

private fun schemeFor(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = LumenPalette.Body,
    background = LumenPalette.Body,
    onBackground = LumenPalette.Ink,
    surface = LumenPalette.Body,
    onSurface = LumenPalette.Ink,
    surfaceVariant = LumenPalette.Panel,
    onSurfaceVariant = LumenPalette.Dim,
    outline = LumenPalette.Line,
    error = LumenPalette.Danger,
)

/** Condensed display numerals: the big EV and parameter readouts. */
val DisplayLarge = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 84.sp,
    lineHeight = 72.sp,
)

val DisplayMedium = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 48.sp,
    lineHeight = 48.sp,
)

val DisplayCell = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 30.sp,
    lineHeight = 30.sp,
)

val DisplaySmall = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 20.sp,
)

val LabelTiny = TextStyle(
    fontFamily = DmMono,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    letterSpacing = 2.sp,
)

val LabelSmall = TextStyle(
    fontFamily = DmMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
)

val BodySmall = TextStyle(
    fontFamily = DmMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
)

private val LumenTypography = Typography(
    displayLarge = DisplayLarge,
    displayMedium = DisplayMedium,
    headlineSmall = DisplaySmall,
    titleLarge = DisplayMedium,
    bodySmall = BodySmall,
    labelSmall = LabelSmall,
)

@Composable
fun LumenTheme(accent: AccentColor = AccentColor.AMBER, content: @Composable () -> Unit) {
    // The metering UI is deliberately dark-only; a light viewfinder ruins night adaptation.
    MaterialTheme(
        colorScheme = schemeFor(accent.color),
        typography = LumenTypography,
        content = content,
    )
}
