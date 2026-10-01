package com.lumeter.ui.meter.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.lumeter.ui.theme.BarlowCondensed
import com.lumeter.ui.theme.ColorAccent
import com.lumeter.ui.theme.ColorDim
import com.lumeter.ui.theme.ColorLine
import com.lumeter.ui.theme.ColorPanel
import com.lumeter.ui.theme.DisplaySmall
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Snapping horizontal value ruler, the reference design's parameter dial: one 56dp cell
 * per stop, current value centered and highlighted, edges faded into the panel.
 */
@Composable
fun ValueRuler(
    entries: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hapticEnabled: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = 56.dp
        val sidePadding = (maxWidth - cell) / 2
        val pagerState = rememberPagerState(
            initialPage = currentIndex.coerceIn(0, entries.lastIndex),
        ) { entries.size }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        androidx.compose.runtime.LaunchedEffect(pagerState, hapticEnabled) {
            androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
                .drop(1)
                .collect {
                    if (hapticEnabled) {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                    }
                }
        }

        // External value change (parameter cell tapped, mode swap) recenters the dial.
        LaunchedEffect(currentIndex) {
            if (!pagerState.isScrollInProgress && pagerState.currentPage != currentIndex &&
                currentIndex in entries.indices
            ) {
                pagerState.animateScrollToPage(currentIndex)
            }
        }
        // A settled page reports the selection back.
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress && pagerState.currentPage != currentIndex) {
                delay(80) // let a fling settle fully before committing
                if (!pagerState.isScrollInProgress) onSelect(pagerState.currentPage)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ColorPanel),
            pageSpacing = 0.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = sidePadding),
        ) { page ->
            val on = page == pagerState.currentPage
            Column(
                Modifier
                    .width(cell)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .width(2.dp)
                        .height(if (on) 18.dp else 10.dp)
                        .background(if (on) ColorAccent else ColorDim.copy(alpha = 0.5f)),
                )
                Text(
                    text = entries[page],
                    style = DisplaySmall.copy(
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                    ),
                    color = if (on) ColorAccent else ColorDim,
                    modifier = Modifier.padding(top = 3.dp, bottom = 4.dp),
                )
            }
        }
        // Edge fades
        Box(
            Modifier
                .width(32.dp)
                .height(60.dp)
                .align(Alignment.CenterStart)
                .background(Brush.horizontalGradient(listOf(ColorPanel, ColorPanel.copy(alpha = 0f)))),
        )
        Box(
            Modifier
                .width(32.dp)
                .height(60.dp)
                .align(Alignment.CenterEnd)
                .background(Brush.horizontalGradient(listOf(ColorPanel.copy(alpha = 0f), ColorPanel))),
        )
    }
}

/** Ruler tick label rule shared by the three parameter dials. */
object RulerLabels {
    fun iso(iso: Int): String = iso.toString()

    fun aperture(aperture: Double): String =
        if (aperture >= 10.0 || kotlin.math.abs(aperture - aperture.toInt()) < 0.01) {
            aperture.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", aperture)
        }

    fun shutter(seconds: Double): String =
        if (seconds >= 1.0) {
            if (seconds.toInt().toDouble() == seconds) "${seconds.toInt()}\"" else "$seconds\""
        } else {
            "${Math.round(1.0 / seconds)}"
        }
}
